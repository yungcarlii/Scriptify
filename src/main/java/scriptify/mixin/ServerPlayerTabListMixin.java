package scriptify.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scriptify.nick.NickRegistry;

/**
 * Vanilla returns {@code null} here so the client falls back to the profile name in the tab list.
 * Returning a component makes the nickname show up in the player list on unmodified clients.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabListMixin {
	@Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
	private void scriptify$nickTabList(CallbackInfoReturnable<Component> callback) {
		String nickname = NickRegistry.get(((ServerPlayer) (Object) this).getUUID());
		if (nickname != null) {
			callback.setReturnValue(Component.literal(nickname));
		}
	}
}
