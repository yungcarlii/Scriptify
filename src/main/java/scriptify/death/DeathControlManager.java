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
import net.minecraft.world.level.GameType;
import scriptify.api.ScriptifyContext;
import scriptify.config.ConfigManager;
import scriptify.nick.PlayerRefresh;

/**
 * Turns a lethal hit into a staged exit instead of a death.
 *
 * <p>The player never actually dies — no death message, no dropped inventory, no respawn screen. They
 * are healed, moved to spectator so they can keep filming, and erased from everyone else's player list
 * behind a genuine-looking leave message.
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
		// Fabric fires this event before vanilla's totem check, so without this a staged death would
		// swallow the hit and the totem in the player's hand would never get a chance to pop. Returning
		// true hands the death back to vanilla, which consumes the totem and revives them normally.
		if (this.config.get().deathControl.respectTotems && holdsDeathProtection(player, source)) {
			return true;
		}
		try {
			player.setHealth(player.getMaxHealth());
			player.getFoodData().setFoodLevel(20);
			player.getFoodData().setSaturation(20.0F);
			player.clearFire();
			player.setGameMode(GameType.SPECTATOR);
			this.staged.add(player.getUUID());
			// Order matters: the death line lands first, then the disconnect — which is exactly the
			// sequence a real fatal hit followed by a rage-quit produces.
			if (this.config.get().deathControl.showDeathMessage) {
				this.context.server().getPlayerList().broadcastSystemMessage(
					source == null
						? Component.translatable("death.attack.generic", player.getDisplayName())
						: source.getLocalizedDeathMessage(player), false);
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
		this.context.server().getPlayerList().broadcastSystemMessage(
			Component.translatable("multiplayer.player.joined", player.getDisplayName())
				.withStyle(ChatFormatting.YELLOW), false);
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
