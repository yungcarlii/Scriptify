package scriptify.compat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Mods known to want the same surfaces Scriptify does, and mods that change something it relies on.
 *
 * <p>Drawn from what is actually installed on Fabric servers — the nickname, skin, permission, tab
 * list and grave mods on Modrinth with the download counts to match. It is a convenience, not the
 * mechanism: it turns "your nicknames stopped working and you have no idea why" into a line in the log
 * at startup, before anybody loses an evening to it.
 *
 * <p>It is deliberately not the only detection. A table of mod ids can only ever know about mods that
 * existed when it was written, so command collisions are found by watching the command tree instead,
 * which works for a mod published tomorrow. See {@link CommandConflicts}.
 *
 * <p>Being listed here is not a criticism of the mod, and none of these are broken by Scriptify — they
 * overlap with it. Several are better at their one job than Scriptify is, which is exactly why
 * {@link ConflictPolicy#YIELD} exists.
 *
 * <p><strong>Only server-side mods can appear here.</strong> {@code FabricLoader.isModLoaded} on a
 * dedicated server sees the server's own mods and nothing else, so listing a client mod would produce
 * an entry that can never match. Client-side interactions worth knowing about — Chat Heads matching
 * heads to names, Flashback recording the packet stream — are documented rather than detected,
 * because pretending to detect them would be a lie told at startup every day.
 */
public final class KnownMods {
	/**
	 * How much the overlap actually matters.
	 *
	 * <p>The distinction exists because the first version of this table logged everything as a warning,
	 * which would have meant shipping a mod that shouts about Lithium on a server where Lithium is
	 * working perfectly. A warning that is usually wrong is a warning people learn to scroll past, and
	 * then the one that matters goes past with it.
	 */
	public enum Kind {
		/** Both mods want to decide the same thing. One of them is going to lose, silently. */
		CONTESTED,
		/** Nothing is fighting. It changes something Scriptify leans on, and that is worth a line. */
		NOTED
	}

	/**
	 * One overlapping mod.
	 *
	 * @param modIds  every id this mod has shipped under; matching any one counts. Ids get renamed
	 *                between versions and a stale id is a silent miss, so more than one is normal.
	 * @param name    what the mod calls itself, for log lines an admin has to act on
	 * @param kind    whether this is a fight or a note
	 * @param domains the surfaces it contests. Empty for a {@link Kind#NOTED} entry, which contests
	 *                nothing by definition.
	 * @param note    what the two mods will actually disagree about
	 */
	public record Known(List<String> modIds, String name, Kind kind, Set<CompatDomain> domains,
			String note) {
	}

	private static Known contested(List<String> ids, String name, Set<CompatDomain> domains,
			String note) {
		return new Known(ids, name, Kind.CONTESTED, domains, note);
	}

	private static Known noted(List<String> ids, String name, String note) {
		return new Known(ids, name, Kind.NOTED, EnumSet.noneOf(CompatDomain.class), note);
	}

	private static final List<Known> ALL = List.of(
		// --- Names ------------------------------------------------------------------------------
		contested(List.of("styled-nicknames"), "Styled Nicknames",
			EnumSet.of(CompatDomain.NICKNAMES),
			"Both rewrite display names and tab list names; the one that runs second wins."),
		contested(List.of("styledchat"), "Styled Chat",
			EnumSet.of(CompatDomain.NICKNAMES),
			"Styled Chat formats the name in chat messages, which can override a Scriptify nickname there "
				+ "while leaving the nametag alone."),
		contested(List.of("drogtor", "drogtor-the-nickinator"), "Drogtor the Nickinator",
			EnumSet.of(CompatDomain.NICKNAMES, CompatDomain.SKINS),
			"Sets both nicknames and skins, and registers /nick."),
		contested(List.of("fabric-custom-names", "customnames"), "Fabric Custom Names",
			EnumSet.of(CompatDomain.NICKNAMES),
			"Applies prefixes, suffixes and nicknames to the same display name."),
		contested(List.of("simple-nicknames", "simplenicknames"), "Simple Nicknames",
			EnumSet.of(CompatDomain.NICKNAMES),
			"Changes the name above a player's head through the same packet field."),
		contested(List.of("namer"), "Namer",
			EnumSet.of(CompatDomain.NICKNAMES),
			"Another nickname implementation on the same display name."),
		contested(List.of("player_roles", "player-roles"), "Player Roles",
			EnumSet.of(CompatDomain.NICKNAMES, CompatDomain.PERMISSIONS),
			"Adds name prefixes and its own permission levels, both of which Scriptify ranks also set."),

		// --- Skins ------------------------------------------------------------------------------
		contested(List.of("fabrictailor"), "FabricTailor",
			EnumSet.of(CompatDomain.SKINS),
			"Stores its own skin per player and reapplies it at login, undoing a Scriptify skin."),
		contested(List.of("skinrestorer", "skinsrestorer"), "SkinRestorer",
			EnumSet.of(CompatDomain.SKINS),
			"Hooks the same login method to write profile textures; whichever runs last decides the skin."),
		contested(List.of("skinshifter"), "SkinShifter",
			EnumSet.of(CompatDomain.SKINS),
			"Swaps skins at runtime through the same profile property."),

		// --- Tab list ---------------------------------------------------------------------------
		contested(List.of("styledplayerlist"), "Styled Player List",
			EnumSet.of(CompatDomain.TAB_LIST),
			"Rebuilds the tab list, which can drop the fake players Scriptify adds to it."),
		contested(List.of("vanish"), "Vanish",
			EnumSet.of(CompatDomain.TAB_LIST, CompatDomain.COMMANDS),
			"Removes players from the tab list and registers /vanish, which Scriptify also registers."),

		// --- Permissions ------------------------------------------------------------------------
		contested(List.of("luckperms"), "LuckPerms",
			EnumSet.of(CompatDomain.PERMISSIONS),
			"The usual reason to yield: LuckPerms is a real permission system and Scriptify ranks are a "
				+ "convenience. Running both means two sources deciding one answer."),
		contested(List.of("essential_commands", "essentialcommands"), "Essential Commands",
			EnumSet.of(CompatDomain.PERMISSIONS, CompatDomain.COMMANDS),
			"Ships its own permission layer and commands including /nick, /back and /home."),

		// --- Death ------------------------------------------------------------------------------
		contested(List.of("universal-graves", "universal_graves"), "Universal Graves",
			EnumSet.of(CompatDomain.DEATH),
			"Creates a grave from the death event Scriptify cancels for staged deaths, so a staged death "
				+ "can leave a real grave holding a copy of the inventory."),
		contested(List.of("yigd"), "You're in Grave Danger",
			EnumSet.of(CompatDomain.DEATH),
			"Same collision as any grave mod: it acts on a death Scriptify is faking."),

		// --- Fake players -------------------------------------------------------------------------
		contested(List.of("carpet"), "Carpet",
			EnumSet.of(CompatDomain.COMMANDS),
			"Carpet's /player spawns fake players of its own. They are recognised as bots and staged "
				+ "deaths apply to them, but /player and the bot commands are two ways to drive the same "
				+ "roster and neither knows what the other did."),

		// --- Recording ----------------------------------------------------------------------------
		noted(List.of("serverreplay"), "ServerReplay",
			"Records the packet stream, so everything Scriptify sends is in the replay - including the "
				+ "brief player-list removal a nickname or skin change needs. Start recording before "
				+ "changing an identity and the change is captured as it happened."),

		// --- Protocol -----------------------------------------------------------------------------
		noted(List.of("viaversion", "viafabric", "viabackwards"), "ViaVersion",
			"Rewrites player-list and entity packets for clients on older protocol versions. Scriptify "
				+ "sends only vanilla packets, so they translate, but an older client may show a "
				+ "nickname change one frame later than a current one."),
		noted(List.of("polymer-core", "polymer", "polymer-common"), "Polymer",
			"Shares the sgui library Scriptify builds its menus with. Both work; if menus stop opening "
				+ "after an update, mismatched sgui versions are the first thing to check."),

		// --- Chunk system -------------------------------------------------------------------------
		noted(List.of("c2me"), "C2ME",
			"Replaces the chunk system Scriptify drives to re-track a player after an identity change. "
				+ "Detected automatically: refreshes fall back to plain packets, which is slightly less "
				+ "precise and works."),
		noted(List.of("moonrise"), "Moonrise",
			"Rewrites chunk and entity ticking. Same fallback as C2ME if the entity tracker is not the "
				+ "vanilla one."),
		noted(List.of("lithium"), "Lithium",
			"Optimises entity and AI ticking. No known conflict - listed because if bots ever behave "
				+ "oddly on a heavily optimised server, this is the first thing worth toggling."),

		// --- Live maps ----------------------------------------------------------------------------
		noted(List.of("bluemap", "squaremap", "dynmap"), "Live map",
			"Live maps read the account name, not the Scriptify nickname, so a disguised player appears "
				+ "on the web map under their real name.")
	);

	private KnownMods() {
	}

	public static List<Known> all() {
		return ALL;
	}
}
