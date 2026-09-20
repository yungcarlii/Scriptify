package scriptify.rollback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import scriptify.api.ScriptifyContext;

/**
 * Taking and putting back player state.
 *
 * <p>Every restore takes a {@code before-restore} snapshot of its own first. That single decision is
 * what makes {@link #undo} free, and it is worth the storage: the most common thing anybody wants
 * immediately after a restore is the state they had a moment earlier, usually because they restored
 * the wrong one.
 */
public final class RollbackService {

	private final ScriptifyContext context;
	private final SnapshotStore store;

	/** The snapshot each player's last restore replaced, so undo knows what to go back to. */
	private final Map<UUID, String> lastRestoreUndo = new ConcurrentHashMap<>();

	public RollbackService(ScriptifyContext context) {
		this.context = context;
		this.store = new SnapshotStore(context);
	}

	public SnapshotStore store() {
		return this.store;
	}

	public List<Snapshot> list(UUID player) {
		return this.store.list(player);
	}

	// ------------------------------------------------------------------ taking

	/** Records everything about a player right now. */
	public Snapshot capture(ServerPlayer player, String reason, String note) {
		return capture(player, reason, note, List.of());
	}

	/**
	 * Records a player, optionally with the stacks that are about to hit the floor.
	 *
	 * @param drops what the player is dropping, for a death snapshot. Empty otherwise. This is the
	 *              piece the old backup system lost entirely, and the one people ask for most.
	 */
	public Snapshot capture(ServerPlayer player, String reason, String note, List<ItemStack> drops) {
		List<ItemStack> inventory = new ArrayList<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			inventory.add(player.getInventory().getItem(slot).copy());
		}
		List<ItemStack> ender = new ArrayList<>();
		for (int slot = 0; slot < player.getEnderChestInventory().getContainerSize(); slot++) {
			ender.add(player.getEnderChestInventory().getItem(slot).copy());
		}
		List<ItemStack> droppedCopies = new ArrayList<>();
		for (ItemStack stack : drops) {
			if (stack != null && !stack.isEmpty()) {
				droppedCopies.add(stack.copy());
			}
		}

		Snapshot snapshot = new Snapshot(
			newId(),
			Instant.now(),
			reason,
			note,
			inventory,
			ender,
			player.experienceLevel,
			player.experienceProgress,
			player.totalExperience,
			List.copyOf(player.getActiveEffects()),
			player.gameMode.getGameModeForPlayer(),
			player.getHealth(),
			player.getFoodData().getFoodLevel(),
			player.getFoodData().getSaturationLevel(),
			droppedCopies);

