package scriptify.access;

import net.minecraft.server.level.ServerPlayer;

/**
 * Tells a bot apart from somebody actually holding a mouse.
 *
 * <p>Every fake-player implementation — Carpet, PvP Bot, HeroBot and the rest — subclasses
 * {@code ServerPlayer} to override its ticking, so an exact class check separates them from real
 * connections without needing to know which mod created them. SkinRestorer identifies fake players
 * the same way.
 *
 * <p>Lives outside the mixin package so the selector mixin can call it: a plain class inside a
 * registered mixin package cannot be loaded normally.
 */
public final class FakePlayers {
	private FakePlayers() {
	}

	public static boolean isFake(ServerPlayer player) {
		return player.getClass() != ServerPlayer.class;
	}
}
