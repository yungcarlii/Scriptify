package scriptify.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.permission.PermissionService;

/**
 * Named effect bundles, both the built-in ones and any the operator saves.
 *
 * <p>Custom presets are captured from whatever the operator currently has active, rather than built
 * through an effect picker. Setting effects up on yourself with {@code /effect} and then saving the
 * result is fewer steps than any menu could be, and it lets you preview exactly what the preset will
 * feel like before committing it.
 */
public final class PotionPresetService {
	private static final String MODULE = "potions";
	private static final String FILE = "presets.json";
	/** 1 minute 30 seconds, in ticks. */
	private static final int SHORT_DURATION = 1800;
	/** 8 minutes, in ticks — the length of an extended vanilla potion. */
	private static final int LONG_DURATION = 9600;

	private final ScriptifyContext context;
	private final Map<String, Preset> builtin = new LinkedHashMap<>();
	private final Map<String, Preset> custom = new LinkedHashMap<>();
	/** Empty set means any player with the Player State node may use the preset. */
	private final Map<String, Set<String>> allowedRanks = new LinkedHashMap<>();

	public PotionPresetService(ScriptifyContext context) {
		this.context = context;
		registerBuiltins();
	}

	private void registerBuiltins() {
		// Combat: Strength II and Speed II for 1:30, Fire Resistance for 8 minutes.
		builtin("combat", "Strength II 1:30, Speed II 1:30, Fire Resistance 8:00", Items.POTION,
			new Entry("minecraft:strength", 1, SHORT_DURATION),
			new Entry("minecraft:speed", 1, SHORT_DURATION),
			new Entry("minecraft:fire_resistance", 0, LONG_DURATION));
		builtin("tank", "Resistance II, Absorption IV, Fire Resistance 8:00", Items.SHIELD,
			new Entry("minecraft:resistance", 1, LONG_DURATION),
			new Entry("minecraft:absorption", 3, LONG_DURATION),
			new Entry("minecraft:fire_resistance", 0, LONG_DURATION));
		builtin("speed", "Speed III and Jump Boost II, 8:00", Items.FEATHER,
			new Entry("minecraft:speed", 2, LONG_DURATION),
			new Entry("minecraft:jump_boost", 1, LONG_DURATION));
		builtin("stealth", "Invisibility and Night Vision, 8:00", Items.ENDER_EYE,
			new Entry("minecraft:invisibility", 0, LONG_DURATION),
			new Entry("minecraft:night_vision", 0, LONG_DURATION));
		builtin("vision", "Night Vision only — film in caves without touching gamma", Items.GOLDEN_CARROT,
			new Entry("minecraft:night_vision", 0, LONG_DURATION));
	}

	private void builtin(String name, String description, Item icon, Entry... effects) {
		this.builtin.put(name, new Preset(name, description, icon, List.of(effects), true));
	}

	// ---------------------------------------------------------------- lookup

	/** Built-ins first, then custom ones, which is the order the menu shows. */
	public List<Preset> ordered() {
		List<Preset> presets = new ArrayList<>(this.builtin.values());
		presets.addAll(this.custom.values());
		return presets;
	}

	public Optional<Preset> get(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		Preset preset = this.builtin.get(key);
		return Optional.ofNullable(preset != null ? preset : this.custom.get(key));
	}

	public List<String> names() {
		List<String> names = new ArrayList<>(this.builtin.keySet());
		names.addAll(this.custom.keySet());
		return names;
	}

	// ---------------------------------------------------------------- applying

	public boolean apply(MinecraftServer server, ServerPlayer player, String name) {
		Optional<Preset> preset = get(name);
		if (preset.isEmpty() || !canUse(name, player)) {
			return false;
		}
		apply(server, player, preset.get());
		return true;
	}

	public void apply(MinecraftServer server, ServerPlayer player, Preset preset) {
		Registry<MobEffect> registry = server.registryAccess().lookupOrThrow(Registries.MOB_EFFECT);
		for (Entry entry : preset.effects()) {
			Identifier id = Identifier.tryParse(entry.effectId());
			if (id == null) {
				continue;
			}
			// An effect that no longer exists is skipped rather than failing the whole preset.
			registry.get(id).ifPresent(holder -> player.addEffect(new MobEffectInstance(
				(Holder<MobEffect>) holder, entry.durationTicks(), entry.amplifier(), false, false, true)));
		}
		Sfx.potion(player);
	}

	public static void clear(ServerPlayer player) {
		player.removeAllEffects();
	}

	// ---------------------------------------------------------------- authoring

	/**
	 * Saves the player's currently active effects as a preset.
	 *
	 * @return {@code false} when they have no effects to capture.
	 */
	public boolean createFromPlayer(ServerPlayer player, String name, Item icon) {
		String key = clean(name);
		if (this.builtin.containsKey(key)) {
			throw new IllegalArgumentException("'" + key + "' is a built-in preset name — pick another.");
		}
		List<Entry> effects = new ArrayList<>();
		for (MobEffectInstance active : player.getActiveEffects()) {
			Identifier id = player.level().registryAccess()
				.lookupOrThrow(Registries.MOB_EFFECT).getKey(active.getEffect().value());
			if (id != null) {
				effects.add(new Entry(id.toString(), active.getAmplifier(), active.getDuration()));
			}
		}
		if (effects.isEmpty()) {
			return false;
		}
		this.custom.put(key, new Preset(key, describe(effects), icon, List.copyOf(effects), false));
		save();
		return true;
	}

