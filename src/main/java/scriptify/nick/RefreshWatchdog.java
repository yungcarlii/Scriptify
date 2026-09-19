package scriptify.nick;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps re-asking the entity tracker to reconcile a refreshed player, until it sticks.
 *
 * <p>Rebuilding a player means untracking and re-tracking them for every observer. The untrack always
 * works; the re-track is conditional, and vanilla will decline it if the observer is momentarily out
 * of range or — the common one — if the chunk the player stands in is still queued in that observer's
 * chunk sender. One attempt is therefore not enough, and a declined attempt leaves the observer
 * holding no entity at all.
 *
 * <p>Vanilla does not clean that up on its own. {@code ChunkMap.tick} only calls {@code updatePlayers}
 * for an entity whose <em>section position changed</em>, so a player standing still stays invisible
 * until they walk 16 blocks or reconnect. That is the bug this exists to make impossible.
 *
 * <p>The repair is simply to ask again on later ticks. {@code updatePlayer} is idempotent — it adds an
 * observer in range and drops one out of range — so re-running it is exactly the reconciliation vanilla
 * performs on a section change, and it converges the moment the conditions allow.
 */
public final class RefreshWatchdog {
	/**
	 * How many ticks to keep reconciling after a refresh.
	 *
	 * <p>Two seconds. Long enough to outlast a chunk send on a loaded server, short enough that the
	 * work is over before anyone could notice it, and the cost per tick is a handful of distance checks
	 * per player being watched.
	 */
	private static final int TICKS = 40;

	/** Ticks remaining at which the packet fallback re-sends, when there is no tracker to ask. */
	private static final int RESEND_FIRST = 30;
	private static final int RESEND_SECOND = 10;

	private static final Map<UUID, Integer> pending = new HashMap<>();

	private RefreshWatchdog() {
	}

	/** Starts (or restarts) the reconciliation window for a player who was just refreshed. */
	public static void watch(ServerPlayer player) {
		pending.put(player.getUUID(), TICKS);
	}

	public static void clear() {
		pending.clear();
	}

	public static void tick(MinecraftServer server) {
		if (pending.isEmpty()) {
			return;
		}
		var iterator = pending.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Integer> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			// Gone, or out of attempts. A disconnected player re-tracks from scratch on their next login,
			// so there is nothing left to repair.
			if (player == null || entry.getValue() <= 0) {
				iterator.remove();
				continue;
			}
			int remaining = entry.getValue() - 1;
			entry.setValue(remaining);
			// Looked up fresh every tick on purpose: changing dimension builds a new tracker, and a stale
			// reference would be reconciling an entity nobody is watching any more.
			if (PlayerRefresh.reconcileTracking(player)) {
				continue;
			}
			// No tracker to ask - another mod owns the chunk system - so the only repair available is to
			// send the spawn again. Twice across the window rather than every tick: a re-send is a real
			// despawn and respawn on the client, and doing that forty times in two seconds would be a
			// visible flicker for everybody who could already see them. Two attempts, spaced, covers the
			// case this exists for, which is a chunk that had not arrived when the first one was sent.
			if (remaining == RESEND_FIRST || remaining == RESEND_SECOND) {
				PlayerRefresh.respawnEntityForViewers(player);
			}
		}
	}
}
