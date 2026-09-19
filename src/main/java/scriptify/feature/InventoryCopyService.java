package scriptify.feature;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Hands one player's exact loadout to another.
 *
 * <p>For scenes where two people have to be visibly identical — a double, a clone shot, a lineup of
 * extras kitted the same way. Building that by hand from a kit gets close; this gets it exact,
 * durability and custom names included.
 *
 * <p>Replaces rather than tops up. "Give them my inventory" means they end up with mine, and a copy
 * that merged into whatever they were already carrying would be neither theirs nor yours.
 */
public final class InventoryCopyService {
	/** Reason recorded on the snapshot taken before a target is overwritten. */
	public static final String REASON_PRE_COPY = "before-copy";

	private InventoryCopyService() {
	}

	/**
	 * Copies {@code source}'s whole inventory over {@code target}'s.
	 *
	 * <p>The target's own inventory is snapshotted first, so an overwrite is always undoable from
	 * {@code /backupinv} — this throws away everything they were carrying, and that is not something to
	 * make irreversible.
	 *
	 * <p>Stacks are deep-copied, so editing one player's sword afterwards does not edit the other's.
	 *
	 * @param backups snapshot store to record the target's previous inventory in.
	 * @return the number of non-empty stacks copied.
	 */
	public static int copy(ServerPlayer source, ServerPlayer target, InventoryBackupService backups) {
		// A kit still being dealt out would overwrite this a tick later.
		scriptify.runtime.ScriptifyRuntime.kits().cancelAnimation(target);
		backups.create(target, REASON_PRE_COPY);

		int slots = Math.min(source.getInventory().getContainerSize(), target.getInventory().getContainerSize());
		int copied = 0;
		for (int slot = 0; slot < slots; slot++) {
			ItemStack stack = source.getInventory().getItem(slot);
			target.getInventory().setItem(slot, stack.copy());
			if (!stack.isEmpty()) {
				copied++;
			}
		}
		// Anything past the shorter of the two inventories is cleared, so the target is left holding a
		// copy and nothing else — a leftover stack in slot 41 would break the illusion.
		for (int slot = slots; slot < target.getInventory().getContainerSize(); slot++) {
			target.getInventory().setItem(slot, ItemStack.EMPTY);
		}

		target.getInventory().setChanged();
		target.containerMenu.broadcastChanges();
		return copied;
	}
}
