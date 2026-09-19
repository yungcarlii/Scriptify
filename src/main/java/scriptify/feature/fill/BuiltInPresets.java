package scriptify.feature.fill;

import java.util.List;

/**
 * The presets a server starts with.
 *
 * <p>Four containers a scene actually asks for: a lived-in survival chest, a weapon store, a trading
 * post and a treasure room. Each is an explicit item list rather than a category selection, because a
 * category is a shape ("all building blocks") and a believable container is a specific handful.
 *
 * <p>Seeded once and then owned by the server: they are ordinary presets afterwards, editable and
 * deletable like any other. Nothing here overwrites a preset that already exists under the same name.
 */
public final class BuiltInPresets {
	private BuiltInPresets() {
	}

	/**
	 * What turns up in a survival player's storage.
	 *
	 * <p>Weighted towards the dull: cobblestone, dirt and a stack of torches are what a real chest is
	 * full of, and a container of nothing but diamonds reads as a creative-mode prop.
	 */
	private static final List<String> SURVIVAL = List.of(
		"minecraft:cobblestone", "minecraft:dirt", "minecraft:stone", "minecraft:andesite",
		"minecraft:diorite", "minecraft:granite", "minecraft:gravel", "minecraft:sand",
		"minecraft:oak_log", "minecraft:oak_planks", "minecraft:spruce_log", "minecraft:birch_planks",
		"minecraft:cobbled_deepslate", "minecraft:torch", "minecraft:stick", "minecraft:flint",
		"minecraft:coal", "minecraft:charcoal", "minecraft:raw_iron", "minecraft:iron_ingot",
		"minecraft:copper_ingot", "minecraft:redstone", "minecraft:wheat", "minecraft:wheat_seeds",
		"minecraft:bread", "minecraft:apple", "minecraft:carrot", "minecraft:potato",
		"minecraft:string", "minecraft:bone", "minecraft:arrow", "minecraft:rotten_flesh",
		"minecraft:gunpowder", "minecraft:leather", "minecraft:bucket", "minecraft:crafting_table");

	private static final List<String> ARMORY = List.of(
		"minecraft:iron_sword", "minecraft:diamond_sword", "minecraft:stone_sword",
		"minecraft:iron_axe", "minecraft:diamond_axe", "minecraft:bow", "minecraft:crossbow",
		"minecraft:arrow", "minecraft:spectral_arrow", "minecraft:shield", "minecraft:trident",
		"minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings",
		"minecraft:iron_boots", "minecraft:diamond_helmet", "minecraft:diamond_chestplate",
		"minecraft:diamond_leggings", "minecraft:diamond_boots", "minecraft:chainmail_chestplate",
		"minecraft:leather_chestplate", "minecraft:golden_apple", "minecraft:tnt");

	private static final List<String> MARKET = List.of(
		"minecraft:emerald", "minecraft:wheat", "minecraft:carrot", "minecraft:potato",
		"minecraft:beetroot", "minecraft:bread", "minecraft:apple", "minecraft:melon_slice",
		"minecraft:pumpkin", "minecraft:sugar_cane", "minecraft:paper", "minecraft:book",
		"minecraft:leather", "minecraft:white_wool", "minecraft:red_wool", "minecraft:blue_wool",
		"minecraft:red_dye", "minecraft:yellow_dye", "minecraft:blue_dye", "minecraft:glass",
		"minecraft:brick", "minecraft:flower_pot", "minecraft:egg", "minecraft:cooked_beef",
		"minecraft:cooked_chicken", "minecraft:cake", "minecraft:honey_bottle", "minecraft:clock");

	private static final List<String> VAULT = List.of(
		"minecraft:diamond", "minecraft:diamond_block", "minecraft:emerald", "minecraft:emerald_block",
		"minecraft:gold_ingot", "minecraft:gold_block", "minecraft:netherite_ingot",
		"minecraft:netherite_scrap", "minecraft:ancient_debris", "minecraft:enchanted_golden_apple",
		"minecraft:golden_apple", "minecraft:enchanted_book", "minecraft:ender_pearl",
		"minecraft:echo_shard", "minecraft:amethyst_shard", "minecraft:totem_of_undying",
		"minecraft:heart_of_the_sea", "minecraft:nautilus_shell", "minecraft:music_disc_pigstep",
		"minecraft:experience_bottle", "minecraft:lapis_lazuli", "minecraft:quartz");

	/** Writes any preset that is not already there. Returns how many were added. */
	public static int seed(FillService service) {
		int added = 0;
		added += add(service, "survival", "minecraft:cobblestone", SURVIVAL, 1, 24, 55);
		added += add(service, "armory", "minecraft:iron_sword", ARMORY, 1, 3, 40);
		added += add(service, "market", "minecraft:emerald", MARKET, 2, 16, 65);
		added += add(service, "vault", "minecraft:diamond_block", VAULT, 1, 6, 35);
		return added;
	}

	/**
	 * Adds one preset unless the name is taken.
	 *
	 * <p>Never overwrites: somebody who edited "vault" to suit their set should not lose it because
	 * the server restarted.
	 */
	private static int add(FillService service, String name, String icon, List<String> items,
			int min, int max, int density) {
		if (service.preset(name).isPresent()) {
			return 0;
		}
		FillSettings settings = new FillSettings();
		settings.clearCategories();
		settings.clearItems();
		items.forEach(settings::addItem);
		settings.setMinCount(min);
		settings.setMaxCount(max);
		settings.setDensity(density);
		settings.setIcon(icon);
		service.savePreset(name, settings);
		return 1;
	}
}
