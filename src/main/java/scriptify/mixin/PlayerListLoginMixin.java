package scriptify.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import scriptify.skin.SkinRegistry;

/**
 * Applies a stored skin override before the player is placed in the world.
 *
 * <p>Doing it here rather than on the join event means the first player-info packet everyone receives
 * already carries the right texture, so no refresh is needed at login and there is no window in which
 * observers cache the wrong skin. This mirrors SkinRestorer, which hooks the same method.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
	@Inject(method = "placeNewPlayer", at = @At("HEAD"))
	private void scriptify$applyStoredSkin(Connection connection, ServerPlayer player,
			CommonListenerCookie cookie, CallbackInfo callback) {
		try {
			SkinRegistry.applyStored(player);
		} catch (RuntimeException exception) {
			// A skin is never worth failing a login over.
		}
	}
}
