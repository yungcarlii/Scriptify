package scriptify.bots.bot;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.Pathfinder;
import scriptify.bots.ai.Rng;
import scriptify.bots.ai.SquadCoordinator;
import scriptify.bots.ai.Swimmer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Movement: steering, obstacle handling, and A* path following.
 *
 * <p>Compared to the original this keeps the cheap steering layer (it is what makes combat
 * movement feel fluid) but puts a real path underneath it, so bots can leave a room. It
 * also removes the two behaviours that made group movement look robotic: every bot walked
 * the exact centre line to its target, and every bot bunny-hopped on the same cadence.</p>
 */
public class BotNavigation {

    private static final Map<String, NavigationState> navStates = new HashMap<>();

    public static class NavigationState {
        public int stuckTicks = 0;
        public Vec3 lastPosition = null;
        public int avoidDirection = 0;
        public int avoidTicks = 0;
        public int jumpCooldown = 0;

        public Vec3 spawnPosition = null;
        public Vec3 wanderTarget = null;
        public int wanderCooldown = 0;

        // Path following
        public List<Vec3> path = List.of();
        public int pathIndex = 0;
        public BlockPos pathGoal = null;
        public int pathCooldown = 0;
        public int pathFailures = 0;

        // Combat movement
        public int strafeDir = 1;
        public int strafeTicks = 0;
        public boolean wtapActive = false;
        public int wtapTicks = 0;
        public int knockbackTicks = 0;
        public int bhopCooldown = 0;

        // Edge safety: the straight line to the goal, before sidesteps and strafing bend it, and
        // whether the ground either side of the bot falls away (a bridge, a ledge).
        public double goalDirX = 0;
        public double goalDirZ = 0;
        public boolean narrowFooting = false;
        /** Last tick's movement was refused at an edge: standing still there is not being stuck. */
        public boolean edgeHeld = false;
    }

    public static NavigationState getState(String botName) {
        return navStates.computeIfAbsent(scriptify.bots.utils.Names.key(botName), k -> new NavigationState());
    }

    public static void removeState(String botName) {
        navStates.remove(scriptify.bots.utils.Names.key(botName));
    }

    public static void clear() {
        navStates.clear();
    }

    /** Requests a sprint reset on the next grounded tick (w-tap knockback technique). */
    public static void startWtap(String botName) {
        NavigationState state = navStates.get(scriptify.bots.utils.Names.key(botName));
        if (state != null) {
            state.wtapActive = true;
            state.wtapTicks = 2;
        }
    }

    // ===================== public movement API =====================

    public static void moveToward(ServerPlayer bot, Entity target, double speed) {
        moveTowardPosition(bot, new Vec3(target.getX(), target.getY(), target.getZ()), speed);
    }

    public static void moveAway(ServerPlayer bot, Entity target, double speed) {
        NavigationState state = getState(bot.getName().getString());
        double dx = bot.getX() - target.getX();
        double dz = bot.getZ() - target.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-4) { dx = 1; dz = 0; dist = 1; }
        dx /= dist;
        dz /= dist;

        // Do not reverse off a ledge: if the ground behind is missing, slide sideways.
        if (!scriptify.bots.ai.Vision.hasFooting(bot, new Vec3(dx, 0, dz), 1.6)) {
            double tmp = dx;
            dx = -dz;
            dz = tmp;
        }

