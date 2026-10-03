package scriptify.bots.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import scriptify.bots.bot.BotManager;

/**
 * Bots are seen, but do not watch.
 *
 * <p>Every entity keeps a list of the players it is sending its movement to, and re-checks it
 * against every player whenever either moves. A bot reads the world directly and has no client to
 * send anything to, so each of those pairings was packets built for nobody - and with a crowd of
 * bots the pairings grow with the square of it. Real players still track every bot, so nothing
 * anyone can see changes.</p>
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {

	@Shadow
	public abstract void removePlayer(ServerPlayer player);

	@Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
	private void scriptify$botsWatchNothing(ServerPlayer player, CallbackInfo ci) {
		if (!BotManager.isFakePlayer(player)) return;
		removePlayer(player);
		ci.cancel();
	}
}
