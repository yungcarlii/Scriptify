package scriptify.bots.ai;

import net.minecraft.server.level.ServerPlayer;
import scriptify.access.BotInputBridge;

/**
 * Presses a bot's movement keys.
 *
 * <p>The keys are held inside the bot's own tick by {@link BotInputBridge}, because anything written
 * to the movement fields from the server tick event is overwritten by the backend before physics
 * reads it. See {@code LivingEntityInputMixin}.</p>
 *
 * <p>A press is held for a few ticks rather than exactly one. A bot the load governor has thinned
 * only decides every few ticks, and a player's hand stays on the key between decisions - letting go
 * on every tick it did not think would turn a steady swim into a stutter.</p>
 */
public final class BotInput {

    private BotInput() {}

    /** Ticks a press outlives the decision that made it. At least the load governor's widest stride. */
    private static final int HOLD_TICKS = 6;

    /**
     * Holds W/A/S/D and space.
     *
     * @return false when the bot cannot be driven this way, in which case nothing was pressed
     */
    public static boolean hold(ServerPlayer bot, float forward, float strafe, boolean jump) {
        return hold(bot, forward, strafe, jump, bot.isSprinting());
    }

    /** Holds W/A/S/D and space, and the sprint key as given. */
    public static boolean hold(ServerPlayer bot, float forward, float strafe, boolean jump, boolean sprint) {
        if (!((Object) bot instanceof BotInputBridge bridge)) return false;
        bridge.scriptify$holdInput(clamp(forward), clamp(strafe), jump, sprint,
            bot.level().getGameTime() + HOLD_TICKS);
        return true;
    }

    /** Lets go of every key. Safe to call whether or not anything is held. */
    public static void release(ServerPlayer bot) {
        if ((Object) bot instanceof BotInputBridge bridge) {
            bridge.scriptify$releaseInput();
        }
    }

    public static boolean isHolding(ServerPlayer bot) {
        return (Object) bot instanceof BotInputBridge bridge && bridge.scriptify$isHoldingInput();
    }

    private static float clamp(float value) {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }
}
