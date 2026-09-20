package scriptify.rollback;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.RegistryOps;
import scriptify.api.ScriptifyContext;

/**
 * Where snapshots live, and which ones survive.
 *
 * <p>One file per player under {@code rollback/}, written through the same atomic path the rest of
 * the mod uses.
 *
 * <h2>Retention</h2>
 *
 * <p>The old backup system kept a flat twenty per player and dropped the oldest. That is the wrong
 * shape: a death snapshot and a routine auto-snapshot are worth entirely different amounts, and on a
 * busy session twenty auto-snapshots would push out the one death somebody actually needed.
 *
 * <p>So each reason gets its own budget and its own expiry, and they do not compete. Deaths are kept
 * longest because the inventory somebody died with is the one that cannot be retaken.
 */
public final class SnapshotStore {
	private static final String MODULE = "rollback";
	private static final int SCHEMA_VERSION = 1;

	/** How many of each reason to keep, and for how long. */
	private record Policy(int keep, Duration expiry) { }

	private static final Map<String, Policy> POLICIES = Map.of(
		Snapshot.REASON_DEATH, new Policy(25, Duration.ofDays(30)),
		Snapshot.REASON_MANUAL, new Policy(25, null),
		Snapshot.REASON_BEFORE_RESTORE, new Policy(10, Duration.ofDays(7)),
		Snapshot.REASON_AUTO, new Policy(40, Duration.ofDays(3)));

	/** Anything with an unrecognised reason is treated as routine. */
	private static final Policy FALLBACK = new Policy(20, Duration.ofDays(3));

	private final ScriptifyContext context;

	public SnapshotStore(ScriptifyContext context) {
		this.context = context;
	}

	private RegistryOps<JsonElement> ops() {
		return RegistryOps.create(JsonOps.INSTANCE, this.context.server().registryAccess());
	}

	/** Players whose old-format backups have already been looked at, so it happens once. */
	private final Set<UUID> migrated = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Newest first, which is the order the timeline wants. */
	public List<Snapshot> list(UUID player) {
		migrateOldBackups(player);
		JsonObject root = this.context.storage()
			.loadObject(MODULE, player + ".json", SnapshotStore::defaults);
		List<Snapshot> snapshots = new ArrayList<>();
		if (!root.has("snapshots") || !root.get("snapshots").isJsonArray()) {
			return snapshots;
		}
		RegistryOps<JsonElement> ops = ops();
		for (JsonElement element : root.getAsJsonArray("snapshots")) {
			try {
				snapshots.add(Snapshot.fromJson(element.getAsJsonObject(), ops, this.context.logger()));
			} catch (RuntimeException exception) {
				// One bad entry must not cost somebody every other snapshot they have.
				this.context.logger().warn("Ignoring a malformed rollback entry for {}.", player,
					exception);
			}
		}
		snapshots.sort(Comparator.comparing(Snapshot::createdAt).reversed());
		return snapshots;
	}

	public Optional<Snapshot> byId(UUID player, String id) {
		return list(player).stream().filter(snapshot -> snapshot.id().equals(id)).findFirst();
	}

	/** Adds a snapshot, prunes to policy, and writes. */
	public void add(UUID player, Snapshot snapshot) {
		List<Snapshot> snapshots = new ArrayList<>(list(player));
		snapshots.add(snapshot);
		write(player, prune(snapshots));
	}

	public boolean delete(UUID player, String id) {
		List<Snapshot> snapshots = new ArrayList<>(list(player));
		boolean removed = snapshots.removeIf(snapshot -> snapshot.id().equals(id));
		if (removed) {
			write(player, snapshots);
		}
		return removed;
	}

	public void purge(UUID player) {
		write(player, List.of());
	}

	/**
	 * Applies the retention policy.
	 *
	 * <p>Separated and package-visible so it can be tested without a server: retention is the part of
	 * this class most likely to quietly throw away something somebody needed.
	 */
	static List<Snapshot> prune(List<Snapshot> snapshots) {
		Instant now = Instant.now();
		Map<String, List<Snapshot>> byReason = new java.util.LinkedHashMap<>();
		for (Snapshot snapshot : snapshots) {
			byReason.computeIfAbsent(snapshot.reason(), key -> new ArrayList<>()).add(snapshot);
		}

		List<Snapshot> kept = new ArrayList<>();
		byReason.forEach((reason, group) -> {
			Policy policy = POLICIES.getOrDefault(reason, FALLBACK);
			group.sort(Comparator.comparing(Snapshot::createdAt).reversed());
			int taken = 0;
			for (Snapshot snapshot : group) {
				if (taken >= policy.keep()) {
					break;
				}
				if (policy.expiry() != null
						&& snapshot.createdAt().isBefore(now.minus(policy.expiry()))) {
					continue;
				}
				kept.add(snapshot);
				taken++;
			}
		});
		kept.sort(Comparator.comparing(Snapshot::createdAt).reversed());
		return kept;
	}

