package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.kits.KitManager;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * The {@code /skits} kit browser.
 *
 * <p>Kits fill a bordered grid, each rendered with the icon its creator was holding when they made it.
 * The nether star in the corner creates a new kit from the viewer's current inventory, prompting for a
 * name through an anvil rename field — which is the only way to take free text from a vanilla client.
 */
public final class KitsMenu {
	/** Interior slots of a 6-row menu, i.e. everything except the one-slot border. */
	private static final int[] CONTENT_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
		37, 38, 39, 40, 41, 42, 43,
	};
	private static final int NEW_KIT_SLOT = 4;
	private static final int PREVIOUS_SLOT = 45;
	private static final int NEXT_SLOT = 53;

	private KitsMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player, int page) {
		if (!context.permissions().has(player, PermissionService.KITS)) {
			Menus.deny(player, "You do not have permission to use kits.");
			return;
		}
		KitManager kits = ScriptifyRuntime.kits();
		List<KitManager.KitEntry> entries = kits.entries();
		int perPage = CONTENT_SLOTS.length;
		int totalPages = Math.max(1, (entries.size() + perPage - 1) / perPage);
		int currentPage = Math.max(0, Math.min(page, totalPages - 1));

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("Kitted").withStyle(ChatFormatting.DARK_GRAY));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(NEW_KIT_SLOT, Menus.button(Items.NETHER_STAR, "New Kit", ChatFormatting.LIGHT_PURPLE,
				"Saves your current inventory as a kit.",
				"The item you are holding becomes its icon.")
			.glow()
			.setCallback((index, type, action, ignored) -> promptForName(context, player))
			.build());

		for (int slot = 0; slot < perPage; slot++) {
			int entryIndex = currentPage * perPage + slot;
			if (entryIndex >= entries.size()) {
				break;
			}
			KitManager.KitEntry entry = entries.get(entryIndex);
			gui.setSlot(CONTENT_SLOTS[slot], Menus.button(entry.icon(), entry.name(), ChatFormatting.GOLD,
					"Left click to equip this kit.",
					"Shift + right click to delete it.")
				.setCallback((index, type, action, ignored) -> {
					if (type.shift && type.isRight) {
						kits.delete(entry.name());
						Menus.notify(player, "Deleted kit '" + entry.name() + "'.");
						open(context, player, currentPage);
						return;
					}
					if (kits.apply(entry.name(), player)) {
						scriptify.feature.Sfx.kitEquip(player);
						Menus.notify(player, "Equipped kit '" + entry.name() + "'.");
					} else {
						Menus.deny(player, "That kit no longer exists.");
						open(context, player, currentPage);
					}
				}).build());
		}

		if (currentPage > 0) {
			gui.setSlot(PREVIOUS_SLOT, Menus.previousPageButton(currentPage, totalPages)
				.setCallback((index, type, action, ignored) -> open(context, player, currentPage - 1)).build());
		}
		if (currentPage < totalPages - 1) {
			gui.setSlot(NEXT_SLOT, Menus.nextPageButton(currentPage + 2, totalPages)
				.setCallback((index, type, action, ignored) -> open(context, player, currentPage + 1)).build());
		}

		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((index, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		scriptify.feature.Sfx.menuOpen(player);
	}

	/**
	 * Opens an anvil so the player can type the kit name — the only way to collect free text from an
	 * unmodified client. The held item is shown in the left slot as a preview of the icon the kit will
	 * get, so the choice is visible before committing.
	 */
	private static void promptForName(ScriptifyContext context, ServerPlayer player) {
		ItemStack heldIcon = player.getInventory().getSelectedItem().copy();
		AnvilInputGui anvil = new AnvilInputGui(player, false);
		anvil.setTitle(Component.literal("Name your kit"));
		anvil.setDefaultInputValue("kit");
		anvil.setSlot(0, new GuiElementBuilder(heldIcon.isEmpty() ? new ItemStack(Items.CHEST) : heldIcon)
			.setName(Component.literal("Type a name, then click the output")
				.withStyle(ChatFormatting.GRAY).withStyle(style -> style.withItalic(false)))
			.build());
		anvil.setSlot(2, Menus.button(Items.LIME_DYE, "Create Kit", ChatFormatting.GREEN,
				"Saves your inventory under the typed name.")
			.setCallback((index, type, action, ignored) -> {
				String name = anvil.getInput();
				try {
					ScriptifyRuntime.kits().create(name, player);
					Menus.notify(player, "Created kit '" + name + "'.");
				} catch (IllegalArgumentException exception) {
					Menus.deny(player, exception.getMessage());
				}
				open(context, player, 0);
			}).build());
		anvil.open();
	}
}
