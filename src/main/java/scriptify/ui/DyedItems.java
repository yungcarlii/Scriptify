package scriptify.ui;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The sixteen colours of an item family, picked by {@link DyeColor}.
 *
 * <p>Minecraft describes coloured items two different ways depending on the version. 26.x folds each
 * family into one item carrying a colour component, so a menu asks it to pick the lime one. 1.21.11
 * has sixteen separate constants instead, and {@code Items.LIME_DYE} is the only way to say it.
 * Neither spelling exists in the other version.
 *
 * <p>Menus should not have to know which of those is true. Every screen in the mod builds its icons
 * from a colour - a green toggle, a grey filler pane, a red cancel button - so the mapping is written
 * down once here and the call sites read the same on either version. Moving between versions is then
 * an edit to this file rather than to forty menus, which is the same reason {@link Colours} exists.
 *
 * <p>The tables are built once at class load and never change. A miss returns the white variant
 * rather than null, because a menu with a missing icon is a menu with a hole in it.
 */
public final class DyedItems {
	private static final Map<DyeColor, Item> DYE = new EnumMap<>(DyeColor.class);
	private static final Map<DyeColor, Item> WOOL = new EnumMap<>(DyeColor.class);
	private static final Map<DyeColor, Item> GLASS_PANE = new EnumMap<>(DyeColor.class);
	private static final Map<DyeColor, Item> BANNER = new EnumMap<>(DyeColor.class);
	private static final Map<DyeColor, Item> CONCRETE = new EnumMap<>(DyeColor.class);
	private static final Map<DyeColor, Item> CANDLE = new EnumMap<>(DyeColor.class);

	static {
		put(DyeColor.WHITE, Items.WHITE_DYE, Items.WHITE_WOOL, Items.WHITE_STAINED_GLASS_PANE,
			Items.WHITE_BANNER, Items.WHITE_CONCRETE, Items.WHITE_CANDLE);
		put(DyeColor.ORANGE, Items.ORANGE_DYE, Items.ORANGE_WOOL, Items.ORANGE_STAINED_GLASS_PANE,
			Items.ORANGE_BANNER, Items.ORANGE_CONCRETE, Items.ORANGE_CANDLE);
		put(DyeColor.MAGENTA, Items.MAGENTA_DYE, Items.MAGENTA_WOOL, Items.MAGENTA_STAINED_GLASS_PANE,
			Items.MAGENTA_BANNER, Items.MAGENTA_CONCRETE, Items.MAGENTA_CANDLE);
		put(DyeColor.LIGHT_BLUE, Items.LIGHT_BLUE_DYE, Items.LIGHT_BLUE_WOOL,
			Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.LIGHT_BLUE_BANNER, Items.LIGHT_BLUE_CONCRETE,
			Items.LIGHT_BLUE_CANDLE);
		put(DyeColor.YELLOW, Items.YELLOW_DYE, Items.YELLOW_WOOL, Items.YELLOW_STAINED_GLASS_PANE,
			Items.YELLOW_BANNER, Items.YELLOW_CONCRETE, Items.YELLOW_CANDLE);
		put(DyeColor.LIME, Items.LIME_DYE, Items.LIME_WOOL, Items.LIME_STAINED_GLASS_PANE,
			Items.LIME_BANNER, Items.LIME_CONCRETE, Items.LIME_CANDLE);
		put(DyeColor.PINK, Items.PINK_DYE, Items.PINK_WOOL, Items.PINK_STAINED_GLASS_PANE,
			Items.PINK_BANNER, Items.PINK_CONCRETE, Items.PINK_CANDLE);
		put(DyeColor.GRAY, Items.GRAY_DYE, Items.GRAY_WOOL, Items.GRAY_STAINED_GLASS_PANE,
			Items.GRAY_BANNER, Items.GRAY_CONCRETE, Items.GRAY_CANDLE);
		put(DyeColor.LIGHT_GRAY, Items.LIGHT_GRAY_DYE, Items.LIGHT_GRAY_WOOL,
			Items.LIGHT_GRAY_STAINED_GLASS_PANE, Items.LIGHT_GRAY_BANNER, Items.LIGHT_GRAY_CONCRETE,
			Items.LIGHT_GRAY_CANDLE);
		put(DyeColor.CYAN, Items.CYAN_DYE, Items.CYAN_WOOL, Items.CYAN_STAINED_GLASS_PANE,
			Items.CYAN_BANNER, Items.CYAN_CONCRETE, Items.CYAN_CANDLE);
		put(DyeColor.PURPLE, Items.PURPLE_DYE, Items.PURPLE_WOOL, Items.PURPLE_STAINED_GLASS_PANE,
			Items.PURPLE_BANNER, Items.PURPLE_CONCRETE, Items.PURPLE_CANDLE);
		put(DyeColor.BLUE, Items.BLUE_DYE, Items.BLUE_WOOL, Items.BLUE_STAINED_GLASS_PANE,
			Items.BLUE_BANNER, Items.BLUE_CONCRETE, Items.BLUE_CANDLE);
		put(DyeColor.BROWN, Items.BROWN_DYE, Items.BROWN_WOOL, Items.BROWN_STAINED_GLASS_PANE,
			Items.BROWN_BANNER, Items.BROWN_CONCRETE, Items.BROWN_CANDLE);
		put(DyeColor.GREEN, Items.GREEN_DYE, Items.GREEN_WOOL, Items.GREEN_STAINED_GLASS_PANE,
			Items.GREEN_BANNER, Items.GREEN_CONCRETE, Items.GREEN_CANDLE);
		put(DyeColor.RED, Items.RED_DYE, Items.RED_WOOL, Items.RED_STAINED_GLASS_PANE,
			Items.RED_BANNER, Items.RED_CONCRETE, Items.RED_CANDLE);
		put(DyeColor.BLACK, Items.BLACK_DYE, Items.BLACK_WOOL, Items.BLACK_STAINED_GLASS_PANE,
			Items.BLACK_BANNER, Items.BLACK_CONCRETE, Items.BLACK_CANDLE);
	}

	private DyedItems() {
	}

	public static Item dye(DyeColor colour) {
		return DYE.getOrDefault(colour, Items.WHITE_DYE);
	}

	public static Item wool(DyeColor colour) {
		return WOOL.getOrDefault(colour, Items.WHITE_WOOL);
	}

	public static Item glassPane(DyeColor colour) {
		return GLASS_PANE.getOrDefault(colour, Items.WHITE_STAINED_GLASS_PANE);
	}

	public static Item banner(DyeColor colour) {
		return BANNER.getOrDefault(colour, Items.WHITE_BANNER);
	}

	public static Item concrete(DyeColor colour) {
		return CONCRETE.getOrDefault(colour, Items.WHITE_CONCRETE);
	}

	public static Item candle(DyeColor colour) {
		return CANDLE.getOrDefault(colour, Items.WHITE_CANDLE);
	}

	private static void put(DyeColor colour, Item dye, Item wool, Item glassPane, Item banner,
			Item concrete, Item candle) {
		DYE.put(colour, dye);
		WOOL.put(colour, wool);
		GLASS_PANE.put(colour, glassPane);
		BANNER.put(colour, banner);
		CONCRETE.put(colour, concrete);
		CANDLE.put(colour, candle);
	}
}
