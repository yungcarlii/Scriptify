package scriptify.permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scriptify.storage.DataStore;

/**
 * Named bundles of permissions, assigned per player.
 *
 * <p>Granting nodes one at a time is fine for one person and miserable for a cast. A rank is the
 * answer to "what is this person allowed to do here", set once and reused, and changing what a rank
 * can do changes it for everyone holding it rather than for whoever you remember to go back to.
 *
 * <p>Everyone has a rank. A player who has never been assigned one counts as holding the default
 * rank, so there is no third state where somebody is neither ranked nor unranked and the answer
 * depends on which code path asked.
 *
 * <p>Ranks stack with direct grants rather than replacing them: the rank is the baseline, and a
 * one-off node granted to a person is an exception on top of it.
 */
public final class RankService {
	private static final String MODULE = "permissions";
	private static final String FILE = "ranks.json";
	/** The rank every unassigned player counts as holding. Always exists. */
	public static final String DEFAULT_RANK = "default";
	/**
	 * The command roots a rank is able to allow or block, worked out from the live command tree.
	 *
	 * <p>This was a hand-written list of twenty-five names, and that was the bug. A rank set to "only
	 * these commands" still allowed every command that had been left off it - and the list omitted
	 * {@code /execute}, {@code /data}, {@code /scoreboard} and {@code /say} among others, so "only
	 * /gamemode" was not a restriction at all. {@code /execute} alone runs anything.
	 *
	 * <p>Asking the dispatcher instead means the set is complete by construction, and picks up
	 * commands from other mods without anybody maintaining a list. Two things are deliberately left
	 * out of it:</p>
	 * <ul>
	 *   <li><b>Scriptify's own commands</b>, which answer to feature nodes. Filtering them here made
	 *       an "only /gamemode" rank lose access to /scriptify as a side effect.</li>
	 *   <li><b>Commands everybody can already run</b> - {@code /help}, {@code /msg}, {@code /list} and
	 *       the rest that need no permission at all. A rank grants access; it is not a way to take
	 *       away what a player had before they were given one, and a filter that silently removed
	 *       {@code /help} would read as the server being broken.</li>
	 * </ul>
	 */
	/**
	 * The permission level each gateable command needs, worked out by asking the command itself.
	 *
	 * <p>Levels are the only lever Minecraft gives a server-side mod over vanilla commands, and every
	 * command carries its own requirement. Rather than keep a table of which command sits at which
	 * level - which would be another list to maintain and another thing to get wrong - each node is
	 * offered a source at level one, then two, and so on, and the first level it accepts is its price.
	 */
	public static Map<String, Integer> commandLevels(MinecraftServer server) {
		if (server == null) {
			return Map.of();
		}
		Map<String, Integer> levels = new LinkedHashMap<>();
		CommandSourceStack base = server.createCommandSourceStack();
		for (String root : gateableCommands(server)) {
			var node = server.getCommands().getDispatcher().getRoot().getChild(root);
			if (node == null) {
				continue;
			}
			for (int level = 1; level <= 4; level++) {
				CommandSourceStack at = base.withPermission(
					net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(
						net.minecraft.server.permissions.PermissionLevel.byId(level)));
				if (node.canUse(at)) {
					levels.put(root, level);
					break;
				}
			}
		}
		return Map.copyOf(levels);
	}

