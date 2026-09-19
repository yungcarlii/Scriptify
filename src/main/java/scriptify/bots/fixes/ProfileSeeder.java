package scriptify.bots.fixes;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps bot spawning off the network.
 *
 * <p>The fake-player backend resolves a name to a UUID while spawning, and vanilla resolves a
 * cache miss by calling Mojang <em>synchronously</em>:</p>
 *
 * <pre>PlayerSpawnCommand.spawn -&gt; ServerConfigHandler.getPlayerUuidByName
 *   -&gt; UserCache.findByName -&gt; YggdrasilGameProfileRepository.findProfileByName -&gt; HTTP</pre>
 *
 * <p>That runs on the server thread, and {@code findByName} makes the call whether or not the
 * world is singleplayer - the offline-mode shortcut sits further down, past the lookup. One
 * uncached name is a stutter; a batch of thirty is thirty round trips back to back. Seeding an
 * offline entry first means the lookup always hits, so the call never happens.</p>
 *
 * <p>No threads, no network, no configuration: one map insert per name, once.</p>
 *
 * <p><b>Do not reintroduce reflection here.</b> This used to look the setter up by name with
 * {@code cache.getClass().getMethod("add", ...)}, which worked in a development run and did
 * nothing in a real installation: a built mod is remapped to intermediary, where the method is
 * {@code method_14508}, but a string literal is not remapped. The failure was swallowed, so
 * every spawn quietly went back to hitting Mojang - a mass spawn cost the server four and a
 * half seconds. {@link UserNameToIdResolver} is a public interface, so the call below is remapped
 * correctly and checked by the compiler.</p>
 */
public final class ProfileSeeder {

    private ProfileSeeder() {}

    private static final Set<String> SEEDED = new HashSet<>();
    /** Set once if seeding ever throws, so the failure is reported rather than repeated silently. */
    private static boolean SEED_FAILED;

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Makes sure spawning this name cannot trigger a network lookup. Cheap enough to call
     * unconditionally; each name is only seeded once per session, which also keeps the cache
     * from rewriting {@code usercache.json} on every spawn.
     */
    public static void seed(MinecraftServer server, String name) {
        if (server == null || name == null || name.isEmpty()) return;
        if (!SEEDED.add(name.toLowerCase(Locale.ROOT))) return;

        try {
            UserNameToIdResolver cache = server.services().nameToIdCache();
            if (cache == null) return;
            cache.add(new NameAndId(offlineUuid(name), name));
        } catch (RuntimeException | LinkageError e) {
            // Logged, not swallowed. A silent failure here is invisible and expensive: every spawn
            // quietly goes back to asking Mojang, which is the whole thing this class exists to
            // stop, and at a few hundred bots Mojang starts answering 429.
            SEED_FAILED = true;
            scriptify.bots.BotRuntime.LOGGER.error("Profile seeding failed for '{}' - "
                + "spawning will fall back to network lookups", name, e);
        }
    }

    public static void reset() {
        SEEDED.clear();
        SEED_FAILED = false;
    }

    /** Whether seeding has failed this session, in which case spawns are hitting the network. */
    public static boolean hasFailed() {
        return SEED_FAILED;
    }
}
