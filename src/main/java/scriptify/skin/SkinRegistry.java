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

	private SkinRegistry() {
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
