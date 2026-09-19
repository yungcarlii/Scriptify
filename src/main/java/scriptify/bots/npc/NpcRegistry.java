package scriptify.bots.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import scriptify.bots.config.WorldConfigHelper;
import scriptify.bots.utils.Names;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a bot's description lives: the defaults, the groups, and the per-bot overrides.
 *
 * <p>Resolution is a three step chain - defaults, then the bot's group, then the bot itself -
 * and {@link #resolve} is the only way anything should ask what a bot's settings are. Reading
 * the layers directly is how the layers end up disagreeing.</p>
 */
public final class NpcRegistry {

    private NpcRegistry() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "npcs.json";

    /** Serialised shape. One object so the file stays readable and versionable. */
    private static class Store {
        NpcSettings defaults = NpcSettings.defaults();
        Map<String, NpcSettings> perBot = new LinkedHashMap<>();
        Map<String, Group> groups = new LinkedHashMap<>();
    }

    public static class Group {
        public String name;
        public NpcSettings settings = new NpcSettings();
        public Set<String> members = new LinkedHashSet<>();

        public Group() {}

        public Group(String name) {
            this.name = name;
        }
    }

    private static Store store = new Store();
    private static Path savePath;
    private static boolean dirty;

    /**
     * Bot key to group key, derived from the groups themselves.
     *
     * <p>{@link #groupOf} is called from {@link #resolve}, which runs for every bot on every tick.
     * Walking every group member list to answer it was quadratic in the roster: two hundred and
     * fifty bots in one group meant sixty-two thousand comparisons a tick, each allocating a
     * lower-cased copy of a name to compare with. That is the same mistake {@code BotTicker}
     * already documents fixing one level up, made again one level down.</p>
     *
     * <p>Derived state, never serialised - rebuilt from the store on load and kept in step by the
     * membership methods below.</p>
     */
    private static final Map<String, String> MEMBER_GROUP = new HashMap<>();

    // ===================== lifecycle =====================

    public static void init(MinecraftServer server) {
        store = new Store();
        MEMBER_GROUP.clear();
        savePath = WorldConfigHelper.getWorldConfigDir().resolve(FILE_NAME);
        load();
    }

    public static void shutdown() {
        save();
    }

    // ===================== defaults =====================

    public static NpcSettings defaults() {
        if (store.defaults == null) store.defaults = NpcSettings.defaults();
        return store.defaults;
    }

    public static void resetDefaults() {
        store.defaults = NpcSettings.defaults();
        dirty = true;
    }

    // ===================== per bot =====================

    /** The bot's own overrides, created on demand. */
    public static NpcSettings overrides(String botName) {
        return store.perBot.computeIfAbsent(Names.key(botName), k -> new NpcSettings());
    }

    public static boolean hasOverrides(String botName) {
        return store.perBot.containsKey(Names.key(botName));
    }

    public static void clearOverrides(String botName) {
        store.perBot.remove(Names.key(botName));
        dirty = true;
    }

    /**
     * What this bot actually is, after defaults, its group, and its own overrides.
     *
     * <p>Cheap enough to call per tick: three map lookups and one allocation. It was not always -
     * see {@link #MEMBER_GROUP} and {@link NpcSettings#overlay}.</p>
     */
    public static NpcSettings resolve(String botName) {
        String key = Names.key(botName);
        NpcSettings result = defaults().copy();

        Group group = groupOfKey(key);
        if (group != null) result.overlay(group.settings);

        NpcSettings own = store.perBot.get(key);
        if (own != null) result.overlay(own);
        return result;
    }

    // ===================== groups =====================

    public static Group group(String name) {
        return store.groups.get(Names.key(name));
    }

    public static Group createGroup(String name) {
        String key = Names.key(name);
        if (store.groups.containsKey(key)) return store.groups.get(key);
        Group group = new Group(name);
        store.groups.put(key, group);
        dirty = true;
        return group;
    }

    public static boolean deleteGroup(String name) {
        boolean removed = store.groups.remove(Names.key(name)) != null;
        if (removed) {
            reindexGroups();
            dirty = true;
        }
        return removed;
    }

    public static List<Group> groups() {
        return new ArrayList<>(store.groups.values());
    }

    /** The group this bot belongs to, or null. A bot is only ever in one. */
    public static Group groupOf(String botName) {
        return groupOfKey(Names.key(botName));
    }

    /** For callers already holding the normalised key, so it is not computed twice. */
    private static Group groupOfKey(String key) {
        String groupKey = MEMBER_GROUP.get(key);
        return groupKey == null ? null : store.groups.get(groupKey);
    }

    /** Rebuilds the reverse index. Called whenever something replaces the store wholesale. */
    private static void reindexGroups() {
        MEMBER_GROUP.clear();
        for (Map.Entry<String, Group> entry : store.groups.entrySet()) {
            for (String member : entry.getValue().members) {
                MEMBER_GROUP.put(Names.key(member), entry.getKey());
            }
        }
    }

    public static void addToGroup(String groupName, String botName) {
        Group target = createGroup(groupName);
        // One group per bot, so joining leaves whatever it was in before. Two groups both
        // claiming a bot would make the override chain ambiguous.
        Group current = groupOfKey(Names.key(botName));
        if (current != null && current != target) {
            current.members.removeIf(m -> Names.key(m).equals(Names.key(botName)));
        }
        target.members.add(botName);
        MEMBER_GROUP.put(Names.key(botName), Names.key(groupName));
        dirty = true;
    }

    public static void removeFromGroup(String botName) {
        String key = Names.key(botName);
        Group current = groupOfKey(key);
        if (current == null) return;
        current.members.removeIf(m -> Names.key(m).equals(key));
        MEMBER_GROUP.remove(key);
        dirty = true;
    }

    public static void markDirty() {
        dirty = true;
    }

    // ===================== persistence =====================

    public static void tickSave(long serverTick) {
        if (dirty && serverTick % 200 == 0) save();
    }

    public static void save() {
        if (savePath == null) return;
        if (scriptify.storage.JsonFile.write(GSON, savePath, store,
            scriptify.bots.BotRuntime.LOGGER, "NPC settings")) {
            dirty = false;
        }
    }

    private static void load() {
        if (savePath == null || !Files.exists(savePath)) return;
        try (Reader reader = Files.newBufferedReader(savePath)) {
            Store loaded = GSON.fromJson(reader, Store.class);
            if (loaded == null) return;
            if (loaded.defaults == null) loaded.defaults = NpcSettings.defaults();
            if (loaded.perBot == null) loaded.perBot = new LinkedHashMap<>();
            if (loaded.groups == null) loaded.groups = new LinkedHashMap<>();
            store = loaded;
            reindexGroups();
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.error("Could not load NPC settings: {}", e.getMessage());
        }
    }
}
