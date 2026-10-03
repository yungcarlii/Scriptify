package scriptify.bots.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import scriptify.bots.bot.BotManager;

/**
 * Bots show on the locator bar but do not have one.
 *
 * <p>Vanilla connects every player to every other player's waypoint and re-checks each pair as
 * anybody moves. With two hundred and fifty bots that is sixty thousand pairs, and profiling a 2 v 250
 * fight put a third of every server tick here - all of it spent keeping bars up to date for players
 * with no screen to draw one on. A bot still transmits, so a real player sees the army on their bar;
 * it just never receives.</p>
 */
@Mixin(ServerWaypointManager.class)
public abstract class ServerWaypointManagerMixin {

	@Shadow
	public abstract void trackWaypoint(WaypointTransmitter waypoint);

	@Inject(method = "addPlayer", at = @At("HEAD"), cancellable = true)
	private void scriptify$botsOnlyTransmit(ServerPlayer player, CallbackInfo ci) {
		if (!BotManager.isFakePlayer(player)) return;
		if (player.isTransmittingWaypoint()) trackWaypoint(player);
		ci.cancel();
	}

	@Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
	private void scriptify$botsReceiveNothing(ServerPlayer player, CallbackInfo ci) {
		if (BotManager.isFakePlayer(player)) ci.cancel();
	}
}
