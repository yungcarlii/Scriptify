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
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * Skin management as a screen.
 *
 * <p>The heads double as a preview: whatever skin a player is currently wearing is the face you see,
 * so the effect of a change is visible without leaving the menu.
 */
public final class SkinMenu {
	private SkinMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer) {
		open(context, viewer, false);
	}

	/**
	 * @param randomFirst when true a plain click rolls a skin instead of asking which account to copy.
	 *                    The main menu opens it this way: dressing an extra as nobody in particular is
	 *                    the usual job, and impersonating a named account is the exception.
	 */
	public static void open(ScriptifyContext context, ServerPlayer viewer, boolean randomFirst) {
		if (!context.permissions().has(viewer, PermissionService.SKIN)) {
			Menus.deny(viewer, "You do not have permission to change skins.");
			return;
		}
		PlayerPicker.open(context, viewer, "Skins", ChatFormatting.AQUA,
			target -> List.of(
				randomFirst ? "Left click: random skin" : "Left click: copy another account's skin",
				randomFirst ? "Right click: copy a specific account" : "Right click: random skin",
				"Shift + left click: restore their own skin",
				"Shift + right click: random identity (name and skin)"),
			(target, click) -> {
				if (click.shift && click.isRight) {
					randomIdentity(context, viewer, target);
				} else if (click.shift) {
					clearSkin(context, viewer, target);
				} else if (click.isRight == randomFirst) {
					// Swapped when the caller wants rolling to be the plain click.
					promptForSource(context, viewer, target);
				} else {
					randomSkin(context, viewer, target);
				}
			}, 0);
	}

	/**
	 * Pulls a random skin from NameMC, falling back to another online player's.
	 *
	 * <p>The lookup is asynchronous, so the menu is not reopened until the result comes back.
	 */
	private static void randomSkin(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target) {
		Menus.notify(viewer, "Fetching a random skin…");
		ScriptifyRuntime.skin().randomSkinAsync(target, applied -> {
			if (applied.isEmpty()) {
				Menus.deny(viewer, "Could not reach NameMC, and nobody else is online.");
			} else {
				Menus.notify(viewer, target.getGameProfile().name() + " now wears " + applied.get() + ".");
			}
			open(context, viewer);
		});
	}

	private static void clearSkin(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target) {
		Menus.notify(viewer, "Looking up their skin…");
		ScriptifyRuntime.skin().clearSkinAsync(target, applied -> {
			if (applied) {
				Menus.notify(viewer, "Restored " + target.getGameProfile().name() + "'s own skin.");
			} else {
				// Almost always a Mojang outage rather than anything the operator did wrong.
				Menus.deny(viewer, "Could not reach Mojang to fetch that skin.");
			}
			open(context, viewer);
		});
	}

	/**
	 * Hands the player a random real account's name and skin together.
	 *
	 * <p>Paired on purpose: a random name over an unrelated random skin reads as two separate glitches
	 * rather than one convincing stranger.
	 */
	private static void randomIdentity(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target) {
		Menus.notify(viewer, "Finding somebody to be…");
		scriptify.feature.IdentityService.randomise(target, name -> {
			if (name.isEmpty()) {
				Menus.deny(viewer, "Could not reach Mojang to find an account.");
			} else {
				Menus.notify(viewer, "Now going by " + name.get() + ", skin and all.");
			}
			open(context, viewer);
		});
	}

	/** Asks which account's skin to copy. */
	private static void promptForSource(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target) {
		AnvilInputGui anvil = new AnvilInputGui(viewer, false);
		anvil.setTitle(Component.literal("Skin for " + target.getGameProfile().name()));
		anvil.setDefaultInputValue(target.getGameProfile().name());
		anvil.setSlot(0, new GuiElementBuilder(Items.PLAYER_HEAD)
			.setProfile(target.getGameProfile())
			.setName(Msg.hint("Type an account name, then click the output"))
			.build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
				"Copies that account's skin onto " + target.getGameProfile().name() + ".",
				"Needs the server to reach Mojang.")
			.setCallback((index, type, action, ignored) -> {
				String source = anvil.getInput().trim();
				if (source.isEmpty()) {
					Menus.deny(viewer, "Type an account name first.");
					return;
				}
				Menus.notify(viewer, "Looking up " + source + "…");
				ScriptifyRuntime.skin().copySkinAsync(source, target, applied -> {
					if (applied) {
						Menus.notify(viewer, "Applied " + source + "'s skin to " + target.getGameProfile().name() + ".");
					} else {
						Menus.deny(viewer, "No account called '" + source + "'.");
					}
					open(context, viewer);
				});
			}).build());
		anvil.open();
	}
}
