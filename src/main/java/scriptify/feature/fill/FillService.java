package scriptify.feature.fill;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import scriptify.api.ScriptifyContext;
import scriptify.permission.PermissionService;

/**
 * Fills the container a player is looking at with random items from the selected categories.
 *
 * <p>Targeting by line of sight rather than by coordinates is the whole point: dressing a build means
 * walking around it and looking at chests, not typing block positions.
 */
public final class FillService {
	private static final String MODULE = "fill";
	private static final String FILE = "presets.json";
	/** How far to look for a container. Slightly beyond normal reach, so it works from a step back. */
	public static final double REACH = 6.0D;

	private final ScriptifyContext context;
	private final Random random = new Random();
	/** Live settings per operator, so two people can dress different scenes at once. */
	private final Map<UUID, FillSettings> working = new HashMap<>();
	private final Map<String, FillSettings> presets = new HashMap<>();
	/** Empty set means every player with Inventory access may load the preset. */
	private final Map<String, Set<String>> presetRanks = new HashMap<>();

	public FillService(ScriptifyContext context) {
		this.context = context;
	}

	/** The settings this player is currently editing. */
	public FillSettings settings(ServerPlayer player) {
		return this.working.computeIfAbsent(player.getUUID(), key -> new FillSettings());
	}

	public void setSettings(ServerPlayer player, FillSettings settings) {
		this.working.put(player.getUUID(), settings);
	}

	// ---------------------------------------------------------------- targeting

