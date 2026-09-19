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
import scriptify.feature.Sfx;
import scriptify.kits.KitManager;
import scriptify.runtime.ScriptifyRuntime;

/**
 * What to do with one kit.
 *
 * <p>Everything a kit can have done to it, each on its own labelled button. The alternative is a stack
 * of modifier-key combinations in the tooltip, which works only for the person who wrote them.
 *
 * <p>Delete asks twice. It is the one action here that cannot be undone, and it sits next to buttons
 * people will be clicking quickly.
 */
public final class KitOptionsMenu {
	private KitOptionsMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target,
			String kit, int page) {
		KitManager kits = ScriptifyRuntime.kits();
		if (!kits.canManage(viewer)) {
			Menus.deny(viewer, "You do not have permission to manage kits.");
			KitsMenu.open(context, viewer, target, page);
			return;
		}
		if (!kits.exists(kit)) {
			Menus.deny(viewer, "That kit is gone.");
			KitsMenu.open(context, viewer, target, page);
			return;
		}
		String name = kits.displayName(kit);

		SimpleGui gui = new SimpleGui(Menus.typeForRows(5), viewer, false);
		gui.setTitle(Msg.header(name, ChatFormatting.GOLD));
		gui.setLockPlayerInventory(true);
		Menus.frame(gui);

		ItemStack icon = kits.entries().stream()
			.filter(entry -> entry.name().equals(kit))
			.map(entry -> entry.icon().copy())
			.findFirst()
			.orElse(new ItemStack(Items.CHEST));
		gui.setSlot(4, GuiElementBuilder.from(icon)
			.hideDefaultTooltip()
			.setName(Msg.title(name, ChatFormatting.GOLD))
			.build());

		gui.setSlot(19, Menus.button(Items.DIAMOND_CHESTPLATE,
				target == null ? "Equip" : "Give to " + target.getGameProfile().name(),
				ChatFormatting.GREEN,
				target == null ? "Puts the kit on you." : "Hands the kit over.")
			.setCallback((slot, type, action, ignored) -> KitsMenu.give(context, viewer, target, kit))
			.build());

		gui.setSlot(20, Menus.button(Items.PLAYER_HEAD, "Give To…", ChatFormatting.AQUA,
				"Pick somebody and hand them this kit.")
			.setCallback((slot, type, action, ignored) ->
				PlayerPicker.open(context, viewer, "Give " + name, ChatFormatting.AQUA,
					candidate -> List.of("Click to give them " + name + "."),
					(candidate, click) -> KitsMenu.give(context, viewer, candidate, kit), 0))
			.build());

		gui.setSlot(21, Menus.button(Items.CRAFTING_TABLE, "Edit Contents", ChatFormatting.YELLOW,
				"Open the kit and change what is in it.",
				"Drag items in and out like a chest.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				KitEditMenu.open(context, viewer, target, kit, page);
			}).build());

		gui.setSlot(22, Menus.button(Items.HOPPER, "Replace From Inventory", ChatFormatting.YELLOW,
				"Overwrites the kit with whatever",
				"you are carrying right now.",
				"",
				"Keeps the name and the icon.")
			.setCallback((slot, type, action, ignored) -> {
				kits.recapture(kit, viewer);
				Menus.notify(viewer, "Replaced " + name + " with your inventory.");
				Sfx.success(viewer);
				open(context, viewer, target, kit, page);
			}).build());

		gui.setSlot(23, Menus.button(Items.NAME_TAG, "Rename", ChatFormatting.AQUA,
				"Spaces and capitals are fine.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				promptRename(context, viewer, target, kit, name, page);
			}).build());

		gui.setSlot(24, Menus.button(Items.ITEM_FRAME, "Set Icon", ChatFormatting.AQUA,
				"Uses the item you are holding",
				"as this kit's menu icon.")
			.setCallback((slot, type, action, ignored) -> {
				ItemStack held = viewer.getInventory().getSelectedItem();
				if (held.isEmpty()) {
					Menus.deny(viewer, "Hold the item you want as the icon.");
					return;
				}
				kits.setIcon(kit, held);
				Menus.notify(viewer, "Icon updated.");
				Sfx.success(viewer);
				open(context, viewer, target, kit, page);
			}).build());

		gui.setSlot(25, Menus.button(Items.BARRIER, "Delete", ChatFormatting.RED,
				"Removes this kit for good.",
				"",
				"Click again on the next screen to confirm.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				confirmDelete(context, viewer, target, kit, name, page);
			}).build());

		int rankCount = kits.allowedRanks(kit).size();
		gui.setSlot(30, Menus.button(Items.TRIPWIRE_HOOK, "Rank Access", ChatFormatting.LIGHT_PURPLE,
			rankCount == 0 ? "Every rank with Use Kits can equip this." : rankCount + " rank(s) can equip this.",
			"",
			"Click to choose one or more ranks.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				KitRankAccessMenu.open(context, viewer, target, kit, page, 0);
			}).build());

		gui.setSlot(36, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> KitsMenu.open(context, viewer, target, page))
			.build());
		gui.setSlot(44, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	private static void confirmDelete(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target,
			String kit, String name, int page) {
		SimpleGui gui = new SimpleGui(Menus.typeForRows(3), viewer, false);
		gui.setTitle(Msg.header("Delete " + name + "?", ChatFormatting.RED));
		gui.setLockPlayerInventory(true);
		Menus.frame(gui);

		gui.setSlot(11, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Yes, delete it", ChatFormatting.RED,
				"This cannot be undone.")
			.setCallback((slot, type, action, ignored) -> {
				ScriptifyRuntime.kits().delete(kit);
				Menus.notify(viewer, "Deleted " + name + ".");
				KitsMenu.open(context, viewer, target, page);
			}).build());

		gui.setSlot(15, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.RED), "Keep it", ChatFormatting.GREEN,
				"Go back without deleting.")
			.setCallback((slot, type, action, ignored) -> open(context, viewer, target, kit, page))
			.build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	private static void promptRename(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target,
			String kit, String name, int page) {
		AnvilInputGui anvil = new AnvilInputGui(viewer, false);
		anvil.setTitle(Component.literal("Rename kit"));
		anvil.setDefaultInputValue(name);
		anvil.setSlot(0, new GuiElementBuilder(Items.NAME_TAG)
			.setName(Msg.hint("Type a name, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Rename", ChatFormatting.GREEN,
				"Keeps the contents and the icon.")
			.setCallback((slot, type, action, ignored) -> {
				try {
					if (ScriptifyRuntime.kits().rename(kit, anvil.getInput())) {
						Menus.notify(viewer, "Renamed to '" + anvil.getInput().trim() + "'.");
						KitsMenu.open(context, viewer, target, page);
						return;
					}
					Menus.deny(viewer, "There is already a kit with that name.");
				} catch (IllegalArgumentException exception) {
					Menus.deny(viewer, exception.getMessage());
				}
				open(context, viewer, target, kit, page);
			}).build());
		anvil.open();
	}
}
