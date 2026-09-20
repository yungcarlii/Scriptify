package scriptify.rollback;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

/**
 * One moment of a player, recorded in pieces.
 *
 * <p>The old backup was a flat list of inventory slots, which answers exactly one question: what was
 * I holding. It cannot answer "what was in my ender chest", "how much XP did I lose", "what effects
 * was I under", and - the one that actually costs people their evening - "what hit the floor when I
 * died". Those are all part of "put me back how I was", and a rollback that restores four fifths of
 * a player is a rollback somebody has to finish by hand.
 *
 * <h2>Why sections</h2>
 *
 * <p>Each section restores independently. A director who wants the gear back but not the XP, or the
 * effects back without the health, can say so. One undivided blob would force the whole thing or
 * nothing, and in practice people want the gear far more often than they want everything.
 */
public record Snapshot(
		String id,
		Instant createdAt,
		String reason,
		String note,
		List<ItemStack> inventory,
		List<ItemStack> enderChest,
		int experienceLevel,
		float experienceProgress,
		int totalExperience,
		List<MobEffectInstance> effects,
		GameType gameMode,
		float health,
		int food,
		float saturation,
		List<ItemStack> deathDrops) {

	/** What a snapshot was taken for. Drives retention and how the timeline colours it. */
	public static final String REASON_MANUAL = "manual";
	public static final String REASON_DEATH = "death";
	/** Taken automatically before any restore, which is what makes undo free. */
	public static final String REASON_BEFORE_RESTORE = "before-restore";
	public static final String REASON_AUTO = "auto";

	private static final DateTimeFormatter LABEL_FORMAT =
		DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());

	/** The independently restorable pieces. */
	public enum Section {
		INVENTORY("Inventory", "Main slots, armour and offhand"),
		ENDER_CHEST("Ender chest", "All ender chest slots"),
		EXPERIENCE("Experience", "Level and progress"),
		EFFECTS("Effects", "Active potion effects"),
		STATE("State", "Game mode, health, hunger"),
		DEATH_DROPS("Death drops", "What hit the floor when they died");

		public final String label;
		public final String description;

		Section(String label, String description) {
			this.label = label;
			this.description = description;
		}
	}

	public Snapshot {
		inventory = inventory == null ? List.of() : List.copyOf(inventory);
		enderChest = enderChest == null ? List.of() : List.copyOf(enderChest);
		effects = effects == null ? List.of() : List.copyOf(effects);
		deathDrops = deathDrops == null ? List.of() : List.copyOf(deathDrops);
		reason = reason == null || reason.isBlank() ? REASON_MANUAL : reason;
		note = note == null ? "" : note;
		gameMode = gameMode == null ? GameType.SURVIVAL : gameMode;
	}

	/** A short human label for the timeline. */
	public String label() {
		return LABEL_FORMAT.format(this.createdAt);
	}

	/** Which sections actually carry anything, so the menu does not offer empty ones. */
	public Set<Section> populatedSections() {
		Set<Section> present = EnumSet.noneOf(Section.class);
		if (containsAnything(this.inventory)) {
			present.add(Section.INVENTORY);
		}
		if (containsAnything(this.enderChest)) {
			present.add(Section.ENDER_CHEST);
		}
		if (this.totalExperience > 0 || this.experienceLevel > 0) {
			present.add(Section.EXPERIENCE);
		}
		if (!this.effects.isEmpty()) {
			present.add(Section.EFFECTS);
		}
		present.add(Section.STATE);
		if (containsAnything(this.deathDrops)) {
			present.add(Section.DEATH_DROPS);
		}
		return present;
	}

	/** How many non-empty stacks the snapshot holds across inventory, ender chest and drops. */
	public int itemCount() {
		return count(this.inventory) + count(this.enderChest) + count(this.deathDrops);
	}

	private static boolean containsAnything(List<ItemStack> stacks) {
		return count(stacks) > 0;
	}

	private static int count(List<ItemStack> stacks) {
		int total = 0;
		for (ItemStack stack : stacks) {
			if (stack != null && !stack.isEmpty()) {
				total++;
			}
		}
		return total;
	}

	// ------------------------------------------------------------------ json

	public JsonObject toJson(RegistryOps<JsonElement> ops, Logger logger) {
		JsonObject root = new JsonObject();
		root.addProperty("id", this.id);
		root.addProperty("createdAt", this.createdAt.toString());
		root.addProperty("reason", this.reason);
		root.addProperty("note", this.note);
		root.add("inventory", writeStacks(this.inventory, ops, logger));
		root.add("enderChest", writeStacks(this.enderChest, ops, logger));
		root.add("deathDrops", writeStacks(this.deathDrops, ops, logger));
		root.addProperty("experienceLevel", this.experienceLevel);
		root.addProperty("experienceProgress", this.experienceProgress);
		root.addProperty("totalExperience", this.totalExperience);
		root.addProperty("gameMode", this.gameMode.getName());
		root.addProperty("health", this.health);
		root.addProperty("food", this.food);
		root.addProperty("saturation", this.saturation);
		root.add("effects", writeEffects(this.effects));
		return root;
	}

	public static Snapshot fromJson(JsonObject root, RegistryOps<JsonElement> ops, Logger logger) {
		return new Snapshot(
			root.has("id") ? root.get("id").getAsString() : Long.toString(System.currentTimeMillis()),
			readInstant(root),
			root.has("reason") ? root.get("reason").getAsString() : REASON_MANUAL,
			root.has("note") ? root.get("note").getAsString() : "",
			readStacks(root, "inventory", ops, logger),
			readStacks(root, "enderChest", ops, logger),
			root.has("experienceLevel") ? root.get("experienceLevel").getAsInt() : 0,
			root.has("experienceProgress") ? root.get("experienceProgress").getAsFloat() : 0.0F,
			root.has("totalExperience") ? root.get("totalExperience").getAsInt() : 0,
			readEffects(root, logger),
			readGameMode(root),
			root.has("health") ? root.get("health").getAsFloat() : 20.0F,
			root.has("food") ? root.get("food").getAsInt() : 20,
			root.has("saturation") ? root.get("saturation").getAsFloat() : 5.0F,
			readStacks(root, "deathDrops", ops, logger));
	}

	private static Instant readInstant(JsonObject root) {
		try {
			return Instant.parse(root.get("createdAt").getAsString());
		} catch (RuntimeException ignored) {
			// A snapshot with an unreadable timestamp is still worth having; it simply sorts oldest.
			return Instant.EPOCH;
		}
	}

	private static GameType readGameMode(JsonObject root) {
		if (!root.has("gameMode")) {
			return GameType.SURVIVAL;
		}
		GameType byName = GameType.byName(root.get("gameMode").getAsString(), GameType.SURVIVAL);
		return byName == null ? GameType.SURVIVAL : byName;
	}

	private static JsonArray writeStacks(List<ItemStack> stacks, RegistryOps<JsonElement> ops,
			Logger logger) {
		JsonArray array = new JsonArray();
		for (ItemStack stack : stacks) {
			array.add(ItemStack.OPTIONAL_CODEC
				.encodeStart(ops, stack == null ? ItemStack.EMPTY : stack)
				.resultOrPartial(error ->
					logger.warn("Skipping an unserialisable stack in a snapshot: {}", error))
				.orElse(new JsonObject()));
		}
		return array;
	}

	private static List<ItemStack> readStacks(JsonObject root, String key,
			RegistryOps<JsonElement> ops, Logger logger) {
		List<ItemStack> stacks = new ArrayList<>();
		if (!root.has(key) || !root.get(key).isJsonArray()) {
			return stacks;
		}
		for (JsonElement element : root.getAsJsonArray(key)) {
			stacks.add(ItemStack.OPTIONAL_CODEC.parse(ops, element)
				.resultOrPartial(error ->
					logger.warn("Skipping an unreadable stack in a snapshot: {}", error))
				.orElse(ItemStack.EMPTY));
		}
		return stacks;
	}

	/**
	 * Effects are written by id rather than through a codec.
	 *
	 * <p>Three numbers and a registry name round-trip without needing registry-aware ops, and an
	 * effect from a mod that has since been removed then costs one skipped line instead of an
	 * unreadable file.
	 */
	private static JsonArray writeEffects(List<MobEffectInstance> effects) {
		JsonArray array = new JsonArray();
		for (MobEffectInstance effect : effects) {
			Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
			if (id == null) {
				continue;
			}
			JsonObject object = new JsonObject();
			object.addProperty("effect", id.toString());
			object.addProperty("duration", effect.getDuration());
			object.addProperty("amplifier", effect.getAmplifier());
			object.addProperty("ambient", effect.isAmbient());
			object.addProperty("visible", effect.isVisible());
			array.add(object);
		}
		return array;
	}

	private static List<MobEffectInstance> readEffects(JsonObject root, Logger logger) {
		List<MobEffectInstance> effects = new ArrayList<>();
		if (!root.has("effects") || !root.get("effects").isJsonArray()) {
			return effects;
		}
		for (JsonElement element : root.getAsJsonArray("effects")) {
			try {
				JsonObject object = element.getAsJsonObject();
				Identifier id = Identifier.tryParse(object.get("effect").getAsString());
				if (id == null || !BuiltInRegistries.MOB_EFFECT.containsKey(id)) {
					continue;
				}
				Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.get(id).orElse(null);
				if (holder == null) {
					continue;
				}
				effects.add(new MobEffectInstance(holder,
					object.get("duration").getAsInt(),
					object.get("amplifier").getAsInt(),
					object.has("ambient") && object.get("ambient").getAsBoolean(),
					!object.has("visible") || object.get("visible").getAsBoolean()));
			} catch (RuntimeException exception) {
				logger.warn("Skipping an unreadable effect in a snapshot.", exception);
			}
		}
		return effects;
	}
}
