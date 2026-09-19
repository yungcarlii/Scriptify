package scriptify.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * The chat panel: Scriptify's layout for anything longer than a sentence.
 *
 * <p>This started life inside the help screen and stayed there, which meant one command looked like a
 * designed product and everything else looked like log output. Having it in one place is what makes
 * the mod feel like one mod - and it is now the only place the wordmark, the indent, the arrow and
 * the colour roles are defined, so changing the look is a single edit rather than a hunt.</p>
 *
 * <p>Colour carries meaning rather than decoration: purple and aqua are the brand, white is a label,
 * aqua is something you can act on, grey is detail, dark grey is punctuation. A panel that colours
 * things for variety teaches the reader nothing.</p>
 */
public final class Panel {
	/** The wordmark is two-tone, split across the two brand colours. */
	public static final ChatFormatting MARK_A = ChatFormatting.LIGHT_PURPLE;
	public static final ChatFormatting MARK_B = ChatFormatting.AQUA;
	/** Punctuation: arrows, separators, the version line. Never carries information on its own. */
	public static final ChatFormatting PUNCT = ChatFormatting.DARK_GRAY;
	public static final ChatFormatting LABEL = ChatFormatting.WHITE;
	/** Anything actionable - a command, a selector, a value you can change. */
	public static final ChatFormatting ACTION = ChatFormatting.AQUA;
	public static final ChatFormatting DETAIL = ChatFormatting.GRAY;

	private Panel() {
	}

	/**
	 * Opens a panel: a blank line, the wordmark, and an optional subtitle.
	 *
	 * <p>The leading blank matters more than it looks. Chat is a stream, and without it a panel runs
	 * straight on from whatever was said before and reads as part of it.</p>
	 */
	public static void open(CommandSourceStack source, String subtitle) {
		blank(source);
		source.sendSuccess(Panel::wordmark, false);
		if (subtitle != null && !subtitle.isBlank()) {
			source.sendSuccess(() -> indent()
				.append(Component.literal(subtitle).withStyle(PUNCT, ChatFormatting.ITALIC)), false);
		}
		blank(source);
	}

	/** Closes a panel with a trailing blank, so the next thing said is not glued to it. */
	public static void close(CommandSourceStack source) {
		blank(source);
	}

	public static MutableComponent wordmark() {
		return indent()
			.append(Component.literal("Script").withStyle(MARK_A, ChatFormatting.BOLD))
			.append(Component.literal("ify").withStyle(MARK_B, ChatFormatting.BOLD));
	}

	/** A section heading. Gold, because it is the one thing on the page that is not a row. */
	public static void heading(CommandSourceStack source, String name) {
		source.sendSuccess(() -> indent()
			.append(Component.literal(name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), false);
	}

	/** A plain heading in white, for grouping rows that are all the same kind. */
	public static void section(CommandSourceStack source, String name) {
		blank(source);
		source.sendSuccess(() -> indent()
			.append(Component.literal(name).withStyle(LABEL, ChatFormatting.BOLD)), false);
	}

	/**
	 * A row: {@code ★ Label  →  value}.
	 *
	 * <p>The arrow is what makes a list of pairs scannable - the eye follows the gutter down the
	 * middle rather than reading every line.</p>
	 */
	public static void row(CommandSourceStack source, String symbol, String label, String value) {
		row(source, symbol, label, value, ACTION, null, null);
	}

	public static void row(CommandSourceStack source, String symbol, String label, String value,
			ChatFormatting valueColour) {
		row(source, symbol, label, value, valueColour, null, null);
	}

	/**
	 * A row that can be clicked and hovered.
	 *
	 * @param command what to put in the chat box, or run, when clicked; null for an inert row
	 * @param run     true to execute the command outright rather than typing it. Only safe for a
	 *                command that needs no arguments - firing a half-written one is worse than
	 *                making the reader press enter.
	 */
	public static void row(CommandSourceStack source, String symbol, String label, String value,
			ChatFormatting valueColour, String command, String hint, boolean... run) {
		boolean execute = run.length > 0 && run[0];
		source.sendSuccess(() -> {
			MutableComponent line = indent();
			if (symbol != null && !symbol.isBlank()) {
				line.append(Component.literal(symbol + " ").withStyle(MARK_A));
			}
			line.append(Component.literal(label).withStyle(LABEL));
			if (value != null && !value.isBlank()) {
				line.append(Component.literal("  →  ").withStyle(PUNCT))
					.append(Component.literal(value).withStyle(valueColour));
			}
			if (command == null && hint == null) {
				return line;
			}
			// A hint with no command is a legitimate row: an explanation that would be a paragraph in
			// chat fits in a tooltip instead. This used to return early whenever there was no command,
			// which silently threw the hint away and made the long-explanation row impossible to write.
			return line.withStyle(style -> style
				.withClickEvent(command == null ? null : execute
					? new ClickEvent.RunCommand(command)
					: new ClickEvent.SuggestCommand(command + " "))
				.withHoverEvent(hint == null ? null : new HoverEvent.ShowText(
					Component.literal(hint).withStyle(DETAIL)
						.append(command == null
							? Component.empty()
							: Component.literal("\n" + (execute ? "Click to run." : "Click to type it."))
								.withStyle(PUNCT)))));
		}, false);
	}

	/** An indented {@code label: value} pair, for the body of a report. */
	public static void detail(CommandSourceStack source, String label, String value) {
		source.sendSuccess(() -> indent()
			.append(Component.literal(" " + label + ": ").withStyle(PUNCT))
			.append(Component.literal(value).withStyle(DETAIL)), false);
	}

	/** A dim closing note - what to do next, or a caveat. */
	public static void note(CommandSourceStack source, String text) {
		source.sendSuccess(() -> indent()
			.append(Component.literal(text).withStyle(PUNCT, ChatFormatting.ITALIC)), false);
	}

	public static void blank(CommandSourceStack source) {
		source.sendSuccess(Component::empty, false);
	}

	/** A left margin, so a panel reads as a block rather than as chat. */
	public static MutableComponent indent() {
		return Component.literal(" ");
	}
}
