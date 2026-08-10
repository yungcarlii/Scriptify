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

public final class InventoryManager {
	public void openInventory(ServerPlayer viewer, ServerPlayer target) {
		viewer.openMenu(new SimpleMenuProvider((syncId, inventory, player) ->
			new LiveContainerMenu(syncId, inventory, target.getInventory(), 41), Component.literal("Inventory: " + target.getGameProfile().name())));
	}

	public void openEnderChest(ServerPlayer viewer, ServerPlayer target) {
		viewer.openMenu(new SimpleMenuProvider((syncId, inventory, player) ->
			new LiveContainerMenu(syncId, inventory, target.getEnderChestInventory(), target.getEnderChestInventory().getContainerSize()), Component.literal("Ender Chest: " + target.getGameProfile().name())));
	}

	private static final class LiveContainerMenu extends AbstractContainerMenu {
		private final Container target;
		private final int targetSize;

		private LiveContainerMenu(int syncId, Inventory viewerInventory, Container target, int targetSize) {
			super(MenuType.GENERIC_9x6, syncId);
			this.target = target;
			this.targetSize = Math.min(54, Math.min(targetSize, target.getContainerSize()));
			for (int slot = 0; slot < 54; slot++) {
				int x = 8 + (slot % 9) * 18;
				int y = 18 + (slot / 9) * 18;
				if (slot < this.targetSize) {
					this.addSlot(new Slot(target, slot, x, y));
				} else {
					this.addSlot(new DisabledSlot(target, 0, x, y));
				}
			}
			for (int row = 0; row < 3; row++) {
				for (int column = 0; column < 9; column++) {
					this.addSlot(new Slot(viewerInventory, column + row * 9 + 9, 8 + column * 18, 140 + row * 18));
				}
			}
			for (int column = 0; column < 9; column++) {
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
			if (index < 54) {
				if (!this.moveItemStackTo(original, 54, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (!this.moveItemStackTo(original, 0, this.targetSize, false)) {
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
