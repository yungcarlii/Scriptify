package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shared chrome for every Scriptify menu — the grey filler border, the close button, page arrows and
 * the toggle/action button styling — so the menus stay visually consistent.
 */
public final class Menus {
	/** Slot index of the close button in a 6-row menu. */
	public static final int CLOSE_SLOT = 49;

	private Menus() {
	}

	public static MenuType<?> typeForRows(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}

	/** Fills every slot with an unlabelled grey pane, matching the bordered look of the mockups. */
	public static void fill(SimpleGui gui) {
		GuiElementBuilder filler = new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE)
			.setName(Component.empty())
			.hideDefaultTooltip();
		for (int slot = 0; slot < gui.getSize(); slot++) {
			gui.setSlot(slot, filler.build());
		}
	}

	/** A plain clickable button with a title and description lines. */
	public static GuiElementBuilder button(Item icon, String title, ChatFormatting colour, String... lore) {
		GuiElementBuilder builder = new GuiElementBuilder(icon)
			.setName(Component.literal(title).withStyle(colour).withStyle(style -> style.withItalic(false)));
		for (String line : lore) {
			builder.addLoreLine(Component.literal(line)
				.withStyle(ChatFormatting.GRAY).withStyle(style -> style.withItalic(false)));
		}
		return builder;
	}

	/** Same as {@link #button} but built from a live stack, used for kit icons. */
	public static GuiElementBuilder button(ItemStack icon, String title, ChatFormatting colour, String... lore) {
		GuiElementBuilder builder = GuiElementBuilder.from(icon.copy())
			.setName(Component.literal(title).withStyle(colour).withStyle(style -> style.withItalic(false)));
		for (String line : lore) {
			builder.addLoreLine(Component.literal(line)
				.withStyle(ChatFormatting.GRAY).withStyle(style -> style.withItalic(false)));
		}
		return builder;
	}

	/**
	 * A boolean toggle: green when on, red when off, with the state spelled out in the name so it reads
	 * at a glance without hovering.
	 */
	public static GuiElementBuilder toggle(Item icon, String title, boolean enabled, String... lore) {
		GuiElementBuilder builder = new GuiElementBuilder(icon)
			.setName(Component.literal(title).withStyle(ChatFormatting.WHITE)
				.withStyle(style -> style.withItalic(false))
				.append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(enabled ? "ON" : "OFF")
					.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)));
		for (String line : lore) {
			builder.addLoreLine(Component.literal(line)
				.withStyle(ChatFormatting.GRAY).withStyle(style -> style.withItalic(false)));
		}
		if (enabled) {
			builder.glow();
		}
		return builder;
	}

	public static GuiElementBuilder closeButton() {
		return new GuiElementBuilder(Items.BARRIER)
			.setName(Component.literal("Close").withStyle(ChatFormatting.RED)
				.withStyle(style -> style.withItalic(false)));
	}

	public static GuiElementBuilder nextPageButton(int nextPage, int totalPages) {
		return new GuiElementBuilder(Items.ARROW)
			.setName(Component.literal("Next page").withStyle(ChatFormatting.YELLOW)
				.withStyle(style -> style.withItalic(false)))
			.setLore(List.of(Component.literal("Page " + nextPage + " of " + totalPages)
				.withStyle(ChatFormatting.GRAY).withStyle(style -> style.withItalic(false))));
	}

	public static GuiElementBuilder previousPageButton(int previousPage, int totalPages) {
		return new GuiElementBuilder(Items.ARROW)
			.setName(Component.literal("Previous page").withStyle(ChatFormatting.YELLOW)
				.withStyle(style -> style.withItalic(false)))
			.setLore(List.of(Component.literal("Page " + previousPage + " of " + totalPages)
				.withStyle(ChatFormatting.GRAY).withStyle(style -> style.withItalic(false))));
	}

	public static GuiElementBuilder backButton() {
		return new GuiElementBuilder(Items.OAK_DOOR)
			.setName(Component.literal("Back").withStyle(ChatFormatting.YELLOW)
				.withStyle(style -> style.withItalic(false)));
	}

	/** Action-bar confirmation; keeps chat clean while still acknowledging the click. */
	public static void notify(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.AQUA), true);
	}

	public static void deny(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), true);
		scriptify.feature.Sfx.error(player);
	}
}
