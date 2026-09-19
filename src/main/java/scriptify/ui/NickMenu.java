package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.nick.NickManager;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * Nickname management as a screen rather than a set of commands to memorise.
 *
 * <p>Pick a face, then type a name in the anvil, roll a random one, or clear it — the three things
 * anyone actually does with nicknames, on one click each.
 */
public final class NickMenu {
	private NickMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer) {
		open(context, viewer, false);
	}

	/**
	 * @param randomFirst when true a plain click rolls a name instead of asking for one. The main menu
	 *                    opens it this way, because casting an anonymous extra is the usual job.
	 */
	public static void open(ScriptifyContext context, ServerPlayer viewer, boolean randomFirst) {
		if (!context.permissions().has(viewer, PermissionService.NICK)) {
			Menus.deny(viewer, "You do not have permission to change nicknames.");
			return;
		}
		NickManager nicks = ScriptifyRuntime.nick();
		PlayerPicker.open(context, viewer, "Nicknames", ChatFormatting.AQUA,
			target -> {
				String current = nicks.displayName(target);
				boolean nicked = nicks.isNicked(target);
				return List.of(
					nicked ? "Currently: " + current : "No nickname set.",
					randomFirst ? "Left click: roll a nickname" : "Left click: type a nickname",
					randomFirst ? "Right click: type one yourself" : "Right click: roll a nickname",
					"Shift click: remove the nickname");
			},
			(target, click) -> {
				if (click.shift) {
					nicks.clear(target);
					Menus.notify(viewer, "Removed " + target.getGameProfile().name() + "'s nickname.");
					open(context, viewer, randomFirst);
				} else if (click.isRight == randomFirst) {
					// The two actions swap places, so the plain click is always the one the caller meant
					// to lead with.
					promptForNickname(context, viewer, target, randomFirst);
				} else {
					String rolled = nicks.random(target);
					Menus.notify(viewer, target.getGameProfile().name() + " is now " + rolled + ".");
					open(context, viewer, randomFirst);
				}
			}, 0);
	}

	/** An anvil is the only way to collect free text from a vanilla client. */
	private static void promptForNickname(ScriptifyContext context, ServerPlayer viewer,
			ServerPlayer target, boolean randomFirst) {
		NickManager nicks = ScriptifyRuntime.nick();
		AnvilInputGui anvil = new AnvilInputGui(viewer, false);
		anvil.setTitle(Component.literal("Nickname for " + target.getGameProfile().name()));
		anvil.setDefaultInputValue(nicks.displayName(target));
		anvil.setSlot(0, new GuiElementBuilder(Items.NAME_TAG)
			.setName(Msg.hint("Type a name, then click the output"))
			.build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
				"Sets this nickname on " + target.getGameProfile().name() + ".",
				"Max 16 characters — the username limit.")
			.setCallback((index, type, action, ignored) -> {
				try {
					String applied = nicks.set(target, anvil.getInput());
					Menus.notify(viewer, target.getGameProfile().name() + " is now " + applied + ".");
				} catch (IllegalArgumentException exception) {
					Menus.deny(viewer, exception.getMessage());
				}
				open(context, viewer, randomFirst);
			}).build());
		anvil.open();
	}
}
