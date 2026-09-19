package scriptify.inventory;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Live views of another player's storage, laid out the way a person reads an inventory.
 *
 * <p>The slots are real: taking, placing, shift-clicking and swapping all work and land in the target's
 * actual inventory as they happen.
 *
 * <p>The layout matters more than it sounds. A raw index mapping puts the hotbar on the top row and
 * buries the armour in the middle, because that is the order the indices happen to be in. Here the
 * armour and offhand sit together along the top, the backpack fills the middle, and the hotbar is the
 * bottom row — the same shape as the inventory being looked at.
 */
public final class InventoryManager {
	private static final int GRID = 54;
	private static final int WIDTH = 9;
	/** Vanilla inventory indices: 0-8 hotbar, 9-35 backpack, 36-39 armour, 40 offhand. */
	private static final int HELMET = 39;
	private static final int OFFHAND = 40;

	/**
	 * Which inventory index each grid slot shows, or -1 for a blocked filler.
	 *
	 * <p>Row 0 is armour then a gap then the offhand, row 1 is a spacer, rows 2-4 are the backpack and
	 * row 5 is the hotbar.
	 */
	private static final int[] PLAYER_LAYOUT = buildPlayerLayout();

	private static int[] buildPlayerLayout() {
		int[] layout = new int[GRID];
		java.util.Arrays.fill(layout, -1);
		// Helmet, chestplate, leggings, boots — worn top to bottom, so shown left to right.
		for (int piece = 0; piece < 4; piece++) {
			layout[piece] = HELMET - piece;
		}
		layout[8] = OFFHAND;
		for (int index = 0; index < 27; index++) {
			layout[18 + index] = 9 + index;
		}
		for (int index = 0; index < WIDTH; index++) {
			layout[45 + index] = index;
		}
		return layout;
	}

	/** The ender chest is a plain 27-slot box, so it sits in the middle three rows. */
	private static int[] buildChestLayout(int size) {
		int[] layout = new int[GRID];
		java.util.Arrays.fill(layout, -1);
		for (int index = 0; index < size && index < 27; index++) {
			layout[9 + index] = index;
		}
		return layout;
	}

	public void openInventory(ServerPlayer viewer, ServerPlayer target) {
		// Editing an inventory that a kit is still filling would have the kit win.
		scriptify.runtime.ScriptifyRuntime.kits().cancelAnimation(target);
		viewer.openMenu(new SimpleMenuProvider((syncId, inventory, player) ->
			new LiveContainerMenu(syncId, inventory, target.getInventory(), PLAYER_LAYOUT),
			Component.literal("Inventory: " + target.getGameProfile().name())));
	}

	public void openEnderChest(ServerPlayer viewer, ServerPlayer target) {
		Container chest = target.getEnderChestInventory();
		viewer.openMenu(new SimpleMenuProvider((syncId, inventory, player) ->
			new LiveContainerMenu(syncId, inventory, chest, buildChestLayout(chest.getContainerSize())),
			Component.literal("Ender Chest: " + target.getGameProfile().name())));
	}

	private static final class LiveContainerMenu extends AbstractContainerMenu {
		private final Container target;
		private final int[] layout;

		private LiveContainerMenu(int syncId, Inventory viewerInventory, Container target, int[] layout) {
			super(MenuType.GENERIC_9x6, syncId);
			this.target = target;
			this.layout = layout;

			for (int slot = 0; slot < GRID; slot++) {
				int x = 8 + (slot % WIDTH) * 18;
				int y = 18 + (slot / WIDTH) * 18;
				int index = layout[slot];
				if (index >= 0 && index < target.getContainerSize()) {
					this.addSlot(new Slot(target, index, x, y));
				} else {
					this.addSlot(new DisabledSlot(target, 0, x, y));
				}
			}
			for (int row = 0; row < 3; row++) {
				for (int column = 0; column < WIDTH; column++) {
					this.addSlot(new Slot(viewerInventory, column + row * WIDTH + 9, 8 + column * 18, 140 + row * 18));
				}
			}
			for (int column = 0; column < WIDTH; column++) {
				this.addSlot(new Slot(viewerInventory, column, 8 + column * 18, 198));
			}
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			Slot slot = this.slots.get(index);
			if (slot == null || !slot.hasItem()) {
				return ItemStack.EMPTY;
			}
			ItemStack original = slot.getItem();
			ItemStack copy = original.copy();
			if (index < GRID) {
				if (!this.moveItemStackTo(original, GRID, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (!moveIntoTarget(original)) {
				return ItemStack.EMPTY;
			}
			if (original.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
			this.target.setChanged();
			return copy;
		}

		/**
		 * Shift-clicks into the target's live slots.
		 *
		 * <p>Walks the layout rather than a slot range, because the grid is no longer a straight run of
		 * indices — a range would happily post a sword into the helmet slot on its way past.
		 */
		private boolean moveIntoTarget(ItemStack stack) {
			for (int slot = 0; slot < GRID; slot++) {
				// Backpack and hotbar only. Armour and the offhand are placed deliberately, never as the
				// first empty space something happened to land in.
				int index = this.layout[slot];
				if (index < 0 || index >= 36) {
					continue;
				}
				if (this.moveItemStackTo(stack, slot, slot + 1, false)) {
					return true;
				}
			}
			return false;
		}
	}

	private static final class DisabledSlot extends Slot {
		private DisabledSlot(Container container, int slot, int x, int y) {
			super(container, slot, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}

		@Override
		public ItemStack getItem() {
			return ItemStack.EMPTY;
		}
	}
}
