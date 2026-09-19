package scriptify.mixin;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import scriptify.feature.ProximityRegistry;

/**
 * Records who is talking, for as long as their message is being handed out.
 *
 * <p>Deliberately on this overload rather than the private one both paths funnel into. This one is
 * reached only by a player actually typing in chat; {@code /say} and {@code /me} arrive through the
 * command-source overload and are left alone, so proximity limits conversation without taking away
 * the ability to address everyone.
 *
 * <p>{@link ChatDeliveryMixin} is the other half, and reads what this records.
 */
@Mixin(PlayerList.class)
public abstract class ChatSourceMixin {
	@Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;"
		+ "Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
		at = @At("HEAD"))
	private void scriptify$markChatSource(PlayerChatMessage message, ServerPlayer sender,
			ChatType.Bound bound, CallbackInfo callback) {
		ProximityRegistry.beginChat(sender);
	}

	@Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;"
		+ "Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
		at = @At("RETURN"))
	private void scriptify$clearChatSource(PlayerChatMessage message, ServerPlayer sender,
			ChatType.Bound bound, CallbackInfo callback) {
		ProximityRegistry.endChat();
	}
}
