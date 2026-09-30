package scriptify.bots.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class BotPath {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    /** Where the walkers are stored, beside the routes but in their own file. */
    private static Path followerPath;
    
    private static final Map<String, PathData> paths = new HashMap<>();
    private static final Map<String, PathFollower> followers = new HashMap<>();
    private static final Set<String> visiblePaths = new HashSet<>();
    
    public static class PathData {
        public String name;
        public List<Vec3> points = new ArrayList<>();
        public boolean loop = false;
        public boolean attack = true;
        public String walkType = "bhop";
        
        public PathData(String name) {
            this.name = name;
        }
    }
    
    public static class PathFollower {
        public String pathName;
        public int currentPoint = 0;
        public boolean reverse = false;
        public Vec3 pausedAtPoint = null;
        public boolean inCombat = false;
        /**
         * Walking the route as a patrol: fight whatever comes into view, then go back to walking.
         * Set by {@code /bots bot-management combatpath}. Unlike the route's own attack flag this
         * does not lean on auto-targeting being switched on - a patrol bot looks for trouble on its
         * own, but only trouble it can actually see.
         */
        public boolean combatPath = false;
        /** How far a patrolling bot can spot an enemy from, in blocks. */
        public double sightRange = DEFAULT_SIGHT_RANGE;
        
        public PathFollower(String pathName) {
            this.pathName = pathName;
        }
    }
    
    
    public static boolean createPath(String name) {
        if (paths.containsKey(name)) {
            return false;
        }
        paths.put(name, new PathData(name));
        save();
        return true;
    }
    
    
    public static boolean deletePath(String name) {
        if (!paths.containsKey(name)) {
            return false;
        }
        paths.remove(name);

        followers.entrySet().removeIf(entry -> entry.getValue().pathName.equals(name));
        save();
        return true;
    }
    
    
    public static boolean addPoint(String pathName, Vec3 point) {
        PathData path = paths.get(pathName);
        if (path == null) {
            return false;
        }
        path.points.add(point);
        save();
        return true;
    }
    
    
    public static boolean removeLastPoint(String pathName) {
        PathData path = paths.get(pathName);
        if (path == null || path.points.isEmpty()) {
            return false;
        }
        path.points.remove(path.points.size() - 1);
        save();
        return true;
    }
    
    
    public static boolean removePoint(String pathName, int index) {
        PathData path = paths.get(pathName);
        if (path == null || index < 0 || index >= path.points.size()) {
            return false;
        }
        path.points.remove(index);
        save();
        return true;
    }
    
    
    public static boolean clearPath(String pathName) {
        PathData path = paths.get(pathName);
        if (path == null) {
            return false;
        }
        path.points.clear();
        save();
        return true;
    }
    
    
    public static boolean setLoop(String pathName, boolean loop) {
        PathData path = paths.get(pathName);
        if (path == null) {
            return false;
        }
        path.loop = loop;
        save();
        return true;
    }
    
    
    public static boolean setAttack(String pathName, boolean attack) {
        PathData path = paths.get(pathName);
        if (path == null) {
            return false;
        }
        path.attack = attack;
        save();
        return true;
    }
    
    
    public static boolean setWalkType(String pathName, String walkType) {
        PathData path = paths.get(pathName);
        if (path == null) {
            return false;
        }
        path.walkType = walkType;
        save();
        return true;
    }
    
    
    public static boolean startFollowing(String botName, String pathName) {
        PathData path = paths.get(pathName);
        if (path == null || path.points.isEmpty()) {
            return false;
        }
        followers.put(botName, new PathFollower(pathName));
        saveFollowers();
        return true;
    }
    
    
    /** Spotting distance for a patrol when the command does not give one. */
    public static final double DEFAULT_SIGHT_RANGE = 24.0;

    /**
     * Puts a bot on a route as a patrol: it walks the route, breaks off to fight any enemy it
     * sees, and picks the route back up where it left it once the fight is over.
     */
    public static boolean startCombatPath(String botName, String pathName, double sightRange) {
        if (!startFollowing(botName, pathName)) {
            return false;
        }
        PathFollower follower = followers.get(botName);
        follower.combatPath = true;
        follower.sightRange = sightRange;
        saveFollowers();
        return true;
    }

    /** Whether this bot is patrolling a route rather than just walking it. */
    public static boolean isCombatPath(String botName) {
        PathFollower follower = followers.get(botName);
        return follower != null && follower.combatPath;
    }

    /** How far a patrolling bot spots enemies from; zero for a bot that is not patrolling. */
    public static double sightRange(String botName) {
        PathFollower follower = followers.get(botName);
        return follower != null && follower.combatPath ? follower.sightRange : 0.0;
    }

    public static boolean stopFollowing(String botName) {
        boolean removed = followers.remove(botName) != null;
        if (removed) {
            saveFollowers();
        }
        return removed;
    }
    
    
    public static Vec3 getNextPoint(String botName) {
        PathFollower follower = followers.get(botName);
        if (follower == null) {
            return null;
        }
        
        PathData path = paths.get(follower.pathName);
        if (path == null || path.points.isEmpty()) {
            return null;
        }
        
        return path.points.get(follower.currentPoint);
    }
    
    
    public static void advanceToNextPoint(String botName) {
        PathFollower follower = followers.get(botName);
        if (follower == null) {
            return;
        }
        
        PathData path = paths.get(follower.pathName);
        if (path == null || path.points.isEmpty()) {
            return;
        }
        
        // A loop walks the circuit and starts again from the first point. It used to turn round at
        // the end and walk back, which is a different thing entirely: a patrol route drawn as a ring
        // was walked as a line, so the bot retraced its own steps instead of coming round.
        follower.currentPoint++;
        if (follower.currentPoint >= path.points.size()) {
            follower.currentPoint = path.loop ? 0 : path.points.size() - 1;
        }
    }
    
    
    public static boolean isFollowing(String botName) {
        return followers.containsKey(botName);
    }
    
    
    public static boolean isFollowing(String botName, String pathName) {
        PathFollower follower = followers.get(botName);
        return follower != null && follower.pathName.equals(pathName);
    }
    
    
    public static boolean setBotPathIndex(String botName, int index) {
        PathFollower follower = followers.get(botName);
        if (follower == null) {
            return false;
        }
        
        PathData path = paths.get(follower.pathName);
        if (path == null || index < 0 || index >= path.points.size()) {
            return false;
        }
        
        follower.currentPoint = index;
        return true;
    }
    
    
    public static PathData getPath(String name) {
        return paths.get(name);
    }
    
    
    public static Map<String, PathData> getAllPaths() {
        return paths;
    }
    
    
    public static PathFollower getFollower(String botName) {
        return followers.get(botName);
    }
    
    
    public static boolean setPathVisible(String pathName, boolean visible) {
        PathData path = paths.get(pathName);
        if (path == null) {
            return false;
        }
        
        if (visible) {
            visiblePaths.add(pathName);
        } else {
            visiblePaths.remove(pathName);
        }
        return true;
    }
    
    
    public static boolean isPathVisible(String pathName) {
        return visiblePaths.contains(pathName);
    }
    
    
    public static Set<String> getVisiblePaths() {
        return visiblePaths;
    }
    
    
    public static void startCombat(String botName, Vec3 currentTarget) {
        PathFollower follower = followers.get(botName);
        if (follower != null && !follower.inCombat) {
            follower.inCombat = true;
            follower.pausedAtPoint = currentTarget;
        }
    }
    
    
    public static void endCombat(String botName) {
        PathFollower follower = followers.get(botName);
        if (follower != null) {
            follower.inCombat = false;

        }
    }
    
    
    public static boolean isInCombat(String botName) {
        PathFollower follower = followers.get(botName);
        return follower != null && follower.inCombat;
    }
    
    
    public static Vec3 getPausedPoint(String botName) {
        PathFollower follower = followers.get(botName);
        return follower != null ? follower.pausedAtPoint : null;
    }
    
    
    public static void clearPausedPoint(String botName) {
        PathFollower follower = followers.get(botName);
        if (follower != null) {
            follower.pausedAtPoint = null;
        }
    }
    
    
    public static boolean shouldAttack(String botName) {
        PathFollower follower = followers.get(botName);
        if (follower == null) {
            return true;
        }
        if (follower.combatPath) {
            return true;
        }
        
        PathData path = paths.get(follower.pathName);
        return path != null && path.attack;
    }
    
    
    public static void init() {
        Path configDir = scriptify.bots.config.WorldConfigHelper.botsDir();
        
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.error("Failed to create config directory: {}", e.getMessage());
        }
        
        configPath = scriptify.bots.config.WorldConfigHelper.getWorldConfigDir().resolve("paths.json");
        followerPath = scriptify.bots.config.WorldConfigHelper.getWorldConfigDir()
            .resolve("path-followers.json");
        load();
        // After the routes, since a walker is dropped when its route no longer exists.
        loadFollowers();
    }
    
    
    public static void save() {
        if (configPath == null) return;
        scriptify.storage.JsonFile.write(GSON, configPath, paths,
            scriptify.bots.BotRuntime.LOGGER, "bot paths");
        saveFollowers();
    }

    /**
     * Who is walking what, written beside the routes themselves.
     *
     * <p>The routes were always saved and the walkers never were, so a restart left every path in the
     * file and nobody on any of them - the bots came back and stood still. Kept in its own file rather
     * than folded into {@code paths.json}, because that file is a bare map of routes and an older
     * build reading a changed shape would drop the lot.
     *
     * <p>Only the path name and how far along it the bot had got are stored. A pause point and a
     * combat flag are both about a moment that ended when the server did, and restoring them would
     * have a bot resuming a fight that is not happening.
     */
    private static void saveFollowers() {
        if (followerPath == null) return;
        Map<String, StoredFollower> stored = new HashMap<>();
        followers.forEach((bot, follower) ->
            stored.put(bot, new StoredFollower(follower.pathName, follower.currentPoint,
                follower.reverse, follower.combatPath, follower.sightRange)));
        scriptify.storage.JsonFile.write(GSON, followerPath, stored,
            scriptify.bots.BotRuntime.LOGGER, "bot path followers");
    }

    /** The saveable part of a {@link PathFollower}. */
    private static class StoredFollower {
        String pathName;
        int currentPoint;
        boolean reverse;
        // Absent from files written before patrols existed, which reads as an ordinary walker.
        boolean combatPath;
        double sightRange;

        StoredFollower(String pathName, int currentPoint, boolean reverse,
                       boolean combatPath, double sightRange) {
            this.pathName = pathName;
            this.currentPoint = currentPoint;
            this.reverse = reverse;
            this.combatPath = combatPath;
            this.sightRange = sightRange;
        }
    }

    /**
     * Puts the walkers back.
     *
     * <p>Nothing has to be re-started afterwards: the ticker asks {@link #isFollowing(String)} for
     * every bot it sees, so a bot that reconnects into a restored entry simply carries on from the
     * point it had reached. A saved entry whose path has since been deleted is dropped.
     */
    private static void loadFollowers() {
        followers.clear();
        if (followerPath == null || !Files.exists(followerPath)) return;
        try (Reader reader = Files.newBufferedReader(followerPath)) {
            Map<String, StoredFollower> stored = GSON.fromJson(reader,
                new TypeToken<Map<String, StoredFollower>>() {}.getType());
            if (stored == null) return;
            stored.forEach((bot, saved) -> {
                if (saved == null || saved.pathName == null || !paths.containsKey(saved.pathName)) {
                    return;
                }
                PathFollower follower = new PathFollower(saved.pathName);
                follower.currentPoint = saved.currentPoint;
                follower.reverse = saved.reverse;
                follower.combatPath = saved.combatPath;
                follower.sightRange = saved.sightRange > 0 ? saved.sightRange : DEFAULT_SIGHT_RANGE;
                followers.put(bot, follower);
            });
            if (!followers.isEmpty()) {
                scriptify.bots.BotRuntime.LOGGER.info(
                    "Resuming {} bot(s) on their paths", followers.size());
            }
        } catch (Exception exception) {
            scriptify.bots.BotRuntime.LOGGER.warn("Could not read bot path followers: {}",
                exception.getMessage());
        }
    }
    
    
    private static void load() {
        if (configPath == null || !Files.exists(configPath)) {
            return;
        }
        
        try (Reader reader = Files.newBufferedReader(configPath)) {
            Map<String, PathData> loadedPaths = GSON.fromJson(reader, 
                new TypeToken<Map<String, PathData>>(){}.getType());
            
            if (loadedPaths != null) {
                paths.clear();
                paths.putAll(loadedPaths);
            }
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.error("Failed to load paths: {}", e.getMessage());
        }
    }
}
