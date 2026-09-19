package scriptify.bots.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.BotRuntime;
import scriptify.bots.ai.TargetSelector;
import scriptify.bots.bot.BotActions;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotPath;
import scriptify.bots.npc.BotIdentity;
import scriptify.bots.npc.IdentityPool;
import scriptify.bots.npc.NpcRegistry;
import scriptify.bots.npc.NpcSettings;
import scriptify.bots.scenario.ScenarioInstance;
import scriptify.bots.scenario.ScenarioManager;
import scriptify.bots.utils.Names;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The supported way to drive SBots from another mod.
 *
 * <p>Everything else in this mod is internal and moves without warning. This does not. A consumer
 * that reaches past this facade into {@code BotManager} or {@code NpcRegistry} will break on an
 * ordinary refactor, and that is not a bug in the refactor.</p>
 *
 * <h2>Why the signatures look like this</h2>
 *
 * <p>No Minecraft type appears anywhere in this class's public surface - names are strings,
 * positions are doubles, items are identifiers. SBots is built against Yarn mappings and a
 * consumer may not be; a method taking {@code ServerPlayer} cannot be called from a mod that
 * knows the class by a different name unless the whole artifact is remapped in lockstep. Plain
 * Java crosses that boundary unchanged, which keeps the two builds independent and rules out
 * name-based reflection at the seam - the failure mode that has already cost this codebase two
 * silent production breakages.</p>
 *
 * <h2>Contract</h2>
 *
 * <p><b>Nothing here throws into the caller.</b> Consumers run user-written scripts, and a script
 * asking about a bot that died a tick ago must get {@code null} or {@code false}, not an exception
 * that takes down whatever was running it. Every method is null-safe on its arguments and answers
 * with an empty or negative result when it cannot do the job.</p>
 *
 * <p><b>Server thread only.</b> These calls read and write live game state. None of them block:
 * there is no I/O on any path here, deliberately, because a network lookup inside a bot operation
 * is the mistake {@link scriptify.bots.fixes.ProfileSeeder} documents.</p>
 *
 * <p><b>Names are case-insensitive.</b> The backend may join a bot under different capitalisation
 * than was asked for, so every lookup here normalises through {@link Names#key}.</p>
 */
public final class SBotsApi {

    /**
     * Listeners are copy-on-write because they are added rarely and walked on every roster change,
     * and because a listener that removes itself while being notified would otherwise corrupt the
     * iteration.
     */
    private static final List<BotListener> LISTENERS = new CopyOnWriteArrayList<>();

    private static MinecraftServer server;

    private SBotsApi() {}

    // ===================== lifecycle =====================

    /** Called by {@link BotRuntime} when the server comes up. Not for consumers. */
    public static void bind(MinecraftServer running) {
        server = running;
    }

    /** Called by {@link BotRuntime} on shutdown. Not for consumers. */
    public static void unbind() {
        server = null;
        LISTENERS.clear();
    }

    /**
     * Whether the API can currently answer anything.
     *
     * <p>False before the server finishes starting and after it stops. Worth checking once rather
     * than interpreting a pile of empty results as "there are no bots".</p>
     */
    public static boolean isAvailable() {
        return server != null;
    }

    // ===================== roster =====================

    /** Every bot on the roster, in roster spelling. Never null. */
    public static List<String> names() {
        if (server == null) return List.of();
        return BotManager.botNames();
    }

    public static int count() {
        return server == null ? 0 : BotManager.getBotCount();
    }

    public static boolean isBot(String name) {
        return server != null && name != null && BotManager.isBot(name);
    }

    /**
     * Spawns a bot, optionally at a position.
     *
     * <p>Pass {@code dimension} as null to use the overworld. A null position spawns wherever the
     * backend defaults to.</p>
     *
     * @return false when the server is down, the name is unusable, or the backend refused
     */
    public static boolean spawn(String name, Double x, Double y, Double z) {
        if (server == null || name == null || name.isBlank()) return false;
        try {
            Vec3 pos = (x == null || y == null || z == null) ? null : new Vec3(x, y, z);
            return BotManager.spawnBot(server, name, BotActions.source(server), pos);
        } catch (RuntimeException | LinkageError e) {
            BotRuntime.LOGGER.warn("spawn({}) failed", name, e);
            return false;
        }
    }

    public static boolean remove(String name) {
        if (server == null || name == null) return false;
        try {
            return BotManager.removeBot(server, name, BotActions.source(server));
        } catch (RuntimeException | LinkageError e) {
            BotRuntime.LOGGER.warn("remove({}) failed", name, e);
            return false;
        }
    }

    // ===================== state =====================

    /**
     * Everything readable about one bot, or {@code null} when it is not on the server.
     *
     * <p>Null is the normal answer for a bot that has just died, and callers are expected to hit
     * it routinely rather than treat it as exceptional.</p>
     */
    public static BotSnapshot state(String name) {
        ServerPlayer bot = resolve(name);
        return bot == null ? null : snapshot(bot);
    }

    /**
     * Snapshots of every bot, in one pass over the player list.
     *
     * <p>The single pass is the point. Resolving each name individually goes through
     * {@code PlayerManager.getPlayer}, which is a linear scan of everyone online - doing that once
     * per bot is quadratic in the roster, and the target is two hundred and fifty bots in one
     * arena. This builds a name index once and reads straight out of it.</p>
     */
    public static List<BotSnapshot> states() {
        if (server == null) return List.of();
        Map<String, ServerPlayer> online = index();
        List<BotSnapshot> out = new ArrayList<>();
        for (String name : BotManager.botNames()) {
            ServerPlayer bot = online.get(Names.key(name));
            if (bot != null) out.add(snapshot(bot));
        }
        return out;
    }

    // ===================== actions =====================

    /** Swings once at whatever the bot is looking at. */
    public static boolean attack(String name) {
        return act(name, BotActions::attack);
    }

    /** One right-click. */
    public static boolean use(String name) {
        return act(name, BotActions::useOnce);
    }

    /** Places the held block. */
    public static boolean place(String name) {
        return act(name, BotActions::place);
    }

    public static boolean jump(String name) {
        return act(name, BotActions::jump);
    }

    /** Swings the arm without an attack, for gestures. */
    public static boolean swing(String name) {
        return act(name, BotActions::swing);
    }

    /**
     * Crouch is a hold, not a toggle: something has to keep asking or it is released. Passing true
     * once will not keep a bot crouched - use the {@code crouching} NPC setting for that.
     */
    public static boolean sneak(String name, boolean sneaking) {
        return act(name, sneaking ? BotActions::sneak : BotActions::unsneak);
    }

    /** Drops the held stack, by running the bot's own drop command. */
    public static boolean drop(String name) {
        if (server == null || !isBot(name)) return false;
        return command("player " + BotManager.canonical(name) + " drop");
    }

    /** Kills a bot outright. */
    public static boolean kill(String name) {
        if (server == null || !isBot(name)) return false;
        try {
            BotActions.kill(BotManager.canonical(name), server);
            return true;
        } catch (RuntimeException | LinkageError e) {
            BotRuntime.LOGGER.warn("kill({}) failed", name, e);
            return false;
        }
    }

    /**
     * Runs a raw server command as the bot backend.
     *
     * <p>The escape hatch for things this facade does not cover. It is unversioned by nature - the
     * command strings belong to the backend, not to SBots - so prefer a named method where one
     * exists.</p>
     */
    public static boolean command(String command) {
        if (server == null || command == null || command.isBlank()) return false;
        try {
            return BotActions.execute(server, command);
        } catch (RuntimeException | LinkageError e) {
            BotRuntime.LOGGER.warn("command({}) failed", command, e);
            return false;
        }
    }

    // ===================== npc settings =====================

    /**
     * Sets one NPC setting on a bot, its group, or the defaults.
     *
     * <p>Scope is {@code "bot"}, {@code "group"} or {@code "default"}. Keys are the
     * {@link NpcSettings} field names: {@code hittable}, {@code collidable}, {@code crouching},
     * {@code listed}, {@code immortal}, {@code dropsOnDeath}, {@code action},
     * {@code kit}.</p>
     *
     * <p>Values are parsed loosely - {@code "true"}, {@code "swing"} - because the caller is
     * usually a script or a config file, where a typo should mean "no" rather than a crash.</p>
     *
     * @return false when the scope, key or value was not understood
     */
    public static boolean setSetting(String scope, String target, String key, String value) {
        if (server == null || key == null) return false;
        try {
            NpcSettings settings = settingsFor(scope, target);
            if (settings == null) return false;
            if (!assign(settings, key, value)) return false;
            NpcRegistry.markDirty();
            return true;
        } catch (RuntimeException | LinkageError e) {
            BotRuntime.LOGGER.warn("setSetting({}, {}, {}) failed", scope, key, value, e);
            return false;
        }
    }

    /** What a bot actually is after defaults, group and its own overrides are layered. */
    public static Map<String, String> settings(String botName) {
        if (server == null || botName == null) return Map.of();
        try {
            NpcSettings s = NpcRegistry.resolve(botName);
            Map<String, String> out = new HashMap<>();
            out.put("hittable", String.valueOf(s.hittable()));
            out.put("collidable", String.valueOf(s.collidable()));
            out.put("crouching", String.valueOf(s.crouching()));
            out.put("listed", String.valueOf(s.listed()));
            out.put("immortal", String.valueOf(s.immortal()));
            out.put("dropsOnDeath", String.valueOf(s.dropsOnDeath()));
            out.put("action", s.action().name().toLowerCase(Locale.ROOT));
            out.put("kit", s.kit == null ? "" : s.kit);
            return out;
        } catch (RuntimeException | LinkageError e) {
            return Map.of();
        }
    }

    public static boolean addToGroup(String groupName, String botName) {
        if (server == null || groupName == null || botName == null) return false;
        try {
            NpcRegistry.createGroup(groupName);
            NpcRegistry.addToGroup(groupName, botName);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean removeFromGroup(String botName) {
        if (server == null || botName == null) return false;
        try {
            NpcRegistry.removeFromGroup(botName);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ===================== identity =====================

    /**
     * Puts a signed skin on a bot.
     *
     * <p>Instant and local: the blob is copied onto the profile and the clients are told to rebuild
     * the player. Both halves are required - a texture without its signature renders as the default
     * skin, so half a skin is indistinguishable from a silent failure.</p>
     *
     * <p>There is deliberately no "fetch this username's skin" method here. Resolving a name means
     * an HTTP call, and this facade guarantees it never blocks the server thread. A consumer that
     * wants lookups does them on its own thread and calls this once it has the blob.</p>
     */
    public static boolean setSkin(String botName, String textureValue, String signature) {
        ServerPlayer bot = resolve(botName);
        if (bot == null) return false;
        return BotIdentity.apply(bot, server, new BotIdentity.Skin(textureValue, signature));
    }

    /**
     * Gives a bot a random appearance from the local pool.
     *
     * <p>No network, by construction - two hundred and fifty bots randomising at once must not
     * produce two hundred and fifty requests. An empty pool means this returns false and the bot
     * keeps its default skin, which is the correct behaviour on a server with no internet.</p>
     */
    public static boolean randomiseSkin(String botName) {
        ServerPlayer bot = resolve(botName);
        if (bot == null) return false;
        IdentityPool.Entry entry = IdentityPool.random();
        if (entry == null) return false;
        return BotIdentity.apply(bot, server, new BotIdentity.Skin(entry.value, entry.signature));
    }

    /**
     * Adds an appearance to the local pool.
     *
     * <p>The seam for a mod that does its own skin lookups: it fetches off-thread on its own terms
     * and deposits the result here, after which the blob is local and free to reuse.</p>
     */
    public static boolean addSkinToPool(String name, String textureValue, String signature) {
        return IdentityPool.add(name, textureValue, signature);
    }

    public static int skinPoolSize() {
        return IdentityPool.size();
    }

    /**
     * Rebuilds a bot for every client.
     *
     * <p>Needed after changing anything a client snapshots when it first sees a player - the name
     * on the profile, or the skin. Consumers with their own identity systems call this once they
     * have changed the profile.</p>
     */
    public static boolean refresh(String botName) {
        ServerPlayer bot = resolve(botName);
        if (bot == null) return false;
        BotIdentity.refresh(bot, server);
        return true;
    }

    /**
     * A bot's UUID as a string, or {@code null} when it is not online.
     *
     * <p>The hook for a consumer that already has its own nickname or skin machinery: bots are
     * ordinary {@code ServerPlayer} objects in the player list, so anything that works on a
     * player by UUID works on a bot. Renaming is not offered here because the name is fixed at join
     * time by the backend and is the key every subsystem stores state under - changing it means
     * despawning and respawning, and pretending otherwise would quietly corrupt the roster.</p>
     */
    public static String uuidOf(String botName) {
        ServerPlayer bot = resolve(botName);
        return bot == null ? null : bot.getUUID().toString();
    }

    // ===================== scenarios and paths =====================

    /** The scenario running a bot, or {@code -1} when it is under the combat AI. */
    public static int scenarioOf(String botName) {
        if (server == null || botName == null) return -1;
        try {
            ScenarioInstance instance = ScenarioManager.forBot(botName);
            return instance == null ? -1 : instance.id;
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /** Stops a scenario by id. */
    public static boolean stopScenario(int id) {
        if (server == null) return false;
        try {
            return ScenarioManager.remove(id);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static int stopAllScenarios() {
        if (server == null) return 0;
        try {
            return ScenarioManager.removeAll();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** Ids of every running scenario. */
    public static List<Integer> scenarios() {
        if (server == null) return List.of();
        try {
            List<Integer> out = new ArrayList<>();
            for (ScenarioInstance instance : ScenarioManager.all()) out.add(instance.id);
            return out;
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    public static boolean startPath(String botName, String pathName) {
        if (server == null || botName == null || pathName == null) return false;
        try {
            return BotPath.startFollowing(BotManager.canonical(botName), pathName);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean stopPath(String botName) {
        if (server == null || botName == null) return false;
        try {
            return BotPath.stopFollowing(BotManager.canonical(botName));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static List<String> paths() {
        if (server == null) return List.of();
        try {
            return new ArrayList<>(BotPath.getAllPaths().keySet());
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    // ===================== events =====================

    /**
     * Registers a lifecycle listener.
     *
     * <p>Listeners are never removed automatically and are cleared on shutdown. A consumer that
     * reloads should remove its own.</p>
     */
    public static void addListener(BotListener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(BotListener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Reports a roster change as the difference between two snapshots. Called by the roster on every
     * add and removal; not for consumers.
     *
     * <p>Takes the difference rather than a single name because the roster changes in batches - a
     * mass spawn, a world reload restoring saved bots - and because a diff cannot drift out of step
     * with the roster the way a hand-maintained pair of notifications can.</p>
     */
    public static void reportRoster(List<String> before, List<String> after) {
        if (LISTENERS.isEmpty() && after != null) {
            // Still has to run for the identity bookkeeping below, but only when something left.
            if (before == null || before.size() <= after.size()) return;
        }
        List<String> was = before == null ? List.of() : before;
        List<String> now = after == null ? List.of() : after;

        java.util.Set<String> nowKeys = new java.util.HashSet<>();
        for (String name : now) nowKeys.add(Names.key(name));
        java.util.Set<String> wasKeys = new java.util.HashSet<>();
        for (String name : was) wasKeys.add(Names.key(name));

        for (String name : now) {
            if (!wasKeys.contains(Names.key(name))) fireSpawned(name);
        }
        for (String name : was) {
            if (!nowKeys.contains(Names.key(name))) {
                // A bot that has left keeps no appearance; the next one under this name starts clean.
                BotIdentity.forget(name);
                fireRemoved(name);
            }
        }
    }

    /** Called by the roster. Not for consumers. */
    public static void fireSpawned(String name) {
        for (BotListener listener : LISTENERS) {
            try {
                listener.botSpawned(name);
            } catch (RuntimeException | LinkageError e) {
                BotRuntime.LOGGER.warn("Bot listener threw on spawn; dropping it.", e);
                LISTENERS.remove(listener);
            }
        }
    }

    /** Called by the roster. Not for consumers. */
    public static void fireRemoved(String name) {
        for (BotListener listener : LISTENERS) {
            try {
                listener.botRemoved(name);
            } catch (RuntimeException | LinkageError e) {
                BotRuntime.LOGGER.warn("Bot listener threw on removal; dropping it.", e);
                LISTENERS.remove(listener);
            }
        }
    }

    // ===================== internals =====================

    /** One bot, or null. Single lookups only - see {@link #states()} for the bulk path. */
    private static ServerPlayer resolve(String name) {
        if (server == null || name == null || !BotManager.isBot(name)) return null;
        return BotManager.getBot(server, BotManager.canonical(name));
    }

    /** Everyone online, keyed case-insensitively, built once for a bulk operation. */
    private static Map<String, ServerPlayer> index() {
        Map<String, ServerPlayer> online = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.put(Names.key(player.getName().getString()), player);
        }
        return online;
    }

    private static BotSnapshot snapshot(ServerPlayer bot) {
        String name = bot.getName().getString();
        String held;
        try {
            held = BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).toString();
        } catch (RuntimeException | LinkageError e) {
            held = "minecraft:air";
        }

        String target = null;
        try {
            int id = TargetSelector.memory(name).targetId;
            if (id >= 0 && bot.level() != null) {
                Entity found = bot.level().getEntity(id);
                if (found != null) target = found.getName().getString();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A target that cannot be resolved is reported as none, which is what it amounts to.
        }

        NpcRegistry.Group group = NpcRegistry.groupOf(name);

        return new BotSnapshot(
            name,
            bot.getUUID().toString(),
            bot.level().dimension().identifier().toString(),
            bot.getX(), bot.getY(), bot.getZ(),
            bot.getYRot(), bot.getXRot(),
            bot.getHealth(), bot.getMaxHealth(),
            held,
            target,
            group == null ? null : group.name,
            scenarioOf(name)
        );
    }

    private static boolean act(String name, java.util.function.BiConsumer<ServerPlayer, MinecraftServer> action) {
        ServerPlayer bot = resolve(name);
        if (bot == null) return false;
        try {
            action.accept(bot, server);
            return true;
        } catch (RuntimeException | LinkageError e) {
            BotRuntime.LOGGER.warn("Action on {} failed", name, e);
            return false;
        }
    }

    private static NpcSettings settingsFor(String scope, String target) {
        String kind = scope == null ? "bot" : scope.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "default", "defaults" -> NpcRegistry.defaults();
            case "group" -> {
                if (target == null) yield null;
                NpcRegistry.Group group = NpcRegistry.createGroup(target);
                yield group == null ? null : group.settings;
            }
            case "bot" -> target == null ? null : NpcRegistry.overrides(target);
            default -> null;
        };
    }

    /**
     * Writes one field by name.
     *
     * <p>A switch rather than reflection, and that is not a stylistic preference: a built mod is
     * remapped, so a field looked up by string works in development and fails silently in
     * production. Every branch here is checked by the compiler.</p>
     */
    private static boolean assign(NpcSettings settings, String key, String value) {
        String field = key.toLowerCase(Locale.ROOT);
        Boolean flag = parseBoolean(value);
        switch (field) {
            case "hittable" -> settings.hittable = flag;
            case "collidable" -> settings.collidable = flag;
            case "crouching" -> settings.crouching = flag;
            case "listed" -> settings.listed = flag;
            case "immortal" -> settings.immortal = flag;
            case "dropsondeath" -> settings.dropsOnDeath = flag;
            case "kit" -> settings.kit = (value == null || value.isBlank()) ? null : value;
            case "action" -> {
                NpcSettings.Action action = parseAction(value);
                if (action == null) return false;
                settings.action = action;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("yes") || v.equals("on")) return Boolean.TRUE;
        if (v.equals("false") || v.equals("no") || v.equals("off")) return Boolean.FALSE;
        return null;
    }

    private static NpcSettings.Action parseAction(String value) {
        if (value == null) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        for (NpcSettings.Action action : NpcSettings.Action.values()) {
            if (action.name().equals(v)) return action;
        }
        return null;
    }
}
