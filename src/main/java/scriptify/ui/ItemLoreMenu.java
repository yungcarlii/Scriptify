package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.ItemMakerService;
import scriptify.feature.Sfx;

/**
 * Edits the item's description lines.
 *
 * <p>Every line is shown as its own button in the order it appears on the item, so removing the third
 * of five is one click rather than deleting back to it. "Undo Last" is kept alongside, because that is
 * still the quickest thing to reach for after a typo.
 */
public final class ItemLoreMenu {
	private static final int[] CELLS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};
	private static final int PREVIOUS_SLOT = 48;
	private static final int NEXT_SLOT = 50;

	private ItemLoreMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player, int page) {
		if (!ItemMenu.guard(context, player)) {
			return;
		}
		ItemStack held = player.getInventory().getSelectedItem();
		List<Component> lines = ItemMakerService.currentLore(held);
		int totalPages = Math.max(1, (lines.size() + CELLS.length - 1) / CELLS.length);
		int current = Math.max(0, Math.min(page, totalPages - 1));

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("» Lore «").withStyle(ChatFormatting.AQUA));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);
		ItemMenu.preview(gui, held);

		for (int cell = 0; cell < CELLS.length; cell++) {
			int index = current * CELLS.length + cell;
			if (index >= lines.size()) {
				break;
			}
			int lineNumber = index;
			gui.setSlot(CELLS[cell], new GuiElementBuilder(Items.PAPER)
				.setName(Component.literal("Line " + (index + 1)).withStyle(ChatFormatting.WHITE))
				.addLoreLine(Component.empty())
				.addLoreLine(lines.get(index).copy())
				.addLoreLine(Component.empty())
				.addLoreLine(Msg.hint("Click to remove this line"))
				.setCallback((slot, type, action, ignored) -> {
					ItemMakerService.removeLoreLine(player.getInventory().getSelectedItem(), lineNumber);
					Menus.notify(player, "Removed line " + (lineNumber + 1) + ".");
					open(context, player, current);
				}).build());
		}

		if (lines.isEmpty()) {
			gui.setSlot(22, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.GRAY), "No lore yet", ChatFormatting.GRAY,
				"Add the first line below.").build());
		}

		gui.setSlot(46, Menus.button(Items.WRITABLE_BOOK, "Add Line", ChatFormatting.GREEN,
				"Appends a line to the bottom.",
				"& colour codes work here too.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(player);
				promptLine(context, player, current);
			}).build());

		gui.setSlot(47, Menus.button(Items.SHEARS, "Undo Last", ChatFormatting.YELLOW,
				"Removes the bottom line.")
			.setCallback((slot, type, action, ignored) -> {
				if (ItemMakerService.removeLastLoreLine(player.getInventory().getSelectedItem())) {
					Menus.notify(player, "Removed the last lore line.");
				} else {
					Menus.deny(player, "There is no lore to remove.");
				}
				open(context, player, current);
			}).build());

		gui.setSlot(51, Menus.button(Items.BARRIER, "Clear All", ChatFormatting.RED,
				"Removes every line at once.")
			.setCallback((slot, type, action, ignored) -> {
				ItemMakerService.clearLore(player.getInventory().getSelectedItem());
				Menus.notify(player, "Cleared the lore.");
				open(context, player, 0);
			}).build());

		if (current > 0) {
			gui.setSlot(PREVIOUS_SLOT, Menus.previousPageButton(current, totalPages)
				.setCallback((slot, type, action, ignored) -> open(context, player, current - 1)).build());
		}
		if (current < totalPages - 1) {
			gui.setSlot(NEXT_SLOT, Menus.nextPageButton(current + 2, totalPages)
				.setCallback((slot, type, action, ignored) -> open(context, player, current + 1)).build());
		}

		gui.setSlot(ItemMenu.BACK_SLOT, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> ItemMenu.open(context, player)).build());
		gui.setSlot(ItemMenu.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	private static void promptLine(ScriptifyContext context, ServerPlayer player, int page) {
		AnvilInputGui anvil = new AnvilInputGui(player, false);
		anvil.setTitle(Component.literal("Add a lore line"));
		anvil.setDefaultInputValue("");
		anvil.setSlot(0, new GuiElementBuilder(Items.WRITABLE_BOOK)
			.setName(Msg.hint("Type a line, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Add", ChatFormatting.GREEN,
				"Appends this line to the item's lore.")
			.setCallback((slot, type, action, ignored) -> {
				try {
					ItemMakerService.addLoreLine(player.getInventory().getSelectedItem(), anvil.getInput());
					Menus.notify(player, "Added a lore line.");
				} catch (IllegalArgumentException exception) {
					Menus.deny(player, exception.getMessage());
				}
				open(context, player, page);
			}).build());
		anvil.open();
	}
}
