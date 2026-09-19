package scriptify.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import scriptify.compat.CompatDomain;
import scriptify.compat.CompatRegistry;
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
		// Before anything else, and before the compat check: this is the only point in a session where
		// the profile is guaranteed to be the one Mojang sent rather than one we or another mod have
		// rewritten, so it is the only place their real skin can be read honestly. Capturing is a read,
		// so it stays safe to do even when another mod owns skins - and it means /identity clear still
		// has something true to restore if ownership changes later.
		try {
			SkinRegistry.captureOriginal(player);
		} catch (RuntimeException exception) {
			// A skin is never worth failing a login over.
		}

		// FabricTailor and SkinRestorer write the same profile property from the same method. Yielding
		// stops the two of them overwriting each other depending on which mixin sorted first.
		if (!CompatRegistry.owns(CompatDomain.SKINS)) {
			return;
		}
		try {
			SkinRegistry.applyStored(player);
		} catch (RuntimeException exception) {
			// A skin is never worth failing a login over.
		}
	}
}
