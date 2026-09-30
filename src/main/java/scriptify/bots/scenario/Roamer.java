package scriptify.bots.scenario;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.Rng;
import scriptify.bots.ai.Swimmer;
import scriptify.bots.bot.BotNavigation;

/**
 * Walking somewhere without looking like a machine, and without getting stuck doing it.
 *
 * <p>Combat movement is built around a target that moves; scenario movement is built around a
 * place, which is a different problem. A bot crossing a square is mostly walking in a straight
 * line, and a straight line is where the failure modes show: it walks into a wall and keeps
 * pressing forward, or it walks off a ledge it had no reason to approach.</p>
 *
 * <p>So three things sit on top of {@link BotNavigation#moveTowardPosition}:</p>
 * <ul>
 *   <li><b>Hazard sense.</b> A drop it could not survive, or lava, is treated as a wall - the
 *       bot steers around rather than walking in. This is what stops it wandering off a
 *       cliff.</li>
 *   <li><b>Stuck detection.</b> Position is sampled on a timer; if the bot asked to move and
 *       did not, it escalates - jump for a one-block step, then turn for a corner it is
 *       grinding against, then give the goal up. Without that last step a bot wedged in a
 *       doorway pushes at it forever.</li>
 *   <li><b>Pauses.</b> People stop. They stop for no reason, look at nothing, carry on.</li>
 * </ul>
 */
public final class Roamer {

    private Roamer() {}

    /** How close counts as arrived. */
    private static final double ARRIVE = 1.6;
    /** Ticks between progress samples. */
    private static final int PROGRESS_INTERVAL = 12;
    /** Blocks of progress expected within that window. */
    private static final double PROGRESS_MIN = 0.55;
    /** Strikes before the goal is abandoned as unreachable. */
    private static final int MAX_STRIKES = 4;
    /** A fall longer than this is treated as impassable. */
    private static final int SAFE_DROP = 4;

    public enum Step {
        /** Still walking. */
        MOVING,
        /** Standing still on purpose - a pause, not a failure. */
        PAUSED,
        /** Reached the goal. */
        ARRIVED,
        /** Could not get there; the caller should pick somewhere else. */
        GIVE_UP
    }

    public static class State {
        /** The far destination, when travelling. Legs toward it are generated as it goes. */
        public Vec3 destination;
        public Vec3 goal;
        public int pauseTicks;
        public int progressTimer;
        public Vec3 lastSample = Vec3.ZERO;
        public int strikes;
        public int detourTicks;
        public double detourSign = 1;
        /** Head sweep while paused. */
        public float glanceYaw;
        public int glanceTicks;

        /** Replaces the current leg without forgetting where the bot is ultimately going. */
        public void setGoalKeepDestination(Vec3 leg) {
            Vec3 keep = this.destination;
            setGoal(leg);
            this.destination = keep;
        }

        public void setGoal(Vec3 goal) {
            this.goal = goal;
            this.destination = null;
            this.strikes = 0;
            this.progressTimer = 0;
            this.detourTicks = 0;
            this.lastSample = Vec3.ZERO;
        }
    }

