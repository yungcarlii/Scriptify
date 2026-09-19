package scriptify.bots.mixin;

import net.minecraft.server.players.PlayerList;
import scriptify.bots.bot.BotSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fake players occupy real player slots, so a server with max-players=20 can only ever
 * hold 20 bots + humans. The original mod hard-coded the slot count to 99999, which also
 * silently disabled the "server full" screen for real players. Instead we add a
 * configurable headroom on top of whatever the server was actually configured for.
 */
@Mixin(PlayerList.class)
public class PlayerSlotMixin {

    // The target is named in a string, so it is not remapped with the rest of the code. This one
    // was still the Yarn name after the merge and failed the mixin apply outright, which is at least
    // the loud kind of failure.
    @Inject(method = "getMaxPlayers", at = @At("RETURN"), cancellable = true)
    private void sbots$addBotHeadroom(CallbackInfoReturnable<Integer> cir) {
        int headroom = BotSettings.get().getBotSlotHeadroom();
        if (headroom <= 0) return;
        int configured = cir.getReturnValueI();
        cir.setReturnValue(Math.min(Integer.MAX_VALUE - 1, configured + headroom));
    }
}
