package scriptify.bots.bot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.LoadGovernor;
import scriptify.bots.ai.SquadCoordinator;
import scriptify.bots.ai.Vision;

import java.util.List;

/**
 * The server tick driver.
 *
 * <p>Work is deliberately spread out rather than run for every bot every tick. Each bot
 * carries a fixed phase offset (see {@link BotProfile#phase}) and expensive subsystems only
 * run on the tick matching that offset, so with twenty bots the cost of style evaluation,
 * equipment scans and path recalculation is amortised across twenty ticks instead of
 * landing all at once. Cheap, latency-sensitive work (aiming, attacking, movement) still
 * runs every tick because anything less is visible as stutter.</p>
 */
public class BotTicker {

    private static long serverTick = 0;

    private static final int AUTO_SAVE_INTERVAL = 1200;   // 60s
    private static final int SETTINGS_FLUSH_INTERVAL = 40; // 2s
    private static final int CLEANUP_INTERVAL = 10;
    private static final int BACKEND_SYNC_INTERVAL = 100;

    private static int autoSaveCounter = 0;

    public static long tick() {
        return serverTick;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BotTicker::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        serverTick++;
        autoSaveCounter++;

        Vision.onServerTick(serverTick);
        // One shared snapshot of where everyone is, so two hundred and fifty bots do not
        // each run their own world query over the same crowd.
        scriptify.bots.ai.BotIndex.rebuild(server, serverTick);
        // How much thinking this machine can afford this tick. Read before anything thinks.
        LoadGovernor.sample(server, serverTick);
        // And how many route searches it will carry. Same reason, different resource: the load
        // governor thins decisions, which are cheap individually; this bounds the one piece of
        // work a single bot can spend a whole tick's budget on.
        scriptify.bots.ai.Pathfinder.beginTick();
        SquadCoordinator.beginTick();
        // Battlefield picture: who is fighting whom, who the group is collapsing on, and
        // what each bot's job is. Rebuilt on a global timer, not per bot.
        scriptify.bots.ai.Engagement.tick(server, serverTick);

        BotManager.tickRestore(server);
        // Straight after the queue, so a bot a scene sent for gets its state on the tick it arrives.
        BotScenes.tick(server);
        PathPopulator.tick(server);
        scriptify.bots.npc.NpcRegistry.tickSave(serverTick);

        // Staged behaviour runs from one sweep rather than the per-bot loop below, because a
        // scenario is a group of bots doing one thing, not a bot doing its own thing.
        scriptify.bots.scenario.ScenarioManager.tick(server, serverTick);
        scriptify.bots.scenario.ScenarioTools.tick(server);
        scriptify.bots.tool.BotTools.tick(server);
        scriptify.bots.scenario.ScenarioPrompt.tick(server);

        if (serverTick % CLEANUP_INTERVAL == 0) {
            BotManager.cleanupDeadBots(server);
        }
        if (serverTick % SETTINGS_FLUSH_INTERVAL == 0) {
            BotSettings.flush();
            BotManager.flushBots();
        }
        if (autoSaveCounter >= AUTO_SAVE_INTERVAL) {
            autoSaveCounter = 0;
            BotManager.updateBotData(server);
            BotManager.saveBots();
            BotManager.flushBots();
        }

        BotPathVisualizer.update(server);
        // Wandering bots and anybody recording a walk, both driven from here so they move on the
        // same clock as everything else rather than on a timer of their own.
        scriptify.bots.npc.EditMode.tick(server, serverTick);
        scriptify.bots.npc.EditSession.tick(server);
        scriptify.bots.npc.MovementReplay.tick(server);

        BotSettings settings = BotSettings.get();
        int equipInterval = Math.max(1, settings.getCheckInterval());

        // Walk the online players once and pick out the bots, rather than looking up each
        // roster name in the player list. The lookup is a case-insensitive scan of every
        // online player, so doing it per bot made the cost quadratic in the bot count -
        // sixty bots meant thousands of string comparisons every tick, for nothing.
        int botsSeen = 0;
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            if (!bot.isAlive()) continue;
            String botName = bot.getName().getString();
            if (!BotManager.isBot(botName)) continue;
            botsSeen++;
            BotActions.abortStrayMining(bot);
            LoadGovernor.applyLagBoost(bot);

            BotProfile profile = BotProfile.of(botName);

            // What the bot *is* - hittable, listed, crouching, its repeating action. Applied
            // before anything decides what it should do, so the two never disagree, and applied
            // every tick however loaded the server is: this is appearance, not decision making,
            // and a bot that flickers between hittable and not is worse than a slow one.
            scriptify.bots.npc.NpcApplier.tick(bot, server, serverTick);

            // A bot in a scenario is being driven by that scenario. Running the combat brain
            // over the top would have someone stood in a conversation break off to go and
            // fight, which is the whole thing scenarios exist not to do.
            //
            // Unless fight on sight is on and it has noticed somebody: then the fight has it, and the
            // scenario or recording gets it back when the fight is over.
            boolean replaying = scriptify.bots.npc.MovementReplay.isPlaying(botName);
            boolean staged = scriptify.bots.scenario.ScenarioManager.owns(botName);
            if ((replaying || staged) && !(scriptify.bots.ai.TargetSelector.onLookout(botName)
                && BotCombat.spot(bot, server, serverTick))) {
                if (staged) scriptify.bots.ai.Crouch.tick(bot, server);
                continue;
            }

            // Mid-air with a water bucket and a long way to fall: nothing matters more this tick, so
            // it is not left to the load governor either. A clutch a few ticks late is a death.
            if (scriptify.bots.ai.WaterClutch.tick(bot, profile, server)) {
                continue;
            }

            boolean techniqueBusy = BotCombat.isTechniqueActive(botName);

            // Can this bot afford a decision this tick? Movement inputs and head angles hold
            // between ticks, so one that sits this out keeps walking and keeps looking where it
            // was aimed; what it loses is a few hundredths of a second of reaction. Under no
            // load this is always true and costs a comparison.
            //
            // Anything already under way is exempt. A bite of a golden apple, a combo, cutting
            // out of a cobweb: those are committed actions running on their own timers, and
            // stretching one is the difference between a bot that reacts slowly and a bot that
            // is visibly broken.
            boolean committed = techniqueBusy || !BotUtils.canAct(bot)
                || scriptify.bots.ai.CobwebTactics.isBusy(botName);
            if (!committed && !LoadGovernor.shouldThink(bot, profile, serverTick)) {
                continue;
            }

            // A fresh hand on the keys every decision: whatever drives the bot this tick presses them
            // again, and anything that wants it still - bridging, eating, a clutch - gets it still.
            scriptify.bots.ai.BotInput.release(bot);

            if (!techniqueBusy) {
                BotUtils.update(bot, server, serverTick);
                scriptify.bots.fixes.EatingMovementFix.apply(bot);
            }

            // Trapped in a hole? Out of it first. Every other piece of movement code assumes
            // the way to somewhere is along the ground, so a bot in a one-block pit walks into
            // the same wall until somebody lets it out.
            if (scriptify.bots.ai.PitEscape.tick(bot, profile, server, serverTick)) {
                continue;
            }

            boolean followingWithoutAttack = BotPath.isFollowing(botName) && !BotPath.shouldAttack(botName)
                && !scriptify.bots.ai.TargetSelector.onLookout(botName);
            if (!followingWithoutAttack) {
                BotCombat.update(bot, server, serverTick);
            }

            // Not while its hands are busy: getting out of a web, fetching the water back, eating.
            // Walking the route there turned the head away from the bucket it was aiming, every tick.
            if (BotPath.isFollowing(botName) && BotUtils.canAct(bot)
                && !scriptify.bots.ai.CobwebTactics.isBusy(botName)) {
                tickPathFollowing(bot, botName, profile);
            }

            // Following a player: catch up whenever there is nothing to fight (or fighting is off).
            if (BotFollow.isFollowing(botName) && BotUtils.canAct(bot)
                && !scriptify.bots.ai.CobwebTactics.isBusy(botName)) {
                var target = BotCombat.getTarget(botName);
                if (!settings.isCombatEnabled() || target == null || !target.isAlive()) {
                    BotFollow.tick(bot, server);
                }
            }

            // Releases the crouch once whatever asked for it stops asking. Cheap: a null map
            // lookup for every bot that is not crouching.
            scriptify.bots.ai.Crouch.tick(bot, server);

            if (profile.onPhase(serverTick, equipInterval)) {
                // Re-read rather than reusing the flag from the top of the loop: combat may
                // have *started* a technique since then, and re-equipping a weapon on the tick
                // a combo begins is exactly the swap that ruins it.
                // canAct rather than the eating flag alone. A bot cutting its way out of a
                // cobweb has deliberately picked up a sword, and auto-equip put a pickaxe back
                // in its hand a tick later - so it stood in the web mining at fist speed with a
                // sword in its bag, which is exactly what it looked like.
                if (BotUtils.canAct(bot) && !BotCombat.isTechniqueActive(botName)) {
                    BotEquipment.autoEquip(bot);
                }
            }
        }

