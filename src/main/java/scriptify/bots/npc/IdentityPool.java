package scriptify.bots.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import scriptify.bots.BotRuntime;
import scriptify.bots.config.WorldConfigHelper;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A local supply of name and skin pairs, for handing bots an appearance without a network call.
 *
 * <p>The requirement this exists to meet is blunt: two hundred and fifty bots randomising at once
 * must not touch the network at all. A per-bot Mojang lookup would be two hundred and fifty round
 * trips, and doing them on the server thread is the failure
 * {@link scriptify.bots.fixes.ProfileSeeder} was written to stop happening again.</p>
 *
 * <p>So the pool is a file. It is read once at startup and drawn from in memory afterwards.
 * Filling it is a deliberate, occasional act - paste in blobs you already hold, or let a mod that
 * does skin lookups populate it - rather than something that happens during a spawn.</p>
 *
 * <p>An empty pool is a supported state, not an error. It means bots wear the default skin, which
 * is exactly what happens on a server with no internet, and nothing about that path blocks or
 * throws.</p>
 */
public final class IdentityPool {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "identity-pool.json";

    private static final List<Entry> POOL = new ArrayList<>();
    private static Path path;

    private IdentityPool() {}

    /**
     * One appearance: a display name and the signed skin that goes with it.
     *
     * <p>Name and skin travel together on purpose. A random name paired with an unrelated random
     * skin reads as two separate glitches rather than as a person.</p>
     */
    public static class Entry {
        public String name;
        public String value;
        public String signature;

        public Entry() {}

        public Entry(String name, String value, String signature) {
            this.name = name;
            this.value = value;
            this.signature = signature;
        }

        boolean usable() {
            return value != null && !value.isBlank()
                && signature != null && !signature.isBlank();
        }
    }

    public static void init(MinecraftServer server) {
        POOL.clear();
        path = WorldConfigHelper.getWorldConfigDir().resolve(FILE);
        load();
    }

    public static void shutdown() {
        POOL.clear();
        path = null;
    }

    private static void load() {
        if (path == null || !Files.exists(path)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            List<Entry> read = GSON.fromJson(reader, new TypeToken<List<Entry>>() {}.getType());
            if (read == null) return;
            for (Entry entry : read) {
                if (entry != null && entry.usable()) POOL.add(entry);
            }
            BotRuntime.LOGGER.info("Identity pool: {} usable appearance(s).", POOL.size());
        } catch (Exception e) {
            BotRuntime.LOGGER.warn("Could not read {}; bots will keep default skins.", FILE, e);
        }
    }

    private static void save() {
        if (path == null) return;
        scriptify.storage.JsonFile.write(GSON, path, POOL, BotRuntime.LOGGER, "identity pool");
    }

    /** How many appearances are available. Zero is normal on a fresh install. */
    public static int size() {
        return POOL.size();
    }

    public static List<Entry> all() {
        return Collections.unmodifiableList(POOL);
    }

    /**
     * Adds an appearance and writes the file.
     *
     * <p>This is the seam a skin-fetching mod plugs into: it does the network work on its own
     * terms, off the server thread, and hands the result here once. From then on the blob is
     * local and free to reuse.</p>
     */
    public static boolean add(String name, String value, String signature) {
        Entry entry = new Entry(name, value, signature);
        if (!entry.usable()) return false;
        POOL.add(entry);
        save();
        return true;
    }

    /** A random appearance, or {@code null} when the pool is empty. */
    public static Entry random() {
        if (POOL.isEmpty()) return null;
        return POOL.get(ThreadLocalRandom.current().nextInt(POOL.size()));
    }
}
