package scriptify.kits;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.config.ScriptifyConfig;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

public final class KitManager {
	private static final String MODULE = "kits";
	private static final String FILE = "kits.json";
	private final ScriptifyContext context;
	private final Map<String, Kit> kits = new HashMap<>();
	private final KitAnimator animator = new KitAnimator();

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
				String key = entry.getKey().toLowerCase();
			Kit loaded = Kit.fromJson(entry.getValue().getAsJsonObject(), ops, this.context);
			this.kits.put(key, loaded.displayName().isBlank()
					? new Kit(loaded.items(), loaded.icon(), display(key), loaded.created(), loaded.allowedRanks())
					: loaded);
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
		this.kits.put(clean(name), Kit.capture(source, icon, display(name)));
		save();
	}

	public boolean delete(String name) {
		boolean removed = this.kits.remove(clean(name)) != null;
		save();
		return removed;
	}

	/**
	 * Gives a kit to a player.
	 *
	 * <p>By default the items are dealt out one at a time by {@link KitAnimator}; set
	 * {@code kits.animate} to false in the config to have them all appear at once instead.
	 */
	public boolean apply(String name, ServerPlayer target) {
		Kit kit = this.kits.get(clean(name));
		if (kit == null || !canUse(name, target)) {
			return false;
		}
		apply(kit, target);
		return true;
	}

	/** Gives a kit as an authorised kit manager, bypassing the recipient's self-service rank gate. */
	public boolean applyManaged(String name, ServerPlayer target) {
		Kit kit = this.kits.get(clean(name));
		if (kit == null) {
			return false;
		}
		apply(kit, target);
		return true;
	}

	private void apply(Kit kit, ServerPlayer target) {
		ScriptifyConfig.Kits settings = ScriptifyRuntime.config().get().kits;
		if (settings.animate) {
			this.animator.start(target, kit.items(), settings.animationIntervalTicks);
		} else {
			kit.apply(target);
		}
	}

	/** Drives the deal-out animation; called once per server tick. */
	public void tick(MinecraftServer server) {
		this.animator.tick(server);
	}

	/**
	 * Stops a half-finished deal.
	 *
	 * <p>Called on disconnect, and by anything else that writes a player's inventory. A running deal
	 * keeps setting slots on a timer, so a copied inventory or an edited one would be overwritten a
	 * tick later by the rest of a kit somebody had already forgotten about.
	 */
	public void cancelAnimation(ServerPlayer player) {
		this.animator.cancel(player.getUUID());
	}

	public Set<String> names() {
		return this.kits.keySet();
	}

	/** Ordered view for the kit menu: display name plus the icon chosen at creation time. */
	public List<KitEntry> entries() {
		List<KitEntry> entries = new ArrayList<>();
		this.kits.forEach((key, kit) ->
			entries.add(new KitEntry(key, kit.displayName(), kit.icon().copy(), kit.itemCount(), kit.created(),
				Set.copyOf(kit.allowedRanks()))));
		// Oldest first, so a new kit lands at the bottom where you just made it rather than jumping into
		// the middle of the grid alphabetically.
		entries.sort(Comparator.comparingLong(KitEntry::created));
		return entries;
	}

	/** Renames a kit, keeping its contents. Returns false when the new name is already taken. */
	public boolean rename(String from, String to) {
		Kit kit = this.kits.get(clean(from));
		String target = clean(to);
		if (kit == null || this.kits.containsKey(target)) {
			return false;
		}
		this.kits.remove(clean(from));
		this.kits.put(target, new Kit(kit.items(), kit.icon(), display(to), kit.created(), kit.allowedRanks()));
		save();
		return true;
	}

	/** Replaces a kit's contents with the player's current inventory, keeping its name and icon. */
	public boolean recapture(String name, ServerPlayer source) {
		Kit existing = this.kits.get(clean(name));
		if (existing == null) {
			return false;
		}
		Kit captured = Kit.capture(source, existing.icon(), existing.displayName(), existing.created());
		this.kits.put(clean(name), new Kit(captured.items(), captured.icon(), captured.displayName(),
			captured.created(), existing.allowedRanks()));
		save();
		return true;
	}

	/** Sets the icon shown in the menu to whatever the player is holding. */
	public boolean setIcon(String name, ItemStack icon) {
		Kit existing = this.kits.get(clean(name));
		if (existing == null) {
			return false;
		}
		ItemStack copy = icon.isEmpty() ? new ItemStack(Items.CHEST) : icon.copy();
		copy.setCount(1);
		this.kits.put(clean(name), new Kit(existing.items(), copy, existing.displayName(), existing.created(),
			existing.allowedRanks()));
		save();
		return true;
	}

	/** The stored stacks, for the editor. Never null for a kit that exists. */
	public ItemStack[] contents(String name) {
		Kit kit = this.kits.get(clean(name));
		return kit == null ? null : kit.items();
	}

	/** Writes edited contents back. */
	public boolean setContents(String name, ItemStack[] items) {
		Kit existing = this.kits.get(clean(name));
		if (existing == null) {
			return false;
		}
		this.kits.put(clean(name), new Kit(items, existing.icon(), existing.displayName(), existing.created(),
			existing.allowedRanks()));
		save();
		return true;
	}

	/** The readable name for a key, or the key itself for a kit written by hand. */
	public String displayName(String name) {
		Kit kit = this.kits.get(clean(name));
		return kit == null ? name : kit.displayName();
	}

	public boolean exists(String name) {
		return this.kits.containsKey(clean(name));
	}

	/** Whether a player may equip this kit. An empty rank list means every kit user may use it. */
	public boolean canUse(String name, ServerPlayer player) {
		Kit kit = this.kits.get(clean(name));
		if (kit == null || !this.context.permissions().has(player, PermissionService.KITS)) {
			return false;
		}
		if (this.context.permissions().has(player, PermissionService.ADMIN) || kit.allowedRanks().isEmpty()) {
			return true;
		}
		return kit.allowedRanks().contains(this.context.permissions().ranks().of(player.getUUID()).name());
	}

	/** Whether this player may edit kits or give them to another player. */
	public boolean canManage(ServerPlayer player) {
		return this.context.permissions().has(player, PermissionService.KITS_MANAGE);
	}

	/** The allowed rank ids for a kit. An empty result represents unrestricted access. */
	public Set<String> allowedRanks(String name) {
		Kit kit = this.kits.get(clean(name));
		return kit == null ? Set.of() : Set.copyOf(kit.allowedRanks());
	}

	/** Adds or removes a rank from a kit's access list. */
	public boolean setRankAllowed(String name, String rankName, boolean allowed) {
		Kit existing = this.kits.get(clean(name));
		var rank = this.context.permissions().ranks().byName(rankName);
		if (existing == null || rank.isEmpty()) {
			return false;
		}
		Set<String> allowedRanks = new LinkedHashSet<>(existing.allowedRanks());
		if (allowed) {
			allowedRanks.add(rank.get().name());
		} else {
			allowedRanks.remove(rank.get().name());
		}
		this.kits.put(clean(name), new Kit(existing.items(), existing.icon(), existing.displayName(),
			existing.created(), allowedRanks));
		save();
		return true;
	}

	/** Removes every rank restriction, allowing every player with {@code scriptify.kits} to use it. */
	public boolean clearRankAccess(String name) {
		Kit existing = this.kits.get(clean(name));
		if (existing == null) {
			return false;
		}
		if (!existing.allowedRanks().isEmpty()) {
			this.kits.put(clean(name), new Kit(existing.items(), existing.icon(), existing.displayName(),
				existing.created(), Set.of()));
			save();
		}
		return true;
	}

	/** A kit as the menu needs to render it. */
	public record KitEntry(String name, String displayName, ItemStack icon, int itemCount, long created,
			Set<String> allowedRanks) {
	}

	/**
	 * The storage key: lowercased, with runs of whitespace collapsed.
	 *
	 * <p>Separate from the display name so "PvP Kit" and "pvp kit" are the same kit while the capitals
	 * survive on screen. Spaces are kept because a kit is named for a person to read, not for a command
	 * parser \u2014 the commands take a quoted string.
	 */
	private static String clean(String name) {
		String cleaned = name.trim().toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^a-z0-9 _\\-]", "")
			.replaceAll("\\s+", " ")
			.trim();
		if (cleaned.isBlank()) {
			throw new IllegalArgumentException("A kit name needs letters or numbers in it.");
		}
		return cleaned;
	}

	/** The name as typed, minus anything that would not render. */
	private static String display(String name) {
		String cleaned = name.trim()
			.replaceAll("[^A-Za-z0-9 _\\-]", "")
			.replaceAll("\\s+", " ")
			.trim();
		return cleaned.isBlank() ? "Kit" : cleaned;
	}

	private JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 2);
		root.add("kits", new JsonObject());
		return root;
	}

	private record Kit(ItemStack[] items, ItemStack icon, String displayName, long created,
			Set<String> allowedRanks) {
		private static Kit capture(ServerPlayer source, ItemStack icon, String displayName) {
			return capture(source, icon, displayName, System.currentTimeMillis(), Set.of());
		}

		private static Kit capture(ServerPlayer source, ItemStack icon, String displayName, long created) {
			return capture(source, icon, displayName, created, Set.of());
		}

		private static Kit capture(ServerPlayer source, ItemStack icon, String displayName, long created,
				Set<String> allowedRanks) {
			ItemStack[] stacks = new ItemStack[source.getInventory().getContainerSize()];
			for (int slot = 0; slot < stacks.length; slot++) {
				stacks[slot] = source.getInventory().getItem(slot).copy();
			}
			return new Kit(stacks, icon, displayName, created, Set.copyOf(allowedRanks));
		}

		/** How many slots actually hold something, for the menu to report. */
		private int itemCount() {
			int count = 0;
			for (ItemStack stack : this.items) {
				if (!stack.isEmpty()) {
					count++;
				}
			}
			return count;
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
			object.addProperty("display", this.displayName);
			object.addProperty("created", this.created);
			JsonArray ranks = new JsonArray();
			this.allowedRanks.forEach(ranks::add);
			object.add("ranks", ranks);
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
			// Kits saved before display names existed keep their key as the name, which is what they
			// were being shown as anyway.
			String display = object.has("display") ? object.get("display").getAsString() : "";
			// Kits saved before creation times existed sort to the front, which is correct: they are the
			// oldest ones.
			long created = object.has("created") ? object.get("created").getAsLong() : 0L;
			Set<String> allowedRanks = new LinkedHashSet<>();
			if (object.has("ranks") && object.get("ranks").isJsonArray()) {
				for (JsonElement element : object.getAsJsonArray("ranks")) {
					String rank = element.getAsString().toLowerCase(java.util.Locale.ROOT)
						.replaceAll("[^a-z0-9_-]", "");
					if (!rank.isEmpty()) {
						allowedRanks.add(rank);
					}
				}
			}
			// Kits saved before icons existed, or with an unreadable icon, fall back to a chest.
			return new Kit(stacks, icon.isEmpty() ? new ItemStack(Items.CHEST) : icon, display, created,
				Set.copyOf(allowedRanks));
		}
	}
}
