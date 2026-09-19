package scriptify.bots.config;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import scriptify.bots.bot.BotSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Metadata for every tunable setting: label, category, range and accessors.
 *
 * <p>The original described each setting twice - once as a field with a clamping setter and
 * once again as a hand-written Brigadier branch - so adding a knob meant editing two files
 * and keeping the ranges in sync by hand. The GUI would have made it three. Everything that
 * needs to enumerate settings now reads this list.</p>
 */
public final class SettingsRegistry {

    private SettingsRegistry() {}

    public enum Kind { BOOL, INT, DOUBLE }

    public static final class Entry {
        public final String key;
        public final String label;
        public final String description;
        public final String category;
        public final Kind kind;
        public final double min;
        public final double max;
        public final double step;
        public final Item icon;

        private final Predicate<BotSettings> boolGet;
        private final BiConsumer<BotSettings, Boolean> boolSet;
        private final ToIntFunction<BotSettings> intGet;
        private final ObjIntConsumer<BotSettings> intSet;
        private final ToDoubleFunction<BotSettings> doubleGet;
        private final ObjDoubleConsumer<BotSettings> doubleSet;

        private Entry(String key, String label, String description, String category, Kind kind,
                      double min, double max, double step, Item icon,
                      Predicate<BotSettings> boolGet, BiConsumer<BotSettings, Boolean> boolSet,
                      ToIntFunction<BotSettings> intGet, ObjIntConsumer<BotSettings> intSet,
                      ToDoubleFunction<BotSettings> doubleGet, ObjDoubleConsumer<BotSettings> doubleSet) {
            this.key = key;
            this.label = label;
            this.description = description;
            this.category = category;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.step = step;
            this.icon = icon;
            this.boolGet = boolGet;
            this.boolSet = boolSet;
            this.intGet = intGet;
            this.intSet = intSet;
            this.doubleGet = doubleGet;
            this.doubleSet = doubleSet;
        }

        public boolean getBool() { return boolGet.test(BotSettings.get()); }
        public int getInt() { return intGet.applyAsInt(BotSettings.get()); }
        public double getDouble() { return doubleGet.applyAsDouble(BotSettings.get()); }

        // Accessors take the settings object rather than closing over the live one, so the
        // same pair of method references reads both the current value and the default.
        public boolean defaultBool() { return boolGet.test(BotSettings.DEFAULTS); }
        public int defaultInt() { return intGet.applyAsInt(BotSettings.DEFAULTS); }
        public double defaultDouble() { return doubleGet.applyAsDouble(BotSettings.DEFAULTS); }

        public String defaultString() {
            return switch (kind) {
                case BOOL -> defaultBool() ? "on" : "off";
                case INT -> Integer.toString(defaultInt());
                case DOUBLE -> String.format(java.util.Locale.US, "%.2f", defaultDouble());
            };
        }

        public String valueString() {
            return switch (kind) {
                case BOOL -> getBool() ? "on" : "off";
                case INT -> Integer.toString(getInt());
                case DOUBLE -> String.format(java.util.Locale.US, "%.2f", getDouble());
            };
        }

        /** Applies a change; {@code delta} is +1 or -1 steps (ignored for booleans). */
        public void adjust(int delta) {
            switch (kind) {
                case BOOL -> setBool(!getBool());
                case INT -> setInt(getInt() + (int) Math.round(step * delta));
                case DOUBLE -> setDouble(getDouble() + step * delta);
            }
        }

        public void setBool(boolean value) { boolSet.accept(BotSettings.get(), value); }
        public void setInt(int value) { intSet.accept(BotSettings.get(), clampInt(value)); }
        public void setDouble(double value) { doubleSet.accept(BotSettings.get(), clampDouble(value)); }

