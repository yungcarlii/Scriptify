package scriptify.bots.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import scriptify.bots.config.WorldConfigHelper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class BotSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BotSettings INSTANCE;
    private static Path configPath;
    

    private boolean autoEquipArmor = true;
    private boolean autoEquipWeapon = true;
    private boolean dropWorseArmor = false;
    private boolean dropWorseWeapons = false;
    private double dropDistance = 3.0;
    private int checkInterval = 20;
    private boolean combatEnabled = true;
    private boolean revengeEnabled = true;
    private boolean autoTargetEnabled = false;
    /** Bots with a job (path, scenario, recording, wander, follow) drop it to fight anyone they notice. */
    private boolean fightOnSight = false;
    private boolean targetPlayers = true;
    private boolean targetHostileMobs = false;
    private boolean targetOtherBots = false;
    private double maxTargetDistance = 64.0;
    private double meleeRange = 3.5;
    private double rangedMinRange = 20.0;
    private double rangedOptimalRange = 40.0;
    private double rangedMaxRange = 60.0;
    private double maceRange = 6.0;
    private int attackCooldown = 10;
    private double moveSpeed = 1.0;
    private boolean criticalsEnabled = true;
    private int criticalFallTicks = 6;
    private int bowMinDrawTime = 40;
    private boolean rangedEnabled = true;
    private boolean maceEnabled = true;
    private boolean autoTotemEnabled = true;
    private boolean totemPriority = true;
    private boolean autoEatEnabled = true;
    private boolean autoShieldEnabled = true;
    private boolean autoMendEnabled = true;
    private double mendDurabilityThreshold = 0.25;
    private double shieldHealthThreshold = 0.5;
    private boolean shieldBreakEnabled = true;
    private int shieldHoldTicks = 60;
    private int shieldRaiseTicks = 12;
    private boolean preferSword = true;
    private int minHungerToEat = 14;
    private boolean autoPotionEnabled = true;
    private boolean cobwebEnabled = true;
    private boolean retreatEnabled = true;
    private double retreatHealthPercent = 0.3;
    private double criticalHealthPercent = 0.15;
    private boolean bhopEnabled = true;
    private boolean idleWanderEnabled = false;
    private double idleWanderRadius = 10.0;
    private boolean factionsEnabled = true;
    private boolean friendlyFireEnabled = false;
    private int missChance = 0;
    private int mistakeChance = 0;
    private int shieldBreakChance = 40;
    private boolean botsRelogs = true;
    private boolean useSpecialNames = false;
    private boolean botLeaveOnDeath = true;
    private boolean attackInvincible = false;
    /** Hard cap on head turn rate in degrees per tick; personalities vary below it. */
    private double aimSpeed = 30.0;
    private boolean shieldMace = true;
    private int maxMassSpawn = 1000;
    private boolean arrowPredictionEnabled = true;
    private boolean rangedStrafeEnabled = true;
    private boolean rangedRetreatOnClose = true;
    private boolean safeSpawn = true;
    private boolean clearOnRemove = true;

    // ===== SBots additions =====
    /** Extra player slots reserved for bots on top of the server's configured max-players. */
    private int botSlotHeadroom = 1000;
    /** Bots must have line of sight before they may attack. Turning this off restores the
     *  original (wall-hacking) behaviour and is only here for arena/testing setups. */
    private boolean requireLineOfSight = true;
    /** How many bots may crowd into melee range of one target at a time. */
    private int squadMeleeSlots = 3;
    /** Minimum spacing bots try to keep from each other while fighting. */
    private double squadSpacing = 2.6;
    /** Bots spread around a shared target instead of stacking on it. */
    private boolean squadTacticsEnabled = true;
    /** Ticks of reaction delay before a bot acts on something new. */
    private int reactionTicks = 4;
    /** How much bots strafe sideways while fighting, 0 to 1. */
    private double strafeAmount = 0.5;
    /** Sprint-reset after landing a hit, for full knockback. */
    private boolean wtapEnabled = true;
    /** Chance to go for a jump crit rather than a flat hit (%). */
    private int critChance = 60;
    /** Chance to spend a cobweb when there is a reason to (%). */
    private int webChance = 25;

    /** Bots may encase themselves in cobweb when about to die. */
    private boolean cobwebBubbleEnabled = true;
    /** Base cooldown between offensive cobweb placements (ticks, jittered per bot). */
    private int cobwebCooldownTicks = 300;
    /** Bots may pillar/bridge with blocks to reach a target they cannot path to. */
    private boolean bridgingEnabled = true;
    /** A* pathfinding for navigation instead of pure steering. */
    private boolean pathfindingEnabled = true;
    /** Node budget per path search - the main knob for pathfinding cost. */
    private int pathNodeBudget = 900;
    /** Bots use sweaty techniques (w-tap, shield feint, strafe resets, bow swap). */
    private boolean advancedTechniquesEnabled = true;
    
    public static final BotSettings DEFAULTS = new BotSettings();
    
    private BotSettings() {}
    
    public static BotSettings get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }
    
    public static void load() {
        Path configDir = scriptify.bots.config.WorldConfigHelper.botsDir();
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {

        }
        
        configPath = WorldConfigHelper.getWorldConfigDir().resolve("settings.json");
        
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                INSTANCE = GSON.fromJson(reader, BotSettings.class);
                if (INSTANCE == null) {
                    INSTANCE = new BotSettings();
                }
            } catch (Exception e) {
                INSTANCE = new BotSettings();
            }
        } else {
            INSTANCE = new BotSettings();
            save();
            flush();
        }
    }

    
    private static boolean dirty = false;

    /**
     * Marks the config dirty instead of writing immediately. Every setter used to hit the
     * disk synchronously, so dragging a slider in a GUI or running a settings macro caused
     * one file write per change on the server thread. {@link #flush()} performs the actual
     * write, and the ticker calls it a couple of times a second at most.
     */
    public static void save() {
        dirty = true;
    }

    public static void flush() {
        if (!dirty || INSTANCE == null || configPath == null) return;
        if (scriptify.storage.JsonFile.write(GSON, configPath, INSTANCE,
            scriptify.bots.BotRuntime.LOGGER, "bot settings")) {
            dirty = false;
        }
    }
    

    public boolean isAutoEquipArmor() { return autoEquipArmor; }
    public boolean isAutoEquipWeapon() { return autoEquipWeapon; }
    public boolean isDropWorseArmor() { return dropWorseArmor; }
    public boolean isDropWorseWeapons() { return dropWorseWeapons; }
    public double getDropDistance() { return dropDistance; }
    public int getCheckInterval() { return checkInterval; }
    
    public boolean isCombatEnabled() { return combatEnabled; }
    public boolean isRevengeEnabled() { return revengeEnabled; }
    public boolean isAutoTargetEnabled() { return autoTargetEnabled; }
    public boolean isFightOnSight() { return fightOnSight; }
    public boolean isTargetPlayers() { return targetPlayers; }
    public boolean isTargetHostileMobs() { return targetHostileMobs; }
    public boolean isTargetOtherBots() { return targetOtherBots; }
    public double getMaxTargetDistance() { return maxTargetDistance; }
    public double getMeleeRange() { return meleeRange; }
    public double getRangedMinRange() { return rangedMinRange; }
    public double getRangedOptimalRange() { return rangedOptimalRange; }
    public double getRangedMaxRange() { return rangedMaxRange; }
    public double getMaceRange() { return maceRange; }
    public int getAttackCooldown() { return attackCooldown; }
    public double getMoveSpeed() { return moveSpeed; }
    public boolean isCriticalsEnabled() { return criticalsEnabled; }
    public int getCriticalFallTicks() { return criticalFallTicks; }
    public int getBowMinDrawTime() { return bowMinDrawTime; }
    public boolean isRangedEnabled() { return rangedEnabled; }
    public boolean isMaceEnabled() { return maceEnabled; }
    
    public boolean isAutoTotemEnabled() { return autoTotemEnabled; }
    public boolean isTotemPriority() { return totemPriority; }
    public boolean isAutoEatEnabled() { return autoEatEnabled; }
    public boolean isAutoShieldEnabled() { return autoShieldEnabled; }
    public boolean isAutoMendEnabled() { return autoMendEnabled; }
    public double getMendDurabilityThreshold() { return mendDurabilityThreshold; }
    public double getShieldHealthThreshold() { return shieldHealthThreshold; }
    public boolean isShieldBreakEnabled() { return shieldBreakEnabled; }
    public boolean isPreferSword() { return preferSword; }
    public int getMinHungerToEat() { return minHungerToEat; }
    public boolean isAutoPotionEnabled() { return autoPotionEnabled; }
    public boolean isCobwebEnabled() { return cobwebEnabled; }
    

    public boolean isRetreatEnabled() { return retreatEnabled; }
    public double getRetreatHealthPercent() { return retreatHealthPercent; }
    public double getCriticalHealthPercent() { return criticalHealthPercent; }
    public boolean isBhopEnabled() { return bhopEnabled; }
    public boolean isIdleWanderEnabled() { return idleWanderEnabled; }
    public double getIdleWanderRadius() { return idleWanderRadius; }
    

    public boolean isFactionsEnabled() { return factionsEnabled; }
    public boolean isFriendlyFireEnabled() { return friendlyFireEnabled; }
    public int getMissChance() { return missChance; }
    public int getMistakeChance() { return mistakeChance; }
    public int getShieldBreakChance() { return shieldBreakChance; }
    public int getShieldHoldTicks() { return shieldHoldTicks; }
    public int getShieldRaiseTicks() { return shieldRaiseTicks; }
    public boolean isBotsRelogs() { return botsRelogs; }
    

    public boolean isUseSpecialNames() { return useSpecialNames; }
    public boolean isBotLeaveOnDeath() { return botLeaveOnDeath; }
    public boolean isAttackInvincible() { return attackInvincible; }
    public boolean isShieldMace() { return shieldMace; }
    public int getMaxMassSpawn() { return maxMassSpawn; }
    public boolean isArrowPredictionEnabled() { return arrowPredictionEnabled; }
    public boolean isRangedStrafeEnabled() { return rangedStrafeEnabled; }
    public boolean isRangedRetreatOnClose() { return rangedRetreatOnClose; }
    public double getAimSpeed() { return aimSpeed; }
    

    public void setAutoEquipArmor(boolean value) { 
        this.autoEquipArmor = value; 
        save();
    }
    public void setAutoEquipWeapon(boolean value) { 
        this.autoEquipWeapon = value; 
        save();
    }
    public void setDropWorseArmor(boolean value) { 
        this.dropWorseArmor = value; 
        save();
    }
    public void setDropWorseWeapons(boolean value) { 
        this.dropWorseWeapons = value; 
        save();
    }
    public void setDropDistance(double value) { 
        this.dropDistance = Math.max(1.0, Math.min(10.0, value)); 
        save();
    }
    public void setCheckInterval(int value) { 
        this.checkInterval = Math.max(1, Math.min(100, value)); 
        save();
    }
    
    public void setCombatEnabled(boolean value) { this.combatEnabled = value; save(); }
    public void setRevengeEnabled(boolean value) { this.revengeEnabled = value; save(); }
    public void setAutoTargetEnabled(boolean value) { this.autoTargetEnabled = value; save(); }
    public void setFightOnSight(boolean value) { this.fightOnSight = value; save(); }
    public void setTargetPlayers(boolean value) { this.targetPlayers = value; save(); }
    public void setTargetHostileMobs(boolean value) { this.targetHostileMobs = value; save(); }
    public void setTargetOtherBots(boolean value) { this.targetOtherBots = value; save(); }
    
    public void setMaxTargetDistance(double value) { 
        this.maxTargetDistance = Math.max(5.0, Math.min(128.0, value)); 
        save(); 
    }
    public void setMeleeRange(double value) { 
        this.meleeRange = Math.max(2.0, Math.min(6.0, value)); 
        save(); 
    }
    public void setRangedMinRange(double value) { 
        this.rangedMinRange = Math.max(3.0, Math.min(20.0, value)); 
        save(); 
    }
    public void setRangedOptimalRange(double value) { 
        this.rangedOptimalRange = Math.max(10.0, Math.min(50.0, value)); 
        save(); 
    }
    public void setRangedMaxRange(double value) { 
        this.rangedMaxRange = Math.max(15.0, Math.min(100.0, value)); 
        save(); 
    }
    public void setMaceRange(double value) { 
        this.maceRange = Math.max(3.0, Math.min(10.0, value)); 
        save(); 
    }
    public void setAttackCooldown(int value) { 
        this.attackCooldown = Math.max(1, Math.min(40, value)); 
        save(); 
    }
    public void setMoveSpeed(double value) { 
        this.moveSpeed = Math.max(0.1, Math.min(2.0, value)); 
        save(); 
    }
    public void setCriticalsEnabled(boolean value) { this.criticalsEnabled = value; save(); }
    public void setCriticalFallTicks(int value) {
        this.criticalFallTicks = Math.max(1, Math.min(10, value));
        save();
    }
    public void setBowMinDrawTime(int value) { 
        this.bowMinDrawTime = Math.max(5, Math.min(100, value)); 
        save(); 
    }
    public void setRangedEnabled(boolean value) { this.rangedEnabled = value; save(); }
    public void setMaceEnabled(boolean value) { this.maceEnabled = value; save(); }
    
    public void setAutoTotemEnabled(boolean value) { this.autoTotemEnabled = value; save(); }
    public void setTotemPriority(boolean value) { this.totemPriority = value; save(); }
    public void setAutoEatEnabled(boolean value) { this.autoEatEnabled = value; save(); }
    public void setAutoShieldEnabled(boolean value) { this.autoShieldEnabled = value; save(); }
    public void setAutoMendEnabled(boolean value) { this.autoMendEnabled = value; save(); }
    public void setMendDurabilityThreshold(double value) { 
        this.mendDurabilityThreshold = Math.max(0.1, Math.min(0.9, value)); 
        save(); 
    }
    public void setShieldHealthThreshold(double value) { 
        this.shieldHealthThreshold = Math.max(0.1, Math.min(1.0, value)); 
        save(); 
    }
    public void setShieldBreakEnabled(boolean value) { this.shieldBreakEnabled = value; save(); }
    public void setPreferSword(boolean value) { this.preferSword = value; save(); }
    public void setMinHungerToEat(int value) { 
        this.minHungerToEat = Math.max(1, Math.min(20, value)); 
        save(); 
    }
    public void setAutoPotionEnabled(boolean value) { this.autoPotionEnabled = value; save(); }
    public void setCobwebEnabled(boolean value) { this.cobwebEnabled = value; save(); }
    

    public void setRetreatEnabled(boolean value) { this.retreatEnabled = value; save(); }
    public void setRetreatHealthPercent(double value) { 
        this.retreatHealthPercent = Math.max(0.1, Math.min(0.9, value)); 
        save(); 
    }
    public void setCriticalHealthPercent(double value) { 
        this.criticalHealthPercent = Math.max(0.05, Math.min(0.5, value)); 
        save(); 
    }
    public void setBhopEnabled(boolean value) { this.bhopEnabled = value; save(); }
    public void setIdleWanderEnabled(boolean value) { this.idleWanderEnabled = value; save(); }
    public void setIdleWanderRadius(double value) { 
        this.idleWanderRadius = Math.max(3.0, Math.min(50.0, value)); 
        save(); 
    }
    

    public void setFactionsEnabled(boolean value) { this.factionsEnabled = value; save(); }
    public void setFriendlyFireEnabled(boolean value) { this.friendlyFireEnabled = value; save(); }
    public void setMissChance(int value) { 
        this.missChance = Math.max(0, Math.min(100, value)); 
        save(); 
    }
    public void setMistakeChance(int value) { 
        this.mistakeChance = Math.max(0, Math.min(100, value)); 
        save(); 
    }
    public void setShieldBreakChance(int value) { 
        this.shieldBreakChance = Math.max(0, Math.min(100, value)); 
        save(); 
    }
    public void setShieldHoldTicks(int value) { 
        this.shieldHoldTicks = Math.max(10, Math.min(200, value)); 
        save(); 
    }
    public void setShieldRaiseTicks(int value) { 
        this.shieldRaiseTicks = Math.max(2, Math.min(40, value)); 
        save(); 
    }
    public void setBotsRelogs(boolean value) { this.botsRelogs = value; save(); }
    public void setUseSpecialNames(boolean value) { this.useSpecialNames = value; save(); }
    public void setBotLeaveOnDeath(boolean value) { this.botLeaveOnDeath = value; save(); }
    public void setAttackInvincible(boolean value) { this.attackInvincible = value; save(); }
    public void setAimSpeed(double value) { 
        this.aimSpeed = Math.max(3.0, Math.min(90.0, value)); 
        save(); 
    }
    
    public void setShieldMace(boolean value) { 
        this.shieldMace = value; 
        save(); 
    }
    public void setMaxMassSpawn(int value) { 
        this.maxMassSpawn = Math.max(50, Math.min(10000, value)); 
        save(); 
    }
    public void setArrowPredictionEnabled(boolean value) {
        this.arrowPredictionEnabled = value;
        save();
    }
    public void setRangedStrafeEnabled(boolean value) {
        this.rangedStrafeEnabled = value;
        save();
    }
    public void setRangedRetreatOnClose(boolean value) {
        this.rangedRetreatOnClose = value;
        save();
    }

    public boolean isSafeSpawn() { return safeSpawn; }

    public void setSafeSpawn(boolean value) {
        this.safeSpawn = value;
        save();
    }

    public boolean isClearOnRemove() { return clearOnRemove; }

    public int getBotSlotHeadroom() { return botSlotHeadroom; }
    public boolean isRequireLineOfSight() { return requireLineOfSight; }
    public int getSquadMeleeSlots() { return squadMeleeSlots; }
    public double getSquadSpacing() { return squadSpacing; }
    public boolean isSquadTacticsEnabled() { return squadTacticsEnabled; }
    public int getReactionTicks() { return reactionTicks; }
    public double getStrafeAmount() { return strafeAmount; }
    public boolean isWtapEnabled() { return wtapEnabled; }
    public int getCritChance() { return critChance; }
    public int getWebChance() { return webChance; }

    public void setReactionTicks(int value) { this.reactionTicks = Math.max(0, Math.min(20, value)); save(); }
    public void setStrafeAmount(double value) { this.strafeAmount = Math.max(0.0, Math.min(1.0, value)); save(); }
    public void setWtapEnabled(boolean value) { this.wtapEnabled = value; save(); }
    public void setCritChance(int value) { this.critChance = Math.max(0, Math.min(100, value)); save(); }
    public void setWebChance(int value) { this.webChance = Math.max(0, Math.min(100, value)); save(); }

    public boolean isCobwebBubbleEnabled() { return cobwebBubbleEnabled; }
    public int getCobwebCooldownTicks() { return cobwebCooldownTicks; }
    public boolean isBridgingEnabled() { return bridgingEnabled; }
    public boolean isPathfindingEnabled() { return pathfindingEnabled; }
    public int getPathNodeBudget() { return pathNodeBudget; }
    public boolean isAdvancedTechniquesEnabled() { return advancedTechniquesEnabled; }

    public void setBotSlotHeadroom(int value) { this.botSlotHeadroom = Math.max(0, Math.min(100000, value)); save(); }
    public void setRequireLineOfSight(boolean value) { this.requireLineOfSight = value; save(); }
    public void setSquadMeleeSlots(int value) { this.squadMeleeSlots = Math.max(1, Math.min(16, value)); save(); }
    public void setSquadSpacing(double value) { this.squadSpacing = Math.max(0.0, Math.min(8.0, value)); save(); }
    public void setSquadTacticsEnabled(boolean value) { this.squadTacticsEnabled = value; save(); }
    public void setCobwebBubbleEnabled(boolean value) { this.cobwebBubbleEnabled = value; save(); }
    public void setCobwebCooldownTicks(int value) { this.cobwebCooldownTicks = Math.max(40, Math.min(1200, value)); save(); }
    public void setBridgingEnabled(boolean value) { this.bridgingEnabled = value; save(); }
    public void setPathfindingEnabled(boolean value) { this.pathfindingEnabled = value; save(); }
    public void setPathNodeBudget(int value) { this.pathNodeBudget = Math.max(100, Math.min(4000, value)); save(); }
    public void setAdvancedTechniquesEnabled(boolean value) { this.advancedTechniquesEnabled = value; save(); }

    public void setClearOnRemove(boolean value) {
        this.clearOnRemove = value;
        save();
    }
}
