package scriptify.access;

import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;

/**
 * Public handle onto the package-private {@code ChunkMap.TrackedEntity}.
 *
 * <p>{@code scriptify.mixin.TrackedEntityMixin} implements this on the real class, so Scriptify can
 * drive the vanilla tracker (untrack, re-track, inspect observers) without an access widener.
 *
 * <p>This deliberately lives outside {@code scriptify.mixin}. That package is declared as the mixin
 * package in {@code scriptify.mixins.json}, and Mixin refuses to let anything inside it be loaded as an
 * ordinary class — a plain interface there throws {@code IllegalClassLoadError} the moment normal code
 * references it.
 */
public interface TrackedEntityBridge {
	void scriptify$removePlayer(ServerPlayer player);

	void scriptify$updatePlayer(ServerPlayer player);

	Set<ServerPlayerConnection> scriptify$seenBy();
}
