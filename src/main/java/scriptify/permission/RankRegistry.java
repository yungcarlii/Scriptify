package scriptify.permission;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static, lifecycle-independent snapshot of what each player's rank allows.
 *
 * <p>The mixins that enforce this run during command dispatch and during permission checks, which
 * happen long before {@link scriptify.runtime.ScriptifyRuntime} is safe to touch and far too often to
 * walk a service on each call. {@link RankService} mirrors its state here and the mixins only read.
 *
 * <p>Reads are on the hot path — {@code permissions()} is consulted for every command node when the
 * tree is built — so this is a plain map lookup and nothing more.
 */
public final class RankRegistry {
	/**
	 * What one rank grants, flattened for lookup.
	 *
	 * <p>The permission set is built once per rank and shared. {@code ServerPlayer.permissions()} is
	 * not cached by vanilla and {@code PermissionSet.union} allocates, so building a union on every
	 * call would allocate once per command node per player every time a command tree is sent.
	 */
	public record Grant(int commandLevel, RankService.CommandMode mode, Set<String> commands,
			net.minecraft.server.permissions.PermissionSet permissions) {
		private Grant(int commandLevel, RankService.CommandMode mode, Set<String> commands) {
			this(commandLevel, mode, commands,
				permissionsFor(effectiveLevel(commandLevel, mode, commands)));
		}

		/**
		 * The level this rank really needs, which is not always the one that was typed in.
		 *
		 * <p>Setting a rank to "only /tp" and finding that /tp still did not work was the most
		 * confusing thing about ranks, and the cause was that a rank had two separate controls doing
		 * two different jobs. The level decides which vanilla commands exist for a player at all; the
		 * list only narrows what is left. Listing /tp while leaving the level at zero therefore
		 * narrowed an empty set down to nothing, and the person was told they had /tp.
		 *
		 * <p>Nobody means that. Naming a command in an "only these" rank is how somebody says "this
		 * rank may use /tp", so the level it needs is granted with it - worked out from the command
		 * rather than assumed, so listing /gamemode grants exactly what /gamemode costs and nothing
		 * above it. The two controls are still there for anyone who wants them; they simply no longer
		 * have to be reasoned about together to get the obvious thing.
		 *
		 * <p>Only in "only these" mode. "All except" starts from everything the level allows and takes
		 * away, so a listed command there is one being blocked - granting it would be backwards.
		 */
		private static int effectiveLevel(int commandLevel, RankService.CommandMode mode,
				Set<String> commands) {
			if (mode != RankService.CommandMode.ONLY) {
				return commandLevel;
			}
			int needed = commandLevel;
			for (String command : commands) {
				Integer cost = LEVELS.get(command);
				if (cost != null) {
					needed = Math.max(needed, cost);
				}
			}
			return needed;
		}

		private static net.minecraft.server.permissions.PermissionSet permissionsFor(int level) {
			return switch (level) {
				case 1 -> net.minecraft.server.permissions.LevelBasedPermissionSet.MODERATOR;
				case 2 -> net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER;
				case 3 -> net.minecraft.server.permissions.LevelBasedPermissionSet.ADMIN;
				case 4 -> net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER;
				default -> null;
			};
		}

		public boolean mayRun(String command) {
			return switch (this.mode) {
				case ALL -> true;
				case ONLY -> this.commands.contains(command);
				case EXCEPT -> !this.commands.contains(command);
			};
		}
	}

	private static final Grant NOTHING =
		new Grant(0, RankService.CommandMode.ALL, Set.of());

	private static final Map<UUID, Grant> PLAYERS = new ConcurrentHashMap<>();
	private static volatile Grant fallback = NOTHING;

	/**
	 * The command roots a rank is allowed to filter.
	 *
	 * <p>Worked out once from the command tree rather than per execution - the gate runs on every
	 * command anybody types, and walking the dispatcher there would be absurd. Held here because the
	 * gate is a mixin and this is the class mixins are allowed to read.
	 */
	private static volatile Set<String> gateable = Set.of();

	/** What each of those costs, so a listed command can grant the level it needs. */
	private static volatile Map<String, Integer> LEVELS = Map.of();

	public static void setGateable(Map<String, Integer> commandLevels) {
		LEVELS = Map.copyOf(commandLevels);
		gateable = Set.copyOf(commandLevels.keySet());
	}

	/** The level a command needs, or null when a rank has no say over it. */
	public static Integer levelOf(String root) {
		return LEVELS.get(root);
	}

	/** The level this grant actually confers, listed commands included. */
	public static int effectiveLevelOf(Grant grant) {
		return Grant.effectiveLevel(grant.commandLevel(), grant.mode(), grant.commands());
	}

	/** Whether a rank's command filter has any say over this command. */
	public static boolean isGateable(String root) {
		return gateable.contains(root);
	}

	private RankRegistry() {
	}

	/** Rebuilds the snapshot from the service. Called on load and after every edit. */
	public static void publish(RankService service) {
		PLAYERS.clear();
		for (RankService.Rank rank : service.all()) {
			Grant grant = new Grant(rank.commandLevel(), rank.commandMode(), rank.commands());
			if (RankService.DEFAULT_RANK.equals(rank.name())) {
				fallback = grant;
			}
		}
		service.assignmentsView().forEach((uuid, name) ->
			service.byName(name).ifPresent(rank -> PLAYERS.put(uuid,
				new Grant(rank.commandLevel(), rank.commandMode(), rank.commands()))));
	}

	/** What this player's rank allows. Never null — an unassigned player gets the default. */
	public static Grant of(UUID player) {
		Grant grant = player == null ? null : PLAYERS.get(player);
		return grant != null ? grant : fallback;
	}

	public static void clear() {
		PLAYERS.clear();
		fallback = NOTHING;
	}
}