        Vec3 away = new Vec3(bot.getX() + dx * 6, bot.getY(), bot.getZ() + dz * 6);
        tickTimers(state);
        steer(bot, away, speed, state, false, null);
    }

    public static void moveTowardPosition(ServerPlayer bot, Vec3 targetPos, double speed) {
        NavigationState state = getState(bot.getName().getString());
        tickTimers(state);

        BotSettings settings = BotSettings.get();
        Vec3 immediate = targetPos;

        if (settings.isPathfindingEnabled()) {
            Vec3 waypoint = followPath(bot, state, targetPos, settings);
            if (waypoint != null) immediate = waypoint;
        }

        steer(bot, immediate, speed, state, false, null);
    }

    /**
     * Combat movement: heads for a position while strafing, keeping clear of allies.
     *
     * @param spacing minimum distance to hold from other bots
     */
    public static void moveCombat(ServerPlayer bot, Vec3 targetPos, double speed,
                                  BotProfile profile, double strafeIntensity, double spacing) {
        NavigationState state = getState(bot.getName().getString());
        tickTimers(state);

        Vec3 goal = targetPos;
        if (spacing > 0) {
            Vec3 push = SquadCoordinator.formation(bot, nearbyBots(bot), spacedFor(bot, targetPos, spacing));
            if (push.lengthSqr() > 1.0E-6) {
                goal = new Vec3(goal.x + push.x * 2.5, goal.y, goal.z + push.z * 2.5);
            }
        }
        steer(bot, goal, speed, state, strafeIntensity > 0.01, profile, strafeIntensity);
    }

    /**
     * Allies close enough to bump into, for spacing.
     *
     * <p>Reads the shared index instead of running its own world query. This is called for
     * every bot on every combat tick, so at two hundred and fifty bots it was two hundred and
     * fifty box queries a tick over the same crowd.</p>
     */
    private static Iterable<ServerPlayer> nearbyBots(ServerPlayer bot) {
        // Two reused buffers rather than two fresh lists. This runs for every bot on every combat
        // tick, so at two hundred and fifty bots the old version handed the collector five hundred
        // short-lived ArrayLists a tick - ten thousand a second - which is the allocation the shared
        // index was introduced to stop paying. The result is consumed inside moveCombat before
        // anything else can call this, so a single buffer per list is enough.
        java.util.List<Entity> near = NEAR_SCRATCH.get();
        scriptify.bots.ai.BotIndex.collectNear(bot, 6.0, near);
        java.util.List<ServerPlayer> out = ALLY_SCRATCH.get();
        out.clear();
        for (Entity e : near) {
            if (e instanceof ServerPlayer p && BotManager.isBot(p.getName().getString())) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * How much room this bot wants, given where it is in the fight.
     *
     * <p>A crowd is not evenly spaced, and spacing everyone identically is what made a roster read
     * as a formation rather than as people. In the footage the density runs as a gradient: packed
     * at the back where nobody can reach anything anyway, opening out toward the front where there
     * is fighting to do and standing on someone else costs you the swing.
     *
     * <p>So the bots at the sharp end hold more room than the ones behind them, and it falls out of
     * one number rather than a second system - distance to what they are walking at.
     */
    private static double spacedFor(ServerPlayer bot, Vec3 targetPos, double spacing) {
        double dx = bot.getX() - targetPos.x;
        double dz = bot.getZ() - targetPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= FRONT_LINE) {
            return spacing * FRONT_SPREAD;
        }
        if (distance >= REAR_LINE) {
            return spacing * REAR_PACK;
        }
        // Between the two, slide smoothly rather than stepping - a visible band edge in a crowd
        // reads as a formation marker, which is the thing this exists to avoid.
        double t = (distance - FRONT_LINE) / (REAR_LINE - FRONT_LINE);
        return spacing * (FRONT_SPREAD + (REAR_PACK - FRONT_SPREAD) * t);
    }

    /** Inside this of the target a bot is at the front and wants elbow room. */
    private static final double FRONT_LINE = 6.0;

    /** Past this it is queueing, and standing closer together is what a queue looks like. */
    private static final double REAR_LINE = 20.0;

    private static final double FRONT_SPREAD = 1.35;
    private static final double REAR_PACK = 0.55;

    private static final ThreadLocal<java.util.List<Entity>> NEAR_SCRATCH =
        ThreadLocal.withInitial(() -> new java.util.ArrayList<>(16));
    private static final ThreadLocal<java.util.List<ServerPlayer>> ALLY_SCRATCH =
        ThreadLocal.withInitial(() -> new java.util.ArrayList<>(8));

    // ===================== path following =====================

    /**
     * Returns the next waypoint to steer at, recalculating the route when the goal moves or
     * the current one is exhausted. Recalculation is rate-limited per bot.
     */
    private static Vec3 followPath(ServerPlayer bot, NavigationState state,
                                    Vec3 goal, BotSettings settings) {
        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        double directDist = botPos.distanceTo(goal);

        // Close by, or clear line of movement: plain steering is better and cheaper.
        if (directDist < 4.0) {
            state.path = List.of();
            return null;
        }

        BlockPos goalBlock = BlockPos.containing(goal.x, goal.y, goal.z);
        boolean goalMoved = state.pathGoal == null
            || state.pathGoal.distSqr(goalBlock) > 9;

        if (state.pathCooldown > 0) state.pathCooldown--;

        boolean exhausted = state.path.isEmpty() || state.pathIndex >= state.path.size();
        // The per-tick search budget is claimed last, after this bot has established it actually
        // wants a route. Refused, it keeps the path it has and tries again next tick - which is
        // why the cooldown is deliberately not touched here: being crowded out of a tick is not
        // the same as having searched and failed, and must not be punished like it.
        if ((goalMoved || exhausted) && state.pathCooldown <= 0
            && Pathfinder.tryReserve()) {
            Level world = bot.level();
            BlockPos start = BlockPos.containing(bot.getX(), bot.getY(), bot.getZ());
            Pathfinder.Result result = Pathfinder.find(world, start, goalBlock,
                settings.getPathNodeBudget(), 1.5);

            state.path = result.waypoints();
            state.pathIndex = 0;
            state.pathGoal = goalBlock;
            // Back off harder after repeated failures so an unreachable target does not
            // burn the node budget every second.
            state.pathFailures = result.complete() ? 0 : Math.min(6, state.pathFailures + 1);
            state.pathCooldown = 15 + state.pathFailures * 20;
        }

        if (state.path.isEmpty()) return null;

        Vec3 waypoint = state.path.get(Math.min(state.pathIndex, state.path.size() - 1));
        double dist = botPos.distanceTo(waypoint);
        if (dist < 1.25) {
            state.pathIndex++;
            if (state.pathIndex >= state.path.size()) {
                state.path = List.of();
                return null;
            }
            waypoint = state.path.get(state.pathIndex);
        }
        return waypoint;
    }

    /** True when the bot has no route to the goal (used to trigger bridging). */
    public static boolean pathBlocked(String botName) {
        NavigationState state = navStates.get(scriptify.bots.utils.Names.key(botName));
        return state != null && state.pathFailures >= 2;
    }

    // ===================== steering =====================

    private static void tickTimers(NavigationState state) {
        if (state.jumpCooldown > 0) state.jumpCooldown--;
        if (state.avoidTicks > 0) state.avoidTicks--;
        if (state.bhopCooldown > 0) state.bhopCooldown--;
        if (state.wtapTicks > 0) state.wtapTicks--;
    }

    private static void steer(ServerPlayer bot, Vec3 targetPos, double speed,
                              NavigationState state, boolean combat, BotProfile profile) {
        steer(bot, targetPos, speed, state, combat, profile, 0.5);
    }

    private static void steer(ServerPlayer bot, Vec3 targetPos, double speed,
                              NavigationState state, boolean combat, BotProfile profile,
                              double strafeIntensity) {
        Level world = bot.level();
        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());

        // Knockback: stop trying to walk while being flung, the way a player would.
        if (isKnockedBack(bot) && state.knockbackTicks < 8) {
            state.knockbackTicks++;
            bot.setSprinting(false);
            bot.zza = 0;
            bot.xxa = 0;
            state.lastPosition = botPos;
            return;
        }
        state.knockbackTicks = 0;

        // Deep water is its own thing, handled before anything below gets a chance to treat it
        // like ground. Every land rule here ends in a jump - the stuck check, a step up, a hole,
        // a bunny hop - and a ground jump in water is what had the bots leaping about like fish.
        if (Swimmer.isSwimming(bot)) {
            Swimmer.swimToward(bot, targetPos, speed);
            state.stuckTicks = 0;
            state.lastPosition = botPos;
            return;
        }
        Swimmer.leave(bot);

        checkIfStuck(bot, state);

        double dx = targetPos.x - botPos.x;
        double dz = targetPos.z - botPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist > 0.1) {
            dx /= horizontalDist;
            dz /= horizontalDist;
        }
        state.goalDirX = dx;
        state.goalDirZ = dz;
        // On a one-wide bridge or a ledge, the ground falls away to one side or the other. A
        // player there walks: no strafing and no hopping, both of which go sideways.
        state.narrowFooting = bot.onGround()
            && (isDangerousDrop(world, botPos.x - dz * 0.9, botPos.y, botPos.z + dx * 0.9)
                || isDangerousDrop(world, botPos.x + dz * 0.9, botPos.y, botPos.z - dx * 0.9));

        // Sidestep when stuck against geometry.
        if (state.avoidTicks > 0) {
            double tmp = dx;
            if (state.avoidDirection > 0) { dx = -dz; dz = tmp; }
            else { dx = dz; dz = -tmp; }
        }

        if (combat && state.narrowFooting) {
            strafeIntensity = 0;
        }
        if (combat && strafeIntensity > 0) {
            updateStrafe(state, profile, strafeIntensity);
            double blend = 0.35 + strafeIntensity * 0.45;
            double tmp = dx;
            dx = dx + dz * state.strafeDir * blend;
            dz = dz - tmp * state.strafeDir * blend;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0E-4) { dx /= len; dz /= len; }
        }

        // --- obstacle probes ---
        BlockPos feetPos = BlockPos.containing(botPos.x + dx * 0.6, botPos.y, botPos.z + dz * 0.6);
        BlockPos headPos = feetPos.above();
        boolean blockAtFeet = isBlockSolid(world, feetPos);
        boolean blockAtHead = isBlockSolid(world, headPos);
        boolean canStepUp = blockAtFeet && !blockAtHead && !isBlockSolid(world, feetPos.above(2));
        boolean isWall = blockAtFeet && blockAtHead;
        boolean onLadder = isClimbable(world, BlockPos.containing(botPos.x, botPos.y, botPos.z))
            || isClimbable(world, BlockPos.containing(botPos.x, botPos.y + 1, botPos.z));
        BlockPos groundFront = BlockPos.containing(botPos.x + dx * 1.3, botPos.y - 1, botPos.z + dz * 1.3);
        boolean holeAhead = !isBlockSolid(world, groundFront) && !isBlockSolid(world, groundFront.below());
        // A hole is only worth leaping if there is somewhere to land on the far side. Jumping at
        // one that turns out to be the edge of a cliff, or of a bridge over the void, is not a
        // thing a player does - and it was exactly how bots chasing someone across a gap died.
        if (holeAhead && isDangerousDrop(world, groundFront.getX() + 0.5, botPos.y, groundFront.getZ() + 0.5)
            && !safeLanding(world, botPos, dx, dz)) {
            holeAhead = false;
        }
        // A fall this close ahead: no hopping towards it either. A bunny hop covers two or three
        // blocks and cannot be steered once it has left the ground.
        // Stricter than the edge guard: any step down at all. Landing from a hop a block lower,
        // still carrying the run, is how a bot that sprint-hopped off its own pillar went on over
        // the edge of the platform below.
        boolean edgeNear = dropWithin(world, botPos, dx, dz, 3.0)
            || stepDownWithin(world, botPos, dx, dz, 3.0);

        BotSettings settings = BotSettings.get();
        String botName = bot.getName().getString();

        String pathWalkType = pathWalkType(botName);

        if (bot.onGround() && state.jumpCooldown <= 0) {
            if (canStepUp) {
                bot.jumpFromGround();
                bot.push(dx * 0.2, 0, dz * 0.2);
                state.jumpCooldown = 8;
            } else if (onLadder) {
                bot.jumpFromGround();
                state.jumpCooldown = 5;
            } else if (holeAhead) {
                bot.jumpFromGround();
                bot.push(dx * 0.35, 0.05, dz * 0.35);
                state.jumpCooldown = 12;
            } else if (isWall && state.avoidTicks <= 0) {
                state.avoidDirection = (Math.random() > 0.5) ? 1 : -1;
                state.avoidTicks = 25;
                bot.jumpFromGround();
                state.jumpCooldown = 10;
            }
        }

        if (onLadder) {
            bot.push(0, 0.12, 0);
            bot.setSprinting(false);
            bot.zza = 1.0f;
            state.lastPosition = botPos;
            return;
        }

        // Bunny hop, on a per-bot irregular cadence instead of every landing.
        boolean useBhop = (pathWalkType != null) ? pathWalkType.equals("bhop") : settings.isBhopEnabled();
        if (useBhop && speed >= 1.0 && bot.onGround() && !canStepUp && !isWall && !holeAhead
            && !edgeNear && !state.narrowFooting
            && state.jumpCooldown <= 0 && state.bhopCooldown <= 0) {
            bot.jumpFromGround();
            state.jumpCooldown = 10;
            state.bhopCooldown = profile != null
                ? Rng.range(profile.rng(), 0, 14)
                : (int) (Math.random() * 10);
        }

        // W-tap: drop sprint for a couple of ticks after landing a hit to reset knockback.
        boolean stopSprint = false;
        if (state.wtapActive) {
            stopSprint = true;
            if (state.wtapTicks <= 0) state.wtapActive = false;
        }

        applyMovement(bot, state, dx, dz, speed, combat, strafeIntensity, stopSprint,
            pathWalkType, horizontalDist);
        state.lastPosition = botPos;
    }

    /**
     * Applies the desired world-space direction as movement input.
     *
     * <p><b>This is the fix for bots creeping backwards.</b> The old code set
     * {@code forwardSpeed = 1.0} unconditionally, which means "walk in the direction you are
     * facing" - so a bot that was facing its target while trying to create space had the
     * backend driving it <em>forwards, into the target</em>, while a velocity nudge pushed it
     * the other way. The two cancelled out into a slow, floaty drift that looked nothing like
     * a player moving.</p>
     *
     * <p>The direction is now decomposed into the bot's own frame, so forward really means
     * forward and strafe really means strafe. Two things follow naturally from that:</p>
     * <ul>
     *   <li>movement happens at proper walking speed instead of fighting itself;</li>
     *   <li>sprinting can be gated on actually moving forwards, which is Minecraft's own rule.
     *       You cannot sprint backwards in this game - which is exactly why real players turn
     *       and run instead of backpedalling, and now the bots have the same incentive.</li>
     * </ul>
     */
    private static void applyMovement(ServerPlayer bot, NavigationState state,
                                      double dx, double dz, double speed, boolean combat,
                                      double strafeIntensity, boolean stopSprint,
                                      String pathWalkType, double horizontalDist) {
        // Minecraft frame: forward is (-sin yaw, cos yaw); positive sidewaysSpeed is left,
        // which is (cos yaw, sin yaw).
        double yaw = Math.toRadians(bot.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        double forward = -dx * sin + dz * cos;
        double sideways = dx * cos + dz * sin;

        // Combat strafing is layered on top of the intended direction rather than replacing it.
        if (combat) {
            sideways += state.strafeDir * strafeIntensity * 0.55;
            double magnitude = Math.hypot(forward, sideways);
            if (magnitude > 1.0) {
                forward /= magnitude;
                sideways /= magnitude;
            }
        }

        boolean walkOnly = pathWalkType != null && pathWalkType.equals("walk");
        // Sprint only when genuinely committed forwards, and never during a w-tap.
        boolean sprint = !walkOnly && !stopSprint && forward > 0.72 && horizontalDist > 1.2;

        // Where that input actually takes the bot, strafe included. Strafing is sideways, and
        // sideways along the edge of a bridge or a cliff is straight off it.
        double wx = -sin * forward + cos * sideways;
        double wz = cos * forward + sin * sideways;
        Vec3 here = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        Level world = bot.level();
        // Further ahead the faster it is going: a sprint covers a block in four ticks.
        Vec3 v0 = bot.getDeltaMovement();
        double lookahead = EDGE_LOOKAHEAD + Math.min(1.5, Math.hypot(v0.x, v0.z) * 4.0);
        state.edgeHeld = false;
        if (dropWithin(world, here, wx, wz, lookahead)) {
            // Keep the part of the move that stays on solid ground: first try without the strafe,
            // then give up on moving at all. A player at an edge stops; they do not step off.
            // Then the plain line to the goal, before any flanking or sidestep bent it.
            if (!dropWithin(world, here, dx, dz, lookahead)) {
                forward = -dx * sin + dz * cos;
                sideways = dx * cos + dz * sin;
            } else if (!dropWithin(world, here, state.goalDirX, state.goalDirZ, lookahead)) {
                dx = state.goalDirX;
                dz = state.goalDirZ;
                forward = -dx * sin + dz * cos;
                sideways = dx * cos + dz * sin;
                sprint = false;
            } else {
                state.edgeHeld = true;
                bot.setSprinting(false);
                bot.zza = 0;
                bot.xxa = 0;
                if (bot.onGround()) {
                    // Shed the run-up too, or momentum carries the bot over regardless.
                    Vec3 v = bot.getDeltaMovement();
                    bot.setDeltaMovement(v.x * 0.3, v.y, v.z * 0.3);
                }
                return;
            }
        }

        bot.setSprinting(sprint);
        bot.zza = (float) (forward * (stopSprint ? 0.4 : 1.0));
        bot.xxa = (float) sideways;

        // A small nudge on top, in the same direction as the input rather than against it,
        // to keep direction changes responsive.
        double moveForce = bot.onGround() ? 0.06 : 0.02;
        bot.push(dx * speed * moveForce, 0, dz * speed * moveForce);
    }

    private static String pathWalkType(String botName) {
        if (!BotPath.isFollowing(botName)) return null;
        var follower = BotPath.getFollower(botName);
        if (follower == null) return null;
        var pathData = BotPath.getPath(follower.pathName);
        return pathData == null ? null : pathData.walkType;
    }

    private static void updateStrafe(NavigationState state, BotProfile profile, double intensity) {
        if (state.strafeTicks <= 0) {
            state.strafeDir = -state.strafeDir;
            // Irregular direction changes: fixed-period strafing is instantly readable.
            state.strafeTicks = profile != null
                ? Rng.gaussianTicks(profile.rng(), 16 - intensity * 6, 6, 4, 40)
                : 8 + (int) (Math.random() * 11);
        }
        state.strafeTicks--;
    }

    private static boolean isKnockedBack(ServerPlayer bot) {
        Vec3 vel = bot.getDeltaMovement();
        return Math.sqrt(vel.x * vel.x + vel.z * vel.z) > 0.35;
    }

    private static void checkIfStuck(ServerPlayer bot, NavigationState state) {
        Vec3 currentPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        if (state.lastPosition == null) {
            state.lastPosition = currentPos;
            return;
        }
        double moved = currentPos.distanceTo(state.lastPosition);
        // Held at an edge on purpose is not stuck, and the cure for stuck - a jump and a sidestep -
        // is the last thing to do at the edge of a drop.
        if (moved < 0.05 && bot.onGround() && !state.edgeHeld) {
            state.stuckTicks++;
            if (state.stuckTicks > 10) {
                if (state.avoidTicks <= 0) {
                    state.avoidDirection = (state.avoidDirection == 0) ? 1 : -state.avoidDirection;
                    state.avoidTicks = 30;
                }
                if (state.jumpCooldown <= 0) {
                    bot.jumpFromGround();
                    state.jumpCooldown = 10;
                }
                // A stuck bot's cached path is worthless; force a fresh search.
                state.path = List.of();
                state.pathCooldown = 0;
                state.stuckTicks = 0;
            }
        } else {
            state.stuckTicks = 0;
        }
    }

    /** A fall of more than this many blocks hurts, so it counts as an edge rather than a step. */
    private static final int SAFE_DROP = 3;

    /** How far ahead of its feet a moving bot checks for an edge, in blocks. */
    private static final double EDGE_LOOKAHEAD = 1.2;

    /**
     * Whether stepping into the cell at this point would mean a fall that hurts.
     *
     * <p>Judged by collision rather than by {@link #isBlockSolid}, which asks whether a block
     * conducts redstone - glass, leaves and slabs do not, and all of them are perfectly good to
     * land on. Water breaks any fall, so a drop into it is not an edge.</p>
     */
    public static boolean isDangerousDrop(Level world, double x, double y, double z) {
        BlockPos cell = BlockPos.containing(x, y + 0.05, z);
        if (collides(world, cell)) return false;               // a wall, not a drop
        for (int down = 1; down <= SAFE_DROP + 1; down++) {
            BlockPos below = cell.below(down);
            if (collides(world, below) || !world.getFluidState(below).isEmpty()) return false;
        }
        return true;
    }

    /** Whether a hurtful drop lies along this heading within {@code reach} blocks. */
    public static boolean dropWithin(Level world, Vec3 from, double dx, double dz, double reach) {
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-3) return false;
        dx /= length;
        dz /= length;
        for (double d = 0.45; d <= reach + 1.0E-6; d += 0.35) {
            if (!supportedAt(world, from.x + dx * d, from.y, from.z + dz * d)) return true;
        }
        return false;
    }

    /**
     * Whether a player stood with their centre here would have ground under them.
     *
     * <p>A player is held up by anything under any part of their feet, not just under their
     * centre. Asking about the centre alone refused the step across two blocks that meet only at
     * a corner - which is how a diagonal bridge meets the platform it was built to reach - so the
     * bot built all the way over and then stood at the last block refusing to walk off it.</p>
     */
    private static boolean supportedAt(Level world, double x, double y, double z) {
        for (double ox = -0.25; ox <= 0.26; ox += 0.5) {
            for (double oz = -0.25; oz <= 0.26; oz += 0.5) {
                if (!isDangerousDrop(world, x + ox, y, z + oz)) return true;
            }
        }
        return false;
    }

    /** Whether the ground falls away at all - even one block - along this heading within reach. */
    private static boolean stepDownWithin(Level world, Vec3 from, double dx, double dz, double reach) {
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-3) return false;
        dx /= length;
        dz /= length;
        for (double d = 0.45; d <= reach + 1.0E-6; d += 0.35) {
            BlockPos cell = BlockPos.containing(from.x + dx * d, from.y + 0.05, from.z + dz * d);
            if (!collides(world, cell) && !collides(world, cell.below())
                && world.getFluidState(cell.below()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a running jump along this heading would come down on something. */
    private static boolean safeLanding(Level world, Vec3 from, double dx, double dz) {
        for (double d = 2.2; d <= 3.4; d += 0.4) {
            BlockPos landing = BlockPos.containing(from.x + dx * d, from.y - 0.5, from.z + dz * d);
            if (collides(world, landing) && !collides(world, landing.above())
                && !collides(world, landing.above(2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean collides(Level world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isBlockSolid(Level world, BlockPos pos) {
        BlockState bs = world.getBlockState(pos);
        return !bs.isAir() && bs.isRedstoneConductor(world, pos);
    }

    private static boolean isClimbable(Level world, BlockPos pos) {
        BlockState bs = world.getBlockState(pos);
        return bs.getBlock() instanceof LadderBlock || bs.getBlock() instanceof VineBlock
            || bs.is(Blocks.SCAFFOLDING) || bs.is(Blocks.TWISTING_VINES)
            || bs.is(Blocks.TWISTING_VINES_PLANT) || bs.is(Blocks.WEEPING_VINES)
            || bs.is(Blocks.WEEPING_VINES_PLANT);
    }

    // ===================== looking =====================
    // Kept as thin wrappers so the rest of the codebase has one way to aim; the actual
    // smoothing lives in AimController.

    public static void lookAt(ServerPlayer bot, Entity target) {
        AimController.lookAt(bot, target, BotProfile.of(bot.getName().getString()));
    }

    public static void lookAtPosition(ServerPlayer bot, Vec3 targetPos) {
        AimController.lookAtPos(bot, targetPos, BotProfile.of(bot.getName().getString()), false);
    }

    public static void lookAway(ServerPlayer bot, Entity target) {
        AimController.lookAway(bot, target, BotProfile.of(bot.getName().getString()));
    }

    // ===================== idle =====================

    public static void idleWander(ServerPlayer bot) {
        BotSettings settings = BotSettings.get();
        if (!settings.isIdleWanderEnabled()) return;

        NavigationState state = getState(bot.getName().getString());
        BotProfile profile = BotProfile.of(bot.getName().getString());
        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        if (state.spawnPosition == null) state.spawnPosition = botPos;
        tickTimers(state);
        if (state.wanderCooldown > 0) state.wanderCooldown--;

        double radius = settings.getIdleWanderRadius();
        if (state.wanderTarget == null || state.wanderCooldown <= 0
            || botPos.distanceTo(state.wanderTarget) < 1.5) {
            double angle = profile.rng().nextDouble() * Math.PI * 2;
            double dist = profile.rng().nextDouble() * radius;
            state.wanderTarget = new Vec3(
                state.spawnPosition.x + Math.cos(angle) * dist,
                state.spawnPosition.y,
                state.spawnPosition.z + Math.sin(angle) * dist
            );
            state.wanderCooldown = Rng.range(profile.rng(), 60, 200);
        }

        double dx = state.wanderTarget.x - botPos.x;
        double dz = state.wanderTarget.z - botPos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (Swimmer.isSwimming(bot)) {
            AimController.lookAtPos(bot, state.wanderTarget, profile, false);
            Swimmer.swimToward(bot, state.wanderTarget, 0.5);
            state.lastPosition = botPos;
            return;
        }
        if (dist > 0.5) {
            AimController.lookAtPos(bot, state.wanderTarget, profile, false);
            bot.setSprinting(false);
            bot.zza = 0.5f;
            bot.push(dx / dist * 0.03, 0, dz / dist * 0.03);

            Level world = bot.level();
            BlockPos feetPos = BlockPos.containing(botPos.x + dx / dist * 0.5, botPos.y,
                botPos.z + dz / dist * 0.5);
            if (isBlockSolid(world, feetPos) && !isBlockSolid(world, feetPos.above())
                && bot.onGround() && state.jumpCooldown <= 0) {
                bot.jumpFromGround();
                state.jumpCooldown = 10;
            }
        } else {
            bot.zza = 0;
            bot.xxa = 0;
        }
        state.lastPosition = botPos;
    }

    public static void resetIdle(String botName) {
        NavigationState state = navStates.get(scriptify.bots.utils.Names.key(botName));
        if (state != null) {
            state.wanderTarget = null;
            state.wanderCooldown = 0;
        }
    }
}
