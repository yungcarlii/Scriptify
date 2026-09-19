package scriptify.mixin;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import scriptify.feature.ProximityRegistry;

/**
 * Drops a chat message for one recipient who is too far from whoever sent it.
 *
 * <p>Per recipient, which is the whole point: vanilla decides once whether a message is sent and then
 * sends the same one to everybody, so anything that filters chat as a whole — the Fabric chat event,
 * for instance — can only choose between everyone hearing it and nobody hearing it. Proximity needs a
 * different answer per player, and this is the only place in the chain that has one player in hand.
 *
 * <p>Skipping the send is all it takes. Nothing is rewritten and no message is reconstructed, so
 * signatures, the secure-chat indicator and client-side reporting are exactly as vanilla left them for
 * the players who do receive it.
 *
 * <p>Only ever active in the middle of a player chat broadcast; see {@link ChatSourceMixin}.
 */
@Mixin(ServerPlayer.class)
public abstract class ChatDeliveryMixin {
	@Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
	private void scriptify$proximityFilter(OutgoingChatMessage message, boolean filtered,
			ChatType.Bound bound, CallbackInfo callback) {
		if (ProximityRegistry.blocks((ServerPlayer) (Object) this)) {
			callback.cancel();
		}
	}
}
