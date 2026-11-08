package scriptify.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * A message on every screen as a title: "Day 7", "The border closes at midnight", a narrator's line.
 *
 * <p>A title is drawn big - about {@value #TITLE_WIDTH} characters fill the screen at the default GUI
 * scale - so a longer message carries on in the subtitle, which is half the size and fits twice as
 * much, and a longer one still becomes pages shown one after another, each held long enough to read.
 * Words are never cut, and colour codes carry over a wrap, so a red message stays red on the subtitle.
 *
 * <p>One at a time server-wide like {@link Countdown}: a new announcement replaces the pages still
 * waiting to be shown.
 */
public final class Announcement {
	static final int TITLE_WIDTH = 22;
	static final int SUBTITLE_WIDTH = 44;
	private static final int FADE_IN = 10;
	private static final int FADE_OUT = 10;
	/** The {@code &} codes the client renders: colours, formatting and reset. */
	private static final Pattern CODE = Pattern.compile("(?i)&[0-9a-fk-or]");

	private static final ArrayDeque<Page> queue = new ArrayDeque<>();
	private static int wait;

	private Announcement() {
	}

	/** One screenful: the title and the smaller line under it, which may be empty. */
	record Page(String title, String subtitle) {
		/** Ticks on screen: long enough for a short title to land, and a tick more per character to read. */
		int stay() {
			return 50 + visible(title) + visible(subtitle);
		}
	}

	/** @return how many pages the message takes */
	public static int show(MinecraftServer server, String message) {
		queue.clear();
		queue.addAll(pages(message));
		wait = 0;
		int count = queue.size();
		tick(server);
		return count;
	}

	public static void stop() {
		queue.clear();
	}

	public static void tick(MinecraftServer server) {
		if (queue.isEmpty() || --wait > 0) {
			return;
		}
		Page page = queue.poll();
		int stay = page.stay();
		wait = FADE_IN + stay + FADE_OUT;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(FADE_IN, stay, FADE_OUT));
			// The subtitle goes first: it only shows with the next title, and an empty one clears the last page's.
			player.connection.send(new ClientboundSetSubtitleTextPacket(ItemMakerService.colour(page.subtitle())));
			player.connection.send(new ClientboundSetTitleTextPacket(ItemMakerService.colour(page.title())));
		}
	}

	/** The message word-wrapped into pages of a title and a subtitle, each line opening in the colour the last one ended in. */
	static List<Page> pages(String message) {
		String[] words = message.trim().split("\\s+");
		List<Page> pages = new ArrayList<>();
		String style = "";
		int at = 0;
		while (at < words.length) {
			int end = fill(words, at, TITLE_WIDTH);
			String title = style + String.join(" ", Arrays.copyOfRange(words, at, end));
			style = style(title);
			at = end;
			end = fill(words, at, SUBTITLE_WIDTH);
			String subtitle = at == end ? "" : style + String.join(" ", Arrays.copyOfRange(words, at, end));
			style = style(style + subtitle);
			at = end;
			pages.add(new Page(title, subtitle));
		}
		return pages;
	}

	/**
	 * Where the words starting at {@code from} stop fitting in {@code width} visible characters. Always at
	 * least one word, so a word longer than the line gets the line to itself instead of being cut.
	 */
	private static int fill(String[] words, int from, int width) {
		int end = from;
		int length = -1;
		while (end < words.length) {
			length += 1 + visible(words[end]);
			if (length > width && end > from) {
				break;
			}
			end++;
		}
		return end;
	}

	/** The codes still in effect at the end of {@code line}; a colour or {@code &r} drops the formatting before it, as on the client. */
	private static String style(String line) {
		String style = "";
		Matcher codes = CODE.matcher(line);
		while (codes.find()) {
			char code = Character.toLowerCase(codes.group().charAt(1));
			style = code == 'r' ? "" : code <= 'f' ? codes.group() : style + codes.group();
		}
		return style;
	}

	private static int visible(String text) {
		return CODE.matcher(text).replaceAll("").length();
	}
}
