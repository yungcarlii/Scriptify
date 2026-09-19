package scriptify.bots.utils;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Key normalisation for per-bot state maps.
 *
 * <p>Bot names are not stable strings. The fake-player backend resolves the requested name
 * against real Mojang profiles, so asking for {@code Fighter3} can produce a player called
 * {@code FIGHTER3}. Every subsystem that stores state per bot ({@code BotProfile},
 * {@code BotCombat}, aiming, targeting, navigation, cobwebs, bridging) is keyed by name, and
 * mixing the requested spelling with the joined spelling silently creates two entries for
 * one bot - one of which is then never ticked and never cleaned up.</p>
 *
 * <p>Normalising every key through here makes the maps case-insensitive, so it does not
 * matter which spelling a caller happens to be holding.</p>
 *
 * <p><b>Why this is cached.</b> Ninety call sites go through this method and most of them are on
 * the per-tick path - resolving a profile, a combat state, an aim state, a manoeuvre state, a
 * navigation state, a crouch hold, a stride band. Walk one bot through one tick and it crosses
 * this fifteen to twenty times. {@code toLowerCase} allocates a fresh String every one of those,
 * so a two-hundred-and-fifty-bot roster was producing on the order of a hundred thousand
 * throwaway Strings a second purely to look things up - in a package that is otherwise careful
 * enough about allocation to share one spatial index and cache its command parses.</p>
 *
 * <p>A roster is a small, fixed set of names, so the mapping from a spelling to its key is worth
 * remembering. The lookup replaces the allocation, and returning the same instance every time
 * also gives the maps downstream an identity hit before they ever compare characters.</p>
 */
public final class Names {

    /**
     * Known spellings to their key.
     *
     * <p>Bounded, because the argument is not always a bot: {@code isBot} asks about every real
     * player and every target candidate, so an open server would otherwise grow this without end.
     * Past the cap the method still works, it simply stops remembering - the cost goes back to
     * what it was rather than becoming a leak.</p>
     */
    private static final Map<String, String> KEYS = new ConcurrentHashMap<>();

    /** Comfortably more than any roster plus the people watching it. */
    private static final int MAX_CACHED = 4096;

    private Names() {}

    public static String key(String botName) {
        if (botName == null) {
            return "";
        }
        String cached = KEYS.get(botName);
        if (cached != null) {
            return cached;
        }
        String key = botName.toLowerCase(Locale.ROOT);
        if (KEYS.size() < MAX_CACHED) {
            // Deliberately keyed by the spelling that was asked about rather than by the key, so a
            // name that is already lower case still short-circuits on the next call.
            KEYS.put(botName, key);
        }
        return key;
    }

    /** Drops the cache. Called when a world unloads, alongside the per-bot state it belongs to. */
    public static void clear() {
        KEYS.clear();
    }
}
