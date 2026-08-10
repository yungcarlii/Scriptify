package scriptify.kits;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;

public final class KitManager {
	private static final String MODULE = "kits";
	private static final String FILE = "kits.json";
	private final ScriptifyContext context;
	private final Map<String, Kit> kits = new HashMap<>();

	public KitManager(ScriptifyContext context) {
		this.context = context;
	}

	public void load() {
		this.kits.clear();
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, this::defaults);
		JsonObject values = root.has("kits") && root.get("kits").isJsonObject() ? root.getAsJsonObject("kits") : new JsonObject();
		RegistryOps<JsonElement> ops = registryOps();
		for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
			try {
				this.kits.put(entry.getKey().toLowerCase(), Kit.fromJson(entry.getValue().getAsJsonObject(), ops, this.context));
			} catch (RuntimeException exception) {
				this.context.logger().warn("Ignoring invalid kit '{}'.", entry.getKey(), exception);
			}
		}
	}

	public void save() {
		JsonObject root = defaults();
		JsonObject values = root.getAsJsonObject("kits");
		RegistryOps<JsonElement> ops = registryOps();
		this.kits.forEach((name, kit) -> values.add(name, kit.toJson(ops, this.context)));
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	/**
	 * Item components (enchantments, custom names, trims, potion contents) only round-trip through
	 * {@link ItemStack#OPTIONAL_CODEC} when the ops carry the server's registries, so every kit
	 * read/write goes through registry-aware ops rather than plain {@code JsonOps}.
	 */
	private RegistryOps<JsonElement> registryOps() {
		return RegistryOps.create(JsonOps.INSTANCE, this.context.server().registryAccess());
	}

	/**
	 * Snapshots the creator's inventory as a named kit.
	 *
	 * <p>The icon shown in the kit menu is whatever the creator is holding at the time — picking a kit's
	 * display item is just "hold the thing and make the kit", with no separate command.
	 */
	public void create(String name, ServerPlayer source) {
		ItemStack held = source.getInventory().getSelectedItem();
		ItemStack icon = held.isEmpty() ? new ItemStack(Items.CHEST) : held.copy();
		icon.setCount(1);
		this.kits.put(clean(name), Kit.capture(source, icon));
		save();
	}

	public boolean delete(String name) {
		boolean removed = this.kits.remove(clean(name)) != null;
		save();
		return removed;
	}

	public boolean apply(String name, ServerPlayer target) {
		Kit kit = this.kits.get(clean(name));
		if (kit == null) {
			return false;
		}
		kit.apply(target);
		return true;
	}

	public Set<String> names() {
		return this.kits.keySet();
	}

	/** Ordered view for the kit menu: display name plus the icon chosen at creation time. */
	public List<KitEntry> entries() {
		List<KitEntry> entries = new ArrayList<>();
		this.kits.forEach((name, kit) -> entries.add(new KitEntry(name, kit.icon().copy())));
		entries.sort(Comparator.comparing(KitEntry::name));
		return entries;
	}

	public boolean exists(String name) {
		return this.kits.containsKey(clean(name));
	}

	/** A kit as the menu needs to render it. */
	public record KitEntry(String name, ItemStack icon) {
	}

	private static String clean(String name) {
		String clean = name.toLowerCase().replaceAll("[^a-z0-9_\\-]", "");
		if (clean.isBlank()) {
			throw new IllegalArgumentException("Kit name must contain letters, numbers, underscores, or hyphens.");
		}
		return clean;
	}

	private JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("kits", new JsonObject());
		return root;
	}

	private record Kit(ItemStack[] items, ItemStack icon) {
		private static Kit capture(ServerPlayer source, ItemStack icon) {
			ItemStack[] stacks = new ItemStack[source.getInventory().getContainerSize()];
			for (int slot = 0; slot < stacks.length; slot++) {
				stacks[slot] = source.getInventory().getItem(slot).copy();
			}
			return new Kit(stacks, icon);
		}

		private void apply(ServerPlayer target) {
			int max = Math.min(this.items.length, target.getInventory().getContainerSize());
			for (int slot = 0; slot < max; slot++) {
				target.getInventory().setItem(slot, this.items[slot].copy());
			}
			target.getInventory().setChanged();
		}

		private JsonObject toJson(RegistryOps<JsonElement> ops, ScriptifyContext context) {
			JsonObject object = new JsonObject();
			JsonArray array = new JsonArray();
			for (ItemStack stack : this.items) {
				array.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack)
					.resultOrPartial(error -> context.logger().warn("Dropping unserialisable kit item: {}", error))
					.orElse(new JsonObject()));
			}
			object.add("items", array);
			object.add("icon", ItemStack.OPTIONAL_CODEC.encodeStart(ops, this.icon)
				.resultOrPartial(error -> context.logger().warn("Dropping unserialisable kit icon: {}", error))
				.orElse(new JsonObject()));
			return object;
		}

		private static Kit fromJson(JsonObject object, RegistryOps<JsonElement> ops, ScriptifyContext context) {
			JsonArray array = object.getAsJsonArray("items");
			ItemStack[] stacks = new ItemStack[array.size()];
			for (int i = 0; i < array.size(); i++) {
				// A single unreadable slot (removed mod item, renamed component) must not void the kit.
				stacks[i] = ItemStack.OPTIONAL_CODEC.parse(ops, array.get(i))
					.resultOrPartial(error -> context.logger().warn("Skipping unreadable kit slot: {}", error))
					.orElse(ItemStack.EMPTY);
			}
			ItemStack icon = object.has("icon")
				? ItemStack.OPTIONAL_CODEC.parse(ops, object.get("icon")).result().orElse(ItemStack.EMPTY)
				: ItemStack.EMPTY;
			// Kits saved before icons existed, or with an unreadable icon, fall back to a chest.
			return new Kit(stacks, icon.isEmpty() ? new ItemStack(Items.CHEST) : icon);
		}
	}
}
