package scriptify.ui;

import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.FakeTabService;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * Picks how busy the server should look.
 *
 * <p>The sizes are laid out smallest to largest with the current one marked, so choosing is a glance
 * rather than a calculation. The exact count is available underneath for anyone who wants a number
 * that is not one of the five.
 */
public final class FakeTabMenu {
	/** Preset, icon, and the slot it sits in — smallest on the left. */
	private record Preset(FakeTabService.Size size, Item icon, int slot, String flavour) {
	}

	private static final Preset[] PRESETS = {
		new Preset(FakeTabService.Size.SMALL, Items.OAK_SAPLING, 20, "A friend group."),
		new Preset(FakeTabService.Size.MEDIUM, Items.OAK_LOG, 21, "A healthy little SMP."),
		new Preset(FakeTabService.Size.BIG, Items.IRON_BLOCK, 22, "A server people have heard of."),
		new Preset(FakeTabService.Size.VERY_BIG, Items.DIAMOND_BLOCK, 23, "A genuinely busy network."),
		new Preset(FakeTabService.Size.MASSIVE, Items.BEACON, 24, "Hypixel cosplay."),
	};

	private FakeTabMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer) {
		if (!context.permissions().has(viewer, PermissionService.ADMIN)) {
			Menus.deny(viewer, "Only Scriptify admins can change the fake tab list.");
			return;
		}
		FakeTabService tab = ScriptifyRuntime.fakeTab();
		int current = tab == null ? 0 : tab.size();
		int faces = tab == null ? 0 : tab.faceCount();
		boolean churn = ScriptifyRuntime.config().get().fakeTab.churn;

		SimpleGui gui = new SimpleGui(Menus.typeForRows(5), viewer, false);
		gui.setTitle(Component.literal("» Fake Tab «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.button(Items.PLAYER_HEAD, "Tab List Padding", ChatFormatting.LIGHT_PURPLE,
				"Shows players who are not connected,",
				"so the server reads as a busy one.",
				"",
				current == 0 ? "Currently off." : "Currently showing " + current + " extra.",
				faces == 0
					? "Faces: none yet — still collecting."
					: "Faces: " + faces + " real skins to draw from.",
				"",
				"Display only — nothing actually joins.")
			.build());

		for (Preset preset : PRESETS) {
			boolean active = current == preset.size().count();
			gui.setSlot(preset.slot(), Menus.button(preset.icon(),
					preset.size().label() + " — " + preset.size().count(),
					active ? ChatFormatting.GREEN : ChatFormatting.GRAY,
					preset.flavour(),
					"",
					active ? "Currently selected." : "Click to use this size.")
				.setCallback((slot, type, action, ignored) -> {
					apply(context, viewer, preset.size().count());
				}).build());
		}

		gui.setSlot(30, Menus.button(Items.BARRIER, "Off", ChatFormatting.RED,
				"Show only the players who are really here.")
			.setCallback((slot, type, action, ignored) -> apply(context, viewer, 0)).build());

		gui.setSlot(32, Menus.button(Items.PAPER, "Exact Count", ChatFormatting.YELLOW,
				"Type a number between 0 and 500.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				promptCount(context, viewer);
			}).build());

		gui.setSlot(34, Menus.button(churn ? Items.CLOCK : Items.STRUCTURE_VOID,
				"Drift", churn ? ChatFormatting.GREEN : ChatFormatting.GRAY,
				"The odd player leaving or arriving,",
				"and pings that move, like a real server.",
				"",
				churn ? "On — the list breathes." : "Off — the list is frozen.",
				"",
				"Turn off to match a screenshot exactly.")
			.setCallback((slot, type, action, ignored) -> {
				var settings = ScriptifyRuntime.config().get().fakeTab;
				settings.churn = !settings.churn;
				ScriptifyRuntime.config().save();
				Menus.notify(viewer, settings.churn
					? "The tab list will drift a little."
					: "The tab list is frozen.");
				open(context, viewer);
			}).build());

		gui.setSlot(36, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, viewer)).build());
		gui.setSlot(44, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	/** Applies a size and writes it to the config, so it survives a restart. */
	private static void apply(ScriptifyContext context, ServerPlayer viewer, int count) {
		FakeTabService tab = ScriptifyRuntime.fakeTab();
		if (tab == null) {
			Menus.deny(viewer, "Scriptify is not started yet.");
			return;
		}
		tab.setCount(count);

		var settings = ScriptifyRuntime.config().get().fakeTab;
		settings.enabled = count > 0;
		settings.count = count;
		ScriptifyRuntime.config().save();

		Menus.notify(viewer, count == 0
			? "Fake tab turned off."
			: "Tab list padded with " + count + " players.");
		open(context, viewer);
	}

	private static void promptCount(ScriptifyContext context, ServerPlayer viewer) {
		AnvilInputGui anvil = new AnvilInputGui(viewer, false);
		anvil.setTitle(Component.literal("How many?"));
		anvil.setDefaultInputValue("100");
		anvil.setSlot(0, new GuiElementBuilder(Items.PAPER)
			.setName(Msg.hint("Type a number, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
				"Sets the fake player count.")
			.setCallback((slot, type, action, ignored) -> {
				try {
					int count = Math.max(0, Math.min(500, Integer.parseInt(anvil.getInput().trim())));
					apply(context, viewer, count);
				} catch (NumberFormatException exception) {
					Menus.deny(viewer, "That is not a number.");
					open(context, viewer);
				}
			}).build());
		anvil.open();
	}
}
