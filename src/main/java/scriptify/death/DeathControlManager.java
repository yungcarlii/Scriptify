package scriptify.death;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import scriptify.api.ScriptifyContext;
import scriptify.config.ConfigManager;
import scriptify.feature.Sfx;
import scriptify.nick.PlayerRefresh;

/**
 * Turns a lethal hit into a staged exit instead of a death.
 *
 * <p>The player never actually dies: no respawn screen, and nothing is taken from them. They are healed,
 * moved to spectator so they can keep filming, and erased from everyone else's player list behind a
 * genuine-looking leave message.
 *
 * <p>From the outside it looks like the real thing — the death sound plays, the death line goes out, and
 * a duplicate of their gear scatters across the ground. The player keeps every item.
 */
public final class DeathControlManager {
	private static final String MODULE = "death-control";
	private static final String FILE = "staged.json";
	private final ScriptifyContext context;
	private final ConfigManager config;
	/** Players currently staged as "left the server" and hidden from the player list. */
	private final Set<UUID> staged = new HashSet<>();

	public DeathControlManager(ScriptifyContext context, ConfigManager config) {
		this.context = context;
		this.config = config;
	}

	public boolean allowDeath(ServerPlayer player, DamageSource source, float amount) {
		if (!this.config.get().deathControl.enabled) {
			return true;
		}
		// Staged deaths belong to the recording, which happens in the vanilla dimensions (Hubify's
		// event world). Anywhere else - Hubify's FFA and hub - a death stays real, so the FFA can
		// respawn the player at its spawn with their kit.
		if (!"minecraft".equals(player.level().dimension().identifier().getNamespace())) {
			return true;
		}
		// A grave mod builds its grave from the death Scriptify cancels, so a staged death can leave a
		// real grave holding a copy of an inventory the player never actually lost. Yielding lets the
		// death happen normally and keeps the graves honest.
		if (!scriptify.compat.CompatRegistry.owns(scriptify.compat.CompatDomain.DEATH)) {
			return true;
		}
		// Before anything below, because everything below can cancel the death, and cancelling is exactly
		// what skips the totem. Fabric's ALLOW_DEATH redirects the `isDeadOrDying` guard that wraps
		// vanilla's `checkTotemDeathProtection`, so a cancelled death never reaches the totem at all —
		// it is silently eaten rather than popped.
		//
		// This sat below the bot branch until 1.5.1, which meant a bot holding a totem was disconnected
		// and the totem simply vanished. Keeping the check at the top makes "cancelling a death must
		// never swallow a totem" a property of the method rather than something each new branch has to
		// remember, which is how it got missed the first time.
		if (this.config.get().deathControl.respectTotems && holdsDeathProtection(player, source)) {
			return true;
		}
		// Bots are not cast members. A fake player from a PvP-bot or NPC mod parked in spectator sits in
		// the world forever with nobody to take it out; disconnecting is both what an operator expects
		// and what those mods do on their own.
		if (this.config.get().deathControl.disconnectBots && isFakePlayer(player)) {
			// Cancelling the death also cancels vanilla's death broadcast, so it has to be sent here or
			// the bot simply vanishes with no indication anyone killed it.
			if (this.config.get().deathControl.showDeathMessage) {
				this.context.server().getPlayerList().broadcastSystemMessage(deathMessage(player, source), false);
			}
			// And the leave message has to be sent by hand too: vanilla broadcasts it from the connection's
			// disconnect handler, not from PlayerList.remove(), and a bot has no real connection to close.
			if (this.config.get().deathControl.fakeLeaveMessage) {
				this.context.server().getPlayerList().broadcastSystemMessage(
					Component.translatable("multiplayer.player.left", player.getDisplayName())
						.withStyle(ChatFormatting.YELLOW), false);
			}
			this.context.server().getPlayerList().remove(player);
			this.context.logger().info("Bot {} was killed and disconnected.", player.getGameProfile().name());
			return false;
		}
		try {
			// Before anything else: the drops have to be spawned while the player is still a living body
			// at the place they fell. Healing and the switch to spectator both come after.
			if (this.config.get().deathControl.dropItems) {
				dropCopyOfInventory(player);
			}
			if (this.config.get().deathControl.playDeathSound) {
				Sfx.playerDeath(player);
			}
			player.setHealth(player.getMaxHealth());
			player.getFoodData().setFoodLevel(20);
			player.getFoodData().setSaturation(20.0F);
			player.clearFire();
			player.setGameMode(GameType.SPECTATOR);
			this.staged.add(player.getUUID());
			// Order matters: the death line lands first, then the disconnect — which is exactly the
			// sequence a real fatal hit followed by a rage-quit produces.
			if (this.config.get().deathControl.showDeathMessage) {
				this.context.server().getPlayerList().broadcastSystemMessage(deathMessage(player, source), false);
			}
			if (this.config.get().deathControl.fakeLeaveMessage) {
				// Reuse vanilla's own translation key and yellow styling so the broadcast is byte-identical
				// to a real disconnect — anything hand-written reads as fake on camera.
				this.context.server().getPlayerList().broadcastSystemMessage(
					Component.translatable("multiplayer.player.left", player.getDisplayName())
						.withStyle(ChatFormatting.YELLOW), false);
			}
			if (this.config.get().deathControl.hideFromPlayerList) {
				hideFromPlayerList(player);
			}
			save();
			// Without this the staged player has no idea what happened: they are a spectator, gone from
			// everyone's tab list, with no visible way back. Tell them, and tell them how to return.
			player.sendSystemMessage(Component.literal("You were staged as having left the server.")
				.withStyle(ChatFormatting.GRAY));
			player.sendSystemMessage(Component.literal("Switch out of spectator, or run /ondeath restore, to rejoin.")
				.withStyle(ChatFormatting.YELLOW));
			this.context.logger().info("Intercepted fatal damage for {}; staged as left-the-server in spectator.",
				player.getGameProfile().name());
			return false;
		} catch (RuntimeException exception) {
			this.context.logger().error("Failed to intercept death for {}.", player.getGameProfile().name(), exception);
			return true;
		}
	}

