package scriptify.bots.ai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.bot.BotActions;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared crouch state.
 *
 * <p>Sneaking is requested by more than one behaviour - bridging holds it for the whole
 * crossing, a crit hop holds it for a moment - and the backend only has "start" and "stop".
 * Tracking it in one place means the two cannot fight each other, and that the crouch is
 * always released: a behaviour stops needing it by simply not asking again, so there is no
 * tick on which it could tidy up after itself.</p>
 */
public final class Crouch {

    private Crouch() {}

    private static final Map<String, Integer> HOLD = new HashMap<>();

    /** Crouch for at least {@code ticks} more ticks. Safe to call every tick. */
    public static void hold(ServerPlayer bot, MinecraftServer server, int ticks) {
        String key = scriptify.bots.utils.Names.key(bot.getName().getString());
        Integer current = HOLD.get(key);
        if (current == null) {
            BotActions.sneak(bot, server);
        }
        if (current == null || current < ticks) {
            HOLD.put(key, ticks);
        }
        bot.setShiftKeyDown(true);
    }

    /** Counts the hold down and releases it. Called once per bot per tick. */
    public static void tick(ServerPlayer bot, MinecraftServer server) {
        // Nobody is crouching: answer without touching the name at all. This runs for every bot on
        // every tick and almost always falls straight through, and getting as far as the lookup
        // meant building the bot's name out of a Component and then lower-casing it - two Strings
        // per bot per tick to discover there was nothing to do.
        if (HOLD.isEmpty()) return;
        String key = scriptify.bots.utils.Names.key(bot.getName().getString());
        Integer remaining = HOLD.get(key);
        if (remaining == null) return;
        if (remaining > 1) {
            HOLD.put(key, remaining - 1);
            return;
        }
        HOLD.remove(key);
        bot.setShiftKeyDown(false);
        BotActions.unsneak(bot, server);
    }

    public static void remove(String botName) {
        HOLD.remove(scriptify.bots.utils.Names.key(botName));
    }

    public static void clear() {
        HOLD.clear();
    }
}
