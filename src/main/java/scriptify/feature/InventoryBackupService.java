package scriptify.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import scriptify.api.ScriptifyContext;

/**
 * Snapshots and restores whole player inventories.
 *
 * <p>The point is take safety: before a scene that destroys, drops or rearranges someone's gear, grab a
 * snapshot and roll it back afterwards instead of rebuilding the loadout by hand.
 */
public final class InventoryBackupService {
	private static final String MODULE = "backups";
	private static final int MAX_PER_PLAYER = 10;
	private static final DateTimeFormatter LABEL_FORMAT =
		DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());

	private final ScriptifyContext context;

	public InventoryBackupService(ScriptifyContext context) {
		this.context = context;
	}

	/**
	 * Stores the player's full inventory, including armour and offhand.
	 *
	 * <p>Oldest snapshots are pruned past {@link #MAX_PER_PLAYER} so this cannot grow without bound on a
	 * long-running server.
	 */
	public String create(ServerPlayer player, String reason) {
		List<Backup> backups = new ArrayList<>(list(player.getUUID()));
		RegistryOps<JsonElement> ops = registryOps();

		JsonArray items = new JsonArray();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			items.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack)
				.resultOrPartial(error -> this.context.logger().warn("Skipping unserialisable slot in backup: {}", error))
				.orElse(new JsonObject()));
		}

		String id = Long.toString(System.currentTimeMillis());
		JsonObject entry = new JsonObject();
		entry.addProperty("id", id);
		entry.addProperty("createdAt", Instant.now().toString());
		entry.addProperty("reason", reason == null ? "manual" : reason);
		entry.add("items", items);
		backups.add(new Backup(id, Instant.now(), reason == null ? "manual" : reason, entry));

		backups.sort(Comparator.comparing(Backup::createdAt));
		while (backups.size() > MAX_PER_PLAYER) {
			backups.remove(0);
		}
		write(player.getUUID(), backups);
		return id;
	}

	/**
	 * Restores a snapshot over the player's current inventory.
	 *
	 * @return {@code false} when no snapshot with that id exists.
	 */
	public boolean restore(ServerPlayer player, String id) {
		RegistryOps<JsonElement> ops = registryOps();
		for (Backup backup : list(player.getUUID())) {
			if (!backup.id().equals(id)) {
				continue;
			}
			JsonArray items = backup.raw().getAsJsonArray("items");
			int max = Math.min(items.size(), player.getInventory().getContainerSize());
			for (int slot = 0; slot < max; slot++) {
				ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, items.get(slot))
					.resultOrPartial(error -> this.context.logger().warn("Skipping unreadable backup slot: {}", error))
					.orElse(ItemStack.EMPTY);
				player.getInventory().setItem(slot, stack);
			}
			player.getInventory().setChanged();
			return true;
		}
		return false;
	}

	public boolean delete(ServerPlayer player, String id) {
		List<Backup> backups = new ArrayList<>(list(player.getUUID()));
		boolean removed = backups.removeIf(backup -> backup.id().equals(id));
		if (removed) {
			write(player.getUUID(), backups);
		}
		return removed;
	}

	/** Newest first, which is the order the menu wants. */
	public List<Backup> listNewestFirst(UUID player) {
		List<Backup> backups = new ArrayList<>(list(player));
		backups.sort(Comparator.comparing(Backup::createdAt).reversed());
		return backups;
	}

	private List<Backup> list(UUID player) {
		JsonObject root = this.context.storage().loadObject(MODULE, player + ".json", InventoryBackupService::defaults);
		List<Backup> backups = new ArrayList<>();
		if (!root.has("backups") || !root.get("backups").isJsonArray()) {
			return backups;
		}
		for (JsonElement element : root.getAsJsonArray("backups")) {
			try {
				JsonObject entry = element.getAsJsonObject();
				backups.add(new Backup(
					entry.get("id").getAsString(),
					Instant.parse(entry.get("createdAt").getAsString()),
					entry.has("reason") ? entry.get("reason").getAsString() : "manual",
					entry));
			} catch (RuntimeException exception) {
				this.context.logger().warn("Ignoring malformed inventory backup for {}.", player);
			}
		}
		return backups;
	}

	private void write(UUID player, List<Backup> backups) {
		JsonObject root = defaults();
		JsonArray array = root.getAsJsonArray("backups");
		backups.forEach(backup -> array.add(backup.raw()));
		this.context.storage().saveObject(MODULE, player + ".json", root);
	}

	private RegistryOps<JsonElement> registryOps() {
		return RegistryOps.create(JsonOps.INSTANCE, this.context.server().registryAccess());
	}

	private static JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("backups", new JsonArray());
		return root;
	}

	public record Backup(String id, Instant createdAt, String reason, JsonObject raw) {
		public String label() {
			return LABEL_FORMAT.format(this.createdAt);
		}
	}
}