    /**
     * Advances one tick toward {@link State#goal}.
     *
     * @param pauseChance chance per tick of stopping to look around; 0 for never
     */
    public static Step step(ServerPlayer bot, BotProfile profile, State st,
                            double speed, double pauseChance, int pauseMin, int pauseMax) {
        if (st.goal == null) return Step.GIVE_UP;

        // --- standing still on purpose ---------------------------------------------------
        if (st.pauseTicks > 0) {
            st.pauseTicks--;
            idleLook(bot, profile, st);
            halt(bot);
            return Step.PAUSED;
        }

        double dx = st.goal.x - bot.getX();
        double dz = st.goal.z - bot.getZ();
        double flat = Math.hypot(dx, dz);
        if (flat <= ARRIVE) return Step.ARRIVED;

        if (pauseChance > 0 && Rng.chance(profile.rng(), pauseChance)) {
            st.pauseTicks = Rng.range(profile.rng(), pauseMin, pauseMax);
            st.glanceTicks = 0;
            return Step.PAUSED;
        }

        // --- have we actually been getting anywhere? -------------------------------------
        if (++st.progressTimer >= PROGRESS_INTERVAL) {
            st.progressTimer = 0;
            Vec3 here = bot.position();
            if (st.lastSample != Vec3.ZERO
                && here.distanceToSqr(st.lastSample) < PROGRESS_MIN * PROGRESS_MIN) {
                st.strikes++;
                if (st.strikes >= MAX_STRIKES) return Step.GIVE_UP;
                if (st.strikes == 1) {
                    // Most obstructions are one block tall: a step, a fence, a slab. Not afloat,
                    // where a ground jump is a leap out of the water; the swim holds space instead.
                    if (!Swimmer.isSwimming(bot)) bot.jumpFromGround();
                } else {
                    // Grinding against a corner. Commit to one way around it for a moment
                    // rather than re-deciding every tick, which just re-picks the wall.
                    st.detourTicks = Rng.range(profile.rng(), 14, 30);
                    st.detourSign = profile.rng().nextBoolean() ? 1 : -1;
                }
            } else {
                st.strikes = 0;
            }
            st.lastSample = here;
        }

        Vec3 dir = new Vec3(dx / flat, 0, dz / flat);

        // --- refuse to walk into a hole or a fire ----------------------------------------
        if (st.detourTicks <= 0 && hazardAhead(bot, dir)) {
            st.detourTicks = Rng.range(profile.rng(), 16, 34);
            st.detourSign = clearerSide(bot, dir);
        }

        Vec3 heading = dir;
        if (st.detourTicks > 0) {
            st.detourTicks--;
            // Sidestep: the goal direction turned a quarter, so the bot keeps making ground
            // rather than backing away from whatever it met.
            heading = new Vec3(-dir.z * st.detourSign, 0, dir.x * st.detourSign);
            Vec3 immediate = new Vec3(
                bot.getX() + heading.x * 3.0, bot.getY(), bot.getZ() + heading.z * 3.0);
            BotNavigation.moveTowardPosition(bot, immediate, speed);
            return Step.MOVING;
        }

        face(bot, profile, dir);
        BotNavigation.moveTowardPosition(bot, st.goal, speed);
        return Step.MOVING;
    }

    /**
     * Points the bot along its direction of travel.
     *
     * <p>Not cosmetic. Movement input is expressed in the bot's own frame, so the yaw decides
     * what "forward" means: a bot walking north while facing east is fed a pure sideways input
     * and crabs there. Nothing in the scenario code was setting the yaw at all - the combat AI
     * gets it for free by aiming at whoever it is fighting - so every scenario bot walked
     * sideways, which is exactly the "looks confused" and "does not face the way it is going"
     * complaint. Sprinting needs it too: the sprint gate wants most of the input to be forward,
     * and a crabbing bot never qualifies.</p>
     */
    public static void face(ServerPlayer bot, BotProfile profile, Vec3 dir) {
        Vec3 ahead = bot.getEyePosition().add(dir.x * 12, 0, dir.z * 12);
        AimController.lookAtPos(bot, ahead, profile, false);
    }

