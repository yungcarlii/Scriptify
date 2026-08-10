package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.RandomFillService;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * Category picker for {@code /randomfill}.
 *
 * <p>Each button fills the player's hotbar and backpack with random picks from that category — a fast
 * way to dress an inventory so it reads as lived-in on camera. Armour and offhand are left alone.
 */
public final class RandomFillMenu {
	private static final int[] CATEGORY_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

	private RandomFillMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player) {
		if (!context.permissions().has(player, PermissionService.INVENTORY)) {
			Menus.deny(player, "You do not have permission to use random fill.");
			return;
		}
		SimpleGui gui = new SimpleGui(Menus.typeForRows(4), player, false);
		gui.setTitle(Component.literal("» RandomFill «").withStyle(ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		List<RandomFillService.Category> categories = RandomFillService.categories();
		for (int index = 0; index < categories.size() && index < CATEGORY_SLOTS.length; index++) {
			RandomFillService.Category category = categories.get(index);
			gui.setSlot(CATEGORY_SLOTS[index], Menus.button(category.icon(), capitalise(category.name()),
					ChatFormatting.GOLD,
					category.description(),
					"Overwrites your hotbar and backpack.")
				.setCallback((slot, type, action, ignored) -> {
					int filled = ScriptifyRuntime.randomFill().fill(player, category.name());
					if (filled < 0) {
						Menus.deny(player, "That category is unavailable.");
					} else {
						Menus.notify(player, "Filled " + filled + " slot(s) with " + category.name() + ".");
					}
				}).build());
		}

		gui.setSlot(31, Menus.button(Items.BARRIER, "Clear Inventory", ChatFormatting.RED,
				"Empties your hotbar and backpack.")
			.setCallback((slot, type, action, ignored) -> {
				for (int index = 0; index < 36; index++) {
					player.getInventory().setItem(index, net.minecraft.world.item.ItemStack.EMPTY);
				}
				player.getInventory().setChanged();
				Menus.notify(player, "Inventory cleared.");
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