        LoadGovernor.noteBotCount(botsSeen);

        if (serverTick % BACKEND_SYNC_INTERVAL == 0) {
            syncBackendSettings(server, settings);
        }
    }

    /** Walks a bot along its assigned patrol path when it is not busy fighting. */
    /**
     * How close counts as having reached a path point, measured flat.
     *
     * <p>Flat on purpose. This was a plain three-dimensional distance, which a bhopping bot fails
     * for most of its stride: the apex of a jump is over a block above the ground on its own, so a
     * bot sailing directly over a point was still more than 1.5 blocks from it and never registered
     * arriving. It would then turn back for a point it was standing on top of, and circle.</p>
     *
     * <p>A path point is a place on the ground. Whether the bot is mid-hop above it is not the
     * question being asked.</p>
     */
    private static final double POINT_REACH = 1.5;
    private static final double POINT_REACH_SQR = POINT_REACH * POINT_REACH;

    /**
     * Vertical allowance, which still has to exist so a bot does not tick off a point on the floor
     * below or the roof above. Three blocks covers a jump and a step; a storey is more than that.
     */
    private static final double POINT_VERTICAL_REACH = 3.0;

    private static boolean reachedPoint(Vec3 botPos, Vec3 point) {
        double dx = botPos.x - point.x;
        double dz = botPos.z - point.z;
        if (dx * dx + dz * dz > POINT_REACH_SQR) {
            return false;
        }
        return Math.abs(botPos.y - point.y) <= POINT_VERTICAL_REACH;
    }

    /** How far from a waypoint a bot's own spot for it may be, so a crowd on one route spreads out. */
    private static final double WANDER = 4.5;

    /** Ticks a bot gets to reach its own spot before it settles for the waypoint itself. */
    private static final int WANDER_PATIENCE = 300;

    /**
     * Where this bot actually walks for the current waypoint: its own spot within a few blocks of it.
     *
     * <p>Every bot heading for the exact same points walked the exact same line, single file. Each one
     * now picks its own spot near every point - fixed per bot and point, so it does not jitter - and
     * the crowd moves the same way along a band rather than a thread. The spot is only used when the
     * bot could walk straight from the waypoint to it on solid, open ground, so it never sends a bot
     * into a wall, a hole or across a river the route was drawn to avoid; and a bot that still has
     * not reached it after a while goes for the waypoint itself.</p>
     */
    private static Vec3 wanderTarget(ServerPlayer bot, String botName, Vec3 point) {
        BotPath.PathFollower follower = BotPath.getFollower(botName);
        if (follower == null) return point;
        // A one-point route is a post to stand on, not a route to walk.
        BotPath.PathData route = BotPath.getPath(follower.pathName);
        if (route == null || route.points.size() < 2) return point;
        if (follower.wanderIndex != follower.currentPoint) {
            follower.wanderIndex = follower.currentPoint;
            follower.wanderTicks = 0;
            follower.wanderPoint = null;
            // Splittable, not Random: Random's first number barely moves between neighbouring seeds,
            // so Walker1 and Walker2 picked nearly the same spot.
            var rng = new java.util.SplittableRandom(botName.hashCode() * 31L + follower.currentPoint);
            double angle = rng.nextDouble() * Math.PI * 2;
            double radius = WANDER * Math.sqrt(rng.nextDouble());
            // From where the bot stands too, whenever it could walk straight to the waypoint itself. A
            // spot that is sound from the waypoint can still lie across the edge of a walkway from here,
            // and the bot cut that corner and fell off the build.
            Vec3 here = bot.position();
            boolean direct = BotNavigation.straightWalkable(bot.level(), here, point);
            // Shrink toward the waypoint until the spot is somewhere it can safely walk to.
            for (double r = radius; r > 0.5 && follower.wanderPoint == null; r *= 0.5) {
                Vec3 spot = new Vec3(point.x + Math.cos(angle) * r, point.y, point.z + Math.sin(angle) * r);
                if (BotNavigation.straightWalkable(bot.level(), point, spot)
                    && (!direct || BotNavigation.straightWalkable(bot.level(), here, spot))) {
                    follower.wanderPoint = spot;
                }
            }
        }
        if (follower.wanderPoint == null) return point;
        // Standing on its spot is not being stuck: at the end of a route it waits there for good.
        if (reachedPoint(bot.position(), follower.wanderPoint)) follower.wanderTicks = 0;
        if (++follower.wanderTicks > WANDER_PATIENCE) return point;
        return follower.wanderPoint;
    }

    private static void tickPathFollowing(ServerPlayer bot, String botName, BotProfile profile) {
        BotPath.PathFollower follower = BotPath.getFollower(botName);
        if (follower == null) return;
        boolean shouldAttack = BotPath.shouldAttack(botName) || scriptify.bots.ai.TargetSelector.onLookout(botName);
        var target = BotCombat.getTarget(botName);
        boolean hasTarget = target != null && target.isAlive();

        if (shouldAttack && hasTarget) {
            if (!BotPath.isInCombat(botName)) {
                BotPath.startCombat(botName, BotPath.getNextPoint(botName));
            }
            // Fighting on one spot is not being stuck.
            follower.stuckAnchor = null;
            return;
        }

        if (BotPath.isInCombat(botName)) {
            BotPath.endCombat(botName);
        }

        long now = bot.level().getGameTime();
        if (lookingAround(bot, botName, follower, profile, now)) return;

        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        Vec3 resume = BotPath.getPausedPoint(botName);
        if (resume != null) {
            if (reachedPoint(botPos, resume)) {
                BotPath.clearPausedPoint(botName);
            } else if (stuck(follower, botPos, now)) {
                // Wherever the fight left it, the route itself is the thing to get back to.
                BotPath.clearPausedPoint(botName);
            } else {
                faceAlongPath(bot, profile, botPos, resume);
                BotNavigation.moveTowardPosition(bot, resume, 1.0);
            }
            return;
        }

        Vec3 point = BotPath.getNextPoint(botName);
        if (point == null) return;
        Vec3 next = wanderTarget(bot, botName, point);
        if (reachedPoint(botPos, next)) {
            BotPath.advanceToNextPoint(botName);
            follower.stuckAnchor = null;
            follower.detour = null;
            return;
        }
        if (stuck(follower, botPos, now)) {
            rescue(bot, botName, follower, point, now);
        }
        Vec3 aim = follower.detour != null ? detourStep(bot, follower, botPos) : null;
        if (aim == null) aim = next;
        faceAlongPath(bot, profile, botPos, aim);
        BotNavigation.moveTowardPosition(bot, aim, 1.0);
    }

    /**
     * Some bots on a mixed route stop now and then, wherever they happen to be, and look about them.
     *
     * @return true while the bot is stood looking, and this tick's walking should not happen
     */
    private static boolean lookingAround(ServerPlayer bot, String botName, BotPath.PathFollower follower,
                                         BotProfile profile, long now) {
        if (now < follower.pausedUntil) {
            scriptify.bots.scenario.Roamer.idleLook(bot, profile, follower.glance);
            scriptify.bots.scenario.Roamer.halt(bot);
            follower.stuckAnchor = null;
            return true;
        }
        double chance = BotPath.pauseChanceOf(botName);
        if (chance <= 0 || !bot.onGround() || !scriptify.bots.ai.Rng.chance(profile.rng(), chance)) {
            return false;
        }
        follower.pausedUntil = now + scriptify.bots.ai.Rng.range(profile.rng(), 40, 160);
        if (follower.glance == null) follower.glance = new scriptify.bots.scenario.Roamer.State();
        follower.glance.glanceTicks = 0;
        scriptify.bots.scenario.Roamer.halt(bot);
        return true;
    }

    /** Longer than this inside one 3x3 patch while trying to walk somewhere, and a bot is stuck. */
    private static final int STUCK_TICKS = 80;

    /** Half the side of that patch. */
    private static final double STUCK_SPAN = 1.5;

    /**
     * How much harder than everyday steering the search looks for a way, as a multiple of the
     * per-search node budget. A bot that dropped off a walkway needs the stairs, which may be a
     * street away; the everyday budget is sized for stepping round a pillar.
     */
    private static final int RESCUE_BUDGET_FACTOR = 6;

    /** Rescues allowed without the bot leaving its patch before it is put back on the route. */
    private static final int MAX_RESCUES = 3;

    /** A point closer than this is where the bot already is, not somewhere to rejoin the route. */
    private static final double REJOIN_MIN = 3.0;

    /** Tick of the last rescue search. One a tick across the server, because each is a big one. */
    private static long lastRescueTick = -1;

    /** Whether the bot has spent too long inside one 3x3 patch. Starts a fresh watch whenever it leaves. */
    private static boolean stuck(BotPath.PathFollower follower, Vec3 here, long now) {
        Vec3 anchor = follower.stuckAnchor;
        if (anchor == null || Math.abs(here.x - anchor.x) > STUCK_SPAN
            || Math.abs(here.z - anchor.z) > STUCK_SPAN || Math.abs(here.y - anchor.y) > STUCK_SPAN) {
            follower.stuckAnchor = here;
            follower.stuckSince = now;
            follower.rescues = 0;
            return false;
        }
        return now - follower.stuckSince >= STUCK_TICKS;
    }

    /**
     * Gets a stuck bot moving again, Baritone-style: a proper search for a way to the waypoint from
     * wherever it has ended up, followed step by step.
     *
     * <p>Everyday steering only searches a little and otherwise walks straight at the point, which is
     * exactly what keeps a bot that fell off a build pressed against the wall under its waypoint. With
     * no way there inside the search, it picks the route up again at the nearest point on its own
     * level, the way somebody who missed the stairs would; with nothing at its level either, it is put
     * back on the route.</p>
     */
    private static void rescue(ServerPlayer bot, String botName, BotPath.PathFollower follower,
                               Vec3 point, long now) {
        // Somebody else's turn this tick: asked again on the next one, since the bot is still stuck.
        if (lastRescueTick == now || !scriptify.bots.ai.Pathfinder.tryReserve()) return;
        lastRescueTick = now;
        follower.stuckAnchor = bot.position();
        follower.stuckSince = now;
        // The offset spot is what walked it off the edge; the route point itself is the safe target.
        follower.wanderIndex = follower.currentPoint;
        follower.wanderPoint = null;

        // A route the search finds but the bot cannot walk (a door, a gap to jump) would be searched
        // for again and again; a few tries without ever leaving the patch is the end of trying.
        boolean outOfTries = ++follower.rescues > MAX_RESCUES;
        if (!outOfTries) {
            var result = scriptify.bots.ai.Pathfinder.find(bot.level(), bot.blockPosition(),
                net.minecraft.core.BlockPos.containing(point),
                BotSettings.get().getPathNodeBudget() * RESCUE_BUDGET_FACTOR, 1.5);
            // Complete but empty is "already within reach of the point" - stuck next to it, so a route
            // there is no help and the bot needs rejoining or putting back like any other dead end.
            if (result.complete() && !result.isEmpty()) {
                follower.detour = result.waypoints();
                follower.detourIndex = 0;
                return;
            }
        }
        follower.detour = null;
        int rejoin = outOfTries ? -1 : nearestPointAtLevel(follower, bot.position());
        if (rejoin >= 0 && rejoin != follower.currentPoint) {
            BotPath.setBotPathIndex(botName, rejoin);
            return;
        }
        follower.rescues = 0;
        // ponytail: walled in with no way to the route inside the search budget - put it back on the
        // route rather than leave it pressing a wall; a wider search is the upgrade if this shows on camera
        bot.absSnapTo(point.x, point.y, point.z, bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
    }

    /** The route point nearest the bot that runs at its own height, or -1 when there is none. */
    private static int nearestPointAtLevel(BotPath.PathFollower follower, Vec3 here) {
        BotPath.PathData route = BotPath.getPath(follower.pathName);
        if (route == null) return -1;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < route.points.size(); i++) {
            Vec3 p = route.points.get(i);
            if (Math.abs(p.y - here.y) > STUCK_SPAN) continue;
            double distance = p.distanceToSqr(here);
            // Not the one it is stood on: rejoining there just walks it back into the same wall.
            if (distance < REJOIN_MIN * REJOIN_MIN) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * The next step of a rescue route: the furthest of the next few that is a straight walk away, so
     * the bot cuts the corners of a block-by-block route the way a player would. Null once it is done.
     */
    private static Vec3 detourStep(ServerPlayer bot, BotPath.PathFollower follower, Vec3 here) {
        List<Vec3> route = follower.detour;
        int last = Math.min(route.size() - 1, follower.detourIndex + 6);
        for (int i = last; i > follower.detourIndex; i--) {
            if (BotNavigation.straightWalkable(bot.level(), here, route.get(i))) {
                follower.detourIndex = i;
                break;
            }
        }
        Vec3 step = route.get(follower.detourIndex);
        if (Math.hypot(step.x - here.x, step.z - here.z) < 0.7 && Math.abs(step.y - here.y) < 1.2) {
            if (++follower.detourIndex >= route.size()) {
                follower.detour = null;
                return null;
            }
            step = route.get(follower.detourIndex);
        }
        return step;
    }

    /**
     * Look where you are going, not at the thing you are going to.
     *
     * <p>Path following used to aim straight at the waypoint. A waypoint is a point on the
     * ground, so the closer the bot got the further down it looked - it arrived staring at its
     * own feet, then snapped its head round to the next point and set off again. Both halves of
     * that read as robotic, and neither is what walking looks like.</p>
     *
     * <p>Aiming at a spot along the direction of travel, at eye height and a fixed distance
     * out, keeps the gaze level and turns the corner gradually: the aim point swings smoothly
     * as the bearing changes, and the aim controller is rate limited on top of that.</p>
     */
    static void faceAlongPath(ServerPlayer bot, BotProfile profile,
                                      Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double flat = Math.hypot(dx, dz);
        if (flat < 0.05) return;

        Vec3 eye = bot.getEyePosition();
        Vec3 ahead = new Vec3(
            eye.x + (dx / flat) * 10.0,
            // Follow the climb or descent of the route, but gently - a slope is not a reason
            // to stare at the floor.
            eye.y + Math.max(-2.0, Math.min(2.0, (to.y - from.y) * 0.35)),
            eye.z + (dz / flat) * 10.0);
        AimController.lookAtPos(bot, ahead, profile, false);
    }

    /**
     * Keeps the fake-player backend's own settings in step with ours. The original ran this
     * every second and rebuilt a whole custom command source each time; once every five
     * seconds through the cached silent source is plenty.
     */
    private static void syncBackendSettings(MinecraftServer server, BotSettings settings) {
        BotActions.execute(server, "herobot botLeaveOnDeath " + settings.isBotLeaveOnDeath());
    }
}
