package scriptify.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scriptify.compat.CompatDomain;
import scriptify.compat.CompatRegistry;
import scriptify.nick.NickRegistry;

/**
 * Lets vanilla commands accept the nickname a player is wearing.
 *
 * <p>A vanilla client builds its own suggestion list from the names in its player list, which are the
 * spoofed ones — so {@code /gamemode} offers the nickname and then vanilla fails to find anybody by it.
 * Suggesting a name that does not work is worse than not suggesting it, and the suggestion list itself
 * is out of a server's reach, so the fix goes on the resolving end instead.
 *
 * <p>Only consulted when the real-name lookup found nothing, so a nickname can never shadow the account
 * it is imitating. Scoped to name lookups: the login duplicate-name check does not come through here.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListNameLookupMixin {
	@Inject(method = "getPlayerByName", at = @At("RETURN"), cancellable = true)
	private void scriptify$resolveNickname(String name, CallbackInfoReturnable<ServerPlayer> callback) {
		if (callback.getReturnValue() != null || !CompatRegistry.owns(CompatDomain.NICKNAMES)) {
			return;
		}
		for (ServerPlayer player : ((PlayerList) (Object) this).getPlayers()) {
			String nickname = NickRegistry.get(player.getUUID());
			if (nickname != null && nickname.equalsIgnoreCase(name)) {
				callback.setReturnValue(player);
				return;
			}
		}
	}
}
