package scriptify.bots.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Group behaviour: stops bots from piling onto one player in a single blob and from all
 * swinging on the same server tick.
 *
 * <p>The original mod had no notion of other bots at all. Every bot independently ran
 * "nearest enemy -> walk at it -> swing when in range", so a squad converged into one
 * stack of bodies and their attacks landed in perfect unison (all cooldowns were the same
 * constant, and they all ticked in the same loop).</p>
 *
 * <p>What remains here is the low-level bookkeeping: who is on whom, how crowded a target is,
 * how far apart bots should stand, and the per-bot offset that keeps a squad's hits from
 * landing on the same tick. The tactical layer above it - focus targets, roles, collapses -
 * lives in {@link Engagement}, and the movement that expresses those decisions lives in
 * {@link Maneuver}.</p>
 *
 * <p>Ring slots and melee tickets used to live here too. Both were replaced: a ring is not how
 * players position, and a ticket queue made bots stand and wait instead of doing something
 * useful. Roles cover that job now.</p>
 *
 * <p>State is rebuilt from scratch every server tick, so there is nothing to leak.</p>
 */
public final class SquadCoordinator {

    private SquadCoordinator() {}

    /** target entity id -> bot names engaging it, sorted for stable slot assignment. */
    private static final Map<Integer, List<String>> ENGAGEMENTS = new HashMap<>();
    private static final Map<Integer, List<String>> PREVIOUS = new HashMap<>();

    public static void beginTick() {
        PREVIOUS.clear();
        PREVIOUS.putAll(ENGAGEMENTS);
        ENGAGEMENTS.clear();
        // Last tick's lists are gone, so whatever was put in order belongs to lists nobody will ask
        // about again. Clearing here is also what makes the ordering re-established once per tick.
        SORTED.clear();
    }

    /** Called by each bot once per tick with the target it intends to fight. */
    public static void declare(String botName, Entity target) {
        if (target == null) return;
        ENGAGEMENTS.computeIfAbsent(target.getId(), k -> new ArrayList<>()).add(botName);
    }

    /**
     * The squad on a target, in a stable order.
     *
     * <p>Sorted once per tick rather than once per read. The order only has to be stable - it decides
     * which stagger slot each bot takes - and the list is rebuilt from scratch every tick, so sorting
     * on read meant re-sorting the same list for every bot that asked, twice per attack interval
     * (once for the size, once for the slot).
     */
    private static List<String> squad(Entity target) {
        List<String> list = PREVIOUS.get(target.getId());
        if (list == null) list = ENGAGEMENTS.get(target.getId());
        if (list == null) return List.of();
        if (SORTED.add(list)) {
            list.sort(Comparator.naturalOrder());
        }
        return list;
    }

    /** Lists already put in order this tick. Identity-based: these are the live lists, not copies. */
    private static final java.util.Set<List<String>> SORTED =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Number of bots currently fighting this target (this bot included). */
    private static int squadSize(Entity target) {
        return squad(target).size();
    }

    /** This bot's index inside the squad, or 0 when it is alone. */
    private static int slotIndex(String botName, Entity target) {
        List<String> list = squad(target);
        int idx = list.indexOf(botName);
        return idx < 0 ? 0 : idx;
    }

    /**
     * How many other bots have already picked this target. Used by target selection so a
     * fresh bot prefers an unattended enemy over joining a five-on-one.
     */
    /** Within this of a target, an attacker is in the front line around it. */
    private static final double CONTACT_RADIUS = 3.2;

    private static final Map<Integer, long[]> CONTACT_CACHE = new HashMap<>();
    private static final ThreadLocal<List<Entity>> CONTACT_SCRATCH =
        ThreadLocal.withInitial(() -> new ArrayList<>(32));