    /**
     * Long-distance travel: pick a bearing and commit to it.
     *
     * <p>Roaming picks somewhere nearby, walks there, stops, and picks again - which is what
     * someone with nothing to do looks like, and completely wrong for someone going somewhere.
     * A player crossing a server has a destination hundreds of blocks away and holds a straight
     * line to it, bunny hopping the whole way. This generates legs along that bearing instead
     * of pathing the whole distance, because a 600 block A* is not a thing you run on a tick.</p>
     */
    public static Step travel(ServerPlayer bot, BotProfile profile, State st, double speed) {
        if (st.destination == null) return Step.GIVE_UP;

        double dx = st.destination.x - bot.getX();
        double dz = st.destination.z - bot.getZ();
        double remaining = Math.hypot(dx, dz);
        if (remaining <= ARRIVE * 2) return Step.ARRIVED;

        // Next leg along the bearing, re-picked whenever the current one is spent.
        if (st.goal == null || Math.hypot(st.goal.x - bot.getX(), st.goal.z - bot.getZ()) <= ARRIVE) {
            Vec3 leg = legToward(bot, st.destination, remaining);
            if (leg == null) {
                // Nothing standable on the bearing - most likely the edge of loaded terrain.
                // Give up on this destination rather than grinding at it.
                return Step.GIVE_UP;
            }
            st.setGoalKeepDestination(leg);
        }

        if (++st.progressTimer >= PROGRESS_INTERVAL) {
            st.progressTimer = 0;
            Vec3 here = bot.position();
            if (st.lastSample != Vec3.ZERO
                && here.distanceToSqr(st.lastSample) < PROGRESS_MIN * PROGRESS_MIN) {
                st.strikes++;
                if (st.strikes >= MAX_STRIKES) return Step.GIVE_UP;
                if (st.strikes == 1) {
                    if (!Swimmer.isSwimming(bot)) bot.jumpFromGround();
                } else {
                    // Drop the leg as well as sidestepping. Sidestepping alone frees the bot
                    // for a moment, and then the next leg is generated on the same bearing and
                    // walks it straight back into the same wall - the strike count resets on
                    // that scrap of progress, so it can grind there indefinitely.
                    st.goal = null;
                    st.detourTicks = Rng.range(profile.rng(), 20, 40);
                    st.detourSign = profile.rng().nextBoolean() ? 1 : -1;
                    return Step.MOVING;
                }
            } else {
                st.strikes = 0;
            }
            st.lastSample = here;
        }

        double gx = st.goal.x - bot.getX();
        double gz = st.goal.z - bot.getZ();
        double flat = Math.max(1.0E-4, Math.hypot(gx, gz));
        Vec3 dir = new Vec3(gx / flat, 0, gz / flat);

        if (st.detourTicks <= 0 && hazardAhead(bot, dir)) {
            st.detourTicks = Rng.range(profile.rng(), 16, 34);
            st.detourSign = clearerSide(bot, dir);
        }

        Vec3 aimAt = st.goal;
        if (st.detourTicks > 0) {
            st.detourTicks--;
            Vec3 side = new Vec3(-dir.z * st.detourSign, 0, dir.x * st.detourSign);
            aimAt = new Vec3(bot.getX() + side.x * 4, bot.getY(), bot.getZ() + side.z * 4);
            dir = side;
        }

        face(bot, profile, dir);
        BotNavigation.moveTowardPosition(bot, aimAt, speed);
        bhop(bot);
        return Step.MOVING;
    }

    /**
     * Bunny hop.
     *
     * <p>Nobody walks a long distance in Minecraft. Jumping on every landing while sprinting is
     * how players actually travel, and its absence is one of the loudest tells that something
     * is not a person. Only while genuinely sprinting forwards - hopping on the spot while
     * turning looks worse than not hopping at all.</p>
     */
    public static void bhop(ServerPlayer bot) {
        if (bot.onGround() && !bot.isInWater() && bot.isSprinting() && bot.zza > 0.7f) {
            bot.jumpFromGround();
        }
    }

    /**
     * A waypoint on the way to a far destination.
     *
     * <p>Legs are long enough that the bot commits to a direction, short enough that the ground
     * under the next one is real. The bearing is nudged a few degrees per leg so a long walk is
     * not a ruler-straight line.</p>
     */
    private static Vec3 legToward(ServerPlayer bot, Vec3 destination, double remaining) {
        double dx = destination.x - bot.getX();
        double dz = destination.z - bot.getZ();
        double length = Math.max(1.0E-4, Math.hypot(dx, dz));
        double stride = Math.min(28.0, remaining);

        // Straight on first, then progressively wider, then behind. A traveller prefers to
        // keep going the way they were headed and only turns as far as they have to.
        for (double spread : new double[]{0, 0.25, -0.25, 0.55, -0.55, 1.0, -1.0, 1.6, -1.6}) {
            double angle = Math.atan2(dz, dx) + spread;
            double x = bot.getX() + Math.cos(angle) * stride;
            double z = bot.getZ() + Math.sin(angle) * stride;
            BlockPos ground = groundAt(bot.level(), x, bot.getY(), z);
            if (ground == null) continue;
            if (wallToward(bot, Math.cos(angle), Math.sin(angle))) continue;
            return new Vec3(x, ground.getY() + 1, z);
        }
        return null;
    }

    /**
     * Stops the bot where it stands without touching its fall. In deep water, stopping means
     * treading water: a scenario bot stood still in a lake would otherwise sink and drown.
     */
    public static void halt(ServerPlayer bot) {
        bot.zza = 0;
        bot.xxa = 0;
        bot.setSprinting(false);
        Vec3 v = bot.getDeltaMovement();
        bot.setDeltaMovement(v.x * 0.4, v.y, v.z * 0.4);
        Swimmer.tread(bot);
    }

