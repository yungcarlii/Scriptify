package scriptify.bots.utils;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * Inventory plumbing shared by the whole mod.
 *
 * <p>The original code repeated the same "find item, swap it into a hotbar slot, select it"
 * block more than twenty times, each copy with slightly different slot numbers and a
 * different bug (some swapped into slot 0, some into slot 8, some clobbered whatever was
 * already there without putting it back). Centralising it removes that class of bug and
 * keeps hotbar churn in one place.</p>
 *
 * <p>Slot layout reminder: 0-8 hotbar, 9-35 main inventory, 36-39 armour
 * (feet, legs, chest, head), 40 offhand.</p>
 */
public final class Inv {

    private Inv() {}

    public static final int HOTBAR_END = 9;
    public static final int MAIN_END = 36;
    public static final int ARMOR_START = 36;
    public static final int OFFHAND = 40;

    /** First slot in the main inventory (0-35) matching the predicate, or -1. */
    public static int find(Inventory inv, Predicate<ItemStack> test) {
        for (int i = 0; i < MAIN_END; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && test.test(stack)) return i;
        }
        return -1;
    }

    public static int findItem(Inventory inv, Item item) {
        return find(inv, s -> s.getItem() == item);
    }

    public static boolean has(Inventory inv, Item item) {
        return findItem(inv, item) >= 0;
    }

    public static int count(Inventory inv, Item item) {
        int total = 0;
        for (int i = 0; i < MAIN_END; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    /** Best slot by score; ties broken by the first match. Returns -1 when nothing scores. */
    public static int best(Inventory inv, java.util.function.ToDoubleFunction<ItemStack> score) {
        int bestSlot = -1;
        double bestScore = 0;
        for (int i = 0; i < MAIN_END; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            double s = score.applyAsDouble(stack);
            if (s > bestScore) {
                bestScore = s;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * Makes the item in {@code slot} the held item, moving it into the hotbar first if
     * needed by swapping with {@code preferredHotbarSlot}. Returns the hotbar slot now
     * holding the item, or -1.
     */
    public static int equip(Inventory inv, int slot, int preferredHotbarSlot) {
        if (slot < 0 || slot >= MAIN_END) return -1;
        int target = Math.max(0, Math.min(8, preferredHotbarSlot));
        if (slot >= HOTBAR_END) {
            ItemStack wanted = inv.getItem(slot);
            ItemStack displaced = inv.getItem(target);
            inv.setItem(target, wanted);
            inv.setItem(slot, displaced);
            slot = target;
        }
        InventoryHelper.setSelectedSlot(inv, slot);
        return slot;
    }

    public static int equip(Inventory inv, int slot) {
        return equip(inv, slot, 0);
    }

    /** Equips the first item of that type, returning the hotbar slot or -1. */
    public static int equipItem(Inventory inv, Item item, int preferredHotbarSlot) {
        int slot = findItem(inv, item);
        return slot < 0 ? -1 : equip(inv, slot, preferredHotbarSlot);
    }

    public static int selected(Inventory inv) {
        return InventoryHelper.getSelectedSlot(inv);
    }

    public static ItemStack held(Inventory inv) {
        return inv.getItem(selected(inv));
    }

    /** Moves a stack into the offhand, returning whatever was displaced. */
    public static ItemStack swapOffhand(Inventory inv, int slot) {
        if (slot < 0 || slot >= MAIN_END) return ItemStack.EMPTY;
        ItemStack incoming = inv.getItem(slot);
        ItemStack outgoing = inv.getItem(OFFHAND);
        inv.setItem(OFFHAND, incoming);
        inv.setItem(slot, outgoing);
        return outgoing;
    }

    /** First empty slot in the main inventory, or -1. */
    public static int firstEmpty(Inventory inv) {
        for (int i = 0; i < MAIN_END; i++) {
            if (inv.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /** Stores a stack anywhere it fits; drops it at the bot's feet as a last resort. */
    public static void stow(ServerPlayer bot, ItemStack stack) {
        if (stack.isEmpty()) return;
        int empty = firstEmpty(bot.getInventory());
        if (empty >= 0) {
            bot.getInventory().setItem(empty, stack);
        } else {
            bot.drop(stack, false, false);
        }
    }

    /** True when the stack carries an enchantment whose id contains the given text. */
    public static boolean hasEnchant(ItemStack stack, String idFragment) {
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return false;
        for (var entry : enchantments.keySet()) {
            if (entry.getRegisteredName().toLowerCase(java.util.Locale.ROOT).contains(idFragment)) {
                return true;
            }
        }
        return false;
    }

    /** Enchantment level by id fragment, 0 when absent. */
    public static int enchantLevel(ItemStack stack, String idFragment) {
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments == null) return 0;
        for (var entry : enchantments.keySet()) {
            if (entry.getRegisteredName().toLowerCase(java.util.Locale.ROOT).contains(idFragment)) {
                return enchantments.getLevel(entry);
            }
        }
        return 0;
    }

    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.FOOD) != null;
    }
}