	/**
	 * Brings a player's pre-rollback backups across, once.
	 *
	 * <p>Lazy rather than a startup sweep: most players never open their history, and reading every
	 * backup file on a long-running server at boot to migrate data nobody asks for is a slow start
	 * for no benefit.
	 *
	 * <p>The old file is <b>left where it is</b>. A downgrade then still finds its data, and the cost
	 * of keeping it is a few kilobytes against the cost of getting this wrong, which is somebody's
	 * inventory history.
	 */
	private void migrateOldBackups(UUID player) {
		if (!this.migrated.add(player)) {
			return;
		}
		JsonObject existing = this.context.storage()
			.loadObject(MODULE, player + ".json", SnapshotStore::defaults);
		if (existing.has("snapshots") && existing.getAsJsonArray("snapshots").size() > 0) {
			// Already has rollback data; nothing to bring across.
			return;
		}

		JsonObject old = this.context.storage()
			.loadObject("backups", player + ".json", SnapshotStore::emptyBackups);
		if (!old.has("backups") || !old.get("backups").isJsonArray()
				|| old.getAsJsonArray("backups").isEmpty()) {
			return;
		}

		RegistryOps<JsonElement> ops = ops();
		List<Snapshot> imported = new ArrayList<>();
		for (JsonElement element : old.getAsJsonArray("backups")) {
			try {
				JsonObject entry = element.getAsJsonObject();
				List<net.minecraft.world.item.ItemStack> inventory = new ArrayList<>();
				if (entry.has("items") && entry.get("items").isJsonArray()) {
					for (JsonElement item : entry.getAsJsonArray("items")) {
						inventory.add(net.minecraft.world.item.ItemStack.OPTIONAL_CODEC
							.parse(ops, item)
							.result()
							.orElse(net.minecraft.world.item.ItemStack.EMPTY));
					}
				}
				imported.add(new Snapshot(
					entry.has("id") ? entry.get("id").getAsString() : String.valueOf(imported.size()),
					Instant.parse(entry.get("createdAt").getAsString()),
					translateReason(entry.has("reason") ? entry.get("reason").getAsString() : "manual"),
					"imported from the old backup format",
					inventory, List.of(), 0, 0.0F, 0, List.of(),
					net.minecraft.world.level.GameType.SURVIVAL, 20.0F, 20, 5.0F, List.of()));
			} catch (RuntimeException exception) {
				this.context.logger().warn("Skipping an unreadable old backup for {}.", player,
					exception);
			}
		}
		if (imported.isEmpty()) {
			return;
		}
		write(player, prune(imported));
		this.context.logger().info("Imported {} old inventory backup(s) for {} into the rollback "
			+ "history. The original file has been left in place.", imported.size(), player);
	}

	/** The old reason tags map onto the new ones one-for-one, apart from the renamed pre-restore. */
	private static String translateReason(String old) {
		return switch (old) {
			case "death" -> Snapshot.REASON_DEATH;
			case "before-restore" -> Snapshot.REASON_BEFORE_RESTORE;
			default -> Snapshot.REASON_MANUAL;
		};
	}

	private static JsonObject emptyBackups() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("backups", new JsonArray());
		return root;
	}

	private void write(UUID player, List<Snapshot> snapshots) {
		JsonObject root = defaults();
		JsonArray array = root.getAsJsonArray("snapshots");
		RegistryOps<JsonElement> ops = ops();
		for (Snapshot snapshot : snapshots) {
			array.add(snapshot.toJson(ops, this.context.logger()));
		}
		this.context.storage().saveObject(MODULE, player + ".json", root);
	}

	private static JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", SCHEMA_VERSION);
		root.add("snapshots", new JsonArray());
		return root;
	}
}
