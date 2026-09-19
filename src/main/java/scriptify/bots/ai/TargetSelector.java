package scriptify.bots.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import scriptify.bots.bot.BotFaction;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scored target selection with commitment and hysteresis.
 *
 * <p>The original mod locked on hard: a forced target stayed forced forever, a revenge
 * target stayed valid for thirty seconds regardless of what else was happening, and
 * auto-targeting always returned the absolute nearest entity. Combined with no group
 * awareness this made every bot glue itself to one player.</p>
 *
 * <p>Now each candidate is scored on distance, visibility, how hurt it is, how recently it
 * hurt this bot, and how many allies are already on it. A bot sticks with its current
 * target for a randomised commitment window and will only switch early if another target
 * scores clearly better - so it neither flip-flops nor tunnel-visions.</p>
 */
public final class TargetSelector {

    /**
     * How many candidates get a line-of-sight check.
     *
     * <p>Three is enough that the visible one almost always wins: it takes a target being
     * beaten on every cheap term at once - closer, more wounded, group-called - by three
     * separate enemies before a visible candidate is missed.</p>
     */
    private static final int FINALISTS = 3;

    /**
     * Reused candidate buffer.
     *
     * <p>A sweep in a crowded arena collects hundreds of entries; allocating that list fresh for
     * every bot, every sweep, is pure garbage. One buffer per thread, cleared and refilled.</p>
     */
    private static final ThreadLocal<List<Entity>> SCRATCH =
        ThreadLocal.withInitial(() -> new ArrayList<>(64));

    private TargetSelector() {}

    private static final Map<String, Memory> MEMORY = new HashMap<>();

    public static class Memory {
        public int targetId = -1;
        public int commitTicks;          // ticks left on the current commitment
        public int lostSightTicks;       // how long the target has been out of sight
        public long lastSwitchTick;
        public int reactionDelay;        // ticks before a newly spotted target is acted on
        public int pendingTargetId = -1;
        public int scanCooldown;         // ticks until the next full candidate sweep
    }

    public static Memory memory(String botName) {
        return MEMORY.computeIfAbsent(scriptify.bots.utils.Names.key(botName), k -> new Memory());
    }

    public static void remove(String botName) {
        MEMORY.remove(scriptify.bots.utils.Names.key(botName));
    }

    public static void clear() {
        MEMORY.clear();
    }