	/**
	 * Brings a staged player back into the scene: restores their game mode and makes them visible in the
	 * player list again.
	 *
	 * @return {@code false} when the player was not staged in the first place.
	 */
	public boolean restore(ServerPlayer player, GameType mode) {
		if (!this.staged.remove(player.getUUID())) {
			return false;
		}
		player.setGameMode(mode);
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		// Spectator leaves the client invisible and non-colliding until it is told otherwise; clearing the
		// flag server-side alone is not enough, which is why the full refresh below is required.
		player.setInvisible(false);
		player.removeEffect(MobEffects.INVISIBILITY);
		player.onUpdateAbilities();
		// Full refresh, not just a tab-list rebuild: the self-respawn packet is what actually re-syncs the
		// client out of the spectator camera and back into a normal body.
		PlayerRefresh.refresh(player);
		save();
		return true;
	}

	public boolean isStaged(ServerPlayer player) {
		return this.staged.contains(player.getUUID());
	}

	public Set<UUID> staged() {
		return Set.copyOf(this.staged);
	}

	/**
	 * Called when a player joins. A staged player who reconnects is put straight back into the scene.
	 *
	 * <p>Without this a staged player is stranded permanently: they reconnect still in spectator, still
	 * flagged, and a fresh login does not clear either. Reconnecting is the obvious thing to try when you
	 * think the server has forgotten you exist, so it has to be the thing that fixes it.
	 */
	public void onJoin(ServerPlayer player) {
		if (!this.staged.contains(player.getUUID())) {
			return;
		}
		restore(player, GameType.SURVIVAL);
		player.sendSystemMessage(Component.literal("Welcome back — your staged death was cleared.")
			.withStyle(ChatFormatting.GRAY));
	}

	// ---------------------------------------------------------------- persistence

