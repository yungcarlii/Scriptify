package scriptify.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * One voice for everything Scriptify says.
 *
 * <p>Every chat line carries the same brand prefix and the same colour language — purple for the mod,
 * green for something that worked, red for something that did not, grey for detail. Transient
 * confirmations go to the action bar instead, so clicking around a menu never floods chat.
 */
public final class Msg {
	public static final ChatFormatting BRAND = ChatFormatting.LIGHT_PURPLE;

	private Msg() {
	}

	/** {@code Scriptify › } in brand colours, prepended to every chat line. */
	private static MutableComponent prefix() {
		return Component.literal("Scriptify").withStyle(BRAND, ChatFormatting.BOLD)
			.append(Component.literal(" › ").withStyle(ChatFormatting.DARK_GRAY));
	}

	/** A prefixed line for command feedback. */
	public static MutableComponent line(String text) {
		return prefix().append(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	public static MutableComponent line(String text, ChatFormatting colour) {
		return prefix().append(Component.literal(text).withStyle(colour));
	}

	public static MutableComponent success(String text) {
		return line(text, ChatFormatting.GREEN);
	}

	public static MutableComponent failure(String text) {
		return line(text, ChatFormatting.RED);
	}

	public static void chat(ServerPlayer player, MutableComponent message) {
		player.sendSystemMessage(message);
	}

	/** Action-bar confirmation — acknowledges a click without adding a chat line. */
	public static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
		player.sendSystemMessage(Component.literal(text).withStyle(colour), true);
	}

	/** A dimmed hint line, used for the "click to…" text under button titles. */
	public static MutableComponent hint(String text) {
		return Component.literal(text)
			.withStyle(ChatFormatting.GRAY)
			.withStyle(style -> style.withItalic(false));
	}

	/** A button title: no italics, so it does not inherit the item-name slant. */
	public static MutableComponent title(String text, ChatFormatting colour) {
		return Component.literal(text)
			.withStyle(colour)
			// Bold on the name only. Every button title goes through here, so the weight is what
			// separates the label from its description without needing a blank line between them.
			.withStyle(style -> style.withItalic(false).withBold(true));
	}

	/** A menu title: bold, so the bar above the grid reads as a heading rather than a caption. */
	public static MutableComponent header(String text, ChatFormatting colour) {
		return Component.literal(text)
			.withStyle(colour)
			.withStyle(style -> style.withItalic(false).withBold(true));
	}
}
