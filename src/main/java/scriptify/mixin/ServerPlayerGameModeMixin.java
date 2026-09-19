package scriptify.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scriptify.death.DeathControlManager;
import scriptify.runtime.ScriptifyRuntime;

/**
 * Leaving spectator is treated as "I'm done being dead".
 *
 * <p>A staged death parks the player in spectator and hides them from the player list. Switching back to
 * any playable game mode is the obvious way to end that, so it restores them automatically — no command
 * needed. Hooking {@code setGameMode} rather than Scriptify's own commands means it also works through
 * vanilla {@code /gamemode}, an operator changing someone else's mode, and any other mod.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerGameModeMixin {
	@Inject(method = "setGameMode", at = @At("RETURN"))
	private void scriptify$restoreStagedOnGameModeChange(GameType mode, CallbackInfoReturnable<Boolean> callback) {
		// Only a real change out of spectator counts. Staging itself sets SPECTATOR, and vanilla returns
		// false when the mode did not actually change.
		if (mode == GameType.SPECTATOR || !callback.getReturnValueZ()) {
			return;
		}
		DeathControlManager deathControl = ScriptifyRuntime.deathControl();
		if (deathControl == null) {
			return;
		}
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (!deathControl.isStaged(player)) {
			return;
		}
		// restore() clears the staged flag before it sets the game mode again, so the re-entrant call this
		// triggers finds nothing staged and stops there.
		deathControl.restore(player, mode);
	}
}
