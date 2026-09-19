package scriptify.ui;

import java.util.Locale;
import java.util.Set;
import net.minecraft.ChatFormatting;

/**
 * The colour helpers {@link ChatFormatting} used to provide itself.
 *
 * <p>In 26.2 {@code ChatFormatting} was reduced to a bare enum carrying a formatting code and
 * nothing else - {@code getName()}, {@code isColor()} and {@code getByName()} were all removed, and
 * team colours moved out to their own {@code TeamColor} type. Scriptify needs all three: ranks store
 * a colour by name in JSON, the item editor lists the colours a name can be, and both have to reject
 * {@code BOLD} and {@code RESET} when a colour was asked for.
 *
 * <p>Kept in one place rather than inlined at each call site, because "which of these enum constants
 * are colours" is a fact about Minecraft rather than about any one screen, and having it written down
 * four times is how three of them end up wrong.
 */
public final class Colours {
	/**
	 * The sixteen that are colours. The rest of the enum - bold, italic, underline, strikethrough,
	 * obfuscated and reset - are styles, and a style where a colour is expected is a bug rather than a
	 * different kind of colour.
	 */
	private static final Set<ChatFormatting> COLOURS = Set.of(
		ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN,
		ChatFormatting.DARK_AQUA, ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE,
		ChatFormatting.GOLD, ChatFormatting.GRAY, ChatFormatting.DARK_GRAY, ChatFormatting.BLUE,
		ChatFormatting.GREEN, ChatFormatting.AQUA, ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE,
		ChatFormatting.YELLOW, ChatFormatting.WHITE);

	private Colours() {
	}

	/** Whether this is a colour rather than a style. */
	public static boolean isColour(ChatFormatting formatting) {
		return formatting != null && COLOURS.contains(formatting);
	}

	/**
	 * The lowercase name, as {@code getName()} used to return it.
	 *
	 * <p>Lowercase deliberately: it is what already sits in everybody's saved ranks file, and changing
	 * the spelling would silently reset every rank's colour on upgrade.
	 */
	public static String name(ChatFormatting formatting) {
		return formatting == null ? "" : formatting.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Looks a formatting up by the name {@link #name} produces.
	 *
	 * @return null when nothing matches, which is what the old {@code getByName} did
	 */
	public static ChatFormatting byName(String name) {
		if (name == null) {
			return null;
		}
		String cleaned = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
		for (ChatFormatting formatting : ChatFormatting.values()) {
			if (formatting.name().equals(cleaned)) {
				return formatting;
			}
		}
		return null;
	}
}
