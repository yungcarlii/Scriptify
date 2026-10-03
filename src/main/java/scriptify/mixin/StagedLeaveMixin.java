package scriptify.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import scriptify.death.DeathControlManager;
import scriptify.runtime.ScriptifyRuntime;

/**
 * A staged player already "left" when they died, so their real disconnect stays silent.
 *
 * <p>Otherwise chat shows them leaving twice: the staged leave at the death, then vanilla's again when
 * they actually quit from spectator.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class StagedLeaveMixin {
	@Shadow
	public ServerPlayer player;

	@Redirect(method = "removePlayerFromWorld", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private void scriptify$silentStagedLeave(PlayerList list, Component message, boolean overlay) {
		DeathControlManager deathControl = ScriptifyRuntime.deathControl();
		if (deathControl != null && deathControl.isStaged(this.player)) {
			return;
		}
		list.broadcastSystemMessage(message, overlay);
	}
}