    /**
     * Picks the entity this bot should fight, or null.
     *
     * @param forcedName    an explicit /bots attack target, honoured while it is reachable
     * @param lastAttacker  most recent damage source, weighted heavily but not absolutely
     */
    public static Entity select(ServerPlayer bot, BotProfile profile, BotSettings settings,
                                MinecraftServer server, String forcedName, Entity lastAttacker,
                                long serverTick) {
        Memory mem = memory(bot.getName().getString());
        if (mem.commitTicks > 0) mem.commitTicks--;

        Entity current = resolve(bot, mem.targetId);

        // A commanded target always wins while it exists and is in range; that is an
        // operator instruction, not an AI decision.
        if (forcedName != null) {
            Entity forced = server.getPlayerList().getPlayer(forcedName);
            if (forced != null && forced.isAlive() && isAttackable(bot, forced, settings)) {
                remember(mem, forced, serverTick, profile);
                return forced;
            }
        }

        // With auto-target off a bot only fights what it is told to fight, or whatever hit
        // it first - it does not go looking for trouble.
        if (!settings.isAutoTargetEnabled()) {
            if (settings.isRevengeEnabled() && lastAttacker != null && lastAttacker.isAlive()
                && isAttackable(bot, lastAttacker, settings)
                && bot.distanceTo(lastAttacker) <= settings.getMaxTargetDistance()) {
                remember(mem, lastAttacker, serverTick, profile);
                return lastAttacker;
            }
            if (!settings.isFactionsEnabled()) {
                mem.targetId = -1;
                return null;
            }
        }

        double maxDist = settings.getMaxTargetDistance();
        Entity groupFocus = settings.isSquadTacticsEnabled()
            ? Engagement.focusFor(bot.getName().getString())
            : null;

        // Full candidate sweeps are the expensive part (an entity query over a box up to
        // 128 blocks across, plus a raycast per candidate). Timing logic still runs every
        // tick; only the sweep is throttled, on a per-bot jittered interval so bots do not
        // all sweep on the same tick.
        if (mem.scanCooldown > 0) {
            mem.scanCooldown--;
            if (current != null && isAttackable(bot, current, settings)
                && bot.distanceTo(current) <= maxDist) {
                return current;
            }
        }
        mem.scanCooldown = Rng.range(profile.rng(), 6, 14);

        // Two passes, and the split is the whole reason this scales.
        //
        // Scoring used to call Vision.canSee on every candidate, and canSee fires up to three
        // raycasts. In a crowded arena a sweep sees every other bot, so the work was quadratic
        // in the bot count - at 250 bots that is roughly six thousand visibility checks and up
        // to eighteen thousand raycasts per tick, against a cache far too small to hold them.
        // It put the server sixteen seconds behind within a minute.
        //
        // So: score everyone on cheap facts only (distance, health, who hit us, what the group
        // called), keep the best few, and only then pay for line of sight. Visibility still
        // decides the winner - it is just asked of three candidates instead of two hundred.
        // Candidates come from the shared index rather than a per-bot world query. Same set,
        // gathered once for the whole server instead of once per bot.
        List<Entity> candidates = SCRATCH.get();
        BotIndex.collectNear(bot, maxDist, candidates);

        // The index holds players only, which is everything this normally cares about. Mob
        // hunting is off by default; when it is on, the world query comes back for that case
        // alone rather than being paid for by everyone.
        if (settings.isTargetHostileMobs()) {
            AABB mobBox = bot.getBoundingBox().inflate(maxDist);
            for (Entity e : bot.level().getEntities(bot, mobBox)) {
                if (e instanceof net.minecraft.world.entity.Mob) candidates.add(e);
            }
        }

        List<Entity> shortlist = new ArrayList<>(FINALISTS);
        double[] shortScores = new double[FINALISTS];
        int shortCount = 0;

        for (Entity candidate : candidates) {
            if (!isAttackable(bot, candidate, settings)) continue;
            double cheap = cheapScore(bot, profile, candidate, lastAttacker, maxDist, groupFocus);

            if (shortCount < FINALISTS) {
                shortlist.add(candidate);
                shortScores[shortCount++] = cheap;
            } else {
                // Replace the weakest finalist, if this beats it.
                int weakest = 0;
                for (int i = 1; i < FINALISTS; i++) {
                    if (shortScores[i] < shortScores[weakest]) weakest = i;
                }
                if (cheap > shortScores[weakest]) {
                    shortlist.set(weakest, candidate);
                    shortScores[weakest] = cheap;
                }
            }
        }

        Entity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < shortCount; i++) {
            Entity candidate = shortlist.get(i);
            double score = shortScores[i] + (Vision.canSee(bot, candidate) ? 0.45 : -0.35);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null) {
            // Keep chasing a target that just ducked behind cover for a moment - but only
            // while it is still a legal target. Without the isAttackable check here, a player
            // switching to creative or spectator kept getting hit for the full three seconds
            // of this grace window, because the sweep dropped them from the candidate list
            // while this branch happily handed the stale reference back.
            if (current != null && mem.lostSightTicks < 60
                && isAttackable(bot, current, settings)
                && bot.distanceTo(current) <= maxDist) {
                mem.lostSightTicks++;
                return current;
            }
            mem.targetId = -1;
            mem.pendingTargetId = -1;
            return null;
        }

        if (current != null && current.isAlive() && best != current) {
            // One candidate, so the line-of-sight check here is affordable and has to be
            // included: the comparison is only fair if both sides were scored the same way.
            double currentScore = cheapScore(bot, profile, current, lastAttacker, maxDist, groupFocus)
                + (Vision.canSee(bot, current) ? 0.45 : -0.35);
            // Switching costs: stay on target during the commitment window unless the new
            // option is clearly better. The margin is personal - stubborn bots need more.
            double margin = 0.3;
            boolean committed = mem.commitTicks > 0;
            if (committed && bestScore < currentScore + margin) {
                return current;
            }
        }

        if (best != current) {
            // Do not react instantly to a new face; queue it behind a reaction delay.
            if (mem.pendingTargetId != best.getId()) {
                mem.pendingTargetId = best.getId();
                mem.reactionDelay = profile.reactionTicks();
                return current != null && current.isAlive() ? current : null;
            }
            if (mem.reactionDelay > 0) {
                mem.reactionDelay--;
                return current != null && current.isAlive() ? current : null;
            }
        }