    /** Slow head drift, the way someone stood waiting looks around. */
    public static void idleLook(ServerPlayer bot, BotProfile profile, State st) {
        if (st.glanceTicks <= 0) {
            st.glanceTicks = Rng.range(profile.rng(), 20, 60);
            st.glanceYaw = bot.getYRot() + (float) Rng.range(profile.rng(), -70.0, 70.0);
        }
        st.glanceTicks--;
        Vec3 eye = bot.getEyePosition();
        double rad = Math.toRadians(st.glanceYaw);
        Vec3 look = eye.add(-Math.sin(rad) * 8, Rng.range(profile.rng(), -1.0, 1.0), Math.cos(rad) * 8);
        AimController.lookAtPos(bot, look, profile, false);
    }

    /**
     * True when the ground a stride ahead is missing, deadly, or a fall the bot would not
     * walk into on purpose.
     */
    public static boolean hazardAhead(ServerPlayer bot, Vec3 dir) {
        Level world = bot.level();
        for (double reach = 1.0; reach <= 2.0; reach += 0.5) {
            BlockPos ahead = BlockPos.containing(
                bot.getX() + dir.x * reach, bot.getY() + 0.05, bot.getZ() + dir.z * reach);

            var here = world.getBlockState(ahead);
            if (here.getBlock() == Blocks.LAVA || here.getBlock() == Blocks.FIRE
                || here.getBlock() == Blocks.SOUL_FIRE || here.getBlock() == Blocks.MAGMA_BLOCK
                || here.getBlock() == Blocks.CAMPFIRE || here.getBlock() == Blocks.SOUL_CAMPFIRE) {
                return true;
            }

            boolean floor = false;
            for (int depth = 1; depth <= SAFE_DROP; depth++) {
                BlockPos below = ahead.below(depth);
                if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                    floor = true;
                    break;
                }
            }
            if (!floor) return true;
        }
        return false;
    }

    /**
     * True when something taller than a step blocks the first few blocks of a heading.
     *
     * <p>Checked when choosing a leg rather than after walking into it. Ground existing at the
     * far end of a leg says nothing about whether there is a cliff face in between.</p>
     */
    private static boolean wallToward(ServerPlayer bot, double dx, double dz) {
        Level world = bot.level();
        for (double reach = 1.0; reach <= 4.0; reach += 1.0) {
            BlockPos feet = BlockPos.containing(
                bot.getX() + dx * reach, bot.getY() + 0.05, bot.getZ() + dz * reach);
            BlockPos head = feet.above();
            boolean atFeet = !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty();
            boolean atHead = !world.getBlockState(head).getCollisionShape(world, head).isEmpty();
            // Solid at both heights is a wall; solid only at the feet is a step, which is fine.
            if (atFeet && atHead) return true;
        }
        return false;
    }

    /** Which way around an obstacle has more room. */
    private static double clearerSide(ServerPlayer bot, Vec3 dir) {
        Vec3 left = new Vec3(-dir.z, 0, dir.x);
        boolean leftBad = hazardAhead(bot, left);
        boolean rightBad = hazardAhead(bot, left.scale(-1));
        if (leftBad && !rightBad) return -1;
        return 1;
    }

    /**
     * A reachable-looking spot to head for next, somewhere around an anchor.
     *
     * <p>Candidates are rejected rather than repaired: trying another random point is cheaper
     * than working out how to fix a bad one, and the bot has nothing better to do.</p>
     */
    public static Vec3 pickSpot(ServerPlayer bot, BotProfile profile,
                                 Vec3 anchor, double radius) {
        Level world = bot.level();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = profile.rng().nextDouble() * Math.PI * 2;
            double distance = radius * (0.25 + profile.rng().nextDouble() * 0.75);
            double x = anchor.x + Math.cos(angle) * distance;
            double z = anchor.z + Math.sin(angle) * distance;

            BlockPos ground = groundAt(world, x, anchor.y, z);
            if (ground == null) continue;
            return new Vec3(x, ground.getY() + 1, z);
        }
        return null;
    }

    /** Nearest standable surface to the given height, or null if there is none nearby. */
    public static BlockPos groundAt(Level world, double x, double y, double z) {
        BlockPos start = BlockPos.containing(x, y, z);
        if (!world.isLoaded(start)) return null;
        for (int offset = 0; offset <= 6; offset++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                BlockPos pos = start.above(offset * sign);
                boolean solid = !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
                boolean headroom =
                    world.getBlockState(pos.above()).getCollisionShape(world, pos.above()).isEmpty()
                        && world.getBlockState(pos.above(2)).getCollisionShape(world, pos.above(2)).isEmpty();
                if (solid && headroom) return pos;
                if (offset == 0) break;
            }
        }
        return null;
    }
}
