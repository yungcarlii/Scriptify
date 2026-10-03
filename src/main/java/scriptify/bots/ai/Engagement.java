package scriptify.bots.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotCombat;
import scriptify.bots.bot.BotFaction;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotSettings;
import scriptify.bots.utils.Names;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Battle-level awareness: who is fighting whom, where the fight is, and what each bot's job
 * in it should be.
 *
 * <p>Until now every bot fought its own private duel that happened to be next to other
 * private duels. That is the root of "group fights look wrong": nobody focused, nobody
 * peeled, nobody hung back, and everyone converged on whoever was nearest. Real group PvP is
 * organised around one idea - <em>collapse on someone and remove them</em> - and everything
 * else (flanking, peeling, holding back) exists to serve that.</p>
 *
 * <p>This class rebuilds a picture of the battlefield a few times a second:</p>
 * <ol>
 *   <li><b>Cluster.</b> Fighting bots within {@link #BATTLE_RADIUS} of each other are one
 *       battle.</li>
 *   <li><b>Focus.</b> Each battle picks one enemy to kill first - the one who is hurt,
 *       isolated, unshielded, or already being hit. The pick is sticky, because a squad that
 *       re-decides every tick never actually kills anything.</li>
 *   <li><b>Roles.</b> Members are assigned spearhead / flanker / screen / reserve based on
 *       who is closest, who is healthy, and who has an ally in trouble.</li>
 * </ol>
 *
 * <p>Rebuilt on a global timer rather than per bot, so the cost is paid once for the whole
 * server rather than once per bot per tick.</p>
 */
public final class Engagement {

    private Engagement() {}

    /** Bots this far apart, or closer, are considered part of the same fight. */
    private static final double BATTLE_RADIUS = 18.0;
    private static final double BATTLE_RADIUS_SQ = BATTLE_RADIUS * BATTLE_RADIUS;

    /** A fight with at least this many bots on it is a brawl, and gets group tactics. */
    public static final int BRAWL_SIZE = 3;

    private static final int REBUILD_INTERVAL = 10;

    public enum Role {
        /** Commits to the focus target and tries to remove it. */
        SPEARHEAD,
        /** Takes the focus target from another angle so it cannot simply back away. */
        FLANKER,
        /** Covers allies - engages whoever is beating on a hurt teammate. */
        SCREEN,
        /** Holds at range: pokes, throws, waits for a gap to open. */
        RESERVE
    }

    public static final class Battle {
        public final List<ServerPlayer> members = new ArrayList<>(4);
        public final List<LivingEntity> enemies = new ArrayList<>(4);
        public Vec3 center = Vec3.ZERO;
        public Entity focus;
        public int focusAgeTicks;

        public int size() {
            return members.size();
        }

        /**
         * True only for a fight big enough that group roles help.
         *
         * <p>Counts <em>distinct</em> combatants. Members and enemies overlap - in a bot versus
         * bot fight each side appears in both lists - so adding the two sizes counted a plain
         * duel as a four-man brawl and handed out reserve roles in a 1v1, which is how bots
         * ended up hanging back with nothing to do.</p>
         */
        /**
         * Distinct combatants, counted once when the battle is built.
         *
         * <p>Cached because the count is a fact about a finished battle and the question is asked
         * far more often than the answer changes: {@code inBrawl} is public, every bot consults it
         * when it re-decides its intent, and the old version allocated a HashSet of up to the whole
         * roster on each of those calls. The battle is rebuilt from scratch every ten ticks, so
         * there is nothing to invalidate - a new battle is a new object.
         */
        private int distinctCombatants = -1;

        public boolean isBrawl() {
            return distinctCombatants() >= BRAWL_SIZE + 1;
        }

        public int distinctCombatants() {
            if (this.distinctCombatants < 0) {
                java.util.Set<Integer> ids =
                    new java.util.HashSet<>(members.size() + enemies.size());
                for (ServerPlayer member : members) ids.add(member.getId());
                for (LivingEntity enemy : enemies) ids.add(enemy.getId());
                this.distinctCombatants = ids.size();
            }
            return this.distinctCombatants;
        }
    }

    private static final List<Battle> BATTLES = new ArrayList<>();
    private static final Map<String, Battle> BY_BOT = new HashMap<>();
    private static final Map<String, Role> ROLES = new HashMap<>();
    /** Focus target id kept across rebuilds so the squad's decision does not oscillate. */
    private static int previousFocusId = -1;

    public static void tick(MinecraftServer server, long serverTick) {
        if (serverTick % REBUILD_INTERVAL != 0) return;
        rebuild(server, serverTick);
    }

    // ===================== queries =====================

    public static Battle battleOf(String botName) {
        return BY_BOT.get(Names.key(botName));
    }

    public static Role roleOf(String botName) {
        return ROLES.getOrDefault(Names.key(botName), Role.SPEARHEAD);
    }

    /** The enemy this bot's group is trying to remove, or null when there is no group plan. */
    public static Entity focusFor(String botName) {
        Battle battle = battleOf(botName);
        return battle == null ? null : battle.focus;
    }

    /** True when this bot is in a fight big enough to warrant group tactics. */
    public static boolean inBrawl(String botName) {
        Battle battle = battleOf(botName);
        return battle != null && battle.isBrawl();
    }

    public static int allyCount(String botName) {
        Battle battle = battleOf(botName);
        return battle == null ? 0 : Math.max(0, battle.size() - 1);
    }

    public static int enemyCount(String botName) {
        Battle battle = battleOf(botName);
        return battle == null ? 0 : battle.enemies.size();
    }

    /** Allies of this bot that are in serious trouble - used by the screen role. */
    public static ServerPlayer struggingAlly(String botName) {
        Battle battle = battleOf(botName);
        if (battle == null) return null;
        ServerPlayer worst = null;
        float worstFraction = 0.45f;
        for (ServerPlayer member : battle.members) {
            if (member.getName().getString().equalsIgnoreCase(botName)) continue;
            float fraction = member.getHealth() / Math.max(1.0f, member.getMaxHealth());
            if (fraction < worstFraction) {
                worstFraction = fraction;
                worst = member;
            }
        }
        return worst;
    }

    public static void clear() {
        BATTLES.clear();
        BY_BOT.clear();
        ROLES.clear();
    }

    // ===================== rebuild =====================

    private static void rebuild(MinecraftServer server, long serverTick) {
        int carriedFocus = BATTLES.size() == 1 ? previousFocusId : -1;
        BATTLES.clear();
        BY_BOT.clear();
        ROLES.clear();

        // --- 1. cluster fighting bots into battles ---------------------------------------
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            if (!bot.isAlive()) continue;
            String botName = bot.getName().getString();
            if (!BotManager.isBot(botName)) continue;

            Entity target = BotCombat.getTarget(botName);
            if (target == null || !target.isAlive()) continue;

            Battle battle = findBattleNear(bot, botName);
            if (battle == null) {
                battle = new Battle();
                BATTLES.add(battle);
            }
            battle.members.add(bot);
            BY_BOT.put(Names.key(botName), battle);

            if (target instanceof LivingEntity living && !battle.enemies.contains(living)) {
                battle.enemies.add(living);
            }
        }

        if (BATTLES.isEmpty()) {
            previousFocusId = -1;
            return;
        }

        // --- 2. centre, focus and roles per battle -----------------------------------------
        for (Battle battle : BATTLES) {
            battle.center = averagePosition(battle.members);
            battle.focus = pickFocus(battle, carriedFocus);
            assignRoles(battle, serverTick);
        }

        previousFocusId = BATTLES.size() == 1 && BATTLES.get(0).focus != null
            ? BATTLES.get(0).focus.getId()
            : -1;
    }

    /**
     * The battle this bot belongs to: one of its own side's, close by.
     *
     * <p>A battle is one side's fight, not everyone's in the area. Grouping by distance alone put both
     * sides of a fight into one battle, so each side's targets became the other's "enemies": a
     * 250-strong army found its own men in the enemy list, called one of them as the group focus,
     * and sent its screens to protect the enemy's wounded.</p>
     */
    private static Battle findBattleNear(ServerPlayer bot, String botName) {
        for (Battle battle : BATTLES) {
            ServerPlayer first = battle.members.get(0);
            if (!sameSide(first.getName().getString(), botName)) continue;
            for (ServerPlayer member : battle.members) {
                if (member.level() != bot.level()) continue;
                if (member.distanceToSqr(bot) <= BATTLE_RADIUS_SQ) return battle;
            }
        }
        return null;
    }

    private static Vec3 averagePosition(List<ServerPlayer> members) {
        if (members.isEmpty()) return Vec3.ZERO;
        double x = 0;
        double y = 0;
        double z = 0;
        for (ServerPlayer member : members) {
            x += member.getX();
            y += member.getY();
            z += member.getZ();
        }
        int n = members.size();
        return new Vec3(x / n, y / n, z / n);
    }

    /**
     * Chooses who the group kills first.
     *
     * <p>Weighting mirrors how players actually call a target: whoever is already hurt, whoever
     * has drifted away from their own team, whoever cannot block. The previous pick gets a
     * bonus so the squad commits rather than re-deciding every rebuild - switching focus is
     * how a group ends up doing chip damage to four people and killing none.</p>
     */
    private static Entity pickFocus(Battle battle, int previousId) {
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (LivingEntity enemy : battle.enemies) {
            if (!enemy.isAlive()) continue;

            double score = 0;

            float fraction = enemy.getHealth() / Math.max(1.0f, enemy.getMaxHealth());
            score += (1.0 - fraction) * 2.2;                 // finish what is already hurt

            // Isolation: an enemy far from their own side is the easy kill.
            double isolation = isolationOf(enemy, battle);
            score += Math.min(1.2, isolation * 0.12);

            // Close to the group's centre of mass means everyone can reach them.
            double reach = Math.sqrt(
                Math.pow(enemy.getX() - battle.center.x, 2)
                    + Math.pow(enemy.getY() - battle.center.y, 2)
                    + Math.pow(enemy.getZ() - battle.center.z, 2));
            score += Math.max(-1.0, 1.0 - reach / 12.0);

            if (enemy instanceof Player player) {
                if (player.isBlocking()) score -= 0.6;        // shielded targets waste time
                if (player.getHealth() <= 6.0f) score += 1.0; // one combo from dead
            }
            if (enemy.getId() == previousId) score += 0.8;    // commitment bonus

            if (score > bestScore) {
                bestScore = score;
                best = enemy;
            }
        }
        return best;
    }

    /** Distance from this enemy to the nearest other enemy - how alone they are. */
    private static double isolationOf(LivingEntity enemy, Battle battle) {
        double nearest = Double.MAX_VALUE;
        for (LivingEntity other : battle.enemies) {
            if (other == enemy || !other.isAlive()) continue;
            nearest = Math.min(nearest, other.distanceTo(enemy));
        }
        return nearest == Double.MAX_VALUE ? 12.0 : nearest;
    }

    /**
     * Hands out jobs.
     *
     * <p>Deliberately not an even split: most of the group goes at the focus target, because
     * that is what makes a collapse work, while a minority covers the flanks and a reserve
     * waits for space. Skill decides who leads - the best fighter present takes the spearhead
     * rather than whoever happens to be closest.</p>
     */
    private static void assignRoles(Battle battle, long serverTick) {
        List<ServerPlayer> members = new ArrayList<>(battle.members);
        if (members.isEmpty()) return;

        BotSettings settings = BotSettings.get();
        int meleeSlots = Math.max(1, settings.getSquadMeleeSlots());

        // Sort by how well placed each bot is to lead the dive: skill first, distance second.
        Entity focus = battle.focus;
        // Whoever is closest to the called target leads the collapse. With every bot
        // fighting to the same rules there is nothing else to rank them by, and proximity is
        // what a real group would go on anyway.
        if (focus != null) {
            members.sort(java.util.Comparator.comparingDouble(member -> member.distanceTo(focus)));
        }

        boolean brawl = battle.isBrawl();
        // Most of the group commits. Reserves only exist once a fight is genuinely crowded -
        // a bot standing at the back is only useful when there is no room at the front.
        boolean crowded = battle.distinctCombatants() >= 6;
        int spearheads = brawl
            ? Math.min(meleeSlots, Math.max(2, (members.size() * 2) / 3))
            : members.size();

        for (int i = 0; i < members.size(); i++) {
            ServerPlayer member = members.get(i);
            String key = Names.key(member.getName().getString());
            Role role;

            if (!brawl || i < spearheads) {
                role = Role.SPEARHEAD;
            } else if (hasStrugglingAlly(member, battle)) {
                // A bot with a badly hurt teammate covers them instead of queueing to attack.
                role = Role.SCREEN;
            } else if (crowded && i >= spearheads + 1) {
                role = Role.RESERVE;
            } else {
                role = Role.FLANKER;
            }

            // A reserve with an opening takes it rather than queueing politely.
            BotProfile profile = BotProfile.of(member.getName().getString());
            if (role == Role.RESERVE && profile.tech(0.5)) {
                role = Role.FLANKER;
            }

            ROLES.put(key, role);
        }
    }

    private static boolean hasStrugglingAlly(ServerPlayer member, Battle battle) {
        for (ServerPlayer other : battle.members) {
            if (other == member) continue;
            if (other.getHealth() / Math.max(1.0f, other.getMaxHealth()) < 0.4f
                && other.distanceTo(member) < 12.0) {
                return true;
            }
        }
        return false;
    }

    /** True when two bots are on the same side (same faction, or both untethered bots). */
    public static boolean sameSide(String a, String b) {
        if (a.equalsIgnoreCase(b)) return true;
        String factionA = BotFaction.getFaction(a);
        String factionB = BotFaction.getFaction(b);
        if (factionA != null && factionB != null) return factionA.equals(factionB);
        return factionA == null && factionB == null;
    }
}
