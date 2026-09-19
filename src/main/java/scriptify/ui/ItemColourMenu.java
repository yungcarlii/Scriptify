package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.ItemMakerService;
import scriptify.feature.Sfx;

/**
 * Repaints the item's name in one of the sixteen chat colours.
 *
 * <p>Exists because recolouring is the single most common edit and typing {@code &c} in front of a
 * name you already wrote is a silly thing to have to do. Each swatch is a wool block in the colour it
 * applies, so the menu is its own legend.
 *
 * <p>The words are kept and the colour replaced, which means a name that already had codes in it
 * comes out uniformly in the new colour rather than partially repainted.
 */
public final class ItemColourMenu {
	/** The sixteen chat colours, each paired with a block that actually looks like it. */
	private static final Map<ChatFormatting, Item> SWATCHES = new LinkedHashMap<>();

	static {
		SWATCHES.put(ChatFormatting.WHITE, scriptify.ui.DyedItems.wool(DyeColor.WHITE));
		SWATCHES.put(ChatFormatting.GRAY, scriptify.ui.DyedItems.wool(DyeColor.LIGHT_GRAY));
		SWATCHES.put(ChatFormatting.DARK_GRAY, scriptify.ui.DyedItems.wool(DyeColor.GRAY));
		SWATCHES.put(ChatFormatting.BLACK, scriptify.ui.DyedItems.wool(DyeColor.BLACK));
		SWATCHES.put(ChatFormatting.RED, scriptify.ui.DyedItems.wool(DyeColor.RED));
		SWATCHES.put(ChatFormatting.DARK_RED, Items.REDSTONE_BLOCK);
		SWATCHES.put(ChatFormatting.GOLD, scriptify.ui.DyedItems.wool(DyeColor.ORANGE));
		SWATCHES.put(ChatFormatting.YELLOW, scriptify.ui.DyedItems.wool(DyeColor.YELLOW));
		SWATCHES.put(ChatFormatting.GREEN, scriptify.ui.DyedItems.wool(DyeColor.LIME));
		SWATCHES.put(ChatFormatting.DARK_GREEN, scriptify.ui.DyedItems.wool(DyeColor.GREEN));
		SWATCHES.put(ChatFormatting.AQUA, scriptify.ui.DyedItems.wool(DyeColor.LIGHT_BLUE));
		SWATCHES.put(ChatFormatting.DARK_AQUA, scriptify.ui.DyedItems.wool(DyeColor.CYAN));
		SWATCHES.put(ChatFormatting.BLUE, scriptify.ui.DyedItems.wool(DyeColor.BLUE));
		SWATCHES.put(ChatFormatting.DARK_BLUE, Items.LAPIS_BLOCK);
		SWATCHES.put(ChatFormatting.LIGHT_PURPLE, scriptify.ui.DyedItems.wool(DyeColor.MAGENTA));
		SWATCHES.put(ChatFormatting.DARK_PURPLE, scriptify.ui.DyedItems.wool(DyeColor.PURPLE));
	}

	/** Two rows of eight, centred — the sixteen colours land in a clean block. */
	private static final int[] CELLS = {
		10, 11, 12, 13, 14, 15, 16, 19,
		20, 21, 22, 23, 24, 25, 28, 29,
	};

	private ItemColourMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player) {
		if (!ItemMenu.guard(context, player)) {
			return;
		}
		ItemStack held = player.getInventory().getSelectedItem();

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("» Name Colour «").withStyle(ChatFormatting.AQUA));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);
		ItemMenu.preview(gui, held);

		int cell = 0;
		for (Map.Entry<ChatFormatting, Item> swatch : SWATCHES.entrySet()) {
			ChatFormatting colour = swatch.getKey();
			gui.setSlot(CELLS[cell++], Menus.button(swatch.getValue(), name(colour), colour,
					"Repaints the name in this colour.")
				.setCallback((slot, type, action, ignored) -> {
					ItemMakerService.recolour(player.getInventory().getSelectedItem(), colour);
					Menus.notify(player, "Name is now " + name(colour).toLowerCase(java.util.Locale.ROOT) + ".");
					Sfx.repair(player);
					ItemMenu.open(context, player);
				}).build());
		}

		gui.setSlot(ItemMenu.BACK_SLOT, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> ItemMenu.open(context, player)).build());
		gui.setSlot(ItemMenu.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	/** "DARK_AQUA" reads badly on a button; "Dark Aqua" does not. */
	private static String name(ChatFormatting colour) {
		String[] words = Colours.name(colour).split("_");
		StringBuilder label = new StringBuilder();
		for (String word : words) {
			if (!label.isEmpty()) {
				label.append(' ');
			}
			label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return label.toString();
	}
}
