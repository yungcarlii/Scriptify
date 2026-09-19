package scriptify.compat;

import java.util.Locale;

/**
 * One surface two mods can end up fighting over.
 *
 * <p>Conflicts between server-side mods are not really "mod A versus mod B" — two mods can share a
 * server happily and collide on exactly one thing. Styled Nicknames and Scriptify both want to decide
 * what name hangs over a player's head, and disagree about nothing else. Splitting the problem by
 * surface is what lets an admin keep both mods and simply say who wins the name.
 *
 * <p>The id is what appears in the config file, so it is written out deliberately rather than derived
 * from the constant name: renaming a constant should not silently invalidate everybody's config.
 */
public enum CompatDomain {
	NICKNAMES("nicknames",
		"the name shown above a player's head, in chat, in the tab list and in death messages"),
	SKINS("skins",
		"the skin texture a player appears to be wearing"),
	TAB_LIST("tab_list",
		"the tab list itself, including fake players and hidden entries"),
	PERMISSIONS("permissions",
		"which commands a player is allowed to run"),
	DEATH("death",
		"what happens at the moment a player dies"),
	COMMANDS("commands",
		"command names, when another mod registers one Scriptify also registers");

	private final String id;
	private final String description;

	CompatDomain(String id, String description) {
		this.id = id;
		this.description = description;
	}

	public String id() {
		return this.id;
	}

	/** A sentence an admin can read in a log line or a config comment. */
	public String description() {
		return this.description;
	}

	/** A short label for menus and reports. */
	public String label() {
		String lower = this.id.replace('_', ' ');
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	/** Matches on the config id, tolerating the constant name and stray case. */
	public static CompatDomain parse(String name) {
		if (name == null) {
			return null;
		}
		String cleaned = name.trim().toLowerCase(Locale.ROOT);
		for (CompatDomain domain : values()) {
			if (domain.id.equals(cleaned) || domain.name().toLowerCase(Locale.ROOT).equals(cleaned)) {
				return domain;
			}
		}
		return null;
	}
}
