package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.InventoryBackupService;
import scriptify.feature.InventoryCopyService;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * The {@code /backupinv} rollback browser.
 *
 * <p>Snapshots are taken two ways: manually, and automatically the instant a player dies — that second
 * one is the important half, because a death is exactly when a loadout disappears and exactly when
 * nobody remembers to press save first.
 *
 * <p>Death snapshots are marked with a skull and are the last to be pruned when the per-player cap is
 * reached, since a manual snapshot can always be retaken and a death cannot.
 */
public final class BackupMenu {
	private static final int[] CONTENT_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};
	private static final int NEW_BACKUP_SLOT = 4;
	private static final int PICK_SLOT = 8;
	private static final int PREVIOUS_SLOT = 45;
	private static final int NEXT_SLOT = 53;

	private BackupMenu() {
	}

	/** Opens the caller's own rollbacks. */
	public static void open(ScriptifyContext context, ServerPlayer player, int page) {
		open(context, player, player, page);
	}

	/**
	 * Opens {@code target}'s rollbacks.
	 *
	 * @param viewer who is looking; the menu acts on {@code target} but reports back to {@code viewer}.
	 */
	public static void open(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target, int page) {
		if (!context.permissions().has(viewer, PermissionService.INVENTORY)) {
			Menus.deny(viewer, "You do not have permission to use inventory backups.");
			return;
		}
		InventoryBackupService backups = ScriptifyRuntime.backups();
		List<InventoryBackupService.Backup> entries = backups.listNewestFirst(target.getUUID());
		int perPage = CONTENT_SLOTS.length;
		int totalPages = Math.max(1, (entries.size() + perPage - 1) / perPage);
		int currentPage = Math.max(0, Math.min(page, totalPages - 1));
		boolean self = viewer == target;

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), viewer, false);
		gui.setTitle(Component.literal("» Rollback: " + target.getGameProfile().name() + " «")
			.withStyle(ChatFormatting.AQUA));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(NEW_BACKUP_SLOT, Menus.button(Items.COMPASS, "Save Now", ChatFormatting.AQUA,
				"Snapshots " + (self ? "your" : target.getGameProfile().name() + "'s") + " inventory.",
				"Deaths are saved automatically as well.")
			.glow()
			.setCallback((slot, type, action, ignored) -> {
				backups.create(target, InventoryBackupService.REASON_MANUAL);
				Sfx.backup(viewer);
				Menus.notify(viewer, "Inventory snapshot saved.");
				open(context, viewer, target, 0);
			}).build());

		// Switching to another player's history, for restoring someone else's gear after a scene.
		gui.setSlot(PICK_SLOT, Menus.button(Items.PLAYER_HEAD, "Another Player", ChatFormatting.GOLD,
				"View someone else's rollbacks.")
			.setCallback((slot, type, action, ignored) -> PlayerPicker.open(context, viewer,
				"Rollbacks", ChatFormatting.AQUA,
				candidate -> List.of("Click to view their snapshots."),
				(picked, click) -> open(context, viewer, picked, 0), 0)).build());

		for (int slot = 0; slot < perPage; slot++) {
			int entryIndex = currentPage * perPage + slot;
			if (entryIndex >= entries.size()) {
				break;
			}
			InventoryBackupService.Backup backup = entries.get(entryIndex);
			boolean death = InventoryBackupService.REASON_DEATH.equals(backup.reason());
			gui.setSlot(CONTENT_SLOTS[slot], Menus.button(
					death ? Items.SKELETON_SKULL : Items.CLOCK,
					backup.label(),
					death ? ChatFormatting.RED : ChatFormatting.GOLD,
					describe(backup.reason()),
					"Left click to restore.",
					"Shift + right click to delete.")
				.setCallback((clicked, type, action, ignored) -> {
					if (type.shift && type.isRight) {
						backups.delete(target, backup.id());
						Menus.notify(viewer, "Deleted that snapshot.");
						open(context, viewer, target, currentPage);
						return;
					}
					// Restoring overwrites what they have now, so keep an undo point.
					backups.create(target, InventoryBackupService.REASON_PRE_RESTORE);
					if (backups.restore(target, backup.id())) {
						Menus.notify(viewer, "Restored the snapshot from " + backup.label() + ".");
						if (!self) {
							// The owner should know their inventory changed under them.
							Msg.chat(target, Msg.line("Your inventory was rolled back."));
						}
					} else {
						Menus.deny(viewer, "That snapshot could not be read.");
					}
					open(context, viewer, target, currentPage);
				}).build());
		}

		if (entries.isEmpty()) {
			gui.setSlot(22, Menus.button(scriptify.ui.DyedItems.glassPane(DyeColor.LIGHT_GRAY), "No snapshots yet",
				ChatFormatting.GRAY,
				"One is taken automatically on death,",
				"or use Save Now above.").build());
		}

		if (currentPage > 0) {
			gui.setSlot(PREVIOUS_SLOT, Menus.previousPageButton(currentPage, totalPages)
				.setCallback((slot, type, action, ignored) -> open(context, viewer, target, currentPage - 1)).build());
		}
		if (currentPage < totalPages - 1) {
			gui.setSlot(NEXT_SLOT, Menus.nextPageButton(currentPage + 2, totalPages)
				.setCallback((slot, type, action, ignored) -> open(context, viewer, target, currentPage + 1)).build());
		}

		gui.setSlot(48, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, viewer)).build());
		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	/**
	 * What this snapshot was taken for.
	 *
	 * <p>Worth spelling out: the automatic ones are the only copy of an inventory that something else
	 * was about to overwrite, and "saved manually" would send someone looking for a snapshot they never
	 * took.
	 */
	private static String describe(String reason) {
		if (InventoryBackupService.REASON_DEATH.equals(reason)) {
			return "Saved automatically on death.";
		}
		if (InventoryBackupService.REASON_PRE_RESTORE.equals(reason)) {
			return "Saved before restoring another snapshot.";
		}
		if (InventoryCopyService.REASON_PRE_COPY.equals(reason)) {
			return "Saved before an inventory was copied over.";
		}
		return "Saved manually.";
	}
}
