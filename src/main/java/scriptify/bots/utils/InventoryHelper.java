package scriptify.bots.utils;

import net.minecraft.world.entity.player.Inventory;

/**
 * Access to the selected hotbar slot.
 *
 * <p>Older mappings had no public setter here, so this went through reflection that tried the
 * yarn name, then a hard-coded intermediary name, then the raw field. Two of those three
 * branches were wrong on 1.21.11 - {@code method_67533} is a different method entirely - and
 * the whole thing was only ever load-bearing because the field fallback happened to work.
 * {@code getSelectedSlot} and {@code setSelectedSlot} are public now, so the calls below are
 * remapped by the build and checked by the compiler.</p>
 *
 * <p>Kept as a class of its own rather than inlined, so there is one place to look if a future
 * version moves it again.</p>
 */
public final class InventoryHelper {

    private InventoryHelper() {}

    public static void setSelectedSlot(Inventory inventory, int slot) {
        // The vanilla setter throws on anything outside the hotbar rather than clamping.
        if (slot < 0 || slot > 8) return;
        inventory.setSelectedSlot(slot);
    }

    public static int getSelectedSlot(Inventory inventory) {
        return inventory.getSelectedSlot();
    }
}
