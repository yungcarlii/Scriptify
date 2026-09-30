package scriptify.bots.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotActions;
import scriptify.bots.bot.BotCombat;
import scriptify.bots.bot.BotPath;
import scriptify.bots.utils.Inv;
import scriptify.bots.utils.Names;

import java.util.HashMap;
import java.util.Map;

/**
 * Getting out of a hole.
 *
 * <p>Dropping somebody into a one-block pit is the oldest trap there is, and until now it worked
 * permanently: the bot would walk into the wall in front of it for the rest of the match, because
 * every piece of movement code assumed the way to somewhere was along the ground. A player in that
 * hole does one of two things within a second or two - towers out of it, or cuts a staircase - and
 * neither is difficult. Not doing either is the single most obviously artificial thing a bot can
 * be seen doing, because the person who put it there is stood at the lip watching.</p>
 *
 * <p><b>Towering is preferred</b> when the bot has anything to build with. It is faster, it works
 * at any depth, and it is what the pillar code already does well - so this borrows
 * {@link Bridger#pillarStep} rather than growing a second copy of it.</p>
 *
 * <p><b>Cutting a staircase</b> is the fallback. To climb one level the bot needs the two cells
 * above the block in front of it cleared, and then that block becomes the step it jumps onto. So
 * it mines head-height first, then the cell above that, then jumps forward - which is exactly the
 * staircase a person digs, and it repeats until the walls stop being walls.</p>
 *
 * <p>Only bots with somewhere to be try any of this. A bot with no target and no path standing in
 * an alcove has been put there, and a decoration that mines its way out of its own set is worse
 * than one that stands still.</p>
 */
public final class PitEscape {

    private PitEscape() {}

    /**
     * How long an escape may run before it is abandoned, in ticks.
     *
     * <p>Fifteen seconds. Long enough to tower out of anything worth calling a pit, short enough
     * that a bot which has misread its surroundings goes back to fighting rather than mining the
     * arena for the rest of the match.</p>
     */
    private static final int MAX_ESCAPE_TICKS = 300;

    /** Ticks before a bot that gave up will consider itself trapped again. */
    private static final int RETRY_COOLDOWN = 100;

    /** How often the walls are examined, in ticks. Geometry does not change fast. */
    private static final int CHECK_INTERVAL = 10;

    /** Aim tolerance for a click that has to land on one particular block, in degrees. */
    private static final double AIM_TOLERANCE = 8.0;

    private static final class State {
        boolean escaping;
        int ticks;
        int cooldown;
        /** Zero, not MIN_VALUE: the difference against a tick count would overflow. */
        long lastCheck = 0;
        boolean boxedIn;
        /** The cell currently being mined, so the aim does not wander between candidates. */
        BlockPos digging;
        int digTicks;
        /** The next cell on the way up and out, being walked to; null when not climbing out. */
        BlockPos climbTo;
        int climbTicks;
    }

    private static final Map<String, State> STATES = new HashMap<>();

    private static State state(String botName) {
        return STATES.computeIfAbsent(Names.key(botName), key -> new State());
    }

    public static void remove(String botName) {
        STATES.remove(Names.key(botName));
    }

    public static void clear() {
        STATES.clear();
    }

    /**
     * Runs one tick of getting unstuck.
     *
     * @return true while the bot is busy escaping, in which case nothing else may drive it
     */
    public static boolean tick(ServerPlayer bot, BotProfile profile, MinecraftServer server,
                               long serverTick) {
        String name = bot.getName().getString();
        State st = state(name);
        if (st.cooldown > 0) st.cooldown--;

        if (st.escaping) {
            st.ticks++;
            if (st.ticks > MAX_ESCAPE_TICKS) {
                stop(bot, st, server);
                st.cooldown = RETRY_COOLDOWN;
                return false;
            }
        }

        // The walls are only re-read on a timer. Between reads the answer stands, which is what
        // makes this affordable to ask of every bot.
        if (serverTick - st.lastCheck >= CHECK_INTERVAL) {
            st.lastCheck = serverTick;
            st.boxedIn = false;
            if (bot.onGround() && hasSomewhereToBe(name)) {
                Survey survey = survey(bot);
                st.boxedIn = survey.confined();
                if (survey.climbTo() != null && st.climbTo == null) {
                    st.climbTo = survey.climbTo();
                    st.climbTicks = 0;
                }
            }
        }

        if (!st.boxedIn) {
            if (st.escaping) stop(bot, st, server);
            return climbOut(bot, st, profile, server);
        }
        st.climbTo = null;
        if (st.cooldown > 0) return false;

        if (!st.escaping) {
            st.escaping = true;
            st.ticks = 0;
        }

        int blockSlot = Bridger.findBuildingBlock(bot);
        if (blockSlot >= 0) {
            // Towering fills the hole in behind you, which also stops the bot falling straight
            // back into it the moment it steps off.
            BotActions.stopUsing(bot, server);
            st.digging = null;
            return Bridger.pillarStep(bot, profile, server, blockSlot);
        }

        return digStep(bot, st, profile, server);
    }

