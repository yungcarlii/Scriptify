package scriptify.rollback;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retention, which is where a rollback system quietly loses the thing somebody needed.
 *
 * <p>The old backup kept a flat twenty per player and dropped the oldest. On a busy session that
 * meant twenty routine snapshots could push out the one death that mattered. These tests pin the
 * behaviour that replaced it: each reason has its own budget, and they do not compete.
 *
 * <p>No Minecraft server is needed — {@code prune} works on timestamps and reasons only, which is
 * exactly why it was separated out.
 */
class SnapshotStoreTest {

	private static Snapshot at(String reason, Instant when) {
		return new Snapshot(reason + "-" + when.toEpochMilli(), when, reason, "",
			List.of(), List.of(), 0, 0.0F, 0, List.of(), GameType.SURVIVAL, 20.0F, 20, 5.0F,
			List.of());
	}

	private static List<Snapshot> many(String reason, int count, Instant base) {
		List<Snapshot> out = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			out.add(at(reason, base.minus(index, ChronoUnit.MINUTES)));
		}
		return out;
	}

	@Test
	void keepsEverythingWhenUnderBudget() {
		List<Snapshot> kept = SnapshotStore.prune(many(Snapshot.REASON_MANUAL, 5, Instant.now()));

		assertEquals(5, kept.size());
	}

	@Test
	void autoSnapshotsNeverPushOutADeath() {
		// The regression this whole policy exists for.
		Instant now = Instant.now();
		List<Snapshot> all = new ArrayList<>(many(Snapshot.REASON_AUTO, 100, now));
		Snapshot death = at(Snapshot.REASON_DEATH, now.minus(2, ChronoUnit.HOURS));
		all.add(death);

		List<Snapshot> kept = SnapshotStore.prune(all);

		assertTrue(kept.contains(death), "the death snapshot was pruned by routine ones");
	}

	@Test
	void eachReasonIsCappedSeparately() {
		Instant now = Instant.now();
		List<Snapshot> all = new ArrayList<>();
		all.addAll(many(Snapshot.REASON_AUTO, 100, now));
		all.addAll(many(Snapshot.REASON_MANUAL, 100, now));
		all.addAll(many(Snapshot.REASON_DEATH, 100, now));

		List<Snapshot> kept = SnapshotStore.prune(all);

		assertEquals(40, count(kept, Snapshot.REASON_AUTO));
		assertEquals(25, count(kept, Snapshot.REASON_MANUAL));
		assertEquals(25, count(kept, Snapshot.REASON_DEATH));
	}

	@Test
	void theNewestOfEachReasonSurvive() {
		Instant now = Instant.now();
		List<Snapshot> all = many(Snapshot.REASON_MANUAL, 40, now);
		Snapshot newest = all.get(0);
		Snapshot oldest = all.get(39);

		List<Snapshot> kept = SnapshotStore.prune(all);

		assertTrue(kept.contains(newest));
		assertFalse(kept.contains(oldest));
	}

	@Test
	void expiredEntriesAreDropped() {
		Instant longAgo = Instant.now().minus(60, ChronoUnit.DAYS);
		Snapshot stale = at(Snapshot.REASON_AUTO, longAgo);

		List<Snapshot> kept = SnapshotStore.prune(List.of(stale));

		assertTrue(kept.isEmpty(), "an auto snapshot from 60 days ago should have expired");
	}

	@Test
	void manualSnapshotsNeverExpire() {
		Instant longAgo = Instant.now().minus(3650, ChronoUnit.DAYS);
		Snapshot ancient = at(Snapshot.REASON_MANUAL, longAgo);

		List<Snapshot> kept = SnapshotStore.prune(List.of(ancient));

		assertEquals(1, kept.size(), "a manual snapshot was taken on purpose and should be kept");
	}

	@Test
	void resultIsSortedNewestFirst() {
		Instant now = Instant.now();
		List<Snapshot> kept = SnapshotStore.prune(many(Snapshot.REASON_MANUAL, 5, now));

		for (int index = 1; index < kept.size(); index++) {
			assertTrue(kept.get(index - 1).createdAt().isAfter(kept.get(index).createdAt()),
				"snapshots came back out of order");
		}
	}

	@Test
	void anUnknownReasonStillGetsABudgetRatherThanBeingDropped() {
		List<Snapshot> kept = SnapshotStore.prune(many("something-new", 3, Instant.now()));

		assertEquals(3, kept.size());
	}

	private static long count(List<Snapshot> snapshots, String reason) {
		return snapshots.stream().filter(snapshot -> snapshot.reason().equals(reason)).count();
	}
}
