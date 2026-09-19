package scriptify.skin;

import com.google.common.collect.LinkedHashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import scriptify.mixin.PlayerAccessor;

/**
 * Static, lifecycle-independent view of stored skin overrides, plus the profile-rewriting itself.
 *
 * <p>Kept static for the same reason as {@link scriptify.nick.NickRegistry}: the login mixin runs long
 * before {@link scriptify.runtime.ScriptifyRuntime} is reachable and must not depend on it.
 */
public final class SkinRegistry {
	public static final String TEXTURES = "textures";

	private static final Map<UUID, Property> SKINS = new ConcurrentHashMap<>();

	/**
	 * Each player's authentic texture, taken at login before anything of ours is applied.
	 *
	 * <p>This is what makes putting somebody back reliable. The alternative - remembering the skin at
	 * the moment we first override it - has a hole in it: by then the player may already be wearing an
	 * override from an earlier session, reapplied at login, and what gets filed as "their own skin" is
	 * a stranger's face. Clearing then restores the wrong person and looks like it did nothing.
	 *
	 * <p>Capturing at login closes that hole because the profile the login carries comes from the
	 * session server, not from us. It is authentic every time, which also means this is
	 * <em>self-healing</em>: it is overwritten on every login rather than only being written once, so
	 * a wrong entry cannot outlive a reconnect, and nothing has to be persisted to disk for a restart
	 * to come back correct.
	 *
	 * <p>An absent entry and a blank one differ. Blank records a player who genuinely has no skin, so
	 * clearing strips the override instead of fetching a replacement for a skin that never existed.
	 */
	private static final Map<UUID, Property> ORIGINALS = new ConcurrentHashMap<>();

	/** Marks a player as having no skin of their own, so absent and blank stay distinguishable. */
	private static final Property NO_SKIN = new Property(TEXTURES, "");

	private SkinRegistry() {
	}

	/**
	 * Records what this player actually looks like, before any override is applied.
	 *
	 * <p>Called from the login mixin at the head of {@code placeNewPlayer} - which is the one moment
	 * the profile is guaranteed to be Mojang's rather than ours.
	 */
	public static void captureOriginal(ServerPlayer player) {
		Property texture = null;
		for (Property property : player.getGameProfile().properties().get(TEXTURES)) {
			texture = property;
			break;
		}
		ORIGINALS.put(player.getUUID(), texture == null ? NO_SKIN : texture);
	}

	/**
	 * The texture this player logged in wearing.
	 *
	 * @return their own texture, a blank-valued property when they have no skin, or {@code null} when
	 *         nothing was captured - a bot, or somebody who has not logged in since the server started.
	 */
	public static Property original(UUID uuid) {
		return uuid == null ? null : ORIGINALS.get(uuid);
	}

	/** True when the captured entry records a player with no skin of their own. */
	public static boolean isNoSkin(Property texture) {
		return texture == null || texture.value() == null || texture.value().isBlank();
	}

	public static void put(UUID uuid, Property texture) {
		if (uuid != null && texture != null) {
			SKINS.put(uuid, texture);
		}
	}

	public static void remove(UUID uuid) {
		if (uuid != null) {
			SKINS.remove(uuid);
		}
	}

	public static void clear() {
		SKINS.clear();
		// Deliberately not clearing ORIGINALS: it is keyed to who is online, not to what we have
		// stored, and dropping it on a config reload would strip the safety net from every player
		// currently connected.
	}

	public static void forgetOriginal(UUID uuid) {
		if (uuid != null) {
			ORIGINALS.remove(uuid);
		}
	}

	public static Property get(UUID uuid) {
		return uuid == null ? null : SKINS.get(uuid);
	}

	/**
	 * Applies the stored override, if any, to a player who has not been spawned yet.
	 *
	 * <p>Called from the login mixin so the very first player-info packet already carries the right
	 * texture — no refresh needed, and no window where observers cache the wrong skin.
	 */
	public static void applyStored(ServerPlayer player) {
		Property texture = get(player.getUUID());
		if (texture != null) {
			applyToProfile(player, texture);
		}
	}

	/**
	 * Replaces the player's {@code GameProfile} with one carrying {@code texture}, keeping the id, the
	 * name and every other property intact.
	 *
	 * @param texture the signed texture to apply, or {@code null} to strip the skin entirely.
	 */
	public static void applyToProfile(ServerPlayer player, Property texture) {
		GameProfile current = player.getGameProfile();
		LinkedHashMultimap<String, Property> properties = LinkedHashMultimap.create(current.properties());
		properties.removeAll(TEXTURES);
		if (texture != null) {
			properties.put(TEXTURES, texture);
		}
		GameProfile updated = new GameProfile(current.id(), current.name(), new PropertyMap(properties));
		((PlayerAccessor) player).scriptify$setGameProfile(updated);
	}
}