	/**
	 * Staged players are persisted so a restart cannot strand them as permanent spectators.
	 */
	public void load() {
		this.staged.clear();
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, DeathControlManager::defaults);
		if (!root.has("staged") || !root.get("staged").isJsonArray()) {
			return;
		}
		for (JsonElement element : root.getAsJsonArray("staged")) {
			try {
				this.staged.add(UUID.fromString(element.getAsString()));
			} catch (IllegalArgumentException ignored) {
				// Skip malformed ids rather than refusing to load the file.
			}
		}
	}

	public void save() {
		JsonObject root = defaults();
		JsonArray array = root.getAsJsonArray("staged");
		this.staged.forEach(uuid -> array.add(uuid.toString()));
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	private static JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("staged", new JsonArray());
		return root;
	}

	/**
	 * Scatters a duplicate of everything the player is carrying, without taking any of it away.
	 *
	 * <p>This is the whole trick of a staged death: from the outside it looks like a real one — gear
	 * strewn across the ground where they fell — while the player themselves loses nothing and can walk
	 * straight back into the next take fully equipped.
	 *
	 * <p>Uses the same call vanilla's own {@code dropAll()} uses, minus the step that empties the slot,
	 * so the scatter pattern and physics are identical to a genuine death.
	 */
	private void dropCopyOfInventory(ServerPlayer player) {
		boolean pickupable = this.config.get().deathControl.dropsCanBePickedUp;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			ItemEntity dropped = player.drop(stack.copy(), true, false);
			if (dropped == null) {
				continue;
			}
			if (!pickupable) {
				// These are duplicates. Letting anyone collect them would be an item-duplication bug
				// dressed up as a feature, so by default they are scenery: visible, but not lootable.
				dropped.setNeverPickUp();
			}
		}
	}

	/**
	 * The death line vanilla itself would have printed.
	 *
	 * <p>Uses the combat tracker rather than the damage source, because that is what {@code die()} does.
	 * The tracker carries the whole fight — who hit them, what they were riding, what pushed them — so
	 * it produces the specific message ("squashed by", "doomed to fall by") where the raw damage source
	 * only knows the final blow. Kills by minecarts, projectiles and knockback all depend on this.
	 */
	private static Component deathMessage(ServerPlayer player, DamageSource source) {
		try {
			Component tracked = player.getCombatTracker().getDeathMessage();
			if (tracked != null) {
				return tracked;
			}
		} catch (RuntimeException ignored) {
			// Fall through to the damage source below.
		}
		return source == null
			? Component.translatable("death.attack.generic", player.getDisplayName())
			: source.getLocalizedDeathMessage(player);
	}

	/**
	 * Detects a bot: a {@link ServerPlayer} that is not exactly a {@code ServerPlayer}.
	 *
	 * <p>Every fake-player implementation — Carpet, PvP Bot, HeroBot and the rest — subclasses
	 * {@code ServerPlayer} to override its ticking, so an exact class check separates them from real
	 * connections without needing to know which mod created them. SkinRestorer identifies fake players
	 * the same way.
	 */
	private static boolean isFakePlayer(ServerPlayer player) {
		return scriptify.access.FakePlayers.isFake(player);
	}

	/**
	 * Mirrors vanilla's own totem test.
	 *
	 * <p>Checks the {@code death_protection} data component rather than the totem item itself, so modded
	 * totems count too, and honours {@code BYPASSES_INVULNERABILITY} — void damage and {@code /kill} kill
	 * through a totem in vanilla, and should here as well.
	 */
	private static boolean holdsDeathProtection(ServerPlayer player, DamageSource source) {
		if (source != null && source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.getItemInHand(hand).get(DataComponents.DEATH_PROTECTION) != null) {
				return true;
			}
		}
		return false;
	}

	private void hideFromPlayerList(ServerPlayer player) {
		ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()));
		for (ServerPlayer viewer : this.context.server().getPlayerList().getPlayers()) {
			if (viewer != player) {
				viewer.connection.send(packet);
			}
		}
	}

	public void setEnabled(boolean enabled) {
		this.config.get().deathControl.enabled = enabled;
		this.config.save();
	}

	public boolean isEnabled() {
		return this.config.get().deathControl.enabled;
	}
}
