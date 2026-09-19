package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.ItemMakerService;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;

/**
 * The item maker: edits whatever the player is holding.
 *
 * <p>The held stack sits at the top of every screen in here, so a change is visible the moment it
 * lands rather than needing the menu closed to check.
 *
 * <p>Everything is reachable by clicking. The equivalent {@code /item} commands still exist for
 * scripting and for anyone who would rather type, but nothing in the editor requires them — an
 * enchantment browser that tells you to go and type a command is not an editor.
 */
public final class ItemMenu {
	static final int PREVIEW_SLOT = 4;
	static final int BACK_SLOT = 45;
	static final int CLOSE_SLOT = 53;

	private ItemMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player) {
		if (!guard(context, player)) {
			return;
		}
		ItemStack held = player.getInventory().getSelectedItem();

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("» Item Maker «").withStyle(ChatFormatting.GOLD));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);
		preview(gui, held);

		gui.setSlot(19, Menus.button(Items.NAME_TAG, "Name", ChatFormatting.AQUA,
				"Rename the item.",
				"& colour codes work, e.g. &cRed Sword",
				"",
				"Now: " + ItemMakerService.plainName(held))
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(player);
				promptName(context, player);
			}).build());

		gui.setSlot(20, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.RED), "Name Colour", ChatFormatting.AQUA,
				"Repaint the name in one colour,",
				"without retyping it.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(player);
				ItemColourMenu.open(context, player);
			}).build());

		int loreLines = ItemMakerService.currentLore(held).size();
		gui.setSlot(21, Menus.button(Items.WRITABLE_BOOK, "Lore", ChatFormatting.AQUA,
				"Add, reorder and remove",
				"the description lines.",
				"",
				loreLines == 0 ? "No lore yet." : loreLines + " line(s).")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(player);
				ItemLoreMenu.open(context, player, 0);
			}).build());

		gui.setSlot(22, Menus.button(Items.ENCHANTED_BOOK, "Enchantments", ChatFormatting.LIGHT_PURPLE,
				"Browse every enchantment and",
				"set any level on any item.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(player);
				ItemEnchantMenu.open(context, player, 0);
			}).build());

		gui.setSlot(23, Menus.toggle(Items.NETHERITE_INGOT, "Unbreakable",
				ItemMakerService.isUnbreakable(held),
				"The item never loses durability.")
			.setCallback((slot, type, action, ignored) -> {
				ItemStack current = player.getInventory().getSelectedItem();
				boolean next = !ItemMakerService.isUnbreakable(current);
				ItemMakerService.setUnbreakable(current, next);
				Menus.notifyToggle(player, "Unbreakable " + (next ? "on" : "off"), next);
				open(context, player);
			}).build());

		gui.setSlot(24, Menus.toggle(Items.GLOWSTONE_DUST, "Glint",
				ItemMakerService.hasGlintOverride(held),
				"Forces the enchanted shimmer on,",
				"with or without real enchantments.")
			.setCallback((slot, type, action, ignored) -> {
				ItemStack current = player.getInventory().getSelectedItem();
				boolean next = !ItemMakerService.hasGlintOverride(current);
				ItemMakerService.setGlint(current, next);
				Menus.notifyToggle(player, "Glint " + (next ? "on" : "off"), next);
				open(context, player);
			}).build());

		gui.setSlot(25, Menus.button(Items.PAPER, "Stack Size", ChatFormatting.YELLOW,
				"Left click +1, right click -1.",
				"Hold shift for 8 at a time.",
				"",
				"Now: " + held.getCount())
			.setCallback((slot, type, action, ignored) -> {
				ItemStack current = player.getInventory().getSelectedItem();
				int step = type.shift ? 8 : 1;
				ItemMakerService.setCount(current, current.getCount() + (type.isRight ? -step : step));
				Sfx.click(player);
				open(context, player);
			}).build());

		gui.setSlot(31, Menus.button(Items.BARRIER, "Clear Everything", ChatFormatting.RED,
				"Removes the name, lore, enchantments",
				"and the forced glint.",
				"",
				"Durability and contents are kept.")
			.setCallback((slot, type, action, ignored) -> {
				ItemStack current = player.getInventory().getSelectedItem();
				ItemMakerService.clearName(current);
				ItemMakerService.clearLore(current);
				ItemMakerService.clearEnchantments(current);
				ItemMakerService.clearGlintOverride(current);
				Menus.notify(player, "Reset the item.");
				open(context, player);
			}).build());

		gui.setSlot(BACK_SLOT, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, player)).build());
		gui.setSlot(CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	/**
	 * Permission and an item in hand, checked once for every screen in the editor.
	 *
	 * <p>Re-checked on each redraw rather than only on the way in, because the whole editor works on the
	 * live held stack: a player can put the item away without closing the menu.
	 */
	static boolean guard(ScriptifyContext context, ServerPlayer player) {
		if (!context.permissions().has(player, PermissionService.ITEMS)) {
			Menus.deny(player, "You do not have permission to use the item maker.");
			return false;
		}
		if (player.getInventory().getSelectedItem().isEmpty()) {
			Menus.deny(player, "Hold the item you want to edit.");
			player.closeContainer();
			return false;
		}
		return true;
	}

	/** The live item, shown at the top of every screen in the editor. */
	static void preview(SimpleGui gui, ItemStack held) {
		gui.setSlot(PREVIEW_SLOT, GuiElementBuilder.from(held.copy())
			.setName(Msg.title("Editing this item", ChatFormatting.GOLD))
			.addLoreLine(Msg.hint("Every change applies to the item in your hand."))
			.build());
	}

	private static void promptName(ScriptifyContext context, ServerPlayer player) {
		AnvilInputGui anvil = new AnvilInputGui(player, false);
		anvil.setTitle(Component.literal("Item name"));
		anvil.setDefaultInputValue(ItemMakerService.plainName(player.getInventory().getSelectedItem()));
		anvil.setSlot(0, GuiElementBuilder.from(player.getInventory().getSelectedItem().copy())
			.setName(Msg.hint("Type a name, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
				"Renames the held item.")
			.setCallback((slot, type, action, ignored) -> {
				ItemMakerService.setName(player.getInventory().getSelectedItem(), anvil.getInput());
				Menus.notify(player, "Renamed the item.");
				open(context, player);
			}).build());
		anvil.open();
	}
}
