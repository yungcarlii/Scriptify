package scriptify.bots.ui;

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
import scriptify.bots.bot.BotKits;

/**
 * Builds a bot kit by hand, in a screen shaped like the inventory it describes.
 *
 * <p>Until now the only way to make a kit was to wear it: empty your own inventory, assemble the
 * gear on yourself, save, and put your own things back. That is fine for the first kit and absurd
 * by the fourth, and it makes editing one - swapping a sword, adding a pearl - a matter of giving
 * the kit to yourself, changing it, deleting the old one and saving a new one under the same
 * name.</p>
 *
 * <p>These are real slots, not buttons that open other screens: items are dragged in, taken out and
 * shift-clicked across exactly as they are anywhere else in the game. Anything else would mean
 * reimplementing inventory handling, worse.</p>
 *
 * <p>Laid out as a player: armour and off hand along the top, backpack in the middle, hotbar along
 * the bottom. The stored order runs hotbar-first, so drawing it raw would put the hotbar at the top
 * of the screen for a reason nobody could guess from looking at it.</p>
 */
public final class BotKitEditMenu {

    private BotKitEditMenu() {}

    private static final int GRID = 54;
    private static final int WIDTH = 9;
    private static final int HELMET = 39;
    private static final int OFFHAND = 40;

    /** Which kit slot each cell of the screen shows, or -1 for a blocked filler. */
    private static final int[] LAYOUT = buildLayout();

    private static int[] buildLayout() {
        int[] layout = new int[GRID];
        java.util.Arrays.fill(layout, -1);
        // Armour reads helmet-first across the top, which is head to foot rather than the
        // storage order, which is the other way round.
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

    public static void open(ServerPlayer viewer, String kit, String botName) {
        ItemStack[] contents = BotKits.contents(kit, viewer);
        if (contents == null) {
            Menus.deny(viewer, "That kit is gone.");
            KitsMenu.open(viewer, botName);
            return;
        }

        // A working copy, so the saved kit is untouched while somebody is still rearranging it.
        // It is written back when the screen closes.
        SimpleContainer working = new SimpleContainer(BotKits.SLOTS);
        for (int slot = 0; slot < contents.length; slot++) {
            working.setItem(slot, contents[slot].copy());
        }

        viewer.openMenu(new SimpleMenuProvider((syncId, inventory, player) ->
            new KitMenu(syncId, inventory, working, kit, viewer, botName),
            Msg.title("Editing " + kit, net.minecraft.ChatFormatting.WHITE)));
        Sfx.click(viewer);
    }

    private static final class KitMenu extends AbstractContainerMenu {
        private final SimpleContainer working;
        private final String kit;
        private final ServerPlayer viewer;
        private final String botName;
        private boolean saved;

        private KitMenu(int syncId, Inventory viewerInventory, SimpleContainer working, String kit,
                        ServerPlayer viewer, String botName) {
            super(MenuType.GENERIC_9x6, syncId);
            this.working = working;
            this.kit = kit;
            this.viewer = viewer;
            this.botName = botName;

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
            if (this.saved) return;
            this.saved = true;

            ItemStack[] items = new ItemStack[this.working.getContainerSize()];
            for (int slot = 0; slot < items.length; slot++) {
                items[slot] = this.working.getItem(slot).copy();
            }
            if (BotKits.setContents(this.kit, items, this.viewer)) {
                Menus.notify(this.viewer, "Saved " + this.kit + ".");
                Sfx.success(this.viewer);
            }

            // Reopened a tick later: closing one container and opening another in the same tick
            // leaves the client showing neither.
            this.viewer.level().getServer().execute(() ->
                KitsMenu.open(this.viewer, this.botName));
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

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
                if (index < 0 || index >= 36) continue;
                if (this.moveItemStackTo(stack, slot, slot + 1, false)) return true;
            }
            return false;
        }
    }

    /** A filler cell: it exists to be grey, and nothing may be put in it or taken out. */
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
