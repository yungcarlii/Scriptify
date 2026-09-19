package scriptify.bots.scenario;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import scriptify.bots.bot.BotManager;
import scriptify.bots.config.WorldConfigHelper;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns every scenario: the templates you edit, the drafts being built, and the running ones.
 *
 * <p>Two things here are load-bearing for performance. Scenarios are ticked from a single
 * sweep rather than from the per-bot loop, and a scenario whose anchor sits in an unloaded
 * chunk is skipped entirely - not ticked at a lower rate, skipped. A staged crowd on the far
 * side of the map should cost nothing at all while nobody is there to see it.</p>
 */
public final class ScenarioManager {

    private ScenarioManager() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "scenarios.json";

    /** Settings the next scenario of each type will be created with. */
    private static final Map<Scenario, ScenarioSettings> TEMPLATES = new EnumMap<>(Scenario.class);

    private static final Map<Integer, ScenarioInstance> SCENARIOS = new LinkedHashMap<>();
    /** Bot name key to scenario id, so the ticker can ask "is this bot busy?" cheaply. */
    private static final Map<String, Integer> ASSIGNMENT = new HashMap<>();

    private static final Map<UUID, ScenarioDraft> DRAFTS = new HashMap<>();

    private static int nextId = 1;
    private static Path savePath;
    private static boolean dirty;

    // ===================== lifecycle =====================

    public static void init(MinecraftServer server) {
        SCENARIOS.clear();
        ASSIGNMENT.clear();
        DRAFTS.clear();
        nextId = 1;
        savePath = WorldConfigHelper.getWorldConfigDir().resolve(FILE_NAME);
        load();
    }

    public static void shutdown() {
        save();
        SCENARIOS.clear();
        ASSIGNMENT.clear();
        DRAFTS.clear();
    }

    // ===================== templates =====================

    public static ScenarioSettings template(Scenario type) {
        return TEMPLATES.computeIfAbsent(type, t -> new ScenarioSettings());
    }

    public static void resetTemplate(Scenario type) {
        TEMPLATES.put(type, new ScenarioSettings());
    }

    // ===================== drafts =====================

    public static ScenarioDraft draft(ServerPlayer player) {
        return DRAFTS.get(player.getUUID());
    }

    public static ScenarioDraft beginDraft(ServerPlayer player, Scenario type) {
        ScenarioDraft draft = new ScenarioDraft(type, template(type).copy());
        DRAFTS.put(player.getUUID(), draft);
        return draft;
    }

    /**
     * Ends a draft, whether it was finished or thrown away.
     *
     * <p>Wiping the prompt is part of ending it, not a courtesy. Simply stopping the reminder
     * leaves the client showing the last line for another three seconds, so the instructions
     * hung around under a scenario that was already running.</p>
     */
    public static void cancelDraft(ServerPlayer player) {
        DRAFTS.remove(player.getUUID());
        ScenarioPrompt.clear(player);
    }

    public static Map<UUID, ScenarioDraft> drafts() {
        return DRAFTS;
    }

    // ===================== scenarios =====================

    public static ScenarioInstance create(ScenarioDraft draft, Level world) {
        ScenarioInstance instance = new ScenarioInstance();
        instance.id = nextId++;
        instance.type = draft.type;
        instance.settings = draft.settings;
        instance.points = new ArrayList<>(draft.points);
        instance.bots.addAll(draft.bots);
        instance.dimension = world.dimension().identifier().toString();

        SCENARIOS.put(instance.id, instance);
        for (String bot : instance.bots) {
            ASSIGNMENT.put(scriptify.bots.utils.Names.key(bot), instance.id);
        }
        dirty = true;
        return instance;
    }

    public static ScenarioInstance get(int id) {
        return SCENARIOS.get(id);
    }

    public static List<ScenarioInstance> all() {
        return new ArrayList<>(SCENARIOS.values());
    }

    public static boolean remove(int id) {
        ScenarioInstance removed = SCENARIOS.remove(id);
        if (removed == null) return false;
        ASSIGNMENT.entrySet().removeIf(e -> e.getValue() == id);
        dirty = true;
        return true;
    }

    public static int removeAll() {
        int count = SCENARIOS.size();
        SCENARIOS.clear();
        ASSIGNMENT.clear();
        dirty = true;
        return count;
    }

    /** The scenario driving this bot, or null. */
    public static ScenarioInstance forBot(String botName) {
        Integer id = ASSIGNMENT.get(scriptify.bots.utils.Names.key(botName));
        if (id == null) return null;
        ScenarioInstance instance = SCENARIOS.get(id);
        return instance != null && instance.enabled ? instance : null;
    }

