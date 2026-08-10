package scriptify.feature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;

/**
 * Named bundles of potion effects, so a scene can be set up with one command instead of a dozen
 * {@code /effect} calls.
 */
public final class PotionPresets {
	/** 1 minute 30 seconds, in ticks. */
	private static final int SHORT_DURATION = 1800;
	/** 8 minutes, in ticks — the length of an extended vanilla potion. */
	private static final int LONG_DURATION = 9600;

	private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

	static {
		// Combat: Strength II and Speed II for 1:30, Fire Resistance for 8 minutes.
		register("combat", "Strength II 1:30, Speed II 1:30, Fire Resistance 8:00",
			Items.POTION,
			new Entry(MobEffects.STRENGTH, 1, SHORT_DURATION),
			new Entry(MobEffects.SPEED, 1, SHORT_DURATION),
			new Entry(MobEffects.FIRE_RESISTANCE, 0, LONG_DURATION));
		register("tank", "Resistance II, Absorption IV, Fire Resistance 8:00",
			Items.SHIELD,
			new Entry(MobEffects.RESISTANCE, 1, LONG_DURATION),
			new Entry(MobEffects.ABSORPTION, 3, LONG_DURATION),
			new Entry(MobEffects.FIRE_RESISTANCE, 0, LONG_DURATION));
		register("speed", "Speed III and Jump Boost II, 8:00",
			Items.FEATHER,
			new Entry(MobEffects.SPEED, 2, LONG_DURATION),
			new Entry(MobEffects.JUMP_BOOST, 1, LONG_DURATION));
		register("stealth", "Invisibility and Night Vision, 8:00",
			Items.ENDER_EYE,
			new Entry(MobEffects.INVISIBILITY, 0, LONG_DURATION),
			new Entry(MobEffects.NIGHT_VISION, 0, LONG_DURATION));
		register("vision", "Night Vision only — film in caves without touching gamma",
			Items.GOLDEN_CARROT,
			new Entry(MobEffects.NIGHT_VISION, 0, LONG_DURATION));
	}

	private PotionPresets() {
	}

	private static void register(String name, String description, net.minecraft.world.item.Item icon, Entry... effects) {
		PRESETS.put(name, new Preset(name, description, icon, List.of(effects)));
	}

	public static Map<String, Preset> all() {
		return Map.copyOf(PRESETS);
	}

	public static List<Preset> ordered() {
		return List.copyOf(PRESETS.values());
	}

	/**
	 * Applies a preset by name.
	 *
	 * @return {@code false} when no preset with that name exists.
	 */
	public static boolean apply(ServerPlayer player, String name) {
		Preset preset = PRESETS.get(name.toLowerCase(Locale.ROOT));
		if (preset == null) {
			return false;
		}
		preset.applyTo(player);
		return true;
	}

	/** Strips every active effect — the "clear" option in the potion menu. */
	public static void clear(ServerPlayer player) {
		player.removeAllEffects();
	}

	public record Preset(String name, String description, net.minecraft.world.item.Item icon, List<Entry> effects) {
		public void applyTo(ServerPlayer player) {
			for (Entry entry : this.effects) {
				// Hidden particles: visible swirls read badly on camera.
				player.addEffect(new MobEffectInstance(entry.effect(), entry.durationTicks(), entry.amplifier(),
					false, false, true));
			}
			Sfx.potion(player);
		}
	}

	public record Entry(Holder<MobEffect> effect, int amplifier, int durationTicks) {
	}
}
