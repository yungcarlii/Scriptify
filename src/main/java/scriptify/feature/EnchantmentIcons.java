package scriptify.feature;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * A symbolic item for each vanilla enchantment.
 *
 * <p>Deriving the icon from an enchantment's supported items gives you the tool it goes on, which
 * means every armour enchantment is a chestplate and every sword enchantment is a sword — a grid where
 * a third of the entries look identical and none of them say what they do.
 *
 * <p>These say what the enchantment <em>is</em> instead: a feather for Feather Falling, a cactus for
 * Thorns, ice for Frost Walker, a bottle of experience for Mending. The grid becomes readable at a
 * glance rather than by tooltip.
 *
 * <p>Vanilla only. Anything unlisted, including every modded enchantment, falls back to the
 * data-derived item, which is still better than a book for all of them.
 */
public final class EnchantmentIcons {
	private static final Map<String, Item> ICONS = new LinkedHashMap<>();

	static {
		// Armour
		ICONS.put("minecraft:protection", Items.SHIELD);
		ICONS.put("minecraft:fire_protection", Items.MAGMA_CREAM);
		ICONS.put("minecraft:feather_falling", Items.FEATHER);
		ICONS.put("minecraft:blast_protection", Items.TNT);
		ICONS.put("minecraft:projectile_protection", Items.ARROW);
		ICONS.put("minecraft:respiration", Items.GLASS_BOTTLE);
		ICONS.put("minecraft:aqua_affinity", Items.WATER_BUCKET);
		ICONS.put("minecraft:thorns", Items.CACTUS);
		ICONS.put("minecraft:depth_strider", Items.LILY_PAD);
		ICONS.put("minecraft:frost_walker", Items.PACKED_ICE);
		ICONS.put("minecraft:binding_curse", Items.IRON_CHAIN);
		ICONS.put("minecraft:soul_speed", Items.SOUL_SAND);
		ICONS.put("minecraft:swift_sneak", Items.ECHO_SHARD);

		// Melee
		ICONS.put("minecraft:sharpness", Items.FLINT);
		ICONS.put("minecraft:smite", Items.ROTTEN_FLESH);
		ICONS.put("minecraft:bane_of_arthropods", Items.SPIDER_EYE);
		ICONS.put("minecraft:knockback", Items.PISTON);
		ICONS.put("minecraft:fire_aspect", Items.BLAZE_POWDER);
		ICONS.put("minecraft:looting", Items.EMERALD);
		ICONS.put("minecraft:sweeping_edge", Items.SUGAR_CANE);

		// Tools
		ICONS.put("minecraft:efficiency", Items.SUGAR);
		ICONS.put("minecraft:silk_touch", Items.STRING);
		ICONS.put("minecraft:unbreaking", Items.OBSIDIAN);
		ICONS.put("minecraft:fortune", Items.DIAMOND);
		ICONS.put("minecraft:mending", Items.EXPERIENCE_BOTTLE);
		ICONS.put("minecraft:vanishing_curse", Items.ENDER_PEARL);

		// Bow and crossbow
		ICONS.put("minecraft:power", Items.GUNPOWDER);
		ICONS.put("minecraft:punch", Items.SLIME_BALL);
		ICONS.put("minecraft:flame", Items.FIRE_CHARGE);
		ICONS.put("minecraft:infinity", Items.SPECTRAL_ARROW);
		ICONS.put("minecraft:multishot", Items.FIREWORK_ROCKET);
		ICONS.put("minecraft:piercing", Items.POINTED_DRIPSTONE);
		ICONS.put("minecraft:quick_charge", Items.CLOCK);

		// Trident
		ICONS.put("minecraft:loyalty", Items.LEAD);
		ICONS.put("minecraft:impaling", Items.PRISMARINE_SHARD);
		ICONS.put("minecraft:riptide", Items.HEART_OF_THE_SEA);
		ICONS.put("minecraft:channeling", Items.LIGHTNING_ROD);

		// Mace
		ICONS.put("minecraft:density", Items.ANVIL);
		ICONS.put("minecraft:breach", Items.NETHERITE_SCRAP);
		ICONS.put("minecraft:wind_burst", Items.WIND_CHARGE);

		// Fishing rod
		ICONS.put("minecraft:luck_of_the_sea", Items.NAUTILUS_SHELL);
		ICONS.put("minecraft:lure", Items.TROPICAL_FISH);
	}

	private EnchantmentIcons() {
	}

	/** The symbolic item for this enchantment id, or {@code null} when there is no entry for it. */
	public static Item forId(String id) {
		return ICONS.get(id);
	}
}
