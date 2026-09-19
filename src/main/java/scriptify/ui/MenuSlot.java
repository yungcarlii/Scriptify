package scriptify.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

public record MenuSlot(int index, Item item, Component name, Component lore, SlotClickHandler handler) {
}
