package scriptify.bots.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.TargetSelector;
import scriptify.bots.config.WorldConfigHelper;
import scriptify.bots.fixes.ProfileSeeder;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry of live bots and their persisted state.
 *
 * <p>Rewritten from the original for three reasons:</p>
 * <ul>
 *   <li>the restore-on-startup routine re-entered {@code server.execute()} in a
 *       self-scheduling loop to emulate a delay, effectively busy-spinning the server task
 *       queue once per bot per tick, with a nested retry loop on top of it. It is now a
 *       plain queue drained on a timer;</li>
 *   <li>{@link #getAllBots()} allocated a fresh {@link HashSet} on every call and the ticker
 *       called it every tick. {@link #botNames()} hands out an immutable snapshot that is
 *       only rebuilt when the roster actually changes;</li>
 *   <li>every spawn/removal path fired telemetry, which has been removed entirely.</li>
 * </ul>
 */
public class BotManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Set<String> bots = new HashSet<>();
    /** Lower-cased mirror of {@link #bots}, so case-insensitive membership is O(1). */
    private static final Set<String> botKeys = new HashSet<>();
    private static final Map<String, BotData> botDataMap = new HashMap<>();
    private static final Map<String, Integer> nullTickCount = new HashMap<>();
    /** Cleanup passes a bot that has already joined may go missing before it is dropped. */
    private static final int MAX_NULL_TICKS = 5;
    /**
     * Cleanup passes to wait for a freshly spawned bot to actually appear.
     *
     * <p>Spawning is not synchronous: the command returns, and the fake player joins some time
     * later - usually under a second, but several seconds is normal on a loaded server. The
     * cleanup pass runs twice a second and used to apply the same five-pass tolerance to a bot
     * that had never joined as to one that had vanished, so any spawn slower than about two
     * and a half seconds was struck off the roster moments before the player appeared. The bot
     * then stood in the world untracked: no target, no AI, nothing.</p>
     */
    private static final int MAX_JOIN_WAIT = 60;
    /** Bots whose player entity has been observed at least once. */
    private static final Set<String> everSeen = new HashSet<>();

    /** Immutable snapshot handed to per-tick iteration; rebuilt only when the set changes. */
    private static List<String> nameSnapshot = List.of();

    private static final Deque<BotData> restoreQueue = new ArrayDeque<>();
    private static int restoreDelay = 0;
    private static final int RESTORE_SPACING_TICKS = 10;

    /** Pending mass-spawn work, drained a few bots per tick. */
    private static int massSpawnRemaining = 0;
    private static int massSpawnDone = 0;
    private static int massSpawnTotal = 0;
    private static Vec3 massSpawnOrigin = null;
    private static CommandSourceStack massSpawnSource = null;
    /**
     * Ticks between spawns in a batch.
     *
     * <p>Spawning a fake player is a full player login, and most of its cost - chunk loading,
     * tab list and advancement sync - lands a moment *after* the command returns. Two per tick
     * therefore looked free to the tick-time governor below while quietly queueing two seconds
     * of work, which arrived all at once as a freeze. One every few ticks bounds the join rate
     * to something the server absorbs without a spike; a batch of twenty-four takes about four
     * seconds instead of one, and nothing stutters.</p>
     */
    private static final int MASS_SPAWN_SPACING_TICKS = 3;
    /** Bots placed per turn while the server is keeping up. */
    private static final int MASS_SPAWN_BATCH = 4;
    /** Above this smoothed tick time the batch slows down; it never stops. */
    private static final float BUSY_TICK_MS = 30.0f;
    /** Above this it slows further still, to one bot every twelve ticks. */
    private static final float HEAVY_TICK_MS = 45.0f;
    private static int massSpawnCooldown;
    private static int massSpawnDelay = 0;


    private static Path savePath;
    private static boolean initialized = false;

    public static class BotData {
        public String name;
        public double x, y, z;
        public float yaw, pitch;
        public String dimension;
        public String gamemode;

        public BotData() {}

        public BotData(ServerPlayer bot) {
            this.name = bot.getName().getString();
            this.x = bot.getX();
            this.y = bot.getY();
            this.z = bot.getZ();
            this.yaw = bot.getYRot();
            this.pitch = bot.getXRot();
            this.dimension = bot.level().dimension().identifier().toString();
            this.gamemode = bot.gameMode.getGameModeForPlayer().getName();
        }
    }

    // ===================== roster =====================

    private static void rosterChanged() {
        // The old snapshot is still in hand here, so the difference against the live set is exactly
        // what changed. Diffing at this one choke point rather than at the dozen add and remove sites
        // means a site added later cannot forget to report itself.
        List<String> before = nameSnapshot;
        // A stable order keeps command output and menus from appearing to reshuffle whenever the
        // hash table happens to resize. This is rebuilt only when the roster changes.
        nameSnapshot = bots.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        botKeys.clear();
        for (String name : bots) {
            botKeys.add(scriptify.bots.utils.Names.key(name));
        }
        scriptify.bots.api.SBotsApi.reportRoster(before, nameSnapshot);
    }

    /** Cheap, allocation-free-per-tick view of the roster. */
    public static List<String> botNames() {
        return nameSnapshot;
    }

    public static Set<String> getAllBots() {
        return new HashSet<>(bots);
    }

    public static int getBotCount() {
        return bots.size();
    }

    public static boolean isBot(String name) {
        if (name == null) return false;
        // A lowercased mirror of the roster, so this is a hash lookup rather than a scan.
        //
        // The backend resolves names against real Mojang profiles, so a bot asked for as
        // "TestBeta" can join as "Testbeta" - the check has to be case-insensitive. It used to
        // do that by walking the whole roster on every miss, and a miss is every real player,
        // every tick, plus every candidate in a target sweep. At two hundred and fifty bots
        // that is tens of thousands of string comparisons a second for a question a set can
        // answer instantly.
        if (bots.contains(name)) return true;
        return botKeys.contains(scriptify.bots.utils.Names.key(name));
    }


    /** The roster spelling for a name, or the input when it is not a tracked bot. */
    /**
     * Whether this player is one of the backend's fake players, by class rather than by name.
     *
     * <p>For the hot paths that ask about every pair of players every tick (entity tracking, the
     * locator bar). A class compare costs nothing; the roster lookup builds the name string first.</p>
     */
    public static boolean isFakePlayer(ServerPlayer player) {
        Class<?> type = player.getClass();
        return type != ServerPlayer.class && type.getName().startsWith("hero.bane.herobot");
    }

    public static String canonical(String name) {
        if (bots.contains(name)) return name;
        for (String tracked : bots) {
            if (tracked.equalsIgnoreCase(name)) return tracked;
        }
        return name;
    }

    public static ServerPlayer getBot(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayer(name);
    }

    private static void forget(String name) {
        bots.remove(name);
        botDataMap.remove(name);
        nullTickCount.remove(name);
        everSeen.remove(name);
        BotCombat.removeState(name);
        BotUtils.removeState(name);
        BotNavigation.removeState(name);
        BotProfile.remove(name);
        AimController.remove(name);
        TargetSelector.remove(name);
        scriptify.bots.ai.Maneuver.remove(name);
        scriptify.bots.ai.Crouch.remove(name);
        scriptify.bots.npc.NpcApplier.forget(name);
        scriptify.bots.ai.Bridger.remove(name);
        scriptify.bots.ai.LoadGovernor.forget(name);
        scriptify.bots.ai.PitEscape.remove(name);
        scriptify.bots.ai.WaterClutch.remove(name);
        BotActions.forget(name);
        rosterChanged();
    }

    // ===================== lifecycle =====================

    public static void init(MinecraftServer server) {
        if (initialized) return;

        // Each world load builds a fresh profile cache, so the record of what has already been
        // seeded has to go with it - otherwise the second world of a session skips seeding and
        // every spawn goes back to hitting the network.
        ProfileSeeder.reset();

        bots.clear();
        botDataMap.clear();
        nullTickCount.clear();
        everSeen.clear();
        restoreQueue.clear();
        restoreDelay = 0;
        clearMassSpawn();
        rosterChanged();

        savePath = WorldConfigHelper.getWorldConfigDir().resolve("bots.json");
        loadBots();

        BotSettings settings = BotSettings.get();
        if (settings.isBotsRelogs() && !botDataMap.isEmpty()) {
            List<BotData> pending = new ArrayList<>(botDataMap.values());
            bots.clear();
            botDataMap.clear();
            rosterChanged();
            restoreQueue.addAll(pending);
            for (BotData data : pending) {
                ProfileSeeder.seed(server, data.name);
            }
            scriptify.bots.BotRuntime.LOGGER.info("Restoring {} bots", restoreQueue.size());
        } else if (!settings.isBotsRelogs()) {
            bots.clear();
            botDataMap.clear();
            rosterChanged();
            saveBots();
            flushBots();
        }

        initialized = true;
    }

    /**
     * Queues a mass spawn. The original recursed through {@code server.execute()} to fake a
     * delay, which drains into the same tick and so spawned every bot at once anyway while
     * allocating a Runnable per step; this spreads the work over real ticks.
     */
    /**
     * Queues a mass spawn, adding to any batch already draining.
     *
     * <p>This used to assign rather than add, and the difference was not academic: a second
     * mass-spawn issued while the first was still working threw away whatever was left of it. Two
     * back-to-back requests for fifty bots produced fifty, or a hundred and eighty-three, depending
     * entirely on how far the first had got - and nothing said which had happened.
     *
     * <p>The origin and source are taken from the most recent request, because that is the one the
     * operator is standing at and watching.</p>
     */
    public static void queueMassSpawn(MinecraftServer server, int count, Vec3 origin,
                                      CommandSourceStack source) {
        massSpawnRemaining += count;
        massSpawnTotal += count;
        massSpawnOrigin = origin;
        massSpawnSource = source;
        massSpawnDelay = 0;
    }

    private static void tickMassSpawn(MinecraftServer server) {
        if (massSpawnRemaining <= 0) return;

        // Spawning a fake player is a full player login: chunk loads, tab list updates,
        // advancement and recipe sync. There is no making that cheap, so the batch is paced
        // against how the server is actually coping.
        //
        // Paced, never stopped. This used to return outright above a tick time of 45ms, which
        // sounds prudent and is not: a few hundred bots hold the smoothed tick time above that
        // permanently, so a batch of five hundred silently gave up at around two hundred and only
        // resumed when somebody killed enough bots to bring the tick time down. A spawn that stops
        // and waits for you to undo your own spawn is worse than a slow one, so the heaviest case
        // still places a bot - just rarely.
        float lastTickMs = server.getCurrentSmoothedTickTime();
        int perTick;
        if (lastTickMs > HEAVY_TICK_MS) {
            massSpawnDelay = MASS_SPAWN_SPACING_TICKS * 4;
            perTick = 1;
        } else if (lastTickMs > BUSY_TICK_MS) {
            massSpawnDelay = MASS_SPAWN_SPACING_TICKS * 2;
            perTick = 1;
        } else {
            massSpawnDelay = MASS_SPAWN_SPACING_TICKS;
            perTick = MASS_SPAWN_BATCH;
        }

        if (massSpawnDelay > 0 && massSpawnCooldown > 0) {
            massSpawnCooldown--;
            return;
        }
        massSpawnCooldown = massSpawnDelay;

        for (int i = 0; i < perTick && massSpawnRemaining > 0; i++) {
            massSpawnRemaining--;
            String name = BotNameGenerator.generateUniqueName();

            // Always spread a batch out. This used to be gated on the "safe spawn" setting,
            // which meant turning that off stacked every bot on one block - never what anyone
            // wants from a mass spawn, and it makes them shove each other apart for the next
            // few seconds. The setting still controls how wide the ring gets.
            Vec3 pos = massSpawnOrigin;
            if (pos != null) {
                // Spread over a ring that grows with the batch size, so a large spawn does
                // not stack every bot in one block and trigger entity cramming.
                int index = massSpawnDone;
                double spread = BotSettings.get().isSafeSpawn() ? 0.8 : 0.45;
                double radius = 1.0 + Math.sqrt(index) * spread;
                double angle = index * 2.399963;   // golden angle: even, non-repeating spread
                pos = new Vec3(
                    massSpawnOrigin.x + Math.cos(angle) * radius,
                    massSpawnOrigin.y,
                    massSpawnOrigin.z + Math.sin(angle) * radius);
            }

            if (spawnBot(server, name, massSpawnSource, pos, false)) {
                massSpawnDone++;
            }
        }

        if (massSpawnRemaining <= 0 && massSpawnSource != null) {
            int done = massSpawnDone;
            int total = massSpawnTotal;
            massSpawnSource.sendSuccess(
                () -> net.minecraft.network.chat.Component.literal("Spawned " + done + " of " + total + " bots."),
                true);
            massSpawnSource = null;
            massSpawnOrigin = null;
            // The batch is finished, so its tallies go back to zero. Without this the counters
            // carry into the next spawn and "Spawned 50 of 150" reports two batches ago as well
            // as this one - which matters now that a queued spawn adds to the batch rather than
            // replacing it.
            massSpawnDone = 0;
            massSpawnTotal = 0;
        }
    }

    /** Drains the restore queue, one bot every {@link #RESTORE_SPACING_TICKS} ticks. */
    public static void tickRestore(MinecraftServer server) {
        tickMassSpawn(server);
        if (restoreQueue.isEmpty()) return;
        if (restoreDelay > 0) {
            restoreDelay--;
            return;
        }
        restoreDelay = RESTORE_SPACING_TICKS;

        BotData data = restoreQueue.poll();
        if (data == null || data.name == null) return;

        ServerPlayer existing = server.getPlayerList().getPlayer(data.name);
        if (existing != null) {
            scriptify.bots.BotRuntime.LOGGER.debug("Skipping {}: already online", data.name);
            return;
        }

        ProfileSeeder.seed(server, data.name);

        String command = String.format(Locale.US,
            "playerspawn %s at %.2f %.2f %.2f facing %.2f %.2f in %s on %s",
            data.name, data.x, data.y, data.z, data.yaw, data.pitch,
            data.gamemode != null ? data.gamemode : "survival",
            data.dimension);

        boolean ok = BotActions.execute(server, command);
        if (!ok) {
            ok = BotActions.execute(server, String.format(Locale.US,
                "playerspawn %s at %.2f %.2f %.2f", data.name, data.x, data.y, data.z));
        }

        if (ok) {
            bots.add(data.name);
            botDataMap.put(data.name, data);
            rosterChanged();
            ServerPlayer joined = server.getPlayerList().getPlayer(data.name);
            if (joined != null) adoptActualName(data.name, joined);
            saveBots();
        } else {
            scriptify.bots.BotRuntime.LOGGER.warn("Failed to restore bot {} - is HeroBot installed?", data.name);
        }
    }

    public static void reset(MinecraftServer server) {
        updateBotData(server);
        saveBots();
        flushBots();
        BotSettings.flush();

        bots.clear();
        botDataMap.clear();
        nullTickCount.clear();
        everSeen.clear();
        restoreQueue.clear();
        restoreDelay = 0;
        clearMassSpawn();
        rosterChanged();
        // A failed final flush has already been logged. Never carry that dirty state into a
        // different world, where it could overwrite the new world's roster on its first tick.
        rosterDirty = false;
        savePath = null;
        initialized = false;
    }

    public static void reloadBots() {
        bots.clear();
        botDataMap.clear();
        nullTickCount.clear();
        everSeen.clear();
        restoreQueue.clear();
        restoreDelay = 0;
        clearMassSpawn();
        rosterChanged();
        savePath = WorldConfigHelper.getWorldConfigDir().resolve("bots.json");
        loadBots();
    }

    private static void clearMassSpawn() {
        massSpawnRemaining = 0;
        massSpawnDone = 0;
        massSpawnTotal = 0;
        massSpawnOrigin = null;
        massSpawnSource = null;
        massSpawnCooldown = 0;
        massSpawnDelay = 0;
    }

    // ===================== spawn / remove =====================

    public static boolean spawnBot(MinecraftServer server, String name, CommandSourceStack source) {
        return spawnBot(server, name, source, null, true);
    }

    public static boolean spawnBot(MinecraftServer server, String name, CommandSourceStack source, Vec3 pos) {
        return spawnBot(server, name, source, pos, true);
    }

    /**
     * @param operatorNamed retained for call-site clarity; spawning no longer behaves
     *                      differently for generated names now that seeding is free
     */
    public static boolean spawnBot(MinecraftServer server, String name, CommandSourceStack source,
                                   Vec3 pos, boolean operatorNamed) {
        ServerPlayer existing = server.getPlayerList().getPlayer(name);
        if (existing != null && existing.isAlive()) {
            if (bots.add(name)) {
                botDataMap.put(name, new BotData(existing));
                rosterChanged();
                saveBots();
            }
            return false;
        }

        // The backend resolves the name to a UUID while spawning, and vanilla resolves a cache
        // miss by calling Mojang on the server thread. Seeding the cache first means that call
        // never happens - and it is a single map insert, so there is nothing to wait for.
        ProfileSeeder.seed(server, name);

        return performSpawn(server, name, source, pos);
    }

    private static boolean performSpawn(MinecraftServer server, String name,
                                        CommandSourceStack source, Vec3 pos) {
        // Never use "~ ~ ~" here. Commands are dispatched through a cached *server* source so
        // they stay out of the operator broadcast, and the server source sits at world spawn -
        // so a relative position put every bot at world spawn no matter where the player was
        // standing. The position, facing and dimension are resolved from the caller instead.
        Vec3 where = pos != null ? pos : (source != null ? source.getPosition() : Vec3.ZERO);
        float yaw = source != null ? source.getRotation().y : 0.0f;
        String dimension = source != null
            ? source.getLevel().dimension().identifier().toString()
            : Level.OVERWORLD.identifier().toString();

        String coords = String.format(Locale.US, "%.2f %.2f %.2f", where.x, where.y, where.z);
        boolean ok = BotActions.execute(server, String.format(Locale.US,
            "playerspawn %s at %s facing %.2f 0 in survival on %s", name, coords, yaw, dimension));
        if (!ok) {
            // Older backends may not accept the dimension clause.
            ok = BotActions.execute(server,
                "playerspawn " + name + " at " + coords + " facing 0 0 in survival");
        }
        if (!ok) {
            ok = BotActions.execute(server, "playerspawn " + name);
            if (ok) BotActions.execute(server, "gamemode survival " + name);
        }
        if (!ok) return false;

        bots.add(name);
        rosterChanged();

        ServerPlayer spawned = server.getPlayerList().getPlayer(name);
        if (spawned != null) {
            name = adoptActualName(name, spawned);
            botDataMap.put(name, new BotData(spawned));
        } else {
            BotData fallback = new BotData();
            fallback.name = name;
            Vec3 p = pos != null ? pos : source.getPosition();
            fallback.x = p.x;
            fallback.y = p.y;
            fallback.z = p.z;
            fallback.yaw = source.getRotation().y;
            fallback.pitch = source.getRotation().x;
            fallback.dimension = source.getLevel().dimension().identifier().toString();
            fallback.gamemode = "survival";
            botDataMap.put(name, fallback);

            // The backend may add the player later in the tick; refresh once it exists.
            final String requested = name;
            server.execute(() -> {
                ServerPlayer late = server.getPlayerList().getPlayer(requested);
                if (late != null) {
                    String actual = adoptActualName(requested, late);
                    botDataMap.put(actual, new BotData(late));
                    saveBots();
                }
            });
        }

        saveBots();
        return true;
    }

    /**
     * Re-keys a bot to whatever name it actually joined under. HeroBot resolves names
     * against real Mojang profiles, so the capitalisation it ends up with is not always the
     * capitalisation that was asked for; if the roster kept the requested spelling, every
     * per-bot map keyed off {@code entity.getName()} would end up with a second, orphaned
     * entry that never gets cleaned up.
     */
    private static String adoptActualName(String requested, ServerPlayer player) {
        String actual = player.getName().getString();
        if (actual.equals(requested)) return requested;

        bots.remove(requested);
        BotData data = botDataMap.remove(requested);
        bots.add(actual);
        if (data != null) {
            data.name = actual;
            botDataMap.put(actual, data);
        }
        rosterChanged();
        return actual;
    }

    public static boolean removeBot(MinecraftServer server, String name, CommandSourceStack source) {
        name = canonical(name);
        boolean wasTracked = bots.contains(name);
        forget(name);
        BotPath.stopFollowing(name);
        saveBots();

        if (BotSettings.get().isClearOnRemove()) {
            BotActions.execute(server, "clear " + name);
        }
        BotActions.kill(name, server);
        return wasTracked;
    }

    public static int removeAllBots(MinecraftServer server, CommandSourceStack source) {
        boolean clear = BotSettings.get().isClearOnRemove();
        int removed = bots.size();
        for (String name : new ArrayList<>(bots)) {
            if (clear) BotActions.execute(server, "clear " + name);
            BotActions.kill(name, server);
            BotPath.stopFollowing(name);
            forget(name);
        }
        bots.clear();
        botDataMap.clear();
        rosterChanged();
        saveBots();
        return removed;
    }

    /** Drops bots whose player entity is gone or dead. */
    public static void cleanupDeadBots(MinecraftServer server) {
        boolean changed = false;

        // Build a name to entity map once. getPlayer(name) is a case-insensitive scan of every
        // online player, so calling it per bot made this quadratic - at two hundred and fifty
        // bots, sixty thousand string comparisons twice a second to answer a question one pass
        // over the list already answers.
        Map<String, ServerPlayer> online = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online.put(scriptify.bots.utils.Names.key(p.getName().getString()), p);
        }

        for (String name : new ArrayList<>(bots)) {
            ServerPlayer bot = online.get(scriptify.bots.utils.Names.key(name));

            if (bot == null) {
                // Tolerate a few passes of absence: the player object briefly disappears
                // during dimension changes and respawns, and a freshly spawned bot has not
                // arrived yet at all.
                int ticks = nullTickCount.merge(name, 1, Integer::sum);
                int limit = everSeen.contains(name) ? MAX_NULL_TICKS : MAX_JOIN_WAIT;
                if (ticks >= limit) {
                    forget(name);
                    changed = true;
                    scriptify.bots.BotRuntime.LOGGER.debug("Removed bot (entity gone): {}", name);
                }
                continue;
            }

            everSeen.add(name);
            nullTickCount.remove(name);
            if (!bot.isAlive() || bot.getHealth() <= 0) {
                forget(name);
                BotActions.kill(name, server);
                changed = true;
            }
        }
        if (changed) saveBots();
    }

    // ===================== persistence =====================

    public static void updateBotData(MinecraftServer server) {
        for (String name : bots) {
            ServerPlayer bot = server.getPlayerList().getPlayer(name);
            if (bot != null && bot.isAlive()) {
                botDataMap.put(name, new BotData(bot));
            }
        }
    }

    private static boolean rosterDirty = false;

    /**
     * Marks the roster for saving instead of writing immediately.
     *
     * <p>This used to serialise the entire roster to disk on every single spawn, removal and
     * cleanup pass - twice per spawn, in fact. A thirty-bot mass spawn therefore performed
     * sixty full JSON writes on the server thread inside a few seconds, which was the single
     * largest avoidable cost of spawning a batch. The ticker flushes at most twice a second,
     * and shutdown flushes unconditionally, so nothing is lost.</p>
     */
    public static void saveBots() {
        rosterDirty = true;
    }

    public static void flushBots() {
        if (!rosterDirty) return;
        // Keep the dirty flag until the replacement succeeds. Clearing it before an I/O failure
        // meant the next scheduled flush did nothing and the most recent roster could be lost.
        if (writeBots()) {
            rosterDirty = false;
        }
    }

    private static boolean writeBots() {
        if (savePath == null) return false;
        return scriptify.storage.JsonFile.write(GSON, savePath, botDataMap,
            scriptify.bots.BotRuntime.LOGGER, "bot roster");
    }

    private static void loadBots() {
        if (savePath == null || !Files.exists(savePath)) return;
        try (Reader reader = Files.newBufferedReader(savePath)) {
            Map<String, BotData> loaded = GSON.fromJson(reader,
                new TypeToken<Map<String, BotData>>() {}.getType());
            if (loaded != null) {
                loaded.forEach((name, data) -> {
                    if (data != null) {
                        if (data.name == null) data.name = name;
                        botDataMap.put(name, data);
                        bots.add(name);
                    }
                });
                rosterChanged();
            }
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.error("Failed to load bots: {}", e.getMessage());
        }
    }

    public static Map<String, BotData> snapshot() {
        return Collections.unmodifiableMap(botDataMap);
    }
}