	/**
	 * The container the player is looking at, if any.
	 *
	 * <p>A large chest resolves to <em>both</em> halves as one 54-slot container. This used to return
	 * whichever half was clicked, on the reasoning that filling one side is predictable - but a large
	 * chest is one chest to everyone who is not reading this code. Filling it left the other half
	 * conspicuously empty, and the density setting silently meant half what it said, because it was a
	 * percentage of 27 rather than of the 54 slots actually on screen.
	 *
	 * <p>The combining is vanilla's own, so it follows the same rule the game does about which
	 * neighbour counts as the other half. The blocked check is deliberately overridden: a chest with
	 * a block sitting on it cannot be opened by hand, but it is still the chest being pointed at, and
	 * refusing to dress a build because something is stacked on top would be a strange rule.
	 */
	public Optional<Container> lookingAt(ServerPlayer player) {
		HitResult hit = player.pick(REACH, 0.0F, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			return Optional.empty();
		}
		BlockPos pos = blockHit.getBlockPos();
		BlockState state = player.level().getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chestBlock) {
			Container combined = ChestBlock.getContainer(chestBlock, state, player.level(), pos, true);
			if (combined != null) {
				return Optional.of(combined);
			}
		}
		BlockEntity blockEntity = player.level().getBlockEntity(pos);
		return blockEntity instanceof Container container ? Optional.of(container) : Optional.empty();
	}

	// ---------------------------------------------------------------- filling

	/**
	 * Fills a container according to {@code settings}.
	 *
	 * @return how many slots received an item, or {@code -1} when no category is selected.
	 */
	public int fill(Container container, FillSettings settings) {
		List<Item> pool = pool(settings);
		if (pool.isEmpty()) {
			return -1;
		}
		container.clearContent();
		int size = container.getContainerSize();
		int target = Math.max(1, size * settings.density() / 100);

		// Pick distinct slots rather than rolling per slot, so density is exact instead of approximate
		// and the result never comes out suspiciously uniform.
		List<Integer> slots = new ArrayList<>(size);
		for (int slot = 0; slot < size; slot++) {
			slots.add(slot);
		}
		java.util.Collections.shuffle(slots, this.random);

		int filled = 0;
		for (int index = 0; index < target && index < slots.size(); index++) {
			Item item = pool.get(this.random.nextInt(pool.size()));
			ItemStack stack = new ItemStack(item);
			int count = settings.minCount() + this.random.nextInt(settings.maxCount() - settings.minCount() + 1);
			stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), count)));
			container.setItem(slots.get(index), stack);
			filled++;
		}
		container.setChanged();
		return filled;
	}

	/**
	 * The combined pool: every selected category, plus any individually chosen items.
	 *
	 * <p>Hand-picked items are added once each, so a short explicit list stays visible against a large
	 * category rather than being drowned out — pick five items and no categories and you get exactly
	 * those five.
	 */
	private List<Item> pool(FillSettings settings) {
		List<Item> pool = new ArrayList<>();
		for (String id : settings.categories()) {
			FillCategory category = FillCategory.byId(id);
			if (category != null) {
				pool.addAll(category.pool());
			}
		}
		for (String id : settings.items()) {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
			if (item != null && item != Items.AIR) {
				pool.add(item);
			}
		}
		return pool;
	}

	// ---------------------------------------------------------------- presets

	public void load() {
		this.presets.clear();
		this.presetRanks.clear();
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, FillService::defaults);
		if (root.has("presets") && root.get("presets").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("presets").entrySet()) {
				try {
					this.presets.put(entry.getKey(), FillSettings.fromJson(entry.getValue().getAsJsonObject()));
				} catch (RuntimeException exception) {
					this.context.logger().warn("Ignoring invalid fill preset '{}'.", entry.getKey());
				}
			}
		}
		if (root.has("access") && root.get("access").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("access").entrySet()) {
				if (!entry.getValue().isJsonArray()) continue;
				Set<String> ranks = new LinkedHashSet<>();
				for (JsonElement value : entry.getValue().getAsJsonArray()) {
					String rank = cleanRank(value.getAsString());
					if (!rank.isEmpty()) ranks.add(rank);
				}
				if (!ranks.isEmpty()) this.presetRanks.put(entry.getKey().toLowerCase(Locale.ROOT), ranks);
			}
		}
		// Seeded after loading, so a stored preset always wins over the built-in of the same name.
		// Not behind the early return the read used to have: an empty file is exactly the case where
		// the built-ins are wanted most.
		if (BuiltInPresets.seed(this) > 0) {
			save();
		}
	}

	public void save() {
		JsonObject root = defaults();
		JsonObject presetObject = root.getAsJsonObject("presets");
		this.presets.forEach((name, settings) -> presetObject.add(name, settings.toJson()));
		JsonObject access = new JsonObject();
		this.presetRanks.forEach((name, ranks) -> {
			if (ranks.isEmpty()) return;
			com.google.gson.JsonArray values = new com.google.gson.JsonArray();
			ranks.forEach(values::add);
			access.add(name, values);
		});
		root.add("access", access);
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	public void savePreset(String name, FillSettings settings) {
		this.presets.put(clean(name), settings.copy());
		save();
	}

	public Optional<FillSettings> preset(String name) {
		FillSettings settings = this.presets.get(clean(name));
		return settings == null ? Optional.empty() : Optional.of(settings.copy());
	}

	public boolean deletePreset(String name) {
		String key = clean(name);
		boolean removed = this.presets.remove(key) != null;
		if (removed) {
			this.presetRanks.remove(key);
			save();
		}
		return removed;
	}

	public List<String> presetNames() {
		List<String> names = new ArrayList<>(this.presets.keySet());
		names.sort(String::compareTo);
		return names;
	}

	public boolean canUsePreset(String name, ServerPlayer player) {
		String key = clean(name);
		if (!this.presets.containsKey(key) || !this.context.permissions().has(player, PermissionService.INVENTORY)) {
			return false;
		}
		Set<String> ranks = this.presetRanks.get(key);
		return this.context.permissions().has(player, PermissionService.ADMIN) || ranks == null || ranks.isEmpty()
			|| ranks.contains(this.context.permissions().ranks().of(player.getUUID()).name());
	}

	public boolean canManagePresets(ServerPlayer player) {
		return this.context.permissions().has(player, PermissionService.FILL_MANAGE);
	}

	public Set<String> allowedRanks(String name) {
		return Set.copyOf(this.presetRanks.getOrDefault(clean(name), Set.of()));
	}

	public boolean setRankAllowed(String name, String rankName, boolean allowed) {
		String key = clean(name);
		var found = this.context.permissions().ranks().byName(rankName);
		if (!this.presets.containsKey(key) || found.isEmpty()) return false;
		Set<String> ranks = new LinkedHashSet<>(this.presetRanks.getOrDefault(key, Set.of()));
		if (allowed) ranks.add(found.get().name()); else ranks.remove(found.get().name());
		if (ranks.isEmpty()) this.presetRanks.remove(key); else this.presetRanks.put(key, ranks);
		save();
		return true;
	}

	public void clearRankAccess(String name) {
		if (this.presetRanks.remove(clean(name)) != null) save();
	}

	private static String clean(String name) {
		String clean = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
		if (clean.isBlank()) {
			throw new IllegalArgumentException("Preset name must contain letters, numbers, underscores, or hyphens.");
		}
		return clean;
	}

	private static String cleanRank(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
	}

	private static JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 2);
		root.add("presets", new JsonObject());
		root.add("access", new JsonObject());
		return root;
	}
}
