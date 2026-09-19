package scriptify.compat;

import java.util.Locale;

/** What Scriptify does about a surface another mod also wants. */
public enum ConflictPolicy {
	/**
	 * Scriptify wins. Its own behaviour stays on, and where the collision is one it can actively undo —
	 * a command another mod registered over the top of ours — it puts its own back.
	 */
	PRIORITY,
	/**
	 * Scriptify stands down and leaves the surface to the other mod.
	 *
	 * <p>Only the colliding surface: yielding nicknames does not turn off kits, skins or anything else.
	 * This is the setting for keeping a nickname mod you already had and using Scriptify for the rest.
	 */
	YIELD,
	/**
	 * Do nothing but say so in the log.
	 *
	 * <p>Both mods run untouched, which for commands means Brigadier merges the two trees and the
	 * result depends on mod load order. Occasionally that is genuinely what you want; usually it is
	 * the state you are trying to get out of.
	 */
	IGNORE;

	public static ConflictPolicy parse(String name, ConflictPolicy fallback) {
		if (name == null) {
			return fallback;
		}
		String cleaned = name.trim().toUpperCase(Locale.ROOT);
		for (ConflictPolicy policy : values()) {
			if (policy.name().equals(cleaned)) {
				return policy;
			}
		}
		return fallback;
	}
}
