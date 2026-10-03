package scriptify.bots.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a player is part way through breaking a block, and which one. */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccessor {
	@Accessor("isDestroyingBlock")
	boolean scriptify$isDestroyingBlock();

	@Accessor("destroyPos")
	BlockPos scriptify$destroyPos();
}
