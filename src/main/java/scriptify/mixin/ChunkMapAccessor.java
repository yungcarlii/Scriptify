package scriptify.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the entity tracker map.
 *
 * <p>Refreshing a player's appearance means making the server's own tracker re-send them, rather than
 * hand-rolling add/remove entity packets — hand-rolled packets leave the tracker's {@code seenBy}
 * bookkeeping out of sync with what clients actually have, which is what makes players get stuck
 * invisible or frozen in a stale state.
 *
 * <p>The value type is erased to {@code Object} because {@code ChunkMap.TrackedEntity} is
 * package-private; {@link TrackedEntityBridge} is the handle used to actually call into it.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Accessor("entityMap")
	Int2ObjectMap<Object> scriptify$getEntityMap();
}
