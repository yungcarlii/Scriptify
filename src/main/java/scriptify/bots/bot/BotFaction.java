package scriptify.bots.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;


public class BotFaction {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    

    private static final Map<String, Set<String>> factions = new HashMap<>();
    

    private static final Map<String, Set<String>> hostileRelations = new HashMap<>();
    

    /**
     * Which faction each member belongs to, keyed by {@link scriptify.bots.utils.Names#key}.
     *
     * <p>Normalised, like every other per-bot map in the mod. The backend resolves a requested name
     * against a real Mojang profile, so a bot asked for as {@code Quartzwin} joins as {@code QuartzWin}
     * - and this map decides who is an ally. Keyed by the raw spelling it answered null for exactly the
     * bots whose names had been resolved, which does not read as a lookup failure: it reads as bots
     * attacking their own side, and as faction hostility quietly doing nothing. The member sets keep the
     * display spelling, because those are shown to people.
     */
    private static final Map<String, String> playerFactionCache = new HashMap<>();

    /** Removes a member from a set regardless of how the name is capitalised. */
    private static boolean removeMemberFrom(Set<String> members, String playerName) {
        return members != null
            && members.removeIf(existing -> existing.equalsIgnoreCase(playerName));
    }
    
    static {
        load();
    }
    
    
    public static boolean createFaction(String name) {
        if (factions.containsKey(name)) return false;
        factions.put(name, new HashSet<>());
        hostileRelations.put(name, new HashSet<>());
        save();
        return true;
    }
    
    
    public static boolean deleteFaction(String name) {
        if (!factions.containsKey(name)) return false;
        

        for (String member : factions.get(name)) {
            playerFactionCache.remove(scriptify.bots.utils.Names.key(member));
        }
        
        factions.remove(name);
        hostileRelations.remove(name);
        

        for (Set<String> enemies : hostileRelations.values()) {
            enemies.remove(name);
        }
        
        save();
        return true;
    }
    
    
    public static boolean addMember(String faction, String playerName) {
        if (!factions.containsKey(faction)) return false;
        

        String key = scriptify.bots.utils.Names.key(playerName);
        String oldFaction = playerFactionCache.get(key);
        if (oldFaction != null && factions.containsKey(oldFaction)) {
            removeMemberFrom(factions.get(oldFaction), playerName);
        }

        // Re-adding under a different capitalisation must not leave two rows for one bot.
        removeMemberFrom(factions.get(faction), playerName);
        factions.get(faction).add(playerName);
        playerFactionCache.put(key, faction);
        save();
        return true;
    }
    
    
    public static boolean removeMember(String faction, String playerName) {
        if (!factions.containsKey(faction)) return false;
        boolean removed = removeMemberFrom(factions.get(faction), playerName);
        if (removed) {
            playerFactionCache.remove(scriptify.bots.utils.Names.key(playerName));
            save();
        }
        return removed;
    }
    
    
    public static boolean setHostile(String faction1, String faction2, boolean hostile) {
        if (!factions.containsKey(faction1) || !factions.containsKey(faction2)) return false;
        if (faction1.equals(faction2)) return false;
        

        hostileRelations.computeIfAbsent(faction1, k -> new HashSet<>());
        hostileRelations.computeIfAbsent(faction2, k -> new HashSet<>());
        
        if (hostile) {
            hostileRelations.get(faction1).add(faction2);
            hostileRelations.get(faction2).add(faction1);
        } else {
            hostileRelations.get(faction1).remove(faction2);
            hostileRelations.get(faction2).remove(faction1);
        }
        save();
        return true;
    }
    
    
    public static String getFaction(String playerName) {
        return playerName == null
            ? null : playerFactionCache.get(scriptify.bots.utils.Names.key(playerName));
    }
    
    
    public static boolean areEnemies(String player1, String player2) {
        String faction1 = getFaction(player1);
        String faction2 = getFaction(player2);
        

        if (faction1 == null || faction2 == null) return false;
        

        if (faction1.equals(faction2)) return false;
        

        Set<String> enemies = hostileRelations.get(faction1);
        return enemies != null && enemies.contains(faction2);
    }
    
    
    public static boolean areAllies(String player1, String player2) {
        String faction1 = getFaction(player1);
        String faction2 = getFaction(player2);
        
        if (faction1 == null || faction2 == null) return false;
        return faction1.equals(faction2);
    }
    
    
    public static Set<String> getAllFactions() {
        return new HashSet<>(factions.keySet());
    }
    
    
    public static Set<String> getMembers(String faction) {
        Set<String> members = factions.get(faction);
        return members != null ? new HashSet<>(members) : new HashSet<>();
    }
    
    
    public static Set<String> getHostileFactions(String faction) {
        Set<String> enemies = hostileRelations.get(faction);
        return enemies != null ? new HashSet<>(enemies) : new HashSet<>();
    }
    

    
    public static void load() {

        Path configDir = scriptify.bots.config.WorldConfigHelper.botsDir();
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {

        }
        
        configPath = scriptify.bots.config.WorldConfigHelper.getWorldConfigDir().resolve("factions.json");
        
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                FactionData data = GSON.fromJson(reader, FactionData.class);
                if (data != null) {
                    factions.clear();
                    hostileRelations.clear();
                    playerFactionCache.clear();
                    
                    if (data.factions != null) {
                        for (var entry : data.factions.entrySet()) {
                            factions.put(entry.getKey(), new HashSet<>(entry.getValue()));
                            for (String member : entry.getValue()) {
                                playerFactionCache.put(
                                    scriptify.bots.utils.Names.key(member), entry.getKey());
                            }
                        }
                    }
                    if (data.hostileRelations != null) {
                        for (var entry : data.hostileRelations.entrySet()) {
                            hostileRelations.put(entry.getKey(), new HashSet<>(entry.getValue()));
                        }
                    }
                }
            } catch (Exception e) {
                scriptify.bots.BotRuntime.LOGGER.error("Faction storage failed", e);
            }
        }
    }
    
    public static void save() {
        if (configPath == null) return;

        FactionData data = new FactionData();
        data.factions = new HashMap<>();
        data.hostileRelations = new HashMap<>();

        for (var entry : factions.entrySet()) {
            data.factions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        for (var entry : hostileRelations.entrySet()) {
            data.hostileRelations.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        scriptify.storage.JsonFile.write(GSON, configPath, data,
            scriptify.bots.BotRuntime.LOGGER, "bot factions");
    }
    
    private static class FactionData {
        Map<String, List<String>> factions;
        Map<String, List<String>> hostileRelations;
    }
}
