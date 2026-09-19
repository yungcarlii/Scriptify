package scriptify.feature;

import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import scriptify.runtime.ScriptifyRuntime;

/**
 * Nickname and skin as a single act.
 *
 * <p>Changing one without the other is the tell that gives a scene away — a player wearing someone
 * else's skin under their own name reads as a bug, not a character. An identity moves both at once, so
 * "become this person" is one command instead of two that can drift apart.
 */
public final class IdentityService {
	private IdentityService() {
	}

	/**
	 * Takes on another account's name and skin.
	 *
	 * <p>The nickname is applied immediately and the skin follows when Mojang answers, so the change is
	 * never left half-done if the lookup is slow.
	 *
	 * @param onResult {@code true} when the skin arrived as well as the name.
	 */
	public static void assume(ServerPlayer target, String sourceName, Consumer<Boolean> onResult) {
		ScriptifyRuntime.nick().set(target, sourceName);
		ScriptifyRuntime.skin().copySkinAsync(sourceName, target, applied -> {
			if (applied) {
				Sfx.vanishIn(target);
			}
			onResult.accept(applied);
		});
	}

	/** Gives the player their own name and skin back. */
	public static void clear(ServerPlayer target, Consumer<Boolean> onResult) {
		ScriptifyRuntime.nick().clear(target);
		ScriptifyRuntime.skin().clearSkinAsync(target, onResult);
	}

	/**
	 * Becomes a random real player: their username and their skin.
	 *
	 * <p>Both halves come from the same verified account, which is the whole point — a random name paired
	 * with an unrelated random skin would look like two separate glitches.
	 *
	 * @param onResult receives the name taken on, or empty when no account could be found.
	 */
	public static void randomise(ServerPlayer target, Consumer<java.util.Optional<String>> onResult) {
		ScriptifyRuntime.skin().randomIdentityAsync(target, name -> {
			if (name.isPresent()) {
				Sfx.vanishIn(target);
			}
			onResult.accept(name);
		});
	}
}
