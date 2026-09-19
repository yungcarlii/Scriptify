package scriptify.bots.tool;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The bot tools: commands you can point at something instead of typing.
 *
 * <p>Most of what you do to a bot needs its name, and its name is the one thing you do not have when
 * you are stood in front of two hundred of them. Every tool here exists to remove that step - the bot
 * you are looking at is the argument, the same idea edit mode already uses when you punch one.
 *
 * <p>There are two shapes, and the difference is not decoration.
 *
 * <ul>
 *   <li>A <b>stick</b> is aimed: right click a bot and the thing happens to that bot. One target, one
 *       click, no ambiguity about which one you meant.</li>
 *   <li>A <b>shield</b> is held: keep the button down and it applies to <em>everyone around you</em>
 *       once the timer fills. That is for the jobs that were never about one bot - kitting an arena,
 *       assigning a side - where naming them individually is the whole chore.</li>
 * </ul>
 *
 * <p><b>Why a shield, specifically.</b> A server cannot observe a held right click on an ordinary
 * item; it is told about one click and nothing after it. A shield is the one common item with a
 * continuous use action, so the server can read how long the button has been down, tick by tick, and
 * stop the moment it comes up. The raised arm is the progress bar. Nothing else in the game can
 * honestly offer "hold this" to an unmodified client, which is why every held tool is a shield and
 * they are told apart by name rather than by item.
 *
 * <p>The item choices avoid anything with its own right-click behaviour on an entity - no name tags,
 * no leads - so a tool never half-does something else on its way to doing its own job.
 */
public enum BotTool {

	// ---------------------------------------------------------------- aimed

	PATH("Path Stick", Items.STICK, Kind.POKE, Binding.PATH,
		"Right click a bot to start it",
		"walking the %s path."),

	ATTACK("Attack Stick", Items.BLAZE_ROD, Kind.POKE, Binding.NONE,
		"Right click a bot to pick it up,",
		"then right click who it should fight."),

	STOP("Stop Stick", Items.BREEZE_ROD, Kind.POKE, Binding.NONE,
		"Right click a bot to call it off -",
		"drops its target and its path."),

	DISMISS("Dismiss Stick", Items.BONE, Kind.POKE, Binding.NONE,
		"Right click a bot to send it away.",
		"Gone for good, not just stopped."),

	PANEL("Panel Stick", Items.AMETHYST_SHARD, Kind.POKE, Binding.NONE,
		"Right click a bot to open its panel -",
		"gear, kit, faction, everything."),

	// ---------------------------------------------------------------- held

	KIT("Kit Shield", Items.SHIELD, Kind.HOLD, Binding.KIT,
		"Hold right click for %d seconds to give",
		"the %s kit to every bot nearby."),

	FACTION("Faction Shield", Items.SHIELD, Kind.HOLD, Binding.FACTION,
		"Hold right click for %d seconds to put",
		"every bot nearby on the %s side."),

	SPAWN("Spawn Shield", Items.SHIELD, Kind.HOLD, Binding.COUNT,
		"Hold right click for %d seconds to spawn",
		"%s bots where you are standing.");

	/** How a tool is used. */
	public enum Kind { POKE, HOLD }

	/**
	 * What a tool needs remembering alongside it.
	 *
	 * <p>Held against the player rather than written into the item, which is deliberate and was
	 * decided the first time round: an item carrying its own binding survives a restart still pointing
	 * at a kit or a path that may since have been deleted, and then fails with nothing useful to say.
	 * Held on the server it either works or tells you the binding is gone.
	 */
	public enum Binding { NONE, KIT, PATH, FACTION, COUNT }

	/** How long a held tool takes to fill, in ticks. Long enough to be deliberate, short enough to use. */
	private static final int DEFAULT_HOLD_TICKS = 60;
	/** The kit shield keeps its original five seconds: it rewrites inventories, so it earns the pause. */
	private static final int KIT_HOLD_TICKS = 100;

	/** How far a held tool reaches, in blocks. One arena's worth. */
	public static final double RADIUS = 32.0;

	private final String displayName;
	private final Item icon;
	private final Kind kind;
	private final Binding binding;
	private final String[] lore;

	BotTool(String displayName, Item icon, Kind kind, Binding binding, String... lore) {
		this.displayName = displayName;
		this.icon = icon;
		this.kind = kind;
		this.binding = binding;
		this.lore = lore;
	}

	public String displayName() {
		return this.displayName;
	}

	public Item icon() {
		return this.icon;
	}

	public Kind kind() {
		return this.kind;
	}

	public Binding binding() {
		return this.binding;
	}

	public boolean needsBinding() {
		return this.binding != Binding.NONE;
	}

	public int holdTicks() {
		return this == KIT ? KIT_HOLD_TICKS : DEFAULT_HOLD_TICKS;
	}

	/** The lowercase word used on the command line: {@code /bots tool path}. */
	public String id() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * The description shown on the item, with the binding filled in.
	 *
	 * <p>Written out per tool rather than assembled from fragments, because these lines are the only
	 * instructions anybody gets: the tool has to explain itself in the hand.
	 */
	public String[] lore(String bound) {
		String[] filled = new String[this.lore.length];
		for (int index = 0; index < this.lore.length; index++) {
			String line = this.lore[index];
			if (line.contains("%d")) {
				line = line.replace("%d", String.valueOf(holdTicks() / 20));
			}
			if (line.contains("%s")) {
				line = line.replace("%s", bound == null ? "chosen" : bound);
			}
			filled[index] = line;
		}
		return filled;
	}

	public static BotTool byId(String id) {
		for (BotTool tool : values()) {
			if (tool.id().equalsIgnoreCase(id)) {
				return tool;
			}
		}
		return null;
	}

	/** Matches the custom name an issued tool carries, which is how one is recognised in the hand. */
	public static BotTool byDisplayName(String name) {
		for (BotTool tool : values()) {
			if (tool.displayName.equals(name)) {
				return tool;
			}
		}
		return null;
	}
}