    /** Whether this bot has any reason to leave where it is standing. */
    private static boolean hasSomewhereToBe(String botName) {
        Entity target = BotCombat.getTarget(botName);
        if (target != null && target.isAlive()) return true;
        return BotPath.isFollowing(botName);
    }

    /** How far the survey looks from the bot, in blocks, each way. */
    private static final int SURVEY_RADIUS = 4;

    /** Most cells the survey visits; plenty for a radius of four. */
    private static final int SURVEY_LIMIT = 250;

    /** Longest a bot spends walking to one cell on the way out, in ticks. */
    private static final int CLIMB_STEP_TICKS = 20;

    /**
     * What the ground around the bot allows.
     *
     * @param confined nowhere to walk to that is either higher or more than a few blocks away:
     *                 a hole, whatever its shape
     * @param climbTo  the first cell on the walk to higher ground, when the bot should take it
     */
    private record Survey(boolean confined, BlockPos climbTo) {}

    /**
     * Walks the ground around the bot the way a player could - on the level, up single steps with
     * headroom, down drops of up to three - and reports what it found.
     *
     * <p>Looking only at the four blocks around the bot was not enough. Half way through cutting a
     * staircase there is always an opening beside the bot: the pocket it just dug, too low to stand
     * in, or the shaft it just climbed out of. Each looked like a way out, the bot stepped into it,
     * and went back and forth between the two for the rest of the fight. A hole is a place you
     * cannot walk out of, and the only way to know that is to try walking.</p>
     */
    private static Survey survey(ServerPlayer bot) {
        Level world = bot.level();
        BlockPos start = BlockPos.containing(bot.getX(), bot.getY() + 0.1, bot.getZ());
        // Out in the open there is nothing to find, and this runs for every bot with a target.
        // Two walls is the least a hole or a trench has; anything less is not worth the walk.
        int walls = walls(world, start);
        if (walls < 2) return new Survey(false, null);
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        parent.put(start, start);
        queue.add(start);

        BlockPos bestHigher = null;
        double bestScore = Double.MAX_VALUE;
        boolean wide = false;
        Vec3 goal = goalOf(bot.getName().getString());

        while (!queue.isEmpty() && parent.size() < SURVEY_LIMIT) {
            BlockPos at = queue.poll();
            if (Math.abs(at.getX() - start.getX()) >= 3 || Math.abs(at.getZ() - start.getZ()) >= 3) {
                wide = true;
            }
            if (at.getY() > start.getY()) {
                double score = goal == null ? 0 : Vec3.atCenterOf(at).distanceToSqr(goal);
                if (score < bestScore) {
                    bestScore = score;
                    bestHigher = at;
                }
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = stepFrom(world, at, dir);
                if (next == null || parent.containsKey(next)) continue;
                if (Math.abs(next.getX() - start.getX()) > SURVEY_RADIUS
                    || Math.abs(next.getZ() - start.getZ()) > SURVEY_RADIUS
                    || Math.abs(next.getY() - start.getY()) > SURVEY_RADIUS) continue;
                parent.put(next, at);
                queue.add(next);
            }
        }

        // Walls on three sides as well. A bot on top of a tall pillar cannot walk anywhere either,
        // but it is not in a hole, and digging or towering is the wrong answer up there.
        boolean confined = !wide && bestHigher == null && walls >= 3;

        // Worth walking to higher ground only when the goal is up there and the bot is down in
        // something - walls round it - rather than out in the open, where the ordinary movement
        // already copes with a slope.
        BlockPos climbTo = null;
        if (bestHigher != null && goal != null && goal.y - bot.getY() >= 1.0) {
            BlockPos step = bestHigher;
            while (!parent.get(step).equals(start)) step = parent.get(step);
            climbTo = step;
        }
        return new Survey(confined, climbTo);
    }

