package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.feature.Sfx;

/**
 * Shared chrome for every Scriptify menu — the background, buttons, toggles, page arrows and click
 * feedback — so every screen in the mod looks and behaves like part of the same product.
 */
public final class Menus {
	/** Slot index of the close button in a 6-row menu. */
	public static final int CLOSE_SLOT = 49;
	private static final int WIDTH = 9;

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

	/**
	 * Lays the background in: a dark frame around the edge, lighter panes inside.
	 *
	 * <p>The two tones are what make the grid read as a deliberate layout rather than a wall of
	 * identical squares — the eye finds the content area immediately.
	 */
	public static void fill(SimpleGui gui) {
		GuiElementBuilder frame = new GuiElementBuilder(scriptify.ui.DyedItems.glassPane(DyeColor.BLACK))
			.setName(Component.empty()).hideDefaultTooltip();
		GuiElementBuilder inner = new GuiElementBuilder(scriptify.ui.DyedItems.glassPane(DyeColor.GRAY))
			.setName(Component.empty()).hideDefaultTooltip();
		int rows = gui.getSize() / WIDTH;
		for (int slot = 0; slot < gui.getSize(); slot++) {
			int column = slot % WIDTH;
			int row = slot / WIDTH;
			boolean edge = row == 0 || row == rows - 1 || column == 0 || column == WIDTH - 1;
			gui.setSlot(slot, (edge ? frame : inner).build());
		}
	}

	/**
	 * Lays only the border in, leaving the interior empty.
	 *
	 * <p>The frame still marks where the menu ends, but the content area stays blank so the icons in it
	 * are the only things competing for attention.
	 */
	public static void frame(SimpleGui gui) {
		GuiElementBuilder edge = new GuiElementBuilder(scriptify.ui.DyedItems.glassPane(DyeColor.BLACK))
			.setName(Component.empty()).hideDefaultTooltip();
		int rows = gui.getSize() / WIDTH;
		for (int slot = 0; slot < gui.getSize(); slot++) {
			int column = slot % WIDTH;
			int row = slot / WIDTH;
			if (row == 0 || row == rows - 1 || column == 0 || column == WIDTH - 1) {
				gui.setSlot(slot, edge.build());
			}
		}
	}

	/** A plain clickable button with a title and description lines. */
	public static GuiElementBuilder button(Item icon, String title, ChatFormatting colour, String... lore) {
		GuiElementBuilder builder = new GuiElementBuilder(icon).setName(Msg.title(title, colour));
		for (String line : lore) {
			builder.addLoreLine(Msg.hint(line));
		}
		return builder;
	}

	/** Same as {@link #button} but built from a live stack, used for kit icons. */
	public static GuiElementBuilder button(ItemStack icon, String title, ChatFormatting colour, String... lore) {
		GuiElementBuilder builder = GuiElementBuilder.from(icon.copy()).setName(Msg.title(title, colour));
		for (String line : lore) {
			builder.addLoreLine(Msg.hint(line));
		}
		return builder;
	}

	/**
	 * A head rendered with the target's own skin, for player pickers.
	 *
	 * <p>Showing the actual face is the difference between picking a player and reading a list of
	 * usernames — and it doubles as a live preview once a skin has been changed.
	 */
	public static GuiElementBuilder playerHead(ServerPlayer target, ChatFormatting colour, String... lore) {
		GuiElementBuilder builder = new GuiElementBuilder(Items.PLAYER_HEAD)
			.setProfile(target.getGameProfile())
			.setName(Msg.title(target.getGameProfile().name(), colour));
		for (String line : lore) {
			builder.addLoreLine(Msg.hint(line));
		}
		return builder;
	}

	/**
	 * A boolean toggle: green when on, red when off, with the state spelled out in the name so it reads
	 * at a glance without hovering, and a glow so it stands out across the grid.
	 */
	public static GuiElementBuilder toggle(Item icon, String title, boolean enabled, String... lore) {
		GuiElementBuilder builder = new GuiElementBuilder(icon)
			.setName(Msg.title(title, ChatFormatting.WHITE)
				.append(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(enabled ? "ON" : "OFF")
					.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)
					.withStyle(style -> style.withBold(true))));
		for (String line : lore) {
			builder.addLoreLine(Msg.hint(line));
		}
		if (enabled) {
			builder.glow();
		}
		return builder;
	}

	public static GuiElementBuilder closeButton() {
		return new GuiElementBuilder(Items.BARRIER).setName(Msg.title("Close", ChatFormatting.RED));
	}

	public static GuiElementBuilder nextPageButton(int nextPage, int totalPages) {
		return new GuiElementBuilder(Items.ARROW)
			.setName(Msg.title("Next page", ChatFormatting.YELLOW))
			.setLore(List.of(Msg.hint("Page " + nextPage + " of " + totalPages)));
	}

	public static GuiElementBuilder previousPageButton(int previousPage, int totalPages) {
		return new GuiElementBuilder(Items.ARROW)
			.setName(Msg.title("Previous page", ChatFormatting.YELLOW))
			.setLore(List.of(Msg.hint("Page " + previousPage + " of " + totalPages)));
	}

	public static GuiElementBuilder backButton() {
		return new GuiElementBuilder(Items.OAK_DOOR).setName(Msg.title("Back", ChatFormatting.YELLOW));
	}

	/** Action-bar confirmation plus a soft cue, so a click always both reads and sounds like it landed. */
	public static void notify(ServerPlayer player, String message) {
		Msg.actionBar(player, message, ChatFormatting.AQUA);
		Sfx.success(player);
	}

	/** Same, but for a refusal: red text and a low tone. */
	public static void deny(ServerPlayer player, String message) {
		Msg.actionBar(player, message, ChatFormatting.RED);
		Sfx.error(player);
	}

	/** Confirmation for a state flip, pitched up or down to match the direction. */
	public static void notifyToggle(ServerPlayer player, String message, boolean enabled) {
		Msg.actionBar(player, message, enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
		Sfx.toggle(player, enabled);
	}
}
