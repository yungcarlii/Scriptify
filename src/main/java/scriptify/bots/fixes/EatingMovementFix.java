package scriptify.bots.fixes;

import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.bot.BotUtils;

public class EatingMovementFix {

    public static void apply(ServerPlayer bot) {
        var state = BotUtils.getState(bot.getName().getString());
        if (!state.isEating) return;

        bot.setSprinting(false);

        bot.zza *= 0.2f;
        bot.xxa *= 0.2f;

        var vel = bot.getDeltaMovement();
        bot.setDeltaMovement(vel.x * 0.8, vel.y, vel.z * 0.8);
    }
}
