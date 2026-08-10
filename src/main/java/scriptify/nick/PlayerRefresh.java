package scriptify.nick;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import scriptify.access.TrackedEntityBridge;
import scriptify.mixin.ChunkMapAccessor;

/**
 * Rebuilds a player for every client that can see them, and for the player themselves.
 *
 * <p>A vanilla client snapshots a player's {@code GameProfile} — name <em>and</em> skin — when it first
 * spawns that entity, so a nickname or skin change is invisible until the entity is recreated. Doing
 * that correctly means driving the server's own entity tracker rather than sending hand-made add and
 * remove packets: the tracker keeps a {@code seenBy} set, and bypassing it leaves the server believing
 * clients have state they no longer have. That desync is what strands players invisible or stuck in a
 * stale spectator view.
 *
 * <p>The sequence here is adapted from SkinRestorer's {@code PlayerUtils.refreshPlayer}, which is the
 * reference implementation for doing this without a reconnect.
 */
public final class PlayerRefresh {
	private PlayerRefresh() {
	}

	/**
	 * Full refresh: rebuilds the player-list entry, re-tracks the entity both ways with every observer,
	 * and re-syncs the player's own client.
	 *
	 * <p>The self-respawn at the end is the part that matters for game-mode changes: a
	 * {@link ClientboundRespawnPacket} carrying {@code KEEP_ALL_DATA} re-sends the current game mode,
	 * abilities and camera state, so leaving spectator actually takes effect client-side instead of
	 * leaving the player floating and invisible.
	 */
	public static void refresh(ServerPlayer player) {
		ServerLevel level = player.level();
		var server = level.getServer();
		if (server == null) {
			return;
		}
		PlayerList playerList = server.getPlayerList();

		// 1. Rebuild the player-list entry for everyone.
		playerList.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
		playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(Collections.singleton(player)));

		// 2. Re-track through the vanilla tracker so seenBy stays truthful.
		retrack(level, player);

