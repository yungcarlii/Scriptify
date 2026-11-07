package scriptify.feature;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * A boss-bar countdown everyone online sees: the event starts in, the border closes in, the hunt begins.
 *
 * <p>One at a time server-wide, so the state is static the way the weather is a single thing. The bar
 * drains smoothly and turns from green to yellow to red, the last three seconds land as big titles with
 * a rising tick, and whoever joins mid-count gets the bar too.
 */
public final class Countdown {
	/** Progress updates per second are every this many ticks; the client smooths between them. */
	private static final int UPDATE_EVERY = 4;

	private static ServerBossEvent bar;
	private static String title;
	private static int totalTicks;
	private static int elapsed;

	private Countdown() {
	}

	public static void start(MinecraftServer server, int seconds, String label) {
		stop();
		title = label;
		totalTicks = seconds * 20;
		elapsed = 0;
		bar = new ServerBossEvent(name(seconds), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
		server.getPlayerList().getPlayers().forEach(bar::addPlayer);
	}

	/** @return false when nothing was counting down */
	public static boolean stop() {
		if (bar == null) {
			return false;
		}
		bar.removeAllPlayers();
		bar = null;
		return true;
	}

	public static boolean running() {
		return bar != null;
	}

	public static void tick(MinecraftServer server) {
		if (bar == null) {
			return;
		}
		elapsed++;
		int left = totalTicks - elapsed;
		if (left <= 0) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Sfx.countdownEnd(player);
			}
			stop();
			return;
		}
		if (elapsed % UPDATE_EVERY == 0) {
			float fraction = left / (float) totalTicks;
			bar.setProgress(fraction);
			bar.setColor(fraction > 0.5F ? BossEvent.BossBarColor.GREEN
				: fraction > 0.2F ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.RED);
		}
		if (left % 20 != 0) {
			return;
		}
		int seconds = left / 20;
		bar.setName(name(seconds));
		// Late joiners. Adding somebody already watching is a no-op.
		server.getPlayerList().getPlayers().forEach(bar::addPlayer);
		if (seconds <= 3) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 20, 5));
				player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(String.valueOf(seconds))));
				Sfx.countdownTick(player, seconds);
			}
		}
	}

	private static Component name(int seconds) {
		return Component.literal(title + "  " + clock(seconds));
	}

	/** {@code 0:05}, {@code 1:30}, {@code 1:00:00} - how a countdown reads on screen. */
	static String clock(int seconds) {
		int hours = seconds / 3600;
		int minutes = seconds / 60 % 60;
		int rest = seconds % 60;
		return hours > 0
			? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
			: String.format(Locale.ROOT, "%d:%02d", minutes, rest);
	}
}
