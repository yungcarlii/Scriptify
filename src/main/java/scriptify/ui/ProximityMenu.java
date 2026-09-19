package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.ProximityService;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * How far a voice carries.
 *
 * <p>The ranges are laid out closest to furthest with the current one marked, so picking one is a
 * glance rather than a calculation, and each is described by what it covers rather than by its number
 * — "a room", "a base" — because nobody thinks in blocks when they are blocking a scene.
 */
public final class ProximityMenu {
	/** A preset range, its icon, the slot it sits in, and what that distance actually covers. */
	private record Range(int blocks, Item icon, int slot, String flavour) {
	}

	private static final Range[] RANGES = {
		new Range(8, Items.CANDLE, 20, "A room. Almost a whisper."),
		new Range(16, Items.OAK_DOOR, 21, "A house."),
		new Range(48, Items.OAK_FENCE, 22, "A base and its yard."),
		new Range(128, Items.SPYGLASS, 23, "Shouting distance across a field."),
		new Range(256, Items.BEACON, 24, "Most of a render distance."),
	};

	private ProximityMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer) {
		if (!context.permissions().has(viewer, PermissionService.ADMIN)) {
			Menus.deny(viewer, "Only Scriptify admins can change proximity chat.");
			return;
		}
		ProximityService proximity = ScriptifyRuntime.proximity();
		if (proximity == null) {
			Menus.deny(viewer, "Scriptify is not started yet.");
			return;
		}
		boolean on = proximity.isEnabled();
		int radius = (int) Math.round(proximity.radius());

		SimpleGui gui = new SimpleGui(Menus.typeForRows(5), viewer, false);
		gui.setTitle(Component.literal("» Proximity Chat «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.button(Items.NOTE_BLOCK, "Proximity Chat", ChatFormatting.LIGHT_PURPLE,
				"Players only hear people near them.",
				"",
				on ? "Currently ON, carrying " + radius + " blocks." : "Currently OFF.",
				"",
				"Private messages, deaths, joins and /say",
				"still reach everyone.")
			.glow().build());

		for (Range range : RANGES) {
			boolean active = on && radius == range.blocks();
			gui.setSlot(range.slot(), Menus.button(range.icon(),
					range.blocks() + " blocks",
					active ? ChatFormatting.GREEN : ChatFormatting.GRAY,
					range.flavour(),
					"",
					active ? "Currently selected." : "Click to use this range.")
				.setCallback((slot, type, action, ignored) -> {
					proximity.setRadius(range.blocks());
					// Choosing a range is how somebody turns this on; making them click a range and then
					// find a separate switch would be a step that exists for no reason.
					proximity.setEnabled(true);
					Menus.notify(viewer, "Chat now carries " + range.blocks() + " blocks.");
					Sfx.success(viewer);
					open(context, viewer);
				}).build());
		}

		gui.setSlot(30, Menus.toggle(Items.REDSTONE_TORCH, "Proximity Chat", on,
				on ? "Click to let everyone hear everyone again."
					: "Click to limit chat to " + radius + " blocks.")
			.setCallback((slot, type, action, ignored) -> {
				proximity.setEnabled(!proximity.isEnabled());
				Menus.notify(viewer, proximity.isEnabled()
					? "Proximity chat on — " + (int) Math.round(proximity.radius()) + " blocks."
					: "Proximity chat off.");
				open(context, viewer);
			}).build());

		gui.setSlot(32, Menus.button(Items.PAPER, "Exact Range", ChatFormatting.YELLOW,
				"Type a distance in blocks.",
				"Between " + (int) ProximityService.MIN_RADIUS
					+ " and " + (int) ProximityService.MAX_RADIUS + ".")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				promptRadius(context, viewer);
			}).build());

		gui.setSlot(36, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, viewer)).build());
		gui.setSlot(44, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	private static void promptRadius(ScriptifyContext context, ServerPlayer viewer) {
		ProximityService proximity = ScriptifyRuntime.proximity();
		AnvilInputGui anvil = new AnvilInputGui(viewer, false);
		anvil.setTitle(Component.literal("How far?"));
		anvil.setDefaultInputValue(String.valueOf((int) Math.round(proximity.radius())));
		anvil.setSlot(0, new GuiElementBuilder(Items.PAPER)
			.setName(Msg.hint("Type a distance in blocks, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
				"Sets how far chat carries.")
			.setCallback((slot, type, action, ignored) -> {
				try {
					double blocks = ProximityService.clamp(Double.parseDouble(anvil.getInput().trim()));
					proximity.setRadius(blocks);
					proximity.setEnabled(true);
					Menus.notify(viewer, "Chat now carries " + (int) Math.round(blocks) + " blocks.");
					Sfx.success(viewer);
					open(context, viewer);
				} catch (NumberFormatException exception) {
					Menus.deny(viewer, "That is not a number.");
					open(context, viewer);
				}
			}).build());
		anvil.open();
	}
}
