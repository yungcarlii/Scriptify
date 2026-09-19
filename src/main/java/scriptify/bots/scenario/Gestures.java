package scriptify.bots.scenario;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.Crouch;
import scriptify.bots.ai.Rng;

import java.util.HashMap;
import java.util.Map;

/**
 * The small body language that makes a standing bot look like a person.
 *
 * <p>Everything here is an <em>offset</em> laid over whatever the bot is already looking at,
 * not a replacement for it. A nod while listening has to stay pointed at the speaker, so the
 * aim runs first and the gesture bends it; only a full turn takes the head over completely.</p>
 *
 * <p>Gestures are short and one at a time. A bot nodding, hopping and spinning at once looks
 * broken rather than alive.</p>
 */
public final class Gestures {

    private Gestures() {}

    public enum Kind {
        /** Yes. Pitch bobs down and back up. */
        NOD,
        /** No. Head turns side to side. */
        SHAKE,
        /** A full turn on the spot. */
        SPIN,
        /** Hopping in place. */
        HOP,
        /** Rapid crouch flicker - the universal "hello". */
        SNEAK_BURST,
        /** Held crouch. */
        SNEAK_HOLD
    }

    private static final class Active {
        Kind kind;
        int ticks;
        int total;
        float baseYaw;
        double amplitude;
        int cycles;
    }

    private static final Map<String, Active> ACTIVE = new HashMap<>();

    public static boolean busy(String botName) {
        return ACTIVE.containsKey(scriptify.bots.utils.Names.key(botName));
    }

    public static void start(ServerPlayer bot, BotProfile profile, Kind kind, int ticks) {
        String key = scriptify.bots.utils.Names.key(bot.getName().getString());
        if (ACTIVE.containsKey(key)) return;

        Active active = new Active();
        active.kind = kind;
        active.ticks = 0;
        active.total = Math.max(2, ticks);
        active.baseYaw = bot.getYRot();
        active.cycles = switch (kind) {
            case NOD -> Rng.range(profile.rng(), 1, 3);
            case SHAKE -> Rng.range(profile.rng(), 2, 3);
            default -> 1;
        };
        active.amplitude = switch (kind) {
            case NOD -> Rng.range(profile.rng(), 8.0, 17.0);
            case SHAKE -> Rng.range(profile.rng(), 16.0, 30.0);
            default -> 0;
        };
        ACTIVE.put(key, active);
    }

    /**
     * Applies whatever gesture is running. Call after aiming.
     *
     * @return true when the gesture has taken over the head, so the caller should not fight it
     */
    public static boolean apply(ServerPlayer bot, MinecraftServer server, BotProfile profile) {
        String key = scriptify.bots.utils.Names.key(bot.getName().getString());
        Active active = ACTIVE.get(key);
        if (active == null) return false;

        active.ticks++;
        double progress = (double) active.ticks / active.total;
        if (progress >= 1.0) {
            ACTIVE.remove(key);
            return false;
        }

        double wave = Math.sin(progress * Math.PI * 2 * active.cycles);

        switch (active.kind) {
            case NOD -> bot.setXRot((float) clampPitch(bot.getXRot() + wave * active.amplitude));
            case SHAKE -> bot.setYRot((float) (bot.getYRot() + wave * active.amplitude));
            case SPIN -> {
                bot.setYRot((float) (active.baseYaw + 360.0 * progress));
                return true;
            }
            case HOP -> {
                if (bot.onGround()) bot.jumpFromGround();
            }
            case SNEAK_BURST -> {
                // Two ticks down, two up: fast enough to read as a wave, slow enough to see.
                if ((active.ticks / 2) % 2 == 0) Crouch.hold(bot, server, 2);
            }
            case SNEAK_HOLD -> Crouch.hold(bot, server, 3);
        }
        return false;
    }

    private static double clampPitch(double pitch) {
        return Math.max(-90.0, Math.min(90.0, pitch));
    }

    public static void remove(String botName) {
        ACTIVE.remove(scriptify.bots.utils.Names.key(botName));
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
