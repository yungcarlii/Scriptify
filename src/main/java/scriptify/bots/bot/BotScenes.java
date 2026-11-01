package scriptify.bots.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.config.WorldConfigHelper;
import scriptify.bots.npc.BotIdentity;
import scriptify.bots.npc.EditMode;
import scriptify.bots.npc.MovementReplay;
import scriptify.bots.npc.NpcRegistry;
import scriptify.bots.npc.NpcSettings;
import scriptify.bots.scenario.ScenarioInstance;
import scriptify.bots.scenario.ScenarioManager;
import scriptify.bots.utils.Names;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Named snapshots of the bots standing in a build, so the build can be emptied to stop the lag and
 * filled again later exactly as it was left.
 *
 * <p>A bot is more than its spot. Removing one takes everything it was doing with it - the route it
 * walked, who it followed, its wander, its recording - and clears its inventory, so a scene keeps all
 * of that beside the position. It also keeps what does survive a removal (skin, faction, group, the
 * bot's own settings), because those may have been changed or deleted by the time the scene comes
 * back. The route is kept as a copy for the same reason: drawing a new path for a bot replaces its old
 * one, and a scene should not depend on that never happening.</p>
 *
 * <p>Bringing a scene back is two steps, because a spawn is not synchronous. The bots go through
 * {@link BotManager#queueRestore}, which paces them and skips anyone already online, and each one's
 * state is put back by {@link #tick} on the first tick it is actually there.</p>
 */
public final class BotScenes {

    private BotScenes() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "scenes.json";

    /** How long a restored bot may take to appear once the queue has drained, before it is given up. */
    private static final int JOIN_WAIT_TICKS = 600;

    /** One bot, as it was when the scene was saved. */
    public static class SceneBot {
        /** Name, position, facing, dimension and game mode: what the restore queue spawns from. */
        public BotManager.BotData spot;
        /** Inventory as slot number to NBT text, the shape kits are stored in. */
        public Map<String, String> items = new LinkedHashMap<>();
        public BotIdentity.Skin skin;
        public String faction;
        public String group;
        /** The bot's own NPC settings, or null when it had none of its own. */
        public NpcSettings settings;
        public String path;
        public int pathPoint;
        public boolean combatPath;
        public double sightRange;
        /** A copy of the route, put back if it has been deleted since. */
        public BotPath.PathData route;
        /** The player it was following. */
        public String leader;
        public boolean wander;
        public List<MovementReplay.Frame> replay;
        public int replayCursor;
        /** Id of the scenario that was driving it. */
        public Integer scenario;
    }

    public static class Scene {
        public String name;
        public String dimension;
        public double x;
        public double y;
        public double z;
        public double radius;
        public List<SceneBot> bots = new ArrayList<>();
    }

    /** A bot spawned by a restore and not here yet. */
    private static final class Pending {
        final SceneBot saved;
        int waited;

        Pending(SceneBot saved) {
            this.saved = saved;
        }
    }

    /** Scene name in lower case to the scene. */
    private static final Map<String, Scene> SCENES = new LinkedHashMap<>();
    /** Bot key to the state to put back once that bot has spawned. */
    private static final Map<String, Pending> PENDING = new HashMap<>();
    private static Path savePath;

    // ===================== lifecycle =====================

    public static void init() {
        SCENES.clear();
        PENDING.clear();
        savePath = WorldConfigHelper.getWorldConfigDir().resolve(FILE_NAME);
        if (!Files.exists(savePath)) return;
        try {
            SCENES.putAll(fromJson(Files.readString(savePath)));
        } catch (Exception e) {
            // Moved aside rather than left, or the next save would write an empty file over every
            // scene somebody spent an afternoon arranging.
            Path broken = savePath.resolveSibling(FILE_NAME + ".broken");
            scriptify.bots.BotRuntime.LOGGER.error("Could not read bot scenes, moved to {}: {}",
                broken, e.getMessage());
            try {
                Files.move(savePath, broken, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveFailure) {
                // Nowhere safe to write, so do not write at all this session.
                savePath = null;
            }
        }
    }

    // ===================== scenes =====================

    public static Scene get(String name) {
        return SCENES.get(name.toLowerCase(Locale.ROOT));
    }

    public static List<Scene> all() {
        return new ArrayList<>(SCENES.values());
    }

    /**
     * Saves every bot within {@code radius} of a point as a scene, replacing one of the same name.
     *
     * @return how many bots it holds; zero, and nothing saved, when there were none
     */
    public static int save(MinecraftServer server, String name, Level level, Vec3 center, double radius) {
        Scene scene = new Scene();
        scene.name = name;
        scene.dimension = level.dimension().identifier().toString();
        scene.x = center.x;
        scene.y = center.y;
        scene.z = center.z;
        scene.radius = radius;
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            if (!bot.isAlive() || bot.level() != level) continue;
            if (!BotManager.isBot(bot.getName().getString())) continue;
            if (bot.position().distanceToSqr(center) > radius * radius) continue;
            scene.bots.add(capture(bot));
        }
        if (scene.bots.isEmpty()) return 0;
        SCENES.put(name.toLowerCase(Locale.ROOT), scene);
        write();
        return scene.bots.size();
    }

    public static boolean delete(String name) {
        if (SCENES.remove(name.toLowerCase(Locale.ROOT)) == null) return false;
        write();
        return true;
    }

    /**
     * Spawns the scene's bots back where they stood.
     *
     * @return how many were sent for; the rest are already here, or already on their way
     */
    public static int restore(MinecraftServer server, Scene scene) {
        int queued = 0;
        for (SceneBot saved : scene.bots) {
            String key = Names.key(saved.spot.name);
            if (isHere(server, saved.spot.name) || PENDING.containsKey(key)) {
                continue;
            }
            PENDING.put(key, new Pending(saved));
            // A copy: the roster re-keys and keeps the object it is handed, and the scene's own record
            // must not drift with it.
            BotManager.queueRestore(copy(saved.spot, BotManager.BotData.class));
            queued++;
        }
        return queued;
    }

    /** Takes the scene's bots out of the world, leaving the scene saved. */
    public static int remove(MinecraftServer server, Scene scene, CommandSourceStack source) {
        int removed = 0;
        for (SceneBot saved : scene.bots) {
            if (BotManager.isBot(saved.spot.name) && BotManager.removeBot(server, saved.spot.name, source)) {
                removed++;
            }
        }
        return removed;
    }

    /** How many of the scene's bots are in the world right now. */
    public static int present(MinecraftServer server, Scene scene) {
        int here = 0;
        for (SceneBot saved : scene.bots) {
            if (isHere(server, saved.spot.name)) here++;
        }
        return here;
    }

    /**
     * Alive, not merely listed: removing a bot kills it, and unless the backend is set to disconnect
     * the dead, the corpse stays in the player list under the bot's name.
     */
    private static boolean isHere(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayer(name);
        return player != null && player.isAlive();
    }

    // ===================== capture and apply =====================

    private static SceneBot capture(ServerPlayer bot) {
        String name = bot.getName().getString();
        SceneBot saved = new SceneBot();
        saved.spot = new BotManager.BotData(bot);

        ItemStack[] contents = new ItemStack[BotKits.SLOTS];
        for (int slot = 0; slot < BotKits.SLOTS; slot++) {
            contents[slot] = bot.getInventory().getItem(slot);
        }
        saved.items = BotKits.encode(contents, bot.registryAccess());
        saved.skin = BotIdentity.worn(name);
        saved.faction = BotFaction.getFaction(name);
        NpcRegistry.Group group = NpcRegistry.groupOf(name);
        saved.group = group == null ? null : group.name;
        saved.settings = NpcRegistry.hasOverrides(name) ? NpcRegistry.overrides(name).copy() : null;

        BotPath.PathFollower follower = BotPath.getFollower(name);
        if (follower != null) {
            saved.path = follower.pathName;
            saved.pathPoint = follower.currentPoint;
            saved.combatPath = follower.combatPath;
            saved.sightRange = follower.sightRange;
            BotPath.PathData route = BotPath.getPath(follower.pathName);
            saved.route = route == null ? null : copy(route, BotPath.PathData.class);
        }
        saved.leader = BotFollow.leaderOf(name);
        saved.wander = EditMode.isWandering(name);
        saved.replay = MovementReplay.frames(name);
        saved.replayCursor = MovementReplay.cursor(name);
        ScenarioInstance scenario = ScenarioManager.forBot(name);
        saved.scenario = scenario == null ? null : scenario.id;
        return saved;
    }

    /**
     * Puts back the state of every restored bot that has arrived.
     *
     * <p>One pass over the online players rather than a lookup per pending bot: a player lookup by
     * name is a scan of the whole list, and a big scene can have a hundred bots waiting.</p>
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String name = player.getName().getString();
            String key = Names.key(name);
            // Only once it is on the roster: a real player who logged in under the same name in the
            // meantime must not be handed a bot's inventory.
            if (!PENDING.containsKey(key) || !BotManager.isBot(name) || !player.isAlive()) continue;
            apply(player, server, PENDING.remove(key).saved);
        }
        // Waiting a turn in the queue is not a failure; only the time after it has drained counts.
        if (!BotManager.isRestoring()) {
            PENDING.values().removeIf(pending -> ++pending.waited > JOIN_WAIT_TICKS);
        }
    }

    private static void apply(ServerPlayer bot, MinecraftServer server, SceneBot saved) {
        String name = bot.getName().getString();
        BotManager.BotData spot = saved.spot;
        // The spawn already put it there, but an older backend that refuses the facing clause spawns
        // it looking north.
        if (bot.level().dimension().identifier().toString().equals(spot.dimension)) {
            bot.absSnapTo(spot.x, spot.y, spot.z, spot.yaw, spot.pitch);
            bot.setYHeadRot(spot.yaw);
        }

        var inventory = bot.getInventory();
        inventory.clearContent();
        BotKits.decode(saved.items, bot.registryAccess()).forEach(inventory::setItem);
        if (saved.skin != null) BotIdentity.apply(bot, server, saved.skin);

        String faction = BotFaction.getFaction(name);
        if (!Objects.equals(faction, saved.faction)) {
            if (saved.faction != null) {
                BotFaction.addMember(saved.faction, name);
            } else {
                BotFaction.removeMember(faction, name);
            }
        }
        NpcRegistry.clearOverrides(name);
        if (saved.settings != null) NpcRegistry.overrides(name).overlay(saved.settings);
        if (saved.group == null) {
            NpcRegistry.removeFromGroup(name);
        } else if (NpcRegistry.group(saved.group) != null) {
            NpcRegistry.addToGroup(saved.group, name);
        }

        if (saved.path != null) {
            if (BotPath.getPath(saved.path) == null && saved.route != null) {
                BotPath.getAllPaths().put(saved.path, copy(saved.route, BotPath.PathData.class));
                BotPath.save();
            }
            boolean walking = saved.combatPath
                ? BotPath.startCombatPath(name, saved.path, saved.sightRange)
                : BotPath.startFollowing(name, saved.path);
            if (walking) BotPath.setBotPathIndex(name, saved.pathPoint);
        }
        if (saved.leader != null) BotFollow.follow(name, saved.leader);
        if (saved.wander && !EditMode.isWandering(name)) EditMode.toggleWander(name);
        if (saved.replay != null) MovementReplay.start(bot, saved.replay, saved.replayCursor);
        if (saved.scenario != null) {
            ScenarioInstance scenario = ScenarioManager.get(saved.scenario);
            if (scenario != null) ScenarioManager.assign(scenario, name);
        }
    }

    // ===================== persistence =====================

    private static void write() {
        if (savePath == null) return;
        scriptify.storage.JsonFile.write(GSON, savePath, SCENES,
            scriptify.bots.BotRuntime.LOGGER, "bot scenes");
    }

    static String toJson(Map<String, Scene> scenes) {
        return GSON.toJson(scenes);
    }

    /** Reads the scene file, dropping entries too broken to spawn anything from. */
    static Map<String, Scene> fromJson(String json) {
        Map<String, Scene> loaded = GSON.fromJson(json,
            new TypeToken<LinkedHashMap<String, Scene>>() {}.getType());
        if (loaded == null) return new LinkedHashMap<>();
        loaded.values().removeIf(scene -> scene == null || scene.bots == null);
        for (Scene scene : loaded.values()) {
            scene.bots.removeIf(bot -> bot == null || bot.spot == null || bot.spot.name == null);
            for (SceneBot bot : scene.bots) {
                if (bot.items == null) bot.items = new LinkedHashMap<>();
            }
        }
        return loaded;
    }

    /** A deep copy by way of the same JSON the file holds, so nothing in a scene is shared with live state. */
    private static <T> T copy(T value, Class<T> type) {
        return GSON.fromJson(GSON.toJson(value), type);
    }
}
