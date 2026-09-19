package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import scriptify.api.ScriptifyContext;
import scriptify.feature.ItemMakerService;
import scriptify.feature.Sfx;

/**
 * Browses every enchantment the server knows and puts any of them on the held item.
 *
 * <p>Levels are set by clicking rather than typed, because the useful range is small and the whole
 * point of the editor is not having to remember ids. Shift jumps to the extremes, which is where a
 * scene usually wants to be anyway: the natural maximum, or gone.
 *
 * <p>Enchantments already on the item are pulled to the front and shown glinting, so building on what
 * is there does not mean hunting for it across five pages.
 */
public final class ItemEnchantMenu {
	private static final int[] CELLS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};
	private static final int PREVIOUS_SLOT = 48;
	private static final int NEXT_SLOT = 50;

	private ItemEnchantMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player, int page) {
		if (!ItemMenu.guard(context, player)) {
			return;
		}
		ItemStack held = player.getInventory().getSelectedItem();
		List<Holder<Enchantment>> all = ordered(context, held);
		int totalPages = Math.max(1, (all.size() + CELLS.length - 1) / CELLS.length);
		int current = Math.max(0, Math.min(page, totalPages - 1));

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("» Enchantments «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);
		ItemMenu.preview(gui, held);

		for (int cell = 0; cell < CELLS.length; cell++) {
			int index = current * CELLS.length + cell;
			if (index >= all.size()) {
				break;
			}
			Holder<Enchantment> enchantment = all.get(index);
			gui.setSlot(CELLS[cell], row(context, player, enchantment, held, current));
		}

		if (current > 0) {
			gui.setSlot(PREVIOUS_SLOT, Menus.previousPageButton(current, totalPages)
				.setCallback((slot, type, action, ignored) -> {
					Sfx.click(player);
					open(context, player, current - 1);
				}).build());
		}
		if (current < totalPages - 1) {
			gui.setSlot(NEXT_SLOT, Menus.nextPageButton(current + 2, totalPages)
				.setCallback((slot, type, action, ignored) -> {
					Sfx.click(player);
					open(context, player, current + 1);
				}).build());
		}

		gui.setSlot(46, Menus.button(Items.BARRIER, "Clear All", ChatFormatting.RED,
				"Removes every enchantment on the item.")
			.setCallback((slot, type, action, ignored) -> {
				ItemMakerService.clearEnchantments(player.getInventory().getSelectedItem());
				Menus.notify(player, "Cleared every enchantment.");
				open(context, player, 0);
			}).build());

		gui.setSlot(ItemMenu.BACK_SLOT, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> ItemMenu.open(context, player)).build());
		gui.setSlot(ItemMenu.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	/** Applied enchantments first, each group already sorted by name. */
	private static List<Holder<Enchantment>> ordered(ScriptifyContext context, ItemStack held) {
		List<Holder<Enchantment>> all = ItemMakerService.enchantments(context.server());
		return all.stream()
			.sorted(java.util.Comparator.comparingInt(
				holder -> ItemMakerService.levelOf(holder, held) > 0 ? 0 : 1))
			.toList();
	}

	private static eu.pb4.sgui.api.elements.GuiElement row(ScriptifyContext context, ServerPlayer player,
			Holder<Enchantment> enchantment, ItemStack held, int page) {
		int level = ItemMakerService.levelOf(enchantment, held);
		int natural = ItemMakerService.naturalMaxLevel(enchantment);
		boolean applied = level > 0;

		// The item the enchantment actually goes on, so the grid can be skimmed by shape rather than
		// read line by line — swords together, armour together, tools together.
		ItemStack icon = new ItemStack(ItemMakerService.iconFor(enchantment));
		if (applied) {
			// Glint marks what is already on the item, which the icon alone no longer distinguishes.
			ItemMakerService.setGlint(icon, true);
		}
		GuiElementBuilder button = GuiElementBuilder.from(icon)
			.hideDefaultTooltip()
			.setName(ItemMakerService.displayName(enchantment, Math.max(level, 1))
				.copy().withStyle(applied ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY))
			.addLoreLine(Component.empty())
			.addLoreLine(applied
				? Msg.line("Level " + level, ChatFormatting.WHITE)
				: Msg.hint("Not on this item"))
			.addLoreLine(Msg.hint("Vanilla goes up to " + natural))
			.addLoreLine(Component.empty())
			.addLoreLine(Msg.hint("Left click: +1 level"))
			.addLoreLine(Msg.hint("Right click: -1 level"))
			.addLoreLine(Msg.hint("Shift + left: level " + natural))
			.addLoreLine(Msg.hint("Shift + right: remove"));

		return button.setCallback((slot, type, action, ignored) -> {
			ItemStack live = player.getInventory().getSelectedItem();
			int now = ItemMakerService.levelOf(enchantment, live);
			int next;
			if (type.shift) {
				next = type.isRight ? 0 : natural;
			} else {
				next = now + (type.isRight ? -1 : 1);
			}
			ItemMakerService.setLevel(live, enchantment, next);
			if (next <= 0) {
				Sfx.click(player);
			} else {
				Sfx.repair(player);
			}
			open(context, player, page);
		}).build();
	}
}