		// 3. Re-sync the player's own client.
		if (!player.isDeadOrDying()) {
			resyncSelf(player, level, playerList);
		}
	}

	/**
	 * Untracks and re-tracks the player against every observer, in both directions.
	 *
	 * <p>The reverse direction matters: the observer's own tracked entity is refreshed for the player
	 * too, otherwise the pair can end up with one side rendering a stale copy of the other.
	 */
	private static void retrack(ServerLevel level, ServerPlayer player) {
		if (retrackViaTracker(level, player)) {
			return;
		}
		// The vanilla tracker was unusable or had no observers registered. That is normal on servers
		// where the chunk system has been replaced (C2ME's chunk-system rewrite, Paper/Cardboard hybrids,
		// mods that wrap ChunkMap.addEntity), so fall back to respawning the entity with plain packets —
		// slightly less precise, but it does not depend on anyone's tracker internals.
		respawnEntityForViewers(player);
	}

	/**
	 * Preferred path: drive the vanilla entity tracker directly.
	 *
	 * @return {@code true} when at least one observer was re-tracked this way.
	 */
	private static boolean retrackViaTracker(ServerLevel level, ServerPlayer player) {
		try {
			ChunkMap chunkMap = level.getChunkSource().chunkMap;
			Object tracked = ((ChunkMapAccessor) chunkMap).scriptify$getEntityMap().get(player.getId());
			if (!(tracked instanceof TrackedEntityBridge trackedEntity)) {
				return false;
			}
			// Copy first: removing observers mutates the live seenBy set.
			Set<ServerPlayerConnection> seenBy = Set.copyOf(trackedEntity.scriptify$seenBy());
			if (seenBy.isEmpty()) {
				return false;
			}
			for (ServerPlayerConnection connection : seenBy) {
				ServerPlayer observer = connection.getPlayer();
				trackedEntity.scriptify$removePlayer(observer);

				Object observerTracked = ((ChunkMapAccessor) chunkMap).scriptify$getEntityMap().get(observer.getId());
				if (observerTracked instanceof TrackedEntityBridge observerEntity) {
					observerEntity.scriptify$removePlayer(player);
					observerEntity.scriptify$updatePlayer(player);
				}
				trackedEntity.scriptify$updatePlayer(observer);
			}
			return true;
		} catch (RuntimeException | LinkageError exception) {
			// Another mod owns the chunk system; the packet fallback handles it.
			return false;
		}
	}

	/**
	 * Fallback: despawn and respawn the player's entity for every other client by hand.
	 *
	 * <p>Clients snapshot the profile when they spawn a player entity, so recreating the entity is what
	 * makes a new skin or name take effect. Entity data, head rotation and equipment are re-sent because
	 * a fresh spawn packet carries none of them.
	 */
	private static void respawnEntityForViewers(ServerPlayer player) {
		var server = player.level().getServer();
		if (server == null) {
			return;
		}
		ClientboundRemoveEntitiesPacket remove = new ClientboundRemoveEntitiesPacket(player.getId());
		ClientboundAddEntityPacket add = new ClientboundAddEntityPacket(
			player.getId(), player.getUUID(),
			player.getX(), player.getY(), player.getZ(),
			player.getXRot(), player.getYRot(),
			EntityType.PLAYER, 0, player.getDeltaMovement(), player.getYHeadRot());
		ClientboundRotateHeadPacket head =
			new ClientboundRotateHeadPacket(player, (byte) (player.getYHeadRot() * 256.0F / 360.0F));

		List<SynchedEntityData.DataValue<?>> data = player.getEntityData().getNonDefaultValues();
		ClientboundSetEntityDataPacket entityData =
			data == null || data.isEmpty() ? null : new ClientboundSetEntityDataPacket(player.getId(), data);

		List<Pair<EquipmentSlot, ItemStack>> worn = new ArrayList<>();
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				worn.add(Pair.of(slot, stack.copy()));
			}
		}
		ClientboundSetEquipmentPacket equipment =
			worn.isEmpty() ? null : new ClientboundSetEquipmentPacket(player.getId(), worn);

		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer == player || viewer.level() != player.level()) {
				continue;
			}
			viewer.connection.send(remove);
			viewer.connection.send(add);
			viewer.connection.send(head);
			if (entityData != null) {
				viewer.connection.send(entityData);
			}
			if (equipment != null) {
				viewer.connection.send(equipment);
			}
		}
	}

	/**
	 * Re-sends the player's own world state: respawn (keeping inventory and stats), position, motion,
	 * passengers, abilities, XP, permission level, level info and active effects.
	 */
	private static void resyncSelf(ServerPlayer player, ServerLevel level, PlayerList playerList) {
		player.connection.send(new ClientboundBundlePacket(List.of(
			new ClientboundRespawnPacket(player.createCommonSpawnInfo(level), ClientboundRespawnPacket.KEEP_ALL_DATA),
			new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F))));
		player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
		player.connection.send(new ClientboundSetEntityMotionPacket(player));

		var vehicle = player.getVehicle();
		if (vehicle != null) {
			player.connection.send(new ClientboundSetPassengersPacket(vehicle));
		}
		if (!player.getPassengers().isEmpty()) {
			player.connection.send(new ClientboundSetPassengersPacket(player));
		}

		player.onUpdateAbilities();
		// Nudges the XP bar, which the respawn packet blanks.
		player.giveExperiencePoints(0);
		playerList.sendPlayerPermissionLevel(player);
		playerList.sendLevelInfo(player, level);
		playerList.sendAllPlayerInfo(player);
		playerList.sendActivePlayerEffects(player);
		for (var effect : player.getActiveEffects()) {
			player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), effect, false));
		}
	}

	/**
	 * Refreshes only what other players see. Kept separate because the self-respawn is comparatively
	 * heavy and briefly blanks the HUD, so it is not worth paying when only observers need updating.
	 */
	public static void resendToOthers(ServerPlayer player) {
		ServerLevel level = player.level();
		var server = level.getServer();
		if (server == null) {
			return;
		}
		server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
		server.getPlayerList().broadcastAll(
			ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(Collections.singleton(player)));
		retrack(level, player);
	}

	/**
	 * Blanks or restores the player's visible armour for observers.
	 *
	 * <p>The invisibility effect hides the body but not worn equipment, so floating armour is the usual
	 * tell. Sending empty equipment removes it for everyone else while the player keeps wearing it
	 * server-side, so protection and durability are unaffected.
	 */
	public static void resendEquipment(ServerPlayer target, boolean hidden) {
		var server = target.level().getServer();
		if (server == null) {
			return;
		}
		List<Pair<EquipmentSlot, ItemStack>> payload = new ArrayList<>();
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack stack = target.getItemBySlot(slot);
			if (hidden) {
				payload.add(Pair.of(slot, ItemStack.EMPTY));
			} else if (!stack.isEmpty()) {
				payload.add(Pair.of(slot, stack.copy()));
			}
		}
		if (payload.isEmpty()) {
			return;
		}
		ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(target.getId(), payload);
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer != target) {
				viewer.connection.send(packet);
			}
		}
	}

	/**
	 * Drops the player out of the tab list for everyone except themselves, without touching their
	 * entity. Used by vanish and staged deaths.
	 */
	public static void hideFromPlayerList(ServerPlayer target) {
		var server = target.level().getServer();
		if (server == null) {
			return;
		}
		ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID()));
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer != target) {
				viewer.connection.send(packet);
			}
		}
	}

	/**
	 * Removes the player's entity from every observer's view, leaving them fully invisible rather than
	 * relying on the invisibility effect (which still renders worn armour and particles).
	 */
	public static void untrackFromAll(ServerPlayer player) {
		ServerLevel level = player.level();
		ChunkMap chunkMap = level.getChunkSource().chunkMap;
		Object tracked = ((ChunkMapAccessor) chunkMap).scriptify$getEntityMap().get(player.getId());
		if (!(tracked instanceof TrackedEntityBridge trackedEntity)) {
			return;
		}
		for (ServerPlayerConnection connection : Set.copyOf(trackedEntity.scriptify$seenBy())) {
			trackedEntity.scriptify$removePlayer(connection.getPlayer());
		}
	}
}