		this.store.add(player.getUUID(), snapshot);
		return snapshot;
	}

	// ------------------------------------------------------------------ restoring

	/** Restores every section a snapshot carries. */
	public boolean restore(ServerPlayer player, String id) {
		return restoreSections(player, id, EnumSet.allOf(Snapshot.Section.class));
	}

	/**
	 * Restores only the named sections.
	 *
	 * <p>Death drops are deliberately <em>not</em> included in a whole restore unless asked for
	 * explicitly: putting the floor items back on top of a restored inventory duplicates everything
	 * the player was carrying when they died.
	 */
	public boolean restoreSections(ServerPlayer player, String id, Set<Snapshot.Section> sections) {
		Optional<Snapshot> found = this.store.byId(player.getUUID(), id);
		if (found.isEmpty()) {
			return false;
		}
		Snapshot snapshot = found.get();

		// Before anything changes. This is what undo goes back to.
		Snapshot safety = capture(player, Snapshot.REASON_BEFORE_RESTORE,
			"before restoring " + snapshot.label());
		this.lastRestoreUndo.put(player.getUUID(), safety.id());

		applySections(player, snapshot, sections);
		this.context.logger().info("Restored {} onto {} ({}).",
			snapshot.label(), player.getGameProfile().name(), describe(sections));
		return true;
	}

	/** Restores individual inventory slots, leaving everything else alone. */
	public boolean restoreSlots(ServerPlayer player, String id, Set<Integer> slots) {
		Optional<Snapshot> found = this.store.byId(player.getUUID(), id);
		if (found.isEmpty() || slots.isEmpty()) {
			return false;
		}
		Snapshot snapshot = found.get();
		Snapshot safety = capture(player, Snapshot.REASON_BEFORE_RESTORE,
			"before restoring " + slots.size() + " slot(s)");
		this.lastRestoreUndo.put(player.getUUID(), safety.id());

		var inventory = player.getInventory();
		for (int slot : slots) {
			if (slot >= 0 && slot < snapshot.inventory().size()
					&& slot < inventory.getContainerSize()) {
				inventory.setItem(slot, snapshot.inventory().get(slot).copy());
			}
		}
		player.inventoryMenu.broadcastChanges();
		return true;
	}

	/**
	 * Puts a player back to just before their last restore.
	 *
	 * @return false when they have not restored anything this session.
	 */
	public boolean undo(ServerPlayer player) {
		String id = this.lastRestoreUndo.remove(player.getUUID());
		if (id == null) {
			return false;
		}
		Optional<Snapshot> safety = this.store.byId(player.getUUID(), id);
		if (safety.isEmpty()) {
			return false;
		}
		applySections(player, safety.get(), EnumSet.allOf(Snapshot.Section.class));
		this.context.logger().info("Undid the last restore for {}.", player.getGameProfile().name());
		return true;
	}

	/**
	 * Puts one player's snapshot onto another player.
	 *
	 * <p>The source is untouched — this copies, it does not move. Used to give a stand-in the same
	 * loadout as the person they are standing in for.
	 */
	public boolean transfer(UUID source, ServerPlayer target, String id,
			Set<Snapshot.Section> sections) {
		Optional<Snapshot> found = this.store.byId(source, id);
		if (found.isEmpty()) {
			return false;
		}
		Snapshot safety = capture(target, Snapshot.REASON_BEFORE_RESTORE, "before a transfer");
		this.lastRestoreUndo.put(target.getUUID(), safety.id());

		applySections(target, found.get(), sections);
		this.context.logger().info("Copied a snapshot from {} onto {}.",
			source, target.getGameProfile().name());
		return true;
	}

	/**
	 * Which snapshots held an item whose id contains the given text.
	 *
	 * <p>The question this answers is "who had the netherite axe an hour ago", which is otherwise a
	 * matter of opening every snapshot in turn.
	 */
	public List<Snapshot> search(UUID player, String itemText) {
		String needle = itemText == null ? "" : itemText.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return List.of();
		}
		List<Snapshot> hits = new ArrayList<>();
		for (Snapshot snapshot : this.store.list(player)) {
			if (contains(snapshot.inventory(), needle)
					|| contains(snapshot.enderChest(), needle)
					|| contains(snapshot.deathDrops(), needle)) {
				hits.add(snapshot);
			}
		}
		return hits;
	}

	private static boolean contains(List<ItemStack> stacks, String needle) {
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
			if (id.contains(needle)) {
				return true;
			}
			String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
			if (name.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ applying

	private void applySections(ServerPlayer player, Snapshot snapshot,
			Set<Snapshot.Section> sections) {
		if (sections.contains(Snapshot.Section.INVENTORY)) {
			var inventory = player.getInventory();
			int size = Math.min(snapshot.inventory().size(), inventory.getContainerSize());
			for (int slot = 0; slot < size; slot++) {
				inventory.setItem(slot, snapshot.inventory().get(slot).copy());
			}
		}
		if (sections.contains(Snapshot.Section.ENDER_CHEST)) {
			var ender = player.getEnderChestInventory();
			int size = Math.min(snapshot.enderChest().size(), ender.getContainerSize());
			for (int slot = 0; slot < size; slot++) {
				ender.setItem(slot, snapshot.enderChest().get(slot).copy());
			}
		}
		if (sections.contains(Snapshot.Section.EXPERIENCE)) {
			player.experienceLevel = snapshot.experienceLevel();
			player.experienceProgress = snapshot.experienceProgress();
			player.totalExperience = snapshot.totalExperience();
			// Without this the client keeps showing whatever bar it had.
			player.setExperienceLevels(snapshot.experienceLevel());
			player.giveExperiencePoints(0);
		}
		if (sections.contains(Snapshot.Section.EFFECTS)) {
			player.removeAllEffects();
			for (MobEffectInstance effect : snapshot.effects()) {
				player.addEffect(new MobEffectInstance(effect));
			}
		}
		if (sections.contains(Snapshot.Section.STATE)) {
			player.setGameMode(snapshot.gameMode());
			player.setHealth(snapshot.health());
			player.getFoodData().setFoodLevel(snapshot.food());
			player.getFoodData().setSaturation(snapshot.saturation());
		}
		if (sections.contains(Snapshot.Section.DEATH_DROPS)) {
			// Added rather than placed, because the drops belong alongside whatever they have now.
			for (ItemStack stack : snapshot.deathDrops()) {
				if (stack != null && !stack.isEmpty()) {
					player.getInventory().placeItemBackInInventory(stack.copy());
				}
			}
		}
		player.inventoryMenu.broadcastChanges();
	}

	private static String describe(Set<Snapshot.Section> sections) {
		if (sections.size() == Snapshot.Section.values().length) {
			return "everything";
		}
		List<String> names = new ArrayList<>();
		sections.forEach(section -> names.add(section.label.toLowerCase(Locale.ROOT)));
		return String.join(", ", names);
	}

	/**
	 * Ids are the creation time in milliseconds, with a counter if two land in the same one.
	 *
	 * <p>Sortable, readable in the file, and unique without dragging in a uuid per snapshot.
	 */
	private String newId() {
		long now = System.currentTimeMillis();
		return Long.toString(now) + "-" + Integer.toHexString(COUNTER.incrementAndGet() & 0xFFF);
	}

	private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
		new java.util.concurrent.atomic.AtomicInteger();
}