        remember(mem, best, serverTick, profile);
        return best;
    }

    private static void remember(Memory mem, Entity target, long serverTick, BotProfile profile) {
        if (mem.targetId != target.getId()) {
            mem.targetId = target.getId();
            mem.lastSwitchTick = serverTick;
            // Commitment window: 3-9 seconds, longer for consistent personalities.
            mem.commitTicks = Rng.gaussianTicks(profile.rng(), 120, 40, 40, 240);
        }
        mem.pendingTargetId = -1;
        mem.lostSightTicks = 0;
    }

    private static Entity resolve(ServerPlayer bot, int id) {
        if (id < 0) return null;
        Entity e = bot.level().getEntity(id);
        return (e != null && e.isAlive()) ? e : null;
    }

    /**
     * Everything worth knowing about a candidate that costs nothing to find out.
     *
     * <p>Deliberately excludes line of sight, which is the one expensive term. The caller adds
     * that to the handful of candidates that survive this.</p>
     */
    private static double cheapScore(ServerPlayer bot, BotProfile profile, Entity candidate,
                                     Entity lastAttacker, double maxDist, Entity groupFocus) {
        double dist = bot.distanceTo(candidate);
        double score = 1.0 - Math.min(1.0, dist / maxDist);

        // Finish wounded enemies.
        if (candidate instanceof LivingEntity living && living.getMaxHealth() > 0) {
            double missing = 1.0 - (living.getHealth() / living.getMaxHealth());
            score += missing * 0.55;
        }

        // Whoever just hit us gets priority, but it is a weight, not a lock.
        if (lastAttacker == candidate) {
            score += 0.7;
        }

        // The group has called a target: back the call. This is what turns four bots each
        // poking a different enemy into four bots removing one. It deliberately outweighs the
        // anti-dogpile term below, because collapsing on one target *is* the plan.
        if (groupFocus == candidate) {
            score += 1.1;
        } else {
            // Otherwise, spread out: joining a pile-on is worth less than a free enemy.
            int crowd = SquadCoordinator.popularity(candidate);
            score -= crowd * 0.22;
        }

        // Real players are more interesting than mobs.
        if (candidate instanceof Player) score += 0.15;

        // Personal noise so identical situations still resolve differently per bot.
        score += profile.rng().nextGaussian() * 0.06;

        return score;
    }

    /**
     * Immediate validity check used at the point of attack, after all the throttled
     * selection logic has run. Selection is deliberately sticky - it has commitment windows,
     * reaction delays and grace periods - so a bot must still confirm, on the tick it swings,
     * that hitting this entity is legal. This is what makes going creative stop a bot
     * instantly instead of at the end of whatever window it was in.
     */
    public static boolean canStillAttack(ServerPlayer bot, Entity target, BotSettings settings) {
        return target != null && target.isAlive() && isAttackable(bot, target, settings);
    }

    /** Faction, gamemode and settings filtering. */
    public static boolean isAttackable(ServerPlayer bot, Entity entity, BotSettings settings) {
        if (entity == bot || entity == null || !entity.isAlive()) return false;
        if (!(entity instanceof LivingEntity)) return false;
        if (entity.level() != bot.level()) return false;

        if (entity instanceof Player player) {
            if (!settings.isAttackInvincible()
                && (player.isSpectator() || player.isCreative() || player.isInvulnerable())) {
                return false;
            }

            // Both names are built from a Text component, so each call allocates a String.
            // This runs once per candidate per sweep - hundreds of times per bot in a crowd -
            // so neither is fetched until something actually needs it, and the bot's own name
            // is only needed on the faction path.
            String targetName = player.getName().getString();

            if (settings.isFactionsEnabled()) {
                String botName = bot.getName().getString();
                if (!settings.isFriendlyFireEnabled() && BotFaction.areAllies(botName, targetName)) {
                    return false;
                }
                if (BotFaction.areEnemies(botName, targetName)) {
                    return true;
                }
            }

            // Case-insensitive on purpose. The backend joins a bot under whatever
            // capitalisation the name actually resolves to, so a bot asked for as "Quartzwin"
            // can join as "QuartzWin" - and those renamed ones are precisely the bots that
            // resolved a real profile and therefore have a skin. An exact-match test misses
            // them, so they fell through to the real-player branch and got attacked even with
            // "target bots" switched off.
            if (BotManager.isBot(targetName)) {
                return settings.isTargetOtherBots();
            }
            return settings.isTargetPlayers();
        }

        if (entity instanceof Mob) {
            return settings.isTargetHostileMobs();
        }
        return false;
    }
}
