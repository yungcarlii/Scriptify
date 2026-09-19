package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.PotionPresetService;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * The {@code /potion} preset picker.
 *
 * <p>Built-in presets come first and cannot be deleted; anything saved from a player follows and can
 * be removed. New presets are captured from whatever effects the operator currently has, so building
 * one is a matter of setting yourself up and pressing save.
 */
public final class PotionMenu {
	private static final int[] PRESET_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
	};

	private PotionMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player) {
		if (!context.permissions().has(player, PermissionService.PLAYER)) {
			Menus.deny(player, "You do not have permission to use potion presets.");
			return;
		}
		PotionPresetService potions = ScriptifyRuntime.potions();
		boolean canManage = potions.canManage(player);

		SimpleGui gui = new SimpleGui(Menus.typeForRows(5), player, false);
		gui.setTitle(Component.literal("» Potion Presets «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		List<PotionPresetService.Preset> presets = potions.ordered().stream()
			.filter(preset -> canManage || potions.canUse(preset.name(), player))
			.toList();
		for (int index = 0; index < presets.size() && index < PRESET_SLOTS.length; index++) {
			PotionPresetService.Preset preset = presets.get(index);
			gui.setSlot(PRESET_SLOTS[index], Menus.button(preset.icon(), capitalise(preset.name()),
					preset.builtin() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GOLD,
					preset.description(),
					preset.builtin() ? "Built in." : "Saved by an operator.",
					potions.allowedRanks(preset.name()).isEmpty() ? "Access: every eligible rank."
						: "Access: " + potions.allowedRanks(preset.name()).size() + " rank(s).",
					"Left click to apply.",
					canManage ? "Right click: manage access." : "",
					!preset.builtin() && canManage ? "Shift + right click: delete." : "")
				.setCallback((slot, type, action, ignored) -> {
					if (type.shift && type.isRight && !preset.builtin() && canManage) {
						potions.delete(preset.name());
						Menus.notify(player, "Deleted preset '" + preset.name() + "'.");
						open(context, player);
						return;
					}
					if (type.isRight && canManage) {
						openAccess(context, player, preset);
						return;
					}
					potions.apply(context.server(), player, preset);
					Menus.notify(player, "Applied the " + preset.name() + " preset.");
				}).build());
		}

		// ---- authoring --------------------------------------------------------
		int activeEffects = player.getActiveEffects().size();
		if (canManage) {
			gui.setSlot(30, Menus.button(Items.BREWING_STAND, "Save My Effects", ChatFormatting.GREEN,
				activeEffects == 0
					? "You have no active effects to save."
					: "Saves your " + activeEffects + " active effect(s) as a preset.",
				"Set yourself up with /effect first,",
				"then save the result here.")
			.setCallback((slot, type, action, ignored) -> {
				if (player.getActiveEffects().isEmpty()) {
					Menus.deny(player, "Give yourself some effects first.");
					return;
				}
				promptSave(context, player);
				}).build());
		}

		gui.setSlot(32, Menus.button(Items.MILK_BUCKET, "Clear Effects", ChatFormatting.RED,
				"Removes every active effect.")
			.setCallback((slot, type, action, ignored) -> {
				PotionPresetService.clear(player);
				Menus.notify(player, "Cleared all effects.");
				open(context, player);
			}).build());

		gui.setSlot(36, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, player)).build());
		gui.setSlot(44, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	private static void openAccess(ScriptifyContext context, ServerPlayer viewer, PotionPresetService.Preset preset) {
		PotionPresetService potions = ScriptifyRuntime.potions();
		RankAccessMenu.open(context, viewer, capitalise(preset.name()), "Player State", new RankAccessMenu.Controller() {
			@Override public java.util.Set<String> allowedRanks() { return potions.allowedRanks(preset.name()); }
			@Override public boolean setAllowed(String rank, boolean allowed) {
				return potions.setRankAllowed(preset.name(), rank, allowed);
			}
			@Override public void clear() { potions.clearRankAccess(preset.name()); }
			@Override public void back() { open(context, viewer); }
		}, 0);
	}

	private static void promptSave(ScriptifyContext context, ServerPlayer player) {
		AnvilInputGui anvil = new AnvilInputGui(player, false);
		anvil.setTitle(Component.literal("Name this preset"));
		anvil.setDefaultInputValue("preset");
		anvil.setSlot(0, new GuiElementBuilder(Items.POTION)
			.setName(Msg.hint("Type a name, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Save", ChatFormatting.GREEN,
				"Captures your current effects, with their",
				"remaining durations, under this name.")
			.setCallback((slot, type, action, ignored) -> {
				try {
					String name = anvil.getInput();
					if (ScriptifyRuntime.potions().createFromPlayer(player, name, Items.POTION)) {
						Menus.notify(player, "Saved preset '" + name + "'.");
					} else {
						Menus.deny(player, "You have no active effects.");
					}
				} catch (IllegalArgumentException exception) {
					Menus.deny(player, exception.getMessage());
				}
				open(context, player);
			}).build());
		anvil.open();
	}

	private static String capitalise(String value) {
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
