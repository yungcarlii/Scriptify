package scriptify.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;
import scriptify.kits.KitManager;
import scriptify.runtime.ScriptifyRuntime;

/**
 * Edits a kit's contents by hand.
 *
 * <p>Real slots rather than clickable buttons, so a kit is changed the way an inventory is: drag items
 * in, take them out, shift-click a stack across. Anything else would mean reinventing chest handling
 * badly.
 *
 * <p>Laid out like a player inventory — armour and offhand along the top, backpack in the middle,
 * hotbar at the bottom — because that is what a kit is a snapshot of, and a raw index order makes the
 * hotbar appear at the top for no reason a reader could guess.
 *
 * <p>Saved when the screen closes. Saving on every click would rewrite the file dozens of times while
 * somebody is still arranging things.
 */
public final class KitEditMenu {
	private static final int GRID = 54;
	private static final int WIDTH = 9;
	private static final int HELMET = 39;
	private static final int OFFHAND = 40;

	/** Which kit slot each grid cell shows, or -1 for a blocked filler. */
	private static final int[] LAYOUT = buildLayout();

	private static int[] buildLayout() {
		int[] layout = new int[GRID];
		java.util.Arrays.fill(layout, -1);
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

	private KitEditMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target,
			String kit, int page) {
		KitManager kits = ScriptifyRuntime.kits();
		if (!kits.canManage(viewer)) {
			Menus.deny(viewer, "You do not have permission to edit kits.");
			KitsMenu.open(context, viewer, target, page);
			return;
		}
		ItemStack[] contents = kits.contents(kit);
		if (contents == null) {
			Menus.deny(viewer, "That kit is gone.");
			KitsMenu.open(context, viewer, target, page);
			return;
		}

		// A working copy, so the stored kit is untouched while somebody is still rearranging it. The
		// copy is written back when the screen closes.
		SimpleContainer working = new SimpleContainer(contents.length);
		for (int slot = 0; slot < contents.length; slot++) {
			working.setItem(slot, contents[slot].copy());
		}

		String name = kits.displayName(kit);
		viewer.openMenu(new SimpleMenuProvider((syncId, inventory, player) ->
			new KitMenu(syncId, inventory, working, kit, viewer, context, target, page),
			Component.literal("Editing " + name)));
		Sfx.menuOpen(viewer);
	}

	private static final class KitMenu extends AbstractContainerMenu {
		private final SimpleContainer working;
		private final String kit;
		private final ServerPlayer viewer;
		private final ScriptifyContext context;
		private final ServerPlayer target;
		private final int page;
		private boolean saved;

		private KitMenu(int syncId, Inventory viewerInventory, SimpleContainer working, String kit,
				ServerPlayer viewer, ScriptifyContext context, ServerPlayer target, int page) {
			super(MenuType.GENERIC_9x6, syncId);
			this.working = working;
			this.kit = kit;
			this.viewer = viewer;
			this.context = context;
			this.target = target;
			this.page = page;

			for (int slot = 0; slot < GRID; slot++) {
				int x = 8 + (slot % WIDTH) * 18;
				int y = 18 + (slot / WIDTH) * 18;
				int index = LAYOUT[slot];
				if (index >= 0 && index < working.getContainerSize()) {
					this.addSlot(new Slot(working, index, x, y));
				} else {
					this.addSlot(new BlockedSlot(working, 0, x, y));
				}
			}
			for (int row = 0; row < 3; row++) {
				for (int column = 0; column < WIDTH; column++) {
					this.addSlot(new Slot(viewerInventory, column + row * WIDTH + 9,
						8 + column * 18, 140 + row * 18));
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
		public void removed(Player player) {
			super.removed(player);
			if (this.saved) {
				return;
			}
			this.saved = true;
			ItemStack[] items = new ItemStack[this.working.getContainerSize()];
			for (int slot = 0; slot < items.length; slot++) {
				items[slot] = this.working.getItem(slot).copy();
			}
			if (ScriptifyRuntime.kits().setContents(this.kit, items)) {
				Menus.notify(this.viewer, "Saved changes to "
					+ ScriptifyRuntime.kits().displayName(this.kit) + ".");
				Sfx.success(this.viewer);
			}
			// Reopened a tick later: closing a container and opening another in the same tick leaves the
			// client showing neither.
			this.viewer.level().getServer().execute(() ->
				KitOptionsMenu.open(this.context, this.viewer, this.target, this.kit, this.page));
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
			} else if (!moveIntoKit(original)) {
				return ItemStack.EMPTY;
			}
			if (original.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
			return copy;
		}

		/** Backpack and hotbar only: armour is placed deliberately, never as the first free space. */
		private boolean moveIntoKit(ItemStack stack) {
			for (int slot = 0; slot < GRID; slot++) {
				int index = LAYOUT[slot];
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

	private static final class BlockedSlot extends Slot {
		private BlockedSlot(SimpleContainer container, int slot, int x, int y) {
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
