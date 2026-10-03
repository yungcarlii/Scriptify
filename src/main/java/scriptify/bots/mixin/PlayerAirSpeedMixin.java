package scriptify.bots.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scriptify.bots.ai.LoadGovernor;
import scriptify.bots.bot.BotManager;

/**
 * The air half of lag compensation: a sprint-jump's speed in the air comes from here, not from the
 * movement-speed attribute {@link LoadGovernor#applyLagBoost} scales.
 */
@Mixin(Player.class)
public abstract class PlayerAirSpeedMixin {
    @Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
    private void sbots$lagBoostAir(CallbackInfoReturnable<Float> cir) {
        double boost = LoadGovernor.lagBoost();
        if (boost <= 1.0) return;
        Player self = (Player) (Object) this;
        if (self.level().isClientSide() || !BotManager.isBot(self.getName().getString())) return;
        cir.setReturnValue((float) (cir.getReturnValueF() * boost));
    }
}
