package scriptify.bots.npc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.utils.Names;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays a recorded performance back through a bot, frame for frame.
 *
 * <p>Distinct from a path on purpose. A path is a list of places and the bot walks between them
 * however it likes, which is right for a patrol route and wrong for a performance: it throws away
 * where you were looking, when you crouched, and every pause and turn that made the movement read as
 * a person rather than as a route. This keeps all of it and replays it exactly.</p>
 *
 * <p>The cost of that fidelity is that the bot is <em>driven</em>, not steered - it is placed where
 * the recording says on every tick, so it walks through anything that has appeared in the way since.
 * That is the correct trade for staged footage, where the take is the point and the physics are not,
 * and it is why paths still exist for everything else.</p>
 */
public final class MovementReplay {

    /**
     * One tick of a performance.
     *
     * <p>Sampled every tick rather than every few. A minute is twelve hundred of these, which is
     * nothing to hold in memory and the only sampling rate at which a head turn stays a head turn
     * instead of becoming a snap between two angles.</p>
     */
    public record Frame(double x, double y, double z, float yaw, float pitch, boolean sneaking) {
        public static Frame of(ServerPlayer player) {
            return new Frame(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.isShiftKeyDown());
        }
    }

    /** A recording assigned to a bot, and how far through it that bot is. */
    private static final class Playback {
        final List<Frame> frames;
        int cursor;

        Playback(List<Frame> frames) {
            this.frames = frames;
        }
    }

    private static final Map<String, Playback> PLAYING = new HashMap<>();

    private MovementReplay() {}

    public static boolean isPlaying(String botName) {
        return PLAYING.containsKey(Names.key(botName));
    }

    /**
     * Hands a recording to a bot and starts it from the beginning.
     *
     * <p>The bot is put on the first frame before anything else, so a performance always begins
     * where it was recorded rather than wherever the bot happened to be standing.</p>
     */
    public static void start(ServerPlayer bot, List<Frame> frames) {
        if (bot == null || frames == null || frames.size() < 2) {
            return;
        }
        PLAYING.put(Names.key(bot.getName().getString()), new Playback(new ArrayList<>(frames)));
        apply(bot, frames.get(0));
    }

    public static void stop(String botName) {
        PLAYING.remove(Names.key(botName));
    }

    /** @return the state it is now in. */
    public static boolean toggle(ServerPlayer bot, List<Frame> frames) {
        if (isPlaying(bot.getName().getString())) {
            stop(bot.getName().getString());
            return false;
        }
        start(bot, frames);
        return true;
    }

    public static void tick(MinecraftServer server) {
        if (PLAYING.isEmpty()) {
            return;
        }
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            Playback playback = PLAYING.get(Names.key(bot.getName().getString()));
            if (playback == null || !bot.isAlive()) {
                continue;
            }
            Frame frame = playback.frames.get(playback.cursor);
            apply(bot, frame);
            // Loops rather than stopping at the end: a recording is a performance to repeat, and a
            // bot frozen on its last frame reads as one that has broken.
            playback.cursor = (playback.cursor + 1) % playback.frames.size();
        }
    }

    private static void apply(ServerPlayer bot, Frame frame) {
        bot.absSnapTo(frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        // The body follows the head for a player, and without this the bot slides along facing
        // whichever way it was last pointed.
        bot.setYHeadRot(frame.yaw());
        bot.setYBodyRot(frame.yaw());
        bot.setShiftKeyDown(frame.sneaking());
    }

    public static void forget(String botName) {
        PLAYING.remove(Names.key(botName));
    }

    public static void clear() {
        PLAYING.clear();
    }
}
