package scriptify.nick;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static, lifecycle-independent view of the active nicknames.
 *
 * <p>Mixins run far earlier than {@link scriptify.runtime.ScriptifyRuntime} starts and must never
 * depend on it being initialised, so {@link NickManager} mirrors its state here and the mixins only
 * ever read from this class.
 */
public final class NickRegistry {
	private static final Map<UUID, String> NICKNAMES = new ConcurrentHashMap<>();

	private NickRegistry() {
	}

	public static void put(UUID uuid, String nickname) {
		if (uuid == null || nickname == null || nickname.isBlank()) {
			return;
		}
		NICKNAMES.put(uuid, nickname);
	}

	public static void remove(UUID uuid) {
		if (uuid != null) {
			NICKNAMES.remove(uuid);
		}
	}

	public static void clear() {
		NICKNAMES.clear();
	}

	/**
	 * @return the nickname for the player, or {@code null} when they are not nicked.
	 */
	public static String get(UUID uuid) {
		return uuid == null ? null : NICKNAMES.get(uuid);
	}

	public static boolean isNicked(UUID uuid) {
		return get(uuid) != null;
	}
}