    /** Where a player stood at {@code at} ends up walking one block towards {@code dir}, or null. */
    private static BlockPos stepFrom(Level world, BlockPos at, Direction dir) {
        BlockPos next = at.relative(dir);
        if (!blocks(world, next)) {
            if (blocks(world, next.above())) return null;          // no room for a head
            for (int down = 0; down <= 3; down++) {
                if (blocks(world, next.below(down + 1))) return next.below(down);
            }
            return null;                                           // a fall that hurts
        }
        // A step up: room on top of it for the body, and room to jump from here.
        if (!blocks(world, next.above()) && !blocks(world, next.above(2))
            && !blocks(world, at.above(2))) {
            return next.above();
        }
        return null;
    }

    /** How many sides of this cell are walls a player could not walk or step through. */
    private static int walls(Level world, BlockPos feet) {
        int walls = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (blocks(world, side) && blocks(world, side.above())) walls++;
        }
        return walls;
    }

    /**
     * Walks to the next cell on the way up and out, hopping when it is a step up.
     *
     * <p>What a player does half way out of a hole: step up onto whichever side is open, even when
     * it is not the side their target is on, and carry on from there.</p>
     */
    private static boolean climbOut(ServerPlayer bot, State st, BotProfile profile,
                                    MinecraftServer server) {
        if (st.climbTo == null) return false;
        st.climbTicks++;
        Vec3 centre = Vec3.atBottomCenterOf(st.climbTo);
        double dx = centre.x - bot.getX();
        double dz = centre.z - bot.getZ();
        boolean arrived = bot.onGround() && Math.hypot(dx, dz) < 0.35
            && bot.getY() >= st.climbTo.getY() - 0.1;
        if (arrived || st.climbTicks > CLIMB_STEP_TICKS) {
            st.climbTo = null;
            return false;
        }
        AimController.lookAtPos(bot, centre.add(0, 1.5, 0), profile, false);
        if (bot.onGround() && st.climbTo.getY() > bot.getY() + 0.5) BotActions.jump(bot, server);
        double len = Math.max(0.1, Math.hypot(dx, dz));
        bot.setSprinting(false);
        Vec3 v = bot.getDeltaMovement();
        double speed = Math.min(0.12, len * 0.3);
        bot.setDeltaMovement(dx / len * speed, v.y, dz / len * speed);
        return true;
    }

    /** Where the bot is trying to get to: its target, or the next point on its route. */
    private static Vec3 goalOf(String name) {
        Entity target = BotCombat.getTarget(name);
        if (target != null && target.isAlive()) return target.position();
        return BotPath.getNextPoint(name);
    }




    /** True when a cell would stop the bot walking into it. */
    private static boolean blocks(Level world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Cuts one step of a staircase.
     *
     * <p>Head height first, then the cell above it. Clearing the block at foot level instead
     * would open a tunnel at the depth the bot is already stuck at, which is not an escape; the
     * block at foot level is wanted exactly where it is, as the stair to jump onto.</p>
     */
    private static boolean digStep(ServerPlayer bot, State st, BotProfile profile,
                                   MinecraftServer server) {
        Level world = bot.level();
        BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.1, bot.getZ());

        BlockPos cell = st.digging;
        if (cell == null || !blocks(world, cell) || st.digTicks > 100) {
            cell = chooseDig(bot, world, feet);
            st.digging = cell;
            st.digTicks = 0;
        }

        if (cell == null) {
            // Nothing diggable in any direction - bedrock walls, or already clear. If the way is
            // open the bot just has to jump for it.
            BotActions.stopUsing(bot, server);
            BotActions.jump(bot, server);
            return true;
        }
        st.digTicks++;

        equipDiggingTool(bot, world.getBlockState(cell), cell, world);

        Vec3 aim = nearFace(bot, cell);
        AimController.lookAtPrecise(bot, aim, profile);

        // Stand still while mining, and stop sprinting into the wall.
        bot.zza = 0;
        bot.xxa = 0;
        bot.setSprinting(false);

        if (!aimedAt(bot, aim)) return true;
        // Held, not clicked: a single click barely marks a block. Re-sent periodically because
        // the backend releases the button when the bot is interrupted.
        if (st.digTicks == 1 || st.digTicks % 20 == 0) {
            BotActions.attackContinuous(bot, server);
        }
        return true;
    }

    /**
     * Which cell to mine next: head height in the most useful direction, then the one above it.
     *
     * <p>Direction is decided by where the bot wants to go, so it digs out towards its target
     * rather than into the hillside behind it. A wall that cannot be broken at all - bedrock, or
     * a barrier - is skipped rather than chipped at forever.</p>
     */
    private static BlockPos chooseDig(ServerPlayer bot, Level world, BlockPos feet) {
        Direction preferred = towardsGoal(bot);

        BlockPos best = null;
        for (int pass = 0; pass < 2; pass++) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                // First pass: only the direction the bot actually wants. Second: anything.
                if (pass == 0 && dir != preferred) continue;
                if (pass == 1 && dir == preferred) continue;

                BlockPos ahead = feet.relative(dir);
                BlockPos head = ahead.above();
                BlockPos over = ahead.above(2);

                if (blocks(world, head) && breakable(world, head)) return head;
                if (blocks(world, over) && breakable(world, over)) best = over;
            }
        }
        return best;
    }

    /** The horizontal direction of whatever the bot is trying to reach. */
    private static Direction towardsGoal(ServerPlayer bot) {
        String name = bot.getName().getString();
        Vec3 goal = null;

        Entity target = BotCombat.getTarget(name);
        if (target != null && target.isAlive()) {
            goal = target.position();
        } else {
            goal = BotPath.getNextPoint(name);
        }
        double dx;
        double dz;
        if (goal == null) {
            Vec3 look = bot.getViewVector(1.0f);
            dx = look.x;
            dz = look.z;
        } else {
            dx = goal.x - bot.getX();
            dz = goal.z - bot.getZ();
        }
        // Written out rather than asked of Direction, whose nearest-axis helper has been renamed
        // more than once; this cannot be wrong and cannot go stale.
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Whether a block can be broken at all. Bedrock and barriers report a negative hardness. */
    private static boolean breakable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getDestroySpeed(world, pos) >= 0.0f;
    }

    /**
     * Puts the fastest thing in the bag into the bot's hand.
     *
     * <p>Scored against the block being broken rather than by item type, because that is the only
     * question that matters and the game already answers it: a shovel is the right tool for dirt
     * and the wrong one for stone, and asking the stack directly gets that right without a table
     * of tools to keep up to date.</p>
     */
    private static void equipDiggingTool(ServerPlayer bot, BlockState state, BlockPos pos,
                                         Level world) {
        var inv = bot.getInventory();
        // The held item is already the incumbent; only swap for something genuinely faster.
        float held = inv.getItem(Inv.selected(inv)).getDestroySpeed(state);
        int best = -1;
        float bestSpeed = held;

        for (int slot = 0; slot < Inv.MAIN_END; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        if (best >= 0) Inv.equip(inv, best, 0);
    }

    /** A point just inside the face of the cell nearest the bot's eyes. */
    private static Vec3 nearFace(ServerPlayer bot, BlockPos cell) {
        Vec3 centre = Vec3.atCenterOf(cell);
        Vec3 toEye = bot.getEyePosition().subtract(centre);
        double length = toEye.length();
        if (length < 0.1) return centre;
        return centre.add(toEye.scale(Math.min(0.45, length * 0.5) / length));
    }

    private static boolean aimedAt(ServerPlayer bot, Vec3 point) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float wantPitch = (float) -(Math.atan2(dy, horizontal) * (180.0 / Math.PI));
        return Math.abs(AimController.wrap(wantYaw - bot.getYRot())) <= AIM_TOLERANCE
            && Math.abs(AimController.wrap(wantPitch - bot.getXRot())) <= AIM_TOLERANCE;
    }

    private static void stop(ServerPlayer bot, State st, MinecraftServer server) {
        st.escaping = false;
        st.ticks = 0;
        st.digging = null;
        st.digTicks = 0;
        // The attack button is held while mining, and a bot that walks off still holding it
        // carries on breaking whatever it looks at next.
        BotActions.stopUsing(bot, server);
    }
}
