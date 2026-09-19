package scriptify.kits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import scriptify.feature.Sfx;

/**
 * Deals a kit out one item at a time instead of dropping it in all at once.
 *
 * <p>Purely presentational, and the reason it exists is that an instant inventory swap reads as a
 * glitch on camera — a beat of items landing left-to-right along the hotbar and armour snapping on
 * last reads as a deliberate moment. Each item plays a cue pitched slightly higher than the last, so
 * the sequence rises as it fills.
 *
 * <p>Only the hotbar and armour are animated. Everything else is placed immediately: the backpack is
 * not on screen while this plays, so staggering it would delay the kit with nothing to show for it.
 */
public final class KitAnimator {
	private static final int ARMOR_FIRST_SLOT = 36;
	private static final int ARMOR_LAST_SLOT = 39;
	/** Slots that never hold anything are skipped, so a half-empty kit still finishes quickly. */
	private final Map<UUID, Animation> running = new HashMap<>();

	/**
	 * Begins dealing {@code items} into the player's inventory.
	 *
	 * <p>The inventory is emptied immediately so the old loadout does not linger behind the incoming
	 * one; only the filling is spread over time.
	 */
	public void start(ServerPlayer player, ItemStack[] items, int intervalTicks) {
		player.getInventory().clearContent();

		int size = Math.min(items.length, player.getInventory().getContainerSize());
		List<Step> steps = new ArrayList<>();
		for (int slot = 0; slot < size; slot++) {
			ItemStack stack = items[slot];
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (isAnimated(slot)) {
				steps.add(new Step(slot, stack.copy()));
			} else {
				// The backpack is not visible while the animation plays, so staggering it only delays the
				// kit for no visual gain. Only what the player can actually watch arrive is animated.
				player.getInventory().setItem(slot, stack.copy());
			}
		}
		steps.sort(java.util.Comparator.comparingInt(step -> dealOrderIndex(step.slot())));
		player.getInventory().setChanged();
		if (steps.isEmpty()) {
			this.running.remove(player.getUUID());
			return;
		}
		// Restarting replaces any run already in flight, so double-clicking a kit cannot interleave two.
		this.running.put(player.getUUID(), new Animation(steps, Math.max(1, intervalTicks)));
	}

	/** Drops a run without filling the remaining slots — used when the player disconnects. */
	public void cancel(UUID player) {
		this.running.remove(player);
	}

	public void tick(MinecraftServer server) {
		if (this.running.isEmpty()) {
			return;
		}
		this.running.entrySet().removeIf(entry -> {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || !player.isAlive()) {
				return true;
			}
			return entry.getValue().advance(player);
		});
	}

	/**
	 * Whether a slot is one the player can watch fill: the hotbar and the four armour slots.
	 *
	 * <p>The backpack is behind a closed screen during the animation, so dealing it out slowly would
	 * delay the kit without anybody seeing it happen.
	 */
	private static boolean isAnimated(int slot) {
		return slot < 9 || (slot >= ARMOR_FIRST_SLOT && slot <= ARMOR_LAST_SLOT);
	}

	/**
	 * Deal order: hotbar left to right, then armour from the boots up — so the character visibly builds
	 * and the helmet is the last beat.
	 */
	private static int dealOrderIndex(int slot) {
		return slot < 9 ? slot : 9 + (slot - ARMOR_FIRST_SLOT);
	}

	private record Step(int slot, ItemStack stack) {
	}

	private static final class Animation {
		private final List<Step> steps;
		private final int interval;
		private int index;
		private int cooldown;

		private Animation(List<Step> steps, int interval) {
			this.steps = steps;
			this.interval = interval;
		}

		/**
		 * Places the next item when its delay elapses.
		 *
		 * @return {@code true} once every item has been dealt.
		 */
		private boolean advance(ServerPlayer player) {
			if (this.cooldown-- > 0) {
				return false;
			}
			this.cooldown = this.interval;
			Step step = this.steps.get(this.index++);
			player.getInventory().setItem(step.slot(), step.stack());
			player.getInventory().setChanged();
			// Armour gets its own thunk; everything else clicks up the scale as the kit fills.
			if (step.slot() >= ARMOR_FIRST_SLOT && step.slot() <= ARMOR_LAST_SLOT) {
				Sfx.kitArmorPiece(player);
			} else {
				Sfx.kitItem(player, (float) this.index / this.steps.size());
			}
			boolean done = this.index >= this.steps.size();
			if (done) {
				// A chord on the last beat, so the deal has an ending rather than just stopping.
				Sfx.kitComplete(player);
			}
			return done;
		}
	}
}