        private int clampInt(int v) { return (int) Math.max(min, Math.min(max, v)); }
        private double clampDouble(double v) {
            double clamped = Math.max(min, Math.min(max, v));
            return Math.round(clamped * 100.0) / 100.0;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static void bool(String key, String label, String description, String category,
                             Item icon, Predicate<BotSettings> get,
                             BiConsumer<BotSettings, Boolean> set) {
        ENTRIES.add(new Entry(key, label, description, category, Kind.BOOL, 0, 1, 1, icon,
            get, set, null, null, null, null));
    }

    private static void integer(String key, String label, String description, String category,
                                Item icon, ToIntFunction<BotSettings> get,
                                ObjIntConsumer<BotSettings> set, int min, int max, int step) {
        ENTRIES.add(new Entry(key, label, description, category, Kind.INT, min, max, step, icon,
            null, null, get, set, null, null));
    }

    private static void number(String key, String label, String description, String category,
                               Item icon, ToDoubleFunction<BotSettings> get,
                               ObjDoubleConsumer<BotSettings> set,
                               double min, double max, double step) {
        ENTRIES.add(new Entry(key, label, description, category, Kind.DOUBLE, min, max, step, icon,
            null, null, null, null, get, set));
    }

    public static final String COMBAT = "Combat";
    public static final String TARGETING = "Targeting";
    public static final String TACTICS = "Tactics";
    public static final String SURVIVAL = "Survival";
    public static final String MOVEMENT = "Movement";
    public static final String GEAR = "Gear";
    public static final String SERVER = "Server";

    static {
        // ---- Combat ----
        bool("combat", "Combat AI", "Master switch for all fighting behaviour", COMBAT,
            Items.IRON_SWORD, BotSettings::isCombatEnabled, BotSettings::setCombatEnabled);
        bool("criticals", "Jump crits", "Jump before hitting for critical damage", COMBAT,
            Items.DIAMOND_SWORD, BotSettings::isCriticalsEnabled, BotSettings::setCriticalsEnabled);
        bool("techniques", "Sweaty techniques", "W-tap, shield feint, bow swap, strafe resets",
            COMBAT, Items.NETHERITE_SWORD, BotSettings::isAdvancedTechniquesEnabled, BotSettings::setAdvancedTechniquesEnabled);
        bool("mace", "Mace attacks", "Wind-charge launch and smash combos", COMBAT,
            Items.MACE, BotSettings::isMaceEnabled, BotSettings::setMaceEnabled);
        bool("ranged", "Bows", "Use bows and crossbows at range", COMBAT,
            Items.BOW, BotSettings::isRangedEnabled, BotSettings::setRangedEnabled);
        bool("shield-break", "Axe shield break", "Switch to an axe against blocking targets",
            COMBAT, Items.NETHERITE_AXE, BotSettings::isShieldBreakEnabled, BotSettings::setShieldBreakEnabled);
        integer("attack-cooldown", "Attack interval", "Base ticks between swings; the weapon speed is applied on top", COMBAT, Items.CLOCK,
            BotSettings::getAttackCooldown, BotSettings::setAttackCooldown,
            1, 40, 1);
        number("melee-range", "Melee reach", "Distance a bot will swing from", COMBAT,
            Items.STICK, BotSettings::getMeleeRange, BotSettings::setMeleeRange, 2.0, 6.0, 0.25);
        integer("miss-chance", "Miss chance %", "Deliberate whiffs, for handicapped fights",
            COMBAT, Items.FEATHER, BotSettings::getMissChance, BotSettings::setMissChance, 0, 100, 5);
        integer("mistake-chance", "Mistake chance %", "Chance to misaim a swing", COMBAT,
            Items.ROTTEN_FLESH, BotSettings::getMistakeChance, BotSettings::setMistakeChance, 0, 100, 5);

        // ---- Targeting ----
        bool("auto-target", "Auto target", "Pick fights without being told to", TARGETING,
            Items.SPYGLASS, BotSettings::isAutoTargetEnabled, BotSettings::setAutoTargetEnabled);
        bool("target-players", "Target players", "Attack real players", TARGETING,
            Items.PLAYER_HEAD, BotSettings::isTargetPlayers, BotSettings::setTargetPlayers);
        bool("target-bots", "Target bots", "Attack other bots", TARGETING,
            Items.ARMOR_STAND, BotSettings::isTargetOtherBots, BotSettings::setTargetOtherBots);
        bool("target-mobs", "Target mobs", "Attack hostile mobs", TARGETING,
            Items.ZOMBIE_HEAD, BotSettings::isTargetHostileMobs, BotSettings::setTargetHostileMobs);
        bool("revenge", "Revenge", "Prioritise whoever just hit them", TARGETING,
            Items.REDSTONE, BotSettings::isRevengeEnabled, BotSettings::setRevengeEnabled);
        bool("line-of-sight", "Require line of sight", "Bots cannot attack through walls",
            TARGETING, Items.GLASS, BotSettings::isRequireLineOfSight, BotSettings::setRequireLineOfSight);
        bool("factions", "Factions", "Respect faction alliances", TARGETING,
            scriptify.ui.DyedItems.banner(DyeColor.WHITE), BotSettings::isFactionsEnabled, BotSettings::setFactionsEnabled);
        bool("friendly-fire", "Friendly fire", "Allow hitting allies", TARGETING,
            Items.FIRE_CHARGE, BotSettings::isFriendlyFireEnabled, BotSettings::setFriendlyFireEnabled);
        number("view-distance", "Target range", "How far bots look for enemies", TARGETING,
            Items.ENDER_EYE, BotSettings::getMaxTargetDistance, BotSettings::setMaxTargetDistance, 5.0, 128.0, 4.0);

        // ---- Tactics ----
        bool("squad-tactics", "Squad tactics", "Spread out and stagger attacks in a group",
            TACTICS, Items.SHIELD, BotSettings::isSquadTacticsEnabled, BotSettings::setSquadTacticsEnabled);
        integer("squad-melee-slots", "Melee slots", "Bots allowed inside melee range of one "
            + "target at a time", TACTICS, Items.IRON_BARS,
            BotSettings::getSquadMeleeSlots, BotSettings::setSquadMeleeSlots, 1, 16, 1);
        number("squad-spacing", "Squad spacing", "Minimum distance bots keep from each other",
            TACTICS, Items.LEAD, BotSettings::getSquadSpacing, BotSettings::setSquadSpacing, 0.0, 8.0, 0.5);
        integer("reaction-ticks", "Reaction delay", "Ticks before a bot reacts to something new",
            COMBAT, Items.CLOCK, BotSettings::getReactionTicks, BotSettings::setReactionTicks,
            0, 20, 1);
        number("strafe", "Strafe amount", "How much bots move sideways while fighting",
            COMBAT, Items.LEATHER_BOOTS, BotSettings::getStrafeAmount,
            BotSettings::setStrafeAmount, 0.0, 1.0, 0.1);
        bool("wtap", "W-tap", "Sprint-reset after a hit for full knockback", COMBAT,
            Items.FEATHER, BotSettings::isWtapEnabled, BotSettings::setWtapEnabled);
        integer("crit-chance", "Crit chance", "Odds of going for a jump crit (%)", COMBAT,
            Items.DIAMOND_SWORD, BotSettings::getCritChance, BotSettings::setCritChance,
            0, 100, 5);
        integer("web-chance", "Web chance", "Odds of spending a cobweb when there is a reason (%)",
            TACTICS, Items.COBWEB, BotSettings::getWebChance, BotSettings::setWebChance,
            0, 100, 5);
        bool("cobweb", "Cobweb traps", "Web the ground the target is running into", TACTICS,
            Items.COBWEB, BotSettings::isCobwebEnabled, BotSettings::setCobwebEnabled);
        bool("cobweb-bubble", "Cobweb bubbling", "Cocoon in web when about to die", TACTICS,
            Items.STRING, BotSettings::isCobwebBubbleEnabled, BotSettings::setCobwebBubbleEnabled);
        integer("cobweb-cooldown", "Web cooldown", "Base ticks between web placements", TACTICS,
            Items.CLOCK, BotSettings::getCobwebCooldownTicks, BotSettings::setCobwebCooldownTicks, 40, 1200, 20);
        bool("bridging", "Bridging", "Build up to targets they cannot reach", TACTICS,
            Items.COBBLESTONE, BotSettings::isBridgingEnabled, BotSettings::setBridgingEnabled);

        // ---- Survival ----
        bool("auto-eat", "Auto eat", "Eat when hurt or hungry, when it is safe to", SURVIVAL,
            Items.COOKED_BEEF, BotSettings::isAutoEatEnabled, BotSettings::setAutoEatEnabled);
        bool("auto-potion", "Auto potion", "Healing and buff potions", SURVIVAL,
            Items.SPLASH_POTION, BotSettings::isAutoPotionEnabled, BotSettings::setAutoPotionEnabled);
        bool("auto-totem", "Auto totem", "Keep a totem in the offhand", SURVIVAL,
            Items.TOTEM_OF_UNDYING, BotSettings::isAutoTotemEnabled, BotSettings::setAutoTotemEnabled);
        bool("totem-priority", "Totem priority", "Never swap a totem out for a shield", SURVIVAL,
            Items.GOLDEN_APPLE, BotSettings::isTotemPriority, BotSettings::setTotemPriority);
        bool("auto-shield", "Auto shield", "Raise a shield against incoming hits", SURVIVAL,
            Items.SHIELD, BotSettings::isAutoShieldEnabled, BotSettings::setAutoShieldEnabled);
        bool("auto-mend", "Auto mend", "Repair mending gear with XP bottles", SURVIVAL,
            Items.EXPERIENCE_BOTTLE, BotSettings::isAutoMendEnabled, BotSettings::setAutoMendEnabled);
        bool("retreat", "Retreat", "Disengage when badly hurt", SURVIVAL,
            Items.LEATHER_BOOTS, BotSettings::isRetreatEnabled, BotSettings::setRetreatEnabled);
        number("retreat-health", "Retreat health", "Health fraction that triggers a retreat",
            SURVIVAL, Items.REDSTONE, BotSettings::getRetreatHealthPercent, BotSettings::setRetreatHealthPercent, 0.1, 0.9, 0.05);

        // ---- Movement ----
        number("move-speed", "Move speed", "Movement speed multiplier", MOVEMENT,
            Items.SUGAR, BotSettings::getMoveSpeed, BotSettings::setMoveSpeed, 0.1, 2.0, 0.1);
        bool("pathfinding", "Pathfinding", "Use A* routes instead of walking straight at things",
            MOVEMENT, Items.COMPASS, BotSettings::isPathfindingEnabled, BotSettings::setPathfindingEnabled);
        integer("path-budget", "Path budget", "Search nodes per path - the main cost knob",
            MOVEMENT, Items.MAP, BotSettings::getPathNodeBudget, BotSettings::setPathNodeBudget, 100, 4000, 100);
        bool("bhop", "Bunny hop", "Hop while sprinting", MOVEMENT,
            Items.RABBIT_FOOT, BotSettings::isBhopEnabled, BotSettings::setBhopEnabled);
        bool("idle", "Idle wander", "Wander around when there is nothing to do", MOVEMENT,
            Items.GRASS_BLOCK, BotSettings::isIdleWanderEnabled, BotSettings::setIdleWanderEnabled);
        number("idle-radius", "Wander radius", "How far from home they wander", MOVEMENT,
            Items.OAK_FENCE, BotSettings::getIdleWanderRadius, BotSettings::setIdleWanderRadius, 3.0, 50.0, 1.0);

        // ---- Gear ----
        bool("auto-armor", "Auto armour", "Equip the best armour they are carrying", GEAR,
            Items.DIAMOND_CHESTPLATE, BotSettings::isAutoEquipArmor, BotSettings::setAutoEquipArmor);
        bool("auto-weapon", "Auto weapon", "Hold the best weapon when idle", GEAR,
            Items.IRON_AXE, BotSettings::isAutoEquipWeapon, BotSettings::setAutoEquipWeapon);
        bool("prefer-sword", "Prefer sword", "Bias weapon choice toward swords", GEAR,
            Items.GOLDEN_SWORD, BotSettings::isPreferSword, BotSettings::setPreferSword);
        bool("drop-armor", "Drop worse armour", "Throw away armour they have outgrown", GEAR,
            Items.HOPPER, BotSettings::isDropWorseArmor, BotSettings::setDropWorseArmor);
        bool("drop-weapon", "Drop worse weapons", "Throw away weapons they have outgrown", GEAR,
            Items.DROPPER, BotSettings::isDropWorseWeapons, BotSettings::setDropWorseWeapons);
        integer("interval", "Gear check interval", "Ticks between equipment scans", GEAR,
            Items.CLOCK, BotSettings::getCheckInterval, BotSettings::setCheckInterval, 1, 100, 5);

        // ---- Server ----
        bool("bots-relog", "Restore on restart", "Bring bots back when the world loads", SERVER,
            Items.ENDER_CHEST, BotSettings::isBotsRelogs, BotSettings::setBotsRelogs);
        bool("bot-leave-on-death", "Leave on death", "Bots disconnect when killed", SERVER,
            Items.SKELETON_SKULL, BotSettings::isBotLeaveOnDeath, BotSettings::setBotLeaveOnDeath);
        bool("clear-on-remove", "Clear on remove", "Wipe inventory before removing a bot", SERVER,
            Items.LAVA_BUCKET, BotSettings::isClearOnRemove, BotSettings::setClearOnRemove);
        bool("attack-invincible", "Attack invincible", "Attack creative and spectator players",
            SERVER, Items.COMMAND_BLOCK, BotSettings::isAttackInvincible, BotSettings::setAttackInvincible);
        bool("special-names", "Special names", "Use config/scriptify/bots/special_names.json when naming",
            SERVER, Items.NAME_TAG, BotSettings::isUseSpecialNames, BotSettings::setUseSpecialNames);
        integer("bot-slot-headroom", "Slot headroom", "Extra player slots reserved for bots",
            SERVER, Items.IRON_DOOR, BotSettings::getBotSlotHeadroom, BotSettings::setBotSlotHeadroom, 0, 100000, 100);
        integer("max-mass-spawn", "Mass spawn cap", "Maximum bots per mass-spawn command", SERVER,
            Items.SPAWNER, BotSettings::getMaxMassSpawn, BotSettings::setMaxMassSpawn, 50, 10000, 50);

        // ---- Combat: fine tuning ----
        integer("crit-fall-ticks", "Crit window", "Falling ticks before a crit is taken", COMBAT,
            Items.FEATHER, BotSettings::getCriticalFallTicks, BotSettings::setCriticalFallTicks,
            1, 10, 1);
        integer("shield-break-chance", "Shield break chance", "Odds of reaching for the axe (%)",
            COMBAT, Items.IRON_AXE, BotSettings::getShieldBreakChance,
            BotSettings::setShieldBreakChance, 0, 100, 5);
        bool("shield-mace", "Predict mace hits", "Raise a shield against an incoming mace smash",
            COMBAT, Items.MACE, BotSettings::isShieldMace, BotSettings::setShieldMace);
        integer("shield-hold-ticks", "Shield hold", "How long a raised shield stays up", COMBAT,
            Items.SHIELD, BotSettings::getShieldHoldTicks, BotSettings::setShieldHoldTicks,
            10, 200, 10);
        integer("shield-raise-ticks", "Shield reaction", "Cap on reaction time before blocking",
            COMBAT, Items.CLOCK, BotSettings::getShieldRaiseTicks,
            BotSettings::setShieldRaiseTicks, 2, 40, 2);

        // ---- Combat: ranged ----
        number("ranged-min-range", "Bow min range", "Closer than this and they stop shooting",
            COMBAT, Items.ARROW, BotSettings::getRangedMinRange,
            BotSettings::setRangedMinRange, 3.0, 20.0, 1.0);
        number("ranged-optimal-range", "Bow ideal range", "The distance archers try to hold",
            COMBAT, Items.SPECTRAL_ARROW, BotSettings::getRangedOptimalRange,
            BotSettings::setRangedOptimalRange, 10.0, 50.0, 2.0);
        number("ranged-max-range", "Bow max range", "Past this they close in instead", COMBAT,
            Items.TIPPED_ARROW, BotSettings::getRangedMaxRange,
            BotSettings::setRangedMaxRange, 15.0, 100.0, 5.0);
        integer("bow-draw-ticks", "Bow draw time", "Full draw time before release", COMBAT,
            Items.BOW, BotSettings::getBowMinDrawTime, BotSettings::setBowMinDrawTime, 5, 100, 5);
        bool("arrow-prediction", "Lead the target", "Aim ahead of a moving target", COMBAT,
            Items.TARGET, BotSettings::isArrowPredictionEnabled,
            BotSettings::setArrowPredictionEnabled);
        bool("ranged-strafe", "Strafe while shooting", "Keep moving between shots", COMBAT,
            Items.LEATHER_BOOTS, BotSettings::isRangedStrafeEnabled,
            BotSettings::setRangedStrafeEnabled);
        bool("ranged-retreat", "Kite when closed down", "Back off rather than switch to melee",
            COMBAT, Items.FEATHER, BotSettings::isRangedRetreatOnClose,
            BotSettings::setRangedRetreatOnClose);

        // ---- Tactics ----
        number("aim-speed", "Turn rate cap", "Maximum head turn, degrees per tick", TACTICS,
            Items.COMPASS, BotSettings::getAimSpeed, BotSettings::setAimSpeed, 3.0, 90.0, 2.0);

        // ---- Gear / server ----
        number("drop-distance", "Drop distance", "How far junk gear is thrown", GEAR,
            Items.HOPPER, BotSettings::getDropDistance, BotSettings::setDropDistance,
            1.0, 10.0, 0.5);
        bool("safe-spawn", "Safe mass spawn", "Spread a batch out to avoid entity cramming",
            SERVER, Items.SPAWNER, BotSettings::isSafeSpawn, BotSettings::setSafeSpawn);
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static List<String> categories() {
        return List.of(COMBAT, TARGETING, TACTICS, SURVIVAL, MOVEMENT, GEAR, SERVER);
    }

    public static Map<String, List<Entry>> byCategory() {
        Map<String, List<Entry>> map = new LinkedHashMap<>();
        for (String category : categories()) {
            map.put(category, ENTRIES.stream().filter(e -> e.category.equals(category)).toList());
        }
        return map;
    }

    public static List<Entry> inCategory(String category) {
        return ENTRIES.stream().filter(e -> e.category.equals(category)).toList();
    }

    public static Entry byKey(String key) {
        for (Entry entry : ENTRIES) {
            if (entry.key.equals(key)) return entry;
        }
        return null;
    }
}