    /**
     * How many bots are already in the front line around a target - close enough to swing at it.
     *
     * <p>Counted, not inferred from who has picked it: a whole army can pick one man, but only so
     * many bodies fit around him. Worked out once per target per tick and shared, since every bot
     * in the crowd asks the same question about the same person.</p>
     */
    public static int contactCount(Entity target) {
        if (!(target instanceof ServerPlayer player)) return 0;
        long now = player.level().getGameTime();
        long[] cached = CONTACT_CACHE.get(player.getId());
        if (cached != null && cached[0] == now) return (int) cached[1];
        List<Entity> near = CONTACT_SCRATCH.get();
        BotIndex.collectNear(player, CONTACT_RADIUS, near);
        int count = 0;
        for (Entity e : near) {
            if (e instanceof ServerPlayer other && scriptify.bots.bot.BotManager.isFakePlayer(other)
                && Math.abs(other.getY() - player.getY()) < 2.0) {
                count++;
            }
        }
        if (cached == null) {
            cached = new long[2];
            CONTACT_CACHE.put(player.getId(), cached);
        }
        cached[0] = now;
        cached[1] = count;
        return count;
    }

    public static int popularity(Entity target) {
        List<String> list = ENGAGEMENTS.get(target.getId());
        return list == null ? 0 : list.size();
    }

    /**
     * Extra delay in ticks applied to this bot's attack so a squad's hits do not land on the
     * same tick. Scales with squad size and stays inside one attack cycle.
     */
    public static int attackStagger(String botName, Entity target, BotProfile profile) {
        int size = squadSize(target);
        if (size <= 1) return 0;
        int idx = slotIndex(botName, target);
        int spread = Math.min(9, size * 2);
        return (idx * 3 + profile.phase) % (spread + 1);
    }

    /**
     * A repulsion vector pushing the bot away from nearby allies, so a group walking to the
     * same place fans out instead of merging into one column.
     */
    /**
     * How a bot sits in the crowd: pushed off whoever is too close, pulled toward the nearest
     * ally when it has drifted out on its own.
     *
     * <p>Separation alone produces an evenly spaced sheet of bots, which is not what a crowd looks
     * like. In the reference footage the mass is plainly lumpy - dense on the high ground and
     * thinning toward the front, with people moving in twos and threes ahead of it, and nobody
     * crossing open ground alone. That shape is what you get from two weak forces rather than one:
     * a short-range push that stops them merging into a column, and a longer-range pull that stops
     * them fanning out into a line. Neither is strong enough to override where the bot was going.
     *
     * <p>The pull is deliberately the weaker of the two and only applies past {@link #PAIR_RANGE},
     * so it does nothing at all to a bot that already has company - it only gathers up strays.
     */
    public static Vec3 formation(ServerPlayer bot, Iterable<ServerPlayer> allies,
                                  double minSpacing) {
        Vec3 apart = separation(bot, allies, minSpacing);

        ServerPlayer nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (ServerPlayer ally : allies) {
            if (ally == bot) continue;
            double dx = ally.getX() - bot.getX();
            double dz = ally.getZ() - bot.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < nearestSq) {
                nearestSq = d2;
                nearest = ally;
            }
        }
        if (nearest == null || nearestSq < PAIR_RANGE * PAIR_RANGE || nearestSq < 1.0E-4) {
            return apart;
        }

        double distance = Math.sqrt(nearestSq);
        double pull = Math.min(1.0, (distance - PAIR_RANGE) / PAIR_RANGE) * PAIR_STRENGTH;
        return new Vec3(
            apart.x + ((nearest.getX() - bot.getX()) / distance) * pull,
            0,
            apart.z + ((nearest.getZ() - bot.getZ()) / distance) * pull);
    }

    /** Past this far from the nearest ally a bot counts as having wandered off on its own. */
    private static final double PAIR_RANGE = 7.0;

    /** And how hard it is drawn back - a nudge, weaker than the push that keeps them apart. */
    private static final double PAIR_STRENGTH = 0.45;

    public static Vec3 separation(ServerPlayer bot, Iterable<ServerPlayer> allies,
                                   double minSpacing) {
        double sx = 0;
        double sz = 0;
        int count = 0;
        for (ServerPlayer ally : allies) {
            if (ally == bot) continue;
            double dx = bot.getX() - ally.getX();
            double dz = bot.getZ() - ally.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > minSpacing * minSpacing || d2 < 1.0E-4) continue;
            double d = Math.sqrt(d2);
            double strength = (minSpacing - d) / minSpacing;
            sx += (dx / d) * strength;
            sz += (dz / d) * strength;
            count++;
        }
        if (count == 0) return Vec3.ZERO;
        return new Vec3(sx / count, 0, sz / count);
    }

    public static void clear() {
        ENGAGEMENTS.clear();
        PREVIOUS.clear();
        SORTED.clear();
    }
}