	public boolean delete(String name) {
		String key = clean(name);
		if (this.builtin.containsKey(key)) {
			throw new IllegalArgumentException("Built-in presets cannot be deleted.");
		}
		boolean removed = this.custom.remove(key) != null;
		if (removed) {
			this.allowedRanks.remove(key);
			save();
		}
		return removed;
	}

	private static String describe(List<Entry> effects) {
		return effects.size() + " effect(s), saved from a player";
	}

	private static String clean(String name) {
		String clean = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
		if (clean.isBlank()) {
			throw new IllegalArgumentException("Preset name must contain letters, numbers, underscores, or hyphens.");
		}
		return clean;
	}

	// ---------------------------------------------------------------- persistence

	public void load() {
		this.custom.clear();
		this.allowedRanks.clear();
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, PotionPresetService::defaults);
		if (!root.has("presets") || !root.get("presets").isJsonObject()) {
			return;
		}
		if (root.has("access") && root.get("access").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("access").entrySet()) {
				if (!entry.getValue().isJsonArray()) {
					continue;
				}
				Set<String> ranks = new LinkedHashSet<>();
				for (JsonElement value : entry.getValue().getAsJsonArray()) {
					String rank = cleanRank(value.getAsString());
					if (!rank.isEmpty()) {
						ranks.add(rank);
					}
				}
				if (!ranks.isEmpty()) {
					this.allowedRanks.put(entry.getKey().toLowerCase(Locale.ROOT), ranks);
				}
			}
		}
		for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("presets").entrySet()) {
			try {
				JsonObject object = entry.getValue().getAsJsonObject();
				List<Entry> effects = new ArrayList<>();
				for (JsonElement element : object.getAsJsonArray("effects")) {
					JsonObject effect = element.getAsJsonObject();
					effects.add(new Entry(
						effect.get("id").getAsString(),
						effect.get("amplifier").getAsInt(),
						effect.get("duration").getAsInt()));
				}
				this.custom.put(entry.getKey(), new Preset(
					entry.getKey(),
					object.has("description") ? object.get("description").getAsString() : describe(effects),
					Items.POTION, List.copyOf(effects), false));
			} catch (RuntimeException exception) {
				this.context.logger().warn("Ignoring invalid potion preset '{}'.", entry.getKey());
			}
		}
	}

	public void save() {
		JsonObject root = defaults();
		JsonObject presets = root.getAsJsonObject("presets");
		this.custom.forEach((name, preset) -> {
			JsonObject object = new JsonObject();
			object.addProperty("description", preset.description());
			JsonArray effects = new JsonArray();
			for (Entry entry : preset.effects()) {
				JsonObject effect = new JsonObject();
				effect.addProperty("id", entry.effectId());
				effect.addProperty("amplifier", entry.amplifier());
				effect.addProperty("duration", entry.durationTicks());
				effects.add(effect);
			}
			object.add("effects", effects);
			presets.add(name, object);
		});
		JsonObject access = new JsonObject();
		this.allowedRanks.forEach((name, ranks) -> {
			if (ranks.isEmpty()) {
				return;
			}
			JsonArray values = new JsonArray();
			ranks.forEach(values::add);
			access.add(name, values);
		});
		root.add("access", access);
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	public boolean canUse(String name, ServerPlayer player) {
		String key = name.toLowerCase(Locale.ROOT);
		if (get(key).isEmpty() || !this.context.permissions().has(player, PermissionService.PLAYER)) {
			return false;
		}
		Set<String> ranks = this.allowedRanks.get(key);
		return this.context.permissions().has(player, PermissionService.ADMIN) || ranks == null || ranks.isEmpty()
			|| ranks.contains(this.context.permissions().ranks().of(player.getUUID()).name());
	}

	public boolean canManage(ServerPlayer player) {
		return this.context.permissions().has(player, PermissionService.POTIONS_MANAGE);
	}

	public Set<String> allowedRanks(String name) {
		return Set.copyOf(this.allowedRanks.getOrDefault(name.toLowerCase(Locale.ROOT), Set.of()));
	}

	public boolean setRankAllowed(String name, String rankName, boolean allowed) {
		String key = name.toLowerCase(Locale.ROOT);
		if (get(key).isEmpty() || this.context.permissions().ranks().byName(rankName).isEmpty()) {
			return false;
		}
		String rank = this.context.permissions().ranks().byName(rankName).get().name();
		Set<String> ranks = new LinkedHashSet<>(this.allowedRanks.getOrDefault(key, Set.of()));
		if (allowed) ranks.add(rank); else ranks.remove(rank);
		if (ranks.isEmpty()) this.allowedRanks.remove(key); else this.allowedRanks.put(key, ranks);
		save();
		return true;
	}

	public void clearRankAccess(String name) {
		if (this.allowedRanks.remove(name.toLowerCase(Locale.ROOT)) != null) {
			save();
		}
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

	/** {@code builtin} presets cannot be deleted and are always listed first. */
	public record Preset(String name, String description, Item icon, List<Entry> effects, boolean builtin) {
	}

	/** Effects are stored by registry id so a preset survives a mod being added or removed. */
	public record Entry(String effectId, int amplifier, int durationTicks) {
	}
}
