package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.PotionPresets;
import scriptify.permission.PermissionService;

/**
 * Picker for the named potion presets — the {@code /potion} menu.
 */
public final class PotionMenu {
	private static final int[] PRESET_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

	private PotionMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player) {
		if (!context.permissions().has(player, PermissionService.PLAYER)) {
			Menus.deny(player, "You do not have permission to use potion presets.");
			return;
		}
		SimpleGui gui = new SimpleGui(Menus.typeForRows(4), player, false);
		gui.setTitle(Component.literal("» Potion Presets «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		List<PotionPresets.Preset> presets = PotionPresets.ordered();
		for (int index = 0; index < presets.size() && index < PRESET_SLOTS.length; index++) {
			PotionPresets.Preset preset = presets.get(index);
			gui.setSlot(PRESET_SLOTS[index], Menus.button(preset.icon(), capitalise(preset.name()),
					ChatFormatting.LIGHT_PURPLE,
					preset.description(),
					"Lasts 8 minutes; particles hidden.")
				.setCallback((slot, type, action, ignored) -> {
					preset.applyTo(player);
					Menus.notify(player, "Applied the " + preset.name() + " preset.");
				}).build());
		}

		gui.setSlot(31, Menus.button(Items.MILK_BUCKET, "Clear Effects", ChatFormatting.RED,
				"Removes every active effect.")
			.setCallback((slot, type, action, ignored) -> {
				PotionPresets.clear(player);
				Menus.notify(player, "Cleared all effects.");
			}).build());

		gui.setSlot(27, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, player)).build());
		gui.setSlot(35, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
	}

	private static String capitalise(String value) {
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
