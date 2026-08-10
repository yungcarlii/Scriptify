package scriptify.mixin;

import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import scriptify.access.TrackedEntityBridge;

/**
 * Implements {@link TrackedEntityBridge} on {@code ChunkMap.TrackedEntity}, which is package-private
 * and therefore unreachable from Scriptify's own packages.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements TrackedEntityBridge {
	@Shadow
	@Final
	Set<ServerPlayerConnection> seenBy;

	@Shadow
	public abstract void removePlayer(ServerPlayer player);

	@Shadow
	public abstract void updatePlayer(ServerPlayer player);

	@Override
	public void scriptify$removePlayer(ServerPlayer player) {
		removePlayer(player);
	}

	@Override
	public void scriptify$updatePlayer(ServerPlayer player) {
		updatePlayer(player);
	}

	@Override
	public Set<ServerPlayerConnection> scriptify$seenBy() {
		return this.seenBy;
	}
}
