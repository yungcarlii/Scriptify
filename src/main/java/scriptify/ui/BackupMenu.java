package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.InventoryBackupService;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * The {@code /backupinv} browser.
 *
 * <p>Each snapshot is one clock in the grid, newest first, labelled with when it was taken. Left click
 * restores it over the current inventory, shift + right click deletes it. The compass at the top takes
 * a fresh snapshot — the intended workflow is "snapshot, shoot the destructive take, restore".
 *
 * <p>Restoring is itself destructive, so it auto-snapshots the current loadout first: an accidental
 * click can always be walked back one step.
 */
public final class BackupMenu {
	private static final int[] CONTENT_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};
	private static final int NEW_BACKUP_SLOT = 4;
	private static final int PREVIOUS_SLOT = 45;
	private static final int NEXT_SLOT = 53;

	private BackupMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player, int page) {
		if (!context.permissions().has(player, PermissionService.INVENTORY)) {
			Menus.deny(player, "You do not have permission to use inventory backups.");
			return;
		}
		InventoryBackupService backups = ScriptifyRuntime.backups();
		List<InventoryBackupService.Backup> entries = backups.listNewestFirst(player.getUUID());
		int perPage = CONTENT_SLOTS.length;
		int totalPages = Math.max(1, (entries.size() + perPage - 1) / perPage);
		int currentPage = Math.max(0, Math.min(page, totalPages - 1));

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("» Inventory Backups «").withStyle(ChatFormatting.AQUA));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(NEW_BACKUP_SLOT, Menus.button(Items.COMPASS, "New Backup", ChatFormatting.AQUA,
				"Snapshots your inventory right now.",
				"The 10 most recent are kept.")
			.glow()
			.setCallback((slot, type, action, ignored) -> {
				backups.create(player, "manual");
				scriptify.feature.Sfx.backup(player);
				Menus.notify(player, "Inventory snapshot saved.");
				open(context, player, 0);
			}).build());

		for (int slot = 0; slot < perPage; slot++) {
			int entryIndex = currentPage * perPage + slot;
			if (entryIndex >= entries.size()) {
				break;
			}
			InventoryBackupService.Backup backup = entries.get(entryIndex);
			gui.setSlot(CONTENT_SLOTS[slot], Menus.button(Items.CLOCK, backup.label(), ChatFormatting.GOLD,
					"Reason: " + backup.reason(),
					"Left click to restore.",
					"Shift + right click to delete.")
				.setCallback((clicked, type, action, ignored) -> {
					if (type.shift && type.isRight) {
						backups.delete(player, backup.id());
						Menus.notify(player, "Deleted that snapshot.");
						open(context, player, currentPage);
						return;
					}
					// Restoring overwrites what they have now, so keep an undo point.
					backups.create(player, "auto-before-restore");
					if (backups.restore(player, backup.id())) {
						Menus.notify(player, "Restored the snapshot from " + backup.label() + ".");
					} else {
						Menus.deny(player, "That snapshot could not be read.");
					}
					open(context, player, currentPage);
				}).build());
		}

		if (entries.isEmpty()) {
			gui.setSlot(22, Menus.button(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "No backups yet",
				ChatFormatting.GRAY, "Use the compass above to take one.").build());
		}

		if (currentPage > 0) {
			gui.setSlot(PREVIOUS_SLOT, Menus.previousPageButton(currentPage, totalPages)
				.setCallback((slot, type, action, ignored) -> open(context, player, currentPage - 1)).build());
		}
		if (currentPage < totalPages - 1) {
			gui.setSlot(NEXT_SLOT, Menus.nextPageButton(currentPage + 2, totalPages)
				.setCallback((slot, type, action, ignored) -> open(context, player, currentPage + 1)).build());
		}

		gui.setSlot(48, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, player)).build());
		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
	}
}
