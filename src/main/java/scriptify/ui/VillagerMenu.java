package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;
import scriptify.feature.VillagerMakerService;
import scriptify.permission.PermissionService;

/**
 * The villager maker as a screen: look at a scene villager, open this, edit its shop.
 *
 * <p>Trades are the hard part to do by command, because each one is three item stacks and typing
 * three stacks with components is not something anybody wants to do twice. Here a trade is built from
 * what you are holding, and the existing ones are shown as what they actually are — cost, cost,
 * result — so a shop can be read at a glance instead of reconstructed from a list.
 */
public final class VillagerMenu {
	private static final int[] TRADE_SLOTS = {
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};
	/** How far to look for a villager. Matches the command. */
	private static final double REACH = 6.0;

	private VillagerMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer) {
		if (!context.permissions().has(viewer, PermissionService.VILLAGERS)) {
			Menus.deny(viewer, "You do not have permission to use the villager maker.");
			return;
		}
		var found = VillagerMakerService.lookingAt(viewer, REACH);
		if (found.isEmpty()) {
			Menus.deny(viewer, "Look at a villager first.");
			return;
		}
		open(context, viewer, found.get());
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer, Villager villager) {
		if (!villager.isAlive()) {
			Menus.deny(viewer, "That villager is gone.");
			return;
		}
		List<MerchantOffer> trades = VillagerMakerService.trades(villager);

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), viewer, false);
		gui.setTitle(Component.literal("» Villager «").withStyle(ChatFormatting.GREEN));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.button(Items.VILLAGER_SPAWN_EGG, villager.getName().getString(),
				ChatFormatting.GREEN,
				trades.isEmpty() ? "No trades yet." : trades.size() + " trade(s).",
				"",
				"Click to rename.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				promptName(context, viewer, villager);
			}).build());

		for (int index = 0; index < TRADE_SLOTS.length && index < trades.size(); index++) {
			MerchantOffer offer = trades.get(index);
			int tradeNumber = index;
			ItemStack result = offer.getResult().copy();
			gui.setSlot(TRADE_SLOTS[index], GuiElementBuilder.from(result)
				.setName(Component.literal("Trade " + (index + 1)).withStyle(ChatFormatting.WHITE))
				.addLoreLine(Component.empty())
				.addLoreLine(Msg.line("Costs: " + describe(offer.getBaseCostA())
					+ describeSecond(offer.getCostB()), ChatFormatting.GRAY))
				.addLoreLine(Msg.line("Gives: " + describe(result), ChatFormatting.GRAY))
				.addLoreLine(Component.empty())
				.addLoreLine(Msg.hint("Click to remove this trade"))
				.setCallback((slot, type, action, ignored) -> {
					VillagerMakerService.removeTrade(villager, tradeNumber);
					Menus.notify(viewer, "Removed trade " + (tradeNumber + 1) + ".");
					open(context, viewer, villager);
				}).build());
		}

		if (trades.isEmpty()) {
			gui.setSlot(22, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.GRAY), "No trades yet", ChatFormatting.GRAY,
				"Add the first one below.").build());
		}

		gui.setSlot(47, Menus.button(Items.EMERALD, "Add Trade", ChatFormatting.GREEN,
				"Uses your inventory:",
				"slot 1 and slot 2 are the cost,",
				"the item in your hand is what they give.")
			.setCallback((slot, type, action, ignored) -> addTrade(context, viewer, villager)).build());

		gui.setSlot(49, Menus.button(Items.BARRIER, "Clear Trades", ChatFormatting.RED,
				"Removes every trade at once.")
			.setCallback((slot, type, action, ignored) -> {
				VillagerMakerService.clearTrades(villager);
				Menus.notify(viewer, "Cleared every trade.");
				open(context, viewer, villager);
			}).build());

		gui.setSlot(51, Menus.button(Items.PACKED_ICE, "Re-freeze", ChatFormatting.AQUA,
				"No AI, no wandering, invulnerable,",
				"and stock that never runs out.",
				"",
				"Apply again if something knocked it loose.")
			.setCallback((slot, type, action, ignored) -> {
				VillagerMakerService.makeStatic(villager);
				Menus.notify(viewer, "Villager frozen in place.");
				open(context, viewer, villager);
			}).build());

		gui.setSlot(45, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, viewer)).build());
		gui.setSlot(53, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	/**
	 * Builds a trade from the viewer's inventory.
	 *
	 * <p>Same convention as {@code /villager addtrade}, so the menu and the command cannot disagree
	 * about which slot means what.
	 */
	private static void addTrade(ScriptifyContext context, ServerPlayer viewer, Villager villager) {
		ItemStack first = viewer.getInventory().getItem(0);
		ItemStack second = viewer.getInventory().getItem(1);
		ItemStack result = viewer.getInventory().getSelectedItem();
		if (first.isEmpty() || result.isEmpty()) {
			Menus.deny(viewer, "Put the cost in slot 1 and hold what they should give.");
			return;
		}
		VillagerMakerService.addTrade(villager, first.copy(), second.copy(), result.copy());
		Menus.notify(viewer, "Added a trade.");
		Sfx.repair(viewer);
		open(context, viewer, villager);
	}

	private static void promptName(ScriptifyContext context, ServerPlayer viewer, Villager villager) {
		AnvilInputGui anvil = new AnvilInputGui(viewer, false);
		anvil.setTitle(Component.literal("Villager name"));
		anvil.setDefaultInputValue(villager.getName().getString());
		anvil.setSlot(0, new GuiElementBuilder(Items.NAME_TAG)
			.setName(Msg.hint("Type a name, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
				"Renames the villager.")
			.setCallback((slot, type, action, ignored) -> {
				villager.setCustomName(Component.literal(anvil.getInput()));
				villager.setCustomNameVisible(true);
				Menus.notify(viewer, "Renamed the villager.");
				open(context, viewer, villager);
			}).build());
		anvil.open();
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getHoverName().getString();
	}

	private static String describeSecond(ItemStack stack) {
		return stack.isEmpty() ? "" : " + " + describe(stack);
	}
}
