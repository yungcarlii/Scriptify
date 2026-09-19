package scriptify.api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import scriptify.core.ScriptifyBootstrap;
import scriptify.permission.RankService;

/**
 * The supported way to read Scriptify's ranks from another mod.
 *
 * <p>Everything else in the permission package is internal and moves without warning. This does
 * not. A consumer that reaches past this facade into {@code RankService} or {@code RankRegistry}
 * will break on an ordinary refactor, and that is not a bug in the refactor.</p>
 *
 * <h2>Why the signatures look like this</h2>
 *
 * <p>No Minecraft type appears anywhere in this class's public surface - ranks are strings,
 * players are UUIDs, levels are ints. A consumer may be built against a different mapping set,
 * and a method taking {@code ServerPlayer} cannot be called from such a mod unless both
 * artifacts are remapped in lockstep. Plain Java crosses that boundary unchanged. This mirrors
 * {@link scriptify.bots.api.SBotsApi}, for the same reason and after the same two silent
 * breakages.</p>
 *
 * <h2>Contract</h2>
 *
 * <p>Nothing here throws into a caller. Every method answers with a safe empty value when the
 * server is not running, when the rank service has not loaded, or when the argument is null. A
 * consumer calling this at an arbitrary moment - which is exactly what a soft dependency does -
 * gets an answer rather than an exception.</p>
 *
 * <p><b>A player holds exactly one rank.</b> {@link RankService#of(UUID)} never returns empty; an
 * unassigned player falls through to {@code default}. {@link #isAssigned(UUID)} is how a caller
 * tells "really is a VIP" from "has not been given a rank at all", and the distinction is not
 * academic: on a server where nobody has configured ranks, every player is nominally
 * {@code default}, so a consumer treating that as "has a rank" would let an untouched Scriptify
 * install silently change its behaviour.</p>
 */
public final class RanksApi {

	/**
	 * Listeners registered before the rank service existed.
	 *
	 * <p>Copy-on-write because registration happens on the server thread at startup and firing
	 * happens on the server thread during an edit, but a consumer is free to register from its own
	 * initialiser and the two are not ordered relative to each other.</p>
	 */
	private static final List<Runnable> PENDING = new CopyOnWriteArrayList<>();

	private RanksApi() {
	}

	/** Whether the rank service is loaded and can be asked questions right now. */
	public static boolean available() {
		return service() != null;
	}

	/**
	 * The name of the rank this player holds.
	 *
	 * @return the rank name, or {@code ""} when the service is unavailable. Never null. An
	 *         unassigned player yields {@code "default"} rather than empty - use
	 *         {@link #isAssigned(UUID)} to tell the two apart.
	 */
	public static String rankOf(UUID player) {
		RankService ranks = service();
		if (ranks == null || player == null) {
			return "";
		}
		try {
			return ranks.of(player).name();
		} catch (RuntimeException ignored) {
			return "";
		}
	}

	/** Whether this player was given a rank explicitly, rather than falling through to default. */
	public static boolean isAssigned(UUID player) {
		RankService ranks = service();
		if (ranks == null || player == null) {
			return false;
		}
		try {
			return ranks.isAssigned(player);
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	/** Every rank name on this server, for an admin picker. Empty when unavailable. */
	public static String[] allRankNames() {
		RankService ranks = service();
		if (ranks == null) {
			return new String[0];
		}
		try {
			List<String> names = new ArrayList<>();
			for (RankService.Rank rank : ranks.all()) {
				names.add(rank.name());
			}
			return names.toArray(new String[0]);
		} catch (RuntimeException ignored) {
			return new String[0];
		}
	}

	/**
	 * The vanilla permission level a rank grants: 0 none, 1 moderator, 2 gamemaster, 3 admin,
	 * 4 owner.
	 *
	 * <p>This is the only ordering the rank model actually has, so it is also the answer to "which
	 * of these two ranks is higher".</p>
	 *
	 * @return the level, or {@code -1} when the rank is unknown or the service is unavailable.
	 */
	public static int commandLevelOf(String rank) {
		RankService ranks = service();
		if (ranks == null || rank == null || rank.isBlank()) {
			return -1;
		}
		try {
			return ranks.byName(rank).map(RankService.Rank::commandLevel).orElse(-1);
		} catch (RuntimeException ignored) {
			return -1;
		}
	}

	/** The display prefix configured for a rank, or {@code ""} when unknown or unset. */
	public static String prefixOf(String rank) {
		RankService ranks = service();
		if (ranks == null || rank == null || rank.isBlank()) {
			return "";
		}
		try {
			return ranks.byName(rank).map(RankService.Rank::prefix).orElse("");
		} catch (RuntimeException ignored) {
			return "";
		}
	}

	/**
	 * Registers a callback fired after any rank edit or grant.
	 *
	 * <p>Registering before the service exists is legal and common - a consumer's initialiser runs
	 * at an unspecified point relative to ours. Such listeners are held here and handed over when
	 * the service binds.</p>
	 *
	 * <p>Fired on the server thread. A listener that throws is swallowed rather than allowed to
	 * break the edit that triggered it.</p>
	 */
	public static void onRanksChanged(Runnable listener) {
		if (listener == null) {
			return;
		}
		RankService ranks = service();
		if (ranks == null) {
			PENDING.add(listener);
			return;
		}
		ranks.addChangeListener(listener);
	}

	/**
	 * Hands over listeners that registered before the service existed.
	 *
	 * <p>Called by {@link RankService#bind} once, at server start.</p>
	 */
	public static void drainPendingListeners(RankService ranks) {
		if (ranks == null) {
			return;
		}
		for (Runnable listener : PENDING) {
			ranks.addChangeListener(listener);
		}
		PENDING.clear();
	}

	/**
	 * The live rank service, or null whenever it is not up.
	 *
	 * <p>Null is the normal state outside a running server, not an error: {@code context()} is
	 * itself null until {@code SERVER_STARTED}, which the bootstrap guards for the same reason.
	 *
	 * <p>{@code LinkageError} is caught alongside the runtime exceptions because a test JVM has no
	 * Minecraft classes to link against, and "the classes are not here" is the unavailable case
	 * rather than a failure worth propagating.</p>
	 */
	private static RankService service() {
		try {
			var context = ScriptifyBootstrap.context();
			if (context == null) {
				return null;
			}
			var permissions = context.permissions();
			return permissions == null ? null : permissions.ranks();
		} catch (RuntimeException | LinkageError ignored) {
			return null;
		}
	}
}
