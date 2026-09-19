package scriptify.bots.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import java.nio.file.Files;
import java.nio.file.Path;


public class WorldConfigHelper {
    
    private static MinecraftServer server;
    private static String currentWorldName = null;
    private static Runnable onWorldChangeCallback = null;
    
    
    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        currentWorldName = getWorldName();
    }
    
    
    public static void setOnWorldChangeCallback(Runnable callback) {
        onWorldChangeCallback = callback;
    }
    
    
    public static Path getWorldConfigDir() {
        checkWorldChange();
        String worldName = getWorldName();
        return getWorldConfigDir(worldName);
    }
    
    
    private static void checkWorldChange() {
        String newWorldName = getWorldName();
        if (currentWorldName != null && !currentWorldName.equals(newWorldName)) {
            scriptify.bots.BotRuntime.LOGGER.info("World changed from {} to {}", currentWorldName, newWorldName);
            currentWorldName = newWorldName;
            if (onWorldChangeCallback != null) {
                onWorldChangeCallback.run();
            }
        } else if (currentWorldName == null) {
            currentWorldName = newWorldName;
        }
    }
    
    
    public static Path getWorldConfigDir(String worldName) {
        Path dir = botsDir()
            .resolve("worlds")
            .resolve(worldName);
        
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.error("Failed to create world config directory: {}", e.getMessage());
        }
        
        return dir;
    }
    
    
    public static Path getGlobalConfigDir() {
        Path dir = botsDir();
        
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.error("Failed to create global config directory: {}", e.getMessage());
        }
        
        return dir;
    }
    
    
    /** Where the old separate mod kept everything. Read once, to move it. */
    private static final String LEGACY_DIR = "sbots";

    private static final java.util.concurrent.atomic.AtomicBoolean MIGRATED =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The one directory every piece of bot state lives under: {@code config/scriptify/bots}.
     *
     * <p>It used to be {@code config/sbots}, from when the bots were a separate mod. One jar with two
     * config directories is two mods wearing a coat, and the first thing anybody does when a mod
     * misbehaves is go and look at its config - finding half of it somewhere else is a bad answer to
     * a reasonable question.</p>
     *
     * <p>The old directory is moved rather than ignored, once, on the first call. Ignoring it would
     * silently discard somebody's factions, paths and saved settings on upgrade, which is the kind of
     * data loss that is only noticed later.</p>
     */
    public static synchronized Path botsDir() {
        Path dir = FabricLoader.getInstance().getConfigDir()
            .resolve("scriptify")
            .resolve("bots");
        if (MIGRATED.compareAndSet(false, true)) {
            migrate(FabricLoader.getInstance().getConfigDir().resolve(LEGACY_DIR), dir);
        }
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.error("Failed to create bot config directory: {}", e.getMessage());
        }
        return dir;
    }

    /**
     * Moves the old directory into the new place, if there is one and the new place is empty.
     *
     * <p>Refuses if the destination already holds anything: two sets of config both claiming to be
     * current is worse than an orphaned folder, and merging them is a guess.</p>
     */
    private static void migrate(Path legacy, Path destination) {
        try {
            if (!Files.isDirectory(legacy) || Files.exists(destination)) {
                return;
            }
            Files.createDirectories(destination.getParent());
            Files.move(legacy, destination);
            scriptify.bots.BotRuntime.LOGGER.info(
                "Moved bot config from config/{} to config/scriptify/bots - the bots are part of "
                    + "Scriptify now rather than a separate mod.", LEGACY_DIR);
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.warn(
                "Could not move config/{} to config/scriptify/bots ({}). The old folder has been left "
                    + "alone; move it across by hand to keep your factions, paths and settings.",
                LEGACY_DIR, e.getMessage());
        }
    }

    private static String getWorldName() {
        if (server != null) {
            String name = server.getWorldData().getLevelName();
            return name;
        }
        return "world";
    }
}