	public static List<String> gateableCommands(MinecraftServer server) {
		if (server == null) {
			return List.of();
		}
		Set<String> ours = scriptify.compat.CommandConflicts.scriptifyRoots();
		// A source holding exactly what a player who is not an operator holds (level 0), to ask each
		// command whether it needs more than that. Not NO_PERMISSIONS: that refuses even a level-0
		// check, so commands meant for everyone (Hubify's /hub, any mod's level-0 command) were
		// counted as level 1 and the default rank was blocked from them.
		CommandSourceStack bare = server.createCommandSourceStack()
			.withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.ALL);
		List<String> roots = new java.util.ArrayList<>();
		for (var node : server.getCommands().getDispatcher().getRoot().getChildren()) {
			String name = node.getName();
			if (ours.contains(name) || node.canUse(bare)) {
				continue;
			}
			roots.add(name);
		}
		java.util.Collections.sort(roots);
		return List.copyOf(roots);
	}

	/** One rank: what it unlocks, and how it looks. */
	public static final class Rank {
		private final String name;
		private final Set<String> nodes = new LinkedHashSet<>();
		private String prefix = "";
		private ChatFormatting colour = ChatFormatting.GRAY;
		private int commandLevel = 0;
		private CommandMode commandMode = CommandMode.ALL;
		private final Set<String> commands = new LinkedHashSet<>();

		private Rank(String name) {
			this.name = name;
		}

		public String name() {
			return this.name;
		}

		public Set<String> nodes() {
			return Set.copyOf(this.nodes);
		}

		public boolean has(String node) {
			return this.nodes.contains(node) || this.nodes.contains(PermissionService.ADMIN);
		}

		public String prefix() {
			return this.prefix;
		}

		public ChatFormatting colour() {
			return this.colour;
		}

		/**
		 * The vanilla permission level this rank grants: 0 none, 1 moderator, 2 gamemaster, 3 admin,
		 * 4 owner.
		 *
		 * <p>Minecraft filters the command tree by these four buckets before a client ever sees it, so
		 * this is what decides which vanilla commands exist for the player at all.
		 */
		public int commandLevel() {
			return this.commandLevel;
		}

		public CommandMode commandMode() {
			return this.commandMode;
		}

		public Set<String> commands() {
			return Set.copyOf(this.commands);
		}

		/**
		 * Whether this rank may run a vanilla command, by its root word.
		 *
		 * <p>Checked at execution, which is the only place per-command control is possible: the level
		 * decides what the client is shown, and this decides what actually runs.
		 */
		public boolean mayRun(String command) {
			return switch (this.commandMode) {
				case ALL -> true;
				case ONLY -> this.commands.contains(command);
				case EXCEPT -> !this.commands.contains(command);
			};
		}

		/** Title case, for menus that should not shout an internal id at the reader. */
		public String label() {
			return this.name.isEmpty()
				? this.name
				: Character.toUpperCase(this.name.charAt(0)) + this.name.substring(1);
		}
	}

	/** How a rank list of commands is read. */
	public enum CommandMode {
		/** Everything the level allows. */
		ALL,
		/** Only the listed commands. */
		ONLY,
		/** Everything the level allows except the listed commands. */
		EXCEPT;

		public static CommandMode parse(String name) {
			for (CommandMode mode : values()) {
				if (mode.name().equalsIgnoreCase(name)) {
					return mode;
				}
			}
			return ALL;
		}
	}

	private final DataStore storage;
	/** Insertion-ordered so the menu lists ranks the way they were created. */
	private final Map<String, Rank> ranks = new LinkedHashMap<>();
	private final Map<UUID, String> assignments = new LinkedHashMap<>();
	/**
	 * Last name each player was seen under.
	 *
	 * <p>Kept here rather than looked up: 1.21.11 has no server-side profile cache to ask, and a member
	 * list showing raw uuids for everyone currently offline would be useless.
	 */
	private final Map<UUID, String> names = new LinkedHashMap<>();
	private boolean dirty;
	private MinecraftServer server;

	/**
	 * Callbacks fired after any rank edit, for consumers outside this mod.
	 *
	 * <p>Copy-on-write because a listener may be added while one is being fired - a consumer that
	 * reacts to a rank change by reading ranks back is entirely reasonable, and a consumer that
	 * registers a second listener from inside the first is unusual but not illegal.
	 */
	private final java.util.List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Shares a name with {@link JsonPermissionService}'s, so permission output reads as one stream. */
	private static final Logger LOGGER = LoggerFactory.getLogger("scriptify/permissions");

	public RankService(DataStore storage) {
		this.storage = storage;
	}

	/**
	 * Registers a callback fired after any rank edit or grant.
	 *
	 * <p>Public because {@link scriptify.api.RanksApi} delegates to it. Not intended to be called
	 * directly from inside this mod - internal code that needs to react to a rank change should read
	 * {@link RankRegistry} instead, which is already published on the same path.
	 */
	public void addChangeListener(Runnable listener) {
		if (listener != null) {
			this.changeListeners.add(listener);
		}
	}

	/**
	 * Whether this id belongs to a bot rather than somebody holding a mouse.
	 *
	 * <p>Ranks describe what a person is trusted with, and a bot is not a person: it is scenery. Left
	 * unchecked, every scene bot was handed the default rank as it joined and written into
	 * {@code ranks.json} by name - which meant a five hundred bot spawn wrote the rank file five
	 * hundred times, and the member list of the default rank filled up with extras nobody had cast.
	 *
	 * <p>Asked of the live player list rather than stored, so it cannot go stale, and only ever
	 * consulted on the rare paths that write - never on the hot lookup.
	 */
	private boolean isBot(UUID id) {
		if (this.server == null || id == null) {
			return false;
		}
		ServerPlayer player = this.server.getPlayerList().getPlayer(id);
		return player != null && scriptify.access.FakePlayers.isFake(player);
	}

	/** Given the server once it exists, so a rank change can reach the people holding it. */
	public void bind(MinecraftServer server) {
		this.server = server;
		// Consumers outside this mod register before the server exists, because their initialiser and
		// ours are not ordered relative to each other. This is where those listeners are collected.
		scriptify.api.RanksApi.drainPendingListeners(this);
		applied();
	}

	/**
	 * Publishes a change and makes the clients aware of it.
	 *
	 * <p>The second half is what was missing, and it is why editing a rank appeared to do nothing.
	 * Minecraft filters the command tree by permission level <em>once</em>, when it builds the tree to
	 * send, and it only rebuilds it at login or when somebody is opped. Raising a rank's level updated
	 * everything on the server correctly and the player carried on seeing the tree they were sent when
	 * they joined - so the new commands did not appear, and taking a level away left commands listed
	 * that would then refuse to run. Both looked like the rank had not saved.
	 *
	 * <p>Resending costs one packet per affected player and only happens when somebody edits a rank.
	 */
	private void applied() {
		RankRegistry.publish(this);
		if (this.server != null) {
			for (ServerPlayer player : this.server.getPlayerList().getPlayers()) {
				// Nothing on the other end of a bot's connection draws a command tree.
				if (scriptify.access.FakePlayers.isFake(player)) {
					continue;
				}
				this.server.getCommands().sendCommands(player);
			}
		}
		// Last, and outside the server null-check, so a consumer hears about a rank edit that happened
		// before the server bound - and so a listener reading command levels sees the state the clients
		// have already been sent rather than the one they are about to be.
		notifyChangeListeners();
	}

	private void notifyChangeListeners() {
		for (Runnable listener : this.changeListeners) {
			try {
				listener.run();
			} catch (RuntimeException failure) {
				// A consumer's bug must not break the rank edit that triggered it. The edit has already
				// been applied and published by this point; only the notification is lost.
				LOGGER.warn("A rank change listener threw; ignoring it.", failure);
			}
		}
	}

	// ------------------------------------------------------------------ lifecycle

	public void load() {
		this.ranks.clear();
		this.assignments.clear();
		this.names.clear();

		JsonObject root = this.storage.loadObject(MODULE, FILE, RankService::defaults);
		int schemaVersion = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1;
		JsonObject stored = root.has("ranks") && root.get("ranks").isJsonObject()
			? root.getAsJsonObject("ranks") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : stored.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject object = entry.getValue().getAsJsonObject();
			Rank rank = new Rank(normalise(entry.getKey()));
			if (object.has("nodes") && object.get("nodes").isJsonArray()) {
				for (JsonElement node : object.getAsJsonArray("nodes")) {
					rank.nodes.add(node.getAsString());
				}
			}
			rank.prefix = object.has("prefix") ? object.get("prefix").getAsString() : "";
			rank.colour = parseColour(object.has("colour") ? object.get("colour").getAsString() : null);
			rank.commandLevel = object.has("commandLevel")
				? Math.max(0, Math.min(4, object.get("commandLevel").getAsInt())) : 0;
			rank.commandMode = CommandMode.parse(
				object.has("commandMode") ? object.get("commandMode").getAsString() : null);
			if (object.has("commands") && object.get("commands").isJsonArray()) {
				for (JsonElement command : object.getAsJsonArray("commands")) {
					String commandName = commandRoot(command.getAsString());
					if (!commandName.isEmpty()) {
						rank.commands.add(commandName);
					}
				}
			}
			this.ranks.put(rank.name(), rank);
		}

		JsonObject players = root.has("players") && root.get("players").isJsonObject()
			? root.getAsJsonObject("players") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
			try {
				this.assignments.put(UUID.fromString(entry.getKey()), normalise(entry.getValue().getAsString()));
			} catch (IllegalArgumentException ignored) {
				// A hand-edited file with a bad uuid should not stop every other rank loading.
			}
		}

		JsonObject seen = root.has("names") && root.get("names").isJsonObject()
			? root.getAsJsonObject("names") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : seen.entrySet()) {
			try {
				this.names.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
			} catch (IllegalArgumentException ignored) {
				// Same tolerance as above.
			}
		}

		// The default must exist even if the file was emptied, or unassigned players have no baseline.
		this.ranks.computeIfAbsent(DEFAULT_RANK, RankService::seedDefault);
		// Before schema 2, scriptify.kits covered both self-service and management. Keep every
		// existing setup behaving the same after splitting those responsibilities into two nodes;
		// owners can later remove Manage Kits from ranks that should only equip loadouts.
		if (schemaVersion < 2) {
			boolean migrated = false;
			for (Rank rank : this.ranks.values()) {
				if (rank.nodes.contains(PermissionService.KITS)
					&& rank.nodes.add(PermissionService.KITS_MANAGE)) {
					migrated = true;
				}
			}
			if (migrated) {
				this.dirty = true;
			}
		}
		if (schemaVersion < 3) {
			boolean migrated = false;
			for (Rank rank : this.ranks.values()) {
				if (rank.nodes.contains(PermissionService.PLAYER)
					&& rank.nodes.add(PermissionService.POTIONS_MANAGE)) {
					migrated = true;
				}
				if (rank.nodes.contains(PermissionService.INVENTORY)
					&& rank.nodes.add(PermissionService.FILL_MANAGE)) {
					migrated = true;
				}
			}
			if (migrated) {
				this.dirty = true;
			}
		}
		applied();
	}

	/**
	 * Marks the store dirty instead of writing immediately.
	 *
	 * <p>Every toggle in the rank menu used to rewrite the whole file, so clicking through a rank's
	 * permissions wrote it a dozen times in as many seconds. The flush happens on the next tick, which
	 * collapses a burst of edits into one write.
	 */
	public void save() {
		this.dirty = true;
	}

	/** Writes if anything changed. Called once per tick. */
	public void flush() {
		if (!this.dirty) {
			return;
		}
		this.dirty = false;
		writeNow();
	}

	private void writeNow() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 3);
		JsonObject stored = new JsonObject();
		this.ranks.forEach((name, rank) -> {
			JsonObject object = new JsonObject();
			JsonArray nodes = new JsonArray();
			rank.nodes.forEach(nodes::add);
			object.add("nodes", nodes);
			object.addProperty("prefix", rank.prefix);
			object.addProperty("colour", scriptify.ui.Colours.name(rank.colour));
			object.addProperty("commandLevel", rank.commandLevel);
			object.addProperty("commandMode", rank.commandMode.name());
			JsonArray commands = new JsonArray();
			rank.commands.forEach(commands::add);
			object.add("commands", commands);
			stored.add(name, object);
		});
		root.add("ranks", stored);

		JsonObject players = new JsonObject();
		this.assignments.forEach((uuid, rank) -> players.addProperty(uuid.toString(), rank));
		root.add("players", players);
		JsonObject seen = new JsonObject();
		this.names.forEach((uuid, name) -> seen.addProperty(uuid.toString(), name));
		root.add("names", seen);
		this.storage.saveObject(MODULE, FILE, root);
	}

	// ------------------------------------------------------------------ queries

	/** Read-only view of who holds what, for the static registry to mirror. */
	public Map<UUID, String> assignmentsView() {
		return Map.copyOf(this.assignments);
	}

	/** Everyone explicitly assigned to this rank. The default rank also covers everyone unlisted. */
	public List<UUID> membersOf(String name) {
		String key = normalise(name);
		List<UUID> members = new ArrayList<>();
		this.assignments.forEach((uuid, rank) -> {
			if (rank.equals(key)) {
				members.add(uuid);
			}
		});
		return members;
	}

	/**
	 * Writes the default rank onto a player who has never been given one.
	 *
	 * <p>Unassigned players already count as default everywhere it matters, but only an explicit
	 * assignment makes them show up in the rank's member list — which is the difference between a
	 * menu that lists your server and one that looks empty.
	 */
	/** The name this player was last seen under, or their id if we have never seen them. */
	public String nameOf(UUID player) {
		return this.names.getOrDefault(player, player.toString().substring(0, 8));
	}

	/**
	 * Records a player name so offline members can still be listed by name.
	 *
	 * <p>Takes the player rather than an id on purpose. Both of these run from the join event, and at
	 * that moment the player is not yet in the player list - so asking the list whether the id belongs
	 * to a bot answered "no" for every bot on the one path where it mattered, and the roster filled up
	 * exactly as before. The object in hand always knows what it is.
	 */
	public void rememberName(ServerPlayer player) {
		if (scriptify.access.FakePlayers.isFake(player)) {
			return;
		}
		String name = player.getGameProfile().name();
		if (!name.equals(this.names.put(player.getUUID(), name))) {
			save();
		}
	}

	/** @see #rememberName(ServerPlayer) for why this takes the player and not its id. */
	public void ensureAssigned(ServerPlayer joining) {
		UUID player = joining.getUUID();
		if (scriptify.access.FakePlayers.isFake(joining)) {
			// A bot that was given a rank by an older build is cleaned up as it reconnects, so the
			// file empties itself out rather than needing anybody to go and prune it.
			boolean hadRank = this.assignments.remove(player) != null;
			boolean hadName = this.names.remove(player) != null;
			if (hadRank || hadName) {
				save();
				applied();
			}
			return;
		}
		if (!isAssigned(player)) {
			this.assignments.put(player, DEFAULT_RANK);
			save();
			applied();
		}
	}

	/**
	 * Drops every stored row that says nothing.
	 *
	 * <p>For clearing out what earlier builds wrote: a bot was given the default rank as it joined and
	 * filed by name, so a world that has hosted a few mass spawns carries thousands of rows for extras
	 * that no longer exist. New ones are refused now and a bot cleans itself up when it reconnects,
	 * but a bot that never returns leaves its row behind.
	 *
	 * <p>Only rows carrying no information are removed - somebody on the default rank with no granted
	 * nodes of their own. That is the state an unassigned player is in anyway, so a real person caught
	 * by this loses nothing and is written back the next time they join. Anyone on a real rank, or
	 * holding an exception, is left alone whether or not they are online. Identifying bots directly
	 * would be the obvious approach and is not safe: on an offline-mode server a real account's id is
	 * built the same way a bot's is, so there is no way to tell them apart once they are offline.
	 *
	 * @return how many rows were dropped
	 */
	public int prune(java.util.function.Predicate<UUID> hasOwnNodes) {
		int before = this.assignments.size() + this.names.size();
		java.util.Set<UUID> keep = new java.util.HashSet<>();
		this.assignments.forEach((id, rank) -> {
			if (!DEFAULT_RANK.equals(rank) || hasOwnNodes.test(id)) {
				keep.add(id);
			}
		});
		this.assignments.keySet().retainAll(keep);
		this.names.keySet().retainAll(keep);
		int removed = before - this.assignments.size() - this.names.size();
		if (removed > 0) {
			save();
			applied();
		}
		return removed;
	}

	public List<Rank> all() {
		return List.copyOf(this.ranks.values());
	}

	public Optional<Rank> byName(String name) {
		return Optional.ofNullable(this.ranks.get(normalise(name)));
	}

	/** The rank a player holds, falling back to the default. Never empty. */
	public Rank of(UUID player) {
		Rank assigned = this.ranks.get(this.assignments.get(player));
		return assigned != null ? assigned : this.ranks.computeIfAbsent(DEFAULT_RANK, RankService::seedDefault);
	}

	/** Whether the player's rank unlocks this node. */
	public boolean grants(UUID player, String node) {
		return of(player).has(node);
	}

	/** Whether this player was assigned a rank explicitly, rather than falling through to the default. */
	public boolean isAssigned(UUID player) {
		return this.ranks.containsKey(this.assignments.get(player));
	}

	// ------------------------------------------------------------------ edits

	/**
	 * Creates an empty rank.
	 *
	 * @return {@code false} when one by that name already exists.
	 */
	public boolean create(String name) {
		String key = normalise(name);
		if (key.isEmpty() || this.ranks.containsKey(key)) {
			return false;
		}
		this.ranks.put(key, new Rank(key));
		save();
		return true;
	}

	/**
	 * Removes a rank. Anyone holding it falls back to the default.
	 *
	 * @return {@code false} for an unknown rank, or an attempt to delete the default.
	 */
	public boolean delete(String name) {
		String key = normalise(name);
		if (DEFAULT_RANK.equals(key) || !this.ranks.containsKey(key)) {
			return false;
		}
		this.ranks.remove(key);
		this.assignments.values().removeIf(key::equals);
		save();
		applied();
		return true;
	}

	public boolean assign(UUID player, String name) {
		String key = normalise(name);
		if (!this.ranks.containsKey(key) || isBot(player)) {
			return false;
		}
		this.assignments.put(player, key);
		save();
		applied();
		return true;
	}

	public void setNode(String name, String node, boolean granted) {
		byName(name).ifPresent(rank -> {
			if (granted) {
				rank.nodes.add(node);
			} else {
				rank.nodes.remove(node);
			}
			save();
			applied();
		});
	}

	public void setPrefix(String name, String prefix) {
		byName(name).ifPresent(rank -> {
			rank.prefix = prefix == null ? "" : prefix;
			save();
		});
	}

	public void setCommandLevel(String name, int level) {
		byName(name).ifPresent(rank -> {
			rank.commandLevel = Math.max(0, Math.min(4, level));
			save();
			applied();
		});
	}

	public void setCommandMode(String name, CommandMode mode) {
		byName(name).ifPresent(rank -> {
			rank.commandMode = mode;
			save();
			applied();
		});
	}

	public void setCommandListed(String name, String command, boolean listed) {
		byName(name).ifPresent(rank -> {
			String root = commandRoot(command);
			if (root.isEmpty()) {
				return;
			}
			if (listed) {
				rank.commands.add(root);
			} else {
				rank.commands.remove(root);
			}
			save();
			applied();
		});
	}

	public void setColour(String name, ChatFormatting colour) {
		byName(name).ifPresent(rank -> {
			rank.colour = colour;
			save();
		});
	}

	// ------------------------------------------------------------------ helpers

	private static String normalise(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
	}

	/** Normalises a root command name so hand-edited rank files behave exactly like the GUI. */
	private static String commandRoot(String command) {
		if (command == null) {
			return "";
		}
		String root = command.trim().toLowerCase(Locale.ROOT);
		if (root.startsWith("/")) {
			root = root.substring(1);
		}
		int space = root.indexOf(' ');
		return space < 0 ? root : root.substring(0, space);
	}

	private static ChatFormatting parseColour(String name) {
		if (name != null) {
			ChatFormatting found = scriptify.ui.Colours.byName(name);
			if (found != null && scriptify.ui.Colours.isColour(found)) {
				return found;
			}
		}
		return ChatFormatting.GRAY;
	}

	private static Rank seedDefault(String name) {
		Rank rank = new Rank(name);
		// Opening the menu and nothing else: a new arrival can look without being able to change
		// anything, which is the only sane starting point on a server that is mid-shoot.
		rank.nodes.add(PermissionService.GUI_OPEN);
		return rank;
	}

	/** A first-run file with the three ranks most crews end up making anyway. */
	private static JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 3);
		JsonObject ranks = new JsonObject();

		ranks.add(DEFAULT_RANK, rankJson(ChatFormatting.GRAY, "", List.of(PermissionService.GUI_OPEN)));
		ranks.add("crew", rankJson(ChatFormatting.AQUA, "Crew", List.of(
			PermissionService.GUI_OPEN, PermissionService.DEATH, PermissionService.PLAYER,
			PermissionService.INVENTORY, PermissionService.KITS, PermissionService.KITS_MANAGE,
			PermissionService.POTIONS_MANAGE, PermissionService.FILL_MANAGE, PermissionService.ITEMS)));
		// Director gets gamemaster: /gamemode, /tp, /give and the rest of the level-2 set, without the
		// server-management commands that live above it.
		ranks.add("director", rankJson(ChatFormatting.GOLD, "Director", PermissionService.ALL_NODES, 2));

		root.add("ranks", ranks);
		root.add("players", new JsonObject());
		return root;
	}

	private static JsonObject rankJson(ChatFormatting colour, String prefix, List<String> nodes) {
		return rankJson(colour, prefix, nodes, 0);
	}

	private static JsonObject rankJson(ChatFormatting colour, String prefix, List<String> nodes, int level) {
		JsonObject object = new JsonObject();
		JsonArray array = new JsonArray();
		new ArrayList<>(nodes).forEach(array::add);
		object.add("nodes", array);
		object.addProperty("prefix", prefix);
		object.addProperty("colour", scriptify.ui.Colours.name(colour));
		object.addProperty("commandLevel", level);
		object.addProperty("commandMode", CommandMode.ALL.name());
		object.add("commands", new JsonArray());
		return object;
	}
}