    /**
     * Whether a scenario currently owns this bot, meaning the combat AI must leave it alone.
     *
     * <p>Only true while the scenario is actually running: a frozen scenario releases its bots
     * to stand where they are rather than handing them back to the fighting code, which would
     * have them sprint off to find someone the moment their chunk unloaded.</p>
     */
    public static boolean owns(String botName) {
        ScenarioInstance instance = forBot(botName);
        return instance != null;
    }

    public static void assign(ScenarioInstance instance, String botName) {
        instance.bots.add(botName);
        ASSIGNMENT.put(scriptify.bots.utils.Names.key(botName), instance.id);
        dirty = true;
    }

    public static void release(String botName) {
        Integer id = ASSIGNMENT.remove(scriptify.bots.utils.Names.key(botName));
        if (id == null) return;
        ScenarioInstance instance = SCENARIOS.get(id);
        if (instance != null) {
            instance.bots.removeIf(n -> n.equalsIgnoreCase(botName));
        }
        dirty = true;
    }

    // ===================== tick =====================

    public static void tick(MinecraftServer server, long serverTick) {
        if (SCENARIOS.isEmpty()) {
            if (dirty && serverTick % 200 == 0) save();
            return;
        }

        for (ScenarioInstance instance : SCENARIOS.values()) {
            instance.live = false;
            if (!instance.enabled) continue;

            Level world = worldOf(server, instance);
            if (world == null) continue;
            if (!isAnchorLoaded(world, instance)) continue;

            instance.live = true;
            switch (instance.type) {
                case SPAWN -> SpawnScenario.tick(server, world, instance, serverTick);
                case CONVERSATION -> ConversationScenario.tick(server, world, instance, serverTick);
                case CROWD -> CrowdScenario.tick(server, world, instance, serverTick);
                case PRISON_CELL -> PrisonScenario.tick(server, world, instance, serverTick);
            }
        }

        if (dirty && serverTick % 200 == 0) save();
    }

    private static Level worldOf(MinecraftServer server, ScenarioInstance instance) {
        for (var world : server.getAllLevels()) {
            if (world.dimension().identifier().toString().equals(instance.dimension)) {
                return world;
            }
        }
        return server.overworld();
    }

    /**
     * A scenario runs only while something of it is loaded.
     *
     * <p>Point-based scenarios are judged by their point; the rest by whether any of their bots
     * is still in a loaded chunk. Either way an untouched corner of the world costs one chunk
     * lookup per tick and nothing else.</p>
     */
    private static boolean isAnchorLoaded(Level world, ScenarioInstance instance) {
        Vec3 anchor = instance.anchor();
        if (anchor != null) {
            return world.isLoaded(BlockPos.containing(anchor));
        }
        for (String name : instance.bots) {
            ServerPlayer bot = world.getServer().getPlayerList().getPlayer(name);
            if (bot == null) continue;
            if (bot.level() == world && world.isLoaded(bot.blockPosition())) {
                return true;
            }
        }
        return false;
    }

    /** Drops bots that no longer exist, so a scenario does not hold on to dead names. */
    public static void prune(MinecraftServer server) {
        for (ScenarioInstance instance : SCENARIOS.values()) {
            instance.bots.removeIf(name -> !BotManager.isBot(name));
        }
        ASSIGNMENT.entrySet().removeIf(e -> {
            ScenarioInstance instance = SCENARIOS.get(e.getValue());
            return instance == null || instance.bots.stream().noneMatch(n -> n.equalsIgnoreCase(e.getKey()));
        });
    }

    // ===================== persistence =====================

    public static void save() {
        if (savePath == null) return;
        if (scriptify.storage.JsonFile.write(GSON, savePath, new ArrayList<>(SCENARIOS.values()),
            scriptify.bots.BotRuntime.LOGGER, "scenarios")) {
            dirty = false;
        }
    }

    private static void load() {
        if (savePath == null || !Files.exists(savePath)) return;
        try (Reader reader = Files.newBufferedReader(savePath)) {
            List<ScenarioInstance> loaded = GSON.fromJson(reader,
                new TypeToken<List<ScenarioInstance>>() {}.getType());
            if (loaded == null) return;
            for (ScenarioInstance instance : loaded) {
                if (instance == null || instance.type == null) continue;
                if (instance.settings == null) instance.settings = new ScenarioSettings();
                if (instance.points == null) instance.points = new ArrayList<>();
                instance.roamers = new HashMap<>();
                instance.timers = new HashMap<>();
                SCENARIOS.put(instance.id, instance);
                nextId = Math.max(nextId, instance.id + 1);
                for (String bot : instance.bots) {
                    ASSIGNMENT.put(scriptify.bots.utils.Names.key(bot), instance.id);
                }
            }
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.error("Could not load scenarios: {}", e.getMessage());
        }
    }
}
