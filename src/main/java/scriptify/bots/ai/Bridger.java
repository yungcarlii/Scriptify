package scriptify.bots.ai;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import scriptify.bots.bot.BotActions;
import scriptify.bots.utils.Inv;

import java.util.HashMap;
import java.util.Map;

/**
 * Lets bots build their way to a target they cannot walk to.
 *
 * <p>The classic failure case: a player stands on a two-block pillar, a roof, or across a
 * gap. The original bots would jog into the wall underneath forever. This module gives them
 * the two moves a human uses - pillar straight up, and bridge forwards over a gap - driven
 * by the same "aim at a real block face and use" mechanism as block placement everywhere
 * else.</p>
 *
 * <p>It is deliberately conservative: it only engages when the target is meaningfully above
 * the bot or the path has already failed, it stops as soon as the target is reachable
 * again, and it will not consume anything the bot might want for combat (no obsidian,
 * no crystals, no cobwebs).</p>
 */
public final class Bridger {

    private Bridger() {}

    private static final Map<String, State> STATES = new HashMap<>();

    /** Within this of the target a bot fights rather than builds towards it. */
    private static final double GAP_MELEE_RANGE = 2.5;

    /** How far the bot's centre may hang past the edge while lining up a bridging placement. */
    private static final double MAX_OVERHANG = 0.28;

    public static class State {
        public int cooldown;
        public int placedThisClimb;
        public int idleTicks;
        public boolean active;
        /** The column being towered up, so the bot cannot wander off its own pillar. */
        public double columnX;
        public double columnZ;
        /** Once committed, the climb runs to completion instead of being re-decided. */
        public boolean latched;
        /** Ticks spent stepping onto the block just laid down while bridging a gap. */
        public int gapCooldown;
        /** Height the bot's feet were at the last time it stood on something. */
        public double groundY = Double.NaN;
        /** Game time gap bridging last had hold of the bot. */
        public long lastGapTick = Long.MIN_VALUE / 2;
    }

    public static State state(String botName) {
        return STATES.computeIfAbsent(scriptify.bots.utils.Names.key(botName), k -> new State());
    }

    public static void remove(String botName) {
        STATES.remove(scriptify.bots.utils.Names.key(botName));
    }

    /**
     * Height difference at which walking is hopeless and building is worth it: two blocks, which
     * nobody can step or jump up. Measured from where the bot is standing - see
     * {@link #heightToClimb}.
     */
    private static final double CLIMB_TRIGGER = 1.9;
    /** Close enough to level to stop towering: one block is a step up. */
    private static final double CLIMB_DONE = 1.5;
    /** How close counts as being at the foot of the wall. */
    private static final double CLIMB_APPROACH = 3.5;
    /** Beyond this the bot walks closer before it considers building. */
    private static final double CLIMB_MAX_RANGE = 20.0;
    private static final int MAX_PILLAR = 24;
    /** Degrees of aim error tolerated before committing the click. */
    private static final double AIM_TOLERANCE = 8.0;

    /**
     * Whether to start towering up.
     *
     * <p>Height alone is not enough: a bot that pillars from wherever it happens to be standing
     * ends up on a tower next to the building rather than on it. So it has to walk to the foot
     * of the wall first - which is what a player does - and only then start placing.</p>
     */
    public static boolean shouldEngage(ServerPlayer bot, Entity target, boolean pathBlocked) {
        if (target == null) return false;

        State st = state(bot.getName().getString());
        double dy = heightToClimb(bot, target, st);

        // A climb, once started, runs until it is level with the target. Re-deciding every
        // tick let the trigger flicker off mid-hop - control went back to the combat code,
        // which promptly walked the bot off the pillar it was standing on.
        if (st.latched) {
            // Finished only once it is standing on the result. Handing back mid-hop gave the
            // combat code a bot in the air with a run-up, and it carried on straight off the top
            // of the pillar it had just built.
            if (dy >= CLIMB_DONE || !bot.onGround()) return true;
            st.latched = false;
            st.placedThisClimb = 0;
            return false;
        }

        if (dy < CLIMB_TRIGGER) return false;
        // Nothing to build with: nothing to commit to. Latching anyway left the climb claiming a
        // bot that could only stand there.
        if (findBuildingBlock(bot) < 0) return false;
        // Somebody in the air is not somebody up a wall. A mace player goes ten blocks up on a
        // wind charge for every smash, and treating that as a ledge had a whole army start
        // pillaring under him each time. Only climb to someone standing on something.
        if (!target.onGround()) return false;

        double horizontal = Math.hypot(target.getX() - bot.getX(), target.getZ() - bot.getZ());
        if (horizontal > CLIMB_MAX_RANGE) return false;   // too far off; walk closer first

        // Higher ground is not a wall. A hillside is a run of one-block steps that any player
        // simply walks up, and towering up the side of it is the single most robotic thing a
        // bot can do. If the way ahead can be stepped onto, walk.
        if (canStepUp(bot, target)) return false;

        // Already at the foot of it, or there is something solid in the way, or the router
        // gave up. Any of those means walking will not get there and it is time to build.
        boolean start = horizontal <= CLIMB_APPROACH || pathBlocked || facingWall(bot, target);
        if (start) {
            st.latched = true;
            st.placedThisClimb = 0;
        }
        return start;
    }

    /**
     * How far above the bot the target stands, measured from the block under the bot's feet.
     *
     * <p>Not from where the bot is this instant. Towering is a string of hops, and at the top of
     * each one the bot is a block and a quarter higher than the pillar it is building - so two
     * blocks short of a ledge read as "nearly level", the climb stopped, and the bot was left on
     * a pillar two blocks under the person it was climbing to.</p>
     */
    private static double heightToClimb(ServerPlayer bot, Entity target, State st) {
        if (bot.onGround() || Double.isNaN(st.groundY)) {
            st.groundY = bot.getY();
        }
        return target.getY() - st.groundY;
    }

    /**
     * True when the ground ahead is at most one block higher and can simply be walked onto.
     *
     * <p>This is what separates a hill from a wall. A step up has solid ground at foot level
     * and clear space for the bot's body above it; a wall does not.</p>
     */
    private static boolean canStepUp(ServerPlayer bot, Entity target) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 0.1) return false;
        dx /= length;
        dz /= length;

        Level world = bot.level();
        BlockPos ahead = BlockPos.containing(
            bot.getX() + dx, bot.getY() + 0.05, bot.getZ() + dz);

        // Solid to stand on, with head room for the two blocks of bot that follow it up.
        return isSolid(world, ahead)
            && !isSolid(world, ahead.above())
            && !isSolid(world, ahead.above(2));
    }

    /**
     * True when something solid stands between the bot and its target at head height. That is
     * the honest cue to start towering: a player walks into the side of a building and then
     * builds up it, rather than measuring distances.
     */
    private static boolean facingWall(ServerPlayer bot, Entity target) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 0.1) return false;
        dx /= length;
        dz /= length;

        // Right in front only. Looking two blocks out, a bot walking past the side of a staircase
        // saw the second step - two high from where it stood - as a wall, and towered up beside a
        // flight of stairs it could have walked up.
        Level world = bot.level();
        for (double step = 0.6; step <= 1.0; step += 0.4) {
            BlockPos ahead = BlockPos.containing(
                bot.getX() + dx * step, bot.getY() + 1.0, bot.getZ() + dz * step);
            if (!world.getBlockState(ahead).getCollisionShape(world, ahead).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs one tick of towering up. Returns true while the bot is committed to building, in
     * which case the caller must not also move or fight it.
     *
     * <p>This is deliberately the real player motion: stop moving, look down at the block you
     * are standing on, jump, and place into the space you just left. Two details make or break
     * it, and both were wrong before:</p>
     * <ul>
     *   <li><b>Stand still.</b> The bot used to keep chasing while it built, so it drifted off
     *       the pillar it had just placed and fell straight back down. Horizontal movement is
     *       now zeroed for the duration - which is also exactly how a player towers.</li>
     *   <li><b>Wait for the aim.</b> It aimed and clicked on the same tick, but the head turns
     *       at a human rate and had not arrived yet, so the click went nowhere. It now holds
     *       until the crosshair is actually on the face.</li>
     * </ul>
     */
    public static boolean tick(ServerPlayer bot, Entity target, BotProfile profile,
                               MinecraftServer server) {
        String name = bot.getName().getString();
        State st = state(name);

        int blockSlot = findBuildingBlock(bot);
        if (blockSlot < 0) {
            st.active = false;
            return false;
        }

        double dy = heightToClimb(bot, target, st);
        if ((dy < CLIMB_DONE || st.placedThisClimb > MAX_PILLAR) && !bot.onGround()) {
            // Up there: come down on the pillar before handing the bot back.
            bot.setSprinting(false);
            Vec3 v = bot.getDeltaMovement();
            bot.setDeltaMovement(v.x * 0.2, v.y, v.z * 0.2);
            return true;
        }
        if (dy < CLIMB_DONE || st.placedThisClimb > MAX_PILLAR) {
            // Level with them, or the tower is getting silly. Back to fighting.
            st.active = false;
            st.latched = false;
            st.placedThisClimb = 0;
            return false;
        }

        st.active = true;
        return pillarStep(bot, profile, server, blockSlot);
    }

    /**
     * One tick of towering straight up. Returns true while the bot is committed to it, in which
     * case the caller must not also move or fight it.
     *
     * <p>Split out from the climb above because the motion has nothing to do with having a
     * target - it is what you do when you are lower than you want to be, which is as true at the
     * bottom of a hole as it is at the foot of a wall. Both callers share the tuning, and there
     * is one place to fix it when it is wrong.</p>
     *
     * @param blockSlot inventory slot holding something to build with
     */
    public static boolean pillarStep(ServerPlayer bot, BotProfile profile, MinecraftServer server,
                                     int blockSlot) {
        State st = state(bot.getName().getString());
        Inv.equip(bot.getInventory(), blockSlot, 3);

        // Anchor before pulling, not after. The column starts at zero, and a bot standing on the
        // ground with no anchor yet was nudged towards the world origin for the one tick before
        // the anchor further down caught up.
        if (bot.onGround()) {
            st.columnX = Math.floor(bot.getX()) + 0.5;
            st.columnZ = Math.floor(bot.getZ()) + 0.5;
        }

        // Hold the column. A pillar only works if you stay on top of it, and a bot that keeps
        // drifting sideways places one block then steps off it. Movement input is cut and the
        // bot is eased back to the centre of the column it started on.
        bot.zza = 0;
        bot.xxa = 0;
        bot.setSprinting(false);

        Vec3 velocity = bot.getDeltaMovement();
        double pullX = (st.columnX - bot.getX()) * 0.25;
        double pullZ = (st.columnZ - bot.getZ()) * 0.25;
        bot.setDeltaMovement(
            velocity.x * 0.1 + Math.max(-0.12, Math.min(0.12, pullX)),
            velocity.y,
            velocity.z * 0.1 + Math.max(-0.12, Math.min(0.12, pullZ)));

        Level world = bot.level();
        BlockPos standing = BlockPos.containing(st.columnX, bot.getY() + 0.05, st.columnZ);
        if (bot.onGround()) {
            standing = BlockPos.containing(bot.getX(), bot.getY() + 0.05, bot.getZ());
        }

        if (st.cooldown > 0) {
            st.cooldown--;
            aimDown(bot, world, standing, profile);
            return true;
        }

        if (bot.onGround()) {
            // Anchor the column to where the bot is standing right now, at the start of every
            // hop. Anchoring once at the start of the climb goes stale the moment the bot
            // shifts a block, and it then places pillars under a spot it is no longer on.
            st.columnX = Math.floor(bot.getX()) + 0.5;
            st.columnZ = Math.floor(bot.getZ()) + 0.5;
            standing = BlockPos.containing(st.columnX, bot.getY() + 0.05, st.columnZ);

            // Look down before the jump, so the head is already in place at the apex.
            aimDown(bot, world, standing, profile);
            BotActions.jump(bot, server);
            st.cooldown = 1;
            return true;
        }

        // Airborne. Near the top of the hop, fill the cell we just left.
        if (bot.getDeltaMovement().y < 0.15) {
            BlockPlacer.Aim aim = BlockPlacer.solve(world, bot, standing.below());
            if (aim == null) aim = BlockPlacer.solve(world, bot, standing);
            if (aim != null) {
                AimController.lookAtPrecise(bot, aim.lookPoint(), profile);
                if (aimedAt(bot, aim.lookPoint())) {
                    BotActions.place(bot, server);
                    st.placedThisClimb++;
                    st.cooldown = Rng.range(profile.rng(), 2, 4);
                }
            }
        }
        return true;
    }

    /** Points the head at the top face of whatever the bot is standing on. */
    private static void aimDown(ServerPlayer bot, Level world, BlockPos standing,
                                BotProfile profile) {
        BlockPlacer.Aim aim = BlockPlacer.solve(world, bot, standing.below());
        if (aim != null) {
            AimController.lookAtPrecise(bot, aim.lookPoint(), profile);
        } else {
            AimController.lookAtPrecise(bot,
                Vec3.atCenterOf(standing.below()), profile);
        }
    }

    /**
     * True once the crosshair is close enough to the intended point to click.
     *
     * <p>Placing a pillar block means looking almost straight down, and at that angle yaw stops
     * meaning anything - every heading points at very nearly the same spot. Enforcing a yaw
     * tolerance there rejected almost every otherwise-good aim, so the bot kept skipping its
     * placement window. Near vertical, only the pitch is checked.</p>
     */
    private static boolean aimedAt(ServerPlayer bot, Vec3 point) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float wantPitch = (float) -(Math.atan2(dy, horizontal) * (180.0 / Math.PI));
        if (Math.abs(AimController.wrap(wantPitch - bot.getXRot())) > AIM_TOLERANCE) {
            return false;
        }
        if (Math.abs(wantPitch) > 70.0f) {
            return true;
        }

        float wantYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(AimController.wrap(wantYaw - bot.getYRot())) <= AIM_TOLERANCE;
    }

    // ===================== bridging a gap =====================

    /**
     * True when there is a drop between the bot and its target.
     *
     * <p>Only a real drop counts - a single step down is something to walk off, not something
     * to build over. The bot must also be on the ground and facing across the gap rather than
     * standing beside it.</p>
     */
    public static boolean shouldBridgeGap(ServerPlayer bot, Entity target) {
        if (target == null) return false;
        // Already plummeting: too late to build, just ride it out.
        if (bot.getDeltaMovement().y < -0.35) return false;

        State st = state(bot.getName().getString());
        // Towering up is its own job. At the top of each hop the bot's feet are over a block of
        // air - the cell it is about to fill - so the drop test below saw a gap on every hop and
        // took the tick away from the climb, which then never got to place anything. One block,
        // and a bot bouncing on it for the rest of the fight.
        if (st.latched) return false;
        // Airborne, the question is where the bot will land, not what is under it this instant.
        // Only carry on if a crossing was already under way.
        if (!bot.onGround() && bot.level().getGameTime() - st.lastGapTick > 4) return false;

        Vec3 step = towards(bot, target);
        if (step == null) return false;

        // Close enough to hit: fight. And never look past the target - the drop behind somebody
        // stood on a pillar is not a gap between you and them, and treating it as one had the bot
        // stood next to its target trying to bridge instead of swinging.
        double flat = Math.hypot(target.getX() - bot.getX(), target.getZ() - bot.getZ());
        if (bot.distanceTo(target) <= GAP_MELEE_RANGE) return false;
        double farthest = Math.min(3.0, flat - 0.5);

        Level world = bot.level();

        // Look a stride and a half ahead, not one block. At walking speed the edge arrives in
        // two ticks, and if the bot happens to be mid-hop on the tick it would have noticed,
        // it sails straight into the hole instead of stopping at it. From half a block, not one:
        // a bot crouched out over the lip has the last gap right under its toes, and a probe
        // starting a block out stepped over it - the bridge stopped one block short every time.
        for (double reach = 0.5; reach <= farthest; reach += 0.5) {
            BlockPos ahead = BlockPos.containing(
                bot.getX() + step.x * reach, bot.getY() + 0.05, bot.getZ() + step.z * reach);
            if (BlockPlacer.isReplaceable(world, ahead)
                && BlockPlacer.isReplaceable(world, ahead.below())
                && BlockPlacer.isReplaceable(world, ahead.below(2))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lays one block at a time across a gap, then steps onto it.
     *
     * <p>This is the careful, deliberate version of bridging rather than a speed technique:
     * walk to the edge, stop, look down at the side of the block you are on, place, step out,
     * repeat. Slow on purpose - it is what it looks like when a player is being careful over a
     * drop, and it is far more reliable than trying to run and place at the same time.</p>
     */
    public static boolean tickGap(ServerPlayer bot, Entity target, BotProfile profile,
                                  MinecraftServer server) {
        State st = state(bot.getName().getString());

        Vec3 step = towards(bot, target);
        if (step == null) return false;

        int blockSlot = findBuildingBlock(bot);
        if (blockSlot < 0) return false;

        st.lastGapTick = bot.level().getGameTime();

        // Crouch for the whole manoeuvre. This is not decoration: sneaking is what stops a
        // player walking off the ledge, and it is what lets the bot stand with its body out
        // over the drop - which it has to do, because the face it needs to click is the *side*
        // of the block under its own feet, and that face is not visible from a centred stance.
        Crouch.hold(bot, server, 6);

        // Just placed one: shuffle out onto it before laying the next.
        if (st.gapCooldown > 0) {
            st.gapCooldown--;
            nudge(bot, step, 0.45);
            return true;
        }

        // Hold still until both feet are down. Placing mid-hop is what puts a bot in the hole.
        if (!bot.onGround()) {
            bot.zza = 0;
            bot.xxa = 0;
            bot.setSprinting(false);
            Vec3 air = bot.getDeltaMovement();
            bot.setDeltaMovement(air.x * 0.5, air.y, air.z * 0.5);
            return true;
        }

        Level world = bot.level();
        Direction dir = Math.abs(step.x) >= Math.abs(step.z)
            ? (step.x > 0 ? Direction.EAST : Direction.WEST)
            : (step.z > 0 ? Direction.SOUTH : Direction.NORTH);

        BlockPos support = supportUnder(bot, dir);
        if (support == null) return false;

        // The cell to fill is the neighbour of the block underfoot, along whichever axis the
        // bot is actually travelling. A diagonal cell shares no face with the support and
        // cannot be placed against it at all.
        BlockPos gap = support.relative(dir);

        if (!BlockPlacer.isReplaceable(world, gap)) {
            // The drop was spotted a stride and a half ahead, but the cell right next to the
            // bot is still solid: it is walking up to the lip, not standing on it. Keep hold
            // of the bot and close that last stride at a walk. Handing control back to the
            // combat movement here was what put the bot in the hole - it arrived at the edge
            // still sprinting and simply ran off it, then bridged its way out from the bottom.
            if (!shouldBridgeGap(bot, target)) return false;
            // Shed the run-up first. Arriving at the lip with sprint momentum carries the bot
            // over it however gently it is steered on the last tick.
            Vec3 v = bot.getDeltaMovement();
            if (Math.hypot(v.x, v.z) > 0.09) {
                brake(bot);
            } else {
                nudge(bot, step, 0.28);
            }
            return true;
        }

        Inv.equip(bot.getInventory(), blockSlot, 3);

        BlockPlacer.Aim aim = BlockPlacer.solveAgainst(world, bot, gap, support, dir);
        if (aim == null) {
            // The side face is not in view yet, which means the bot is still standing too far
            // back on its own block. Creep forward until the eyes clear the lip and the face
            // comes into sight - but only up to the overhang a crouching player can hold.
            // Relying on the crouch alone to stop the bot was not enough: the backend applies
            // it a tick or two later than the movement, and in that window the bot walked
            // straight off the edge and finished the crossing from the bottom of the hole.
            if (overhang(bot, support, step) < MAX_OVERHANG) {
                nudge(bot, step, 0.22);
            } else {
                brake(bot);
            }
            return true;
        }

        // Stand still to place. Stepping out mid-placement is how you end up in the hole.
        brake(bot);

        // Aiming at the solved face turns the bot to look back and down at the block under its
        // feet, which is what bridging actually looks like. It used to aim roughly at where it
        // wanted the block to end up - facing its destination, head level - and the click
        // landed on the top face of its own support, where its body blocked the placement.
        AimController.lookAtPrecise(bot, aim.lookPoint(), profile);

        if (aimedAt(bot, aim.lookPoint())) {
            BotActions.place(bot, server);
            st.gapCooldown = Rng.range(profile.rng(), 5, 9);
        }
        return true;
    }

    /**
     * Walks the bot along {@code step} at the given speed whatever way its head is pointing.
     *
     * <p>Movement input is in the bot's own frame, so "forward" is wherever it happens to be
     * facing. While bridging it is looking backwards at the block it is placing against, so a
     * hard-coded positive forward input would drive it away from the gap.</p>
     */
    private static void nudge(ServerPlayer bot, Vec3 step, double speed) {
        bot.setSprinting(false);
        double yaw = Math.toRadians(bot.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        bot.zza = (float) ((-step.x * sin + step.z * cos) * speed);
        bot.xxa = (float) ((step.x * cos + step.z * sin) * speed);
        // Movement input alone is not enough for the last few centimetres onto the lip: the
        // backend rewrites the input each tick from its own state, so a small direct nudge is
        // what actually carries the bot out over the edge. Kept tiny - the crouch is what
        // stops it becoming a fall.
        bot.push(step.x * speed * 0.06, 0, step.z * speed * 0.06);
    }

    /** Kills horizontal movement without touching the fall. */
    private static void brake(ServerPlayer bot) {
        bot.zza = 0;
        bot.xxa = 0;
        bot.setSprinting(false);
        Vec3 velocity = bot.getDeltaMovement();
        bot.setDeltaMovement(velocity.x * 0.2, velocity.y, velocity.z * 0.2);
    }

    /**
     * The block the bot is actually standing on, given it may be hanging over the edge.
     *
     * <p>{@link BlockPlacer#groundBelow} reads the cell under the bot's own centre, which is
     * correct right up until the bot crouches out over the lip - and it has to do exactly that
     * to see the side face it needs to click. Past the edge, that call returns the empty cell
     * in front, so the next placement was aimed two cells out with nothing to place against.
     * That is why bridges stopped after a single block. Step back along the travel axis to
     * find the block genuinely underfoot.</p>
     */
    private static BlockPos supportUnder(ServerPlayer bot, Direction dir) {
        Level world = bot.level();
        BlockPos under = BlockPlacer.groundBelow(bot);
        if (isSolid(world, under)) return under;

        // Overhanging, or standing on a corner: the block holding the bot up is one of the cells
        // under its hitbox rather than the one under its centre. Of those, the one furthest along
        // the way it is heading, since that is the one whose side face extends the bridge.
        // Checking only straight back along the travel axis missed a bot perched on the corner of
        // a platform with its centre over air on the other axis, which never started a diagonal
        // crossing at all.
        double y = bot.getY() - 0.2;
        BlockPos best = null;
        double bestAlong = Double.NEGATIVE_INFINITY;
        // A hair wider than the hitbox's half width of 0.3: a crouching player at the lip is held
        // by exactly its edge, touching the block it stands on and nothing more.
        for (double ox = -0.32; ox <= 0.33; ox += 0.32) {
            for (double oz = -0.32; oz <= 0.33; oz += 0.32) {
                BlockPos cell = BlockPos.containing(bot.getX() + ox, y, bot.getZ() + oz);
                if (!isSolid(world, cell)) continue;
                double along = cell.getX() * dir.getStepX() + cell.getZ() * dir.getStepZ();
                if (along > bestAlong) {
                    bestAlong = along;
                    best = cell;
                }
            }
        }
        return best;
    }

    private static boolean isSolid(Level world, BlockPos pos) {
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /**
     * How far past the leading edge of {@code support} the bot's centre has moved, in blocks.
     *
     * <p>Negative while it is still fully on the block. A crouching player can hold roughly a
     * third of a block of overhang, which is exactly the reach needed to see the side face
     * below - past that, it is falling.</p>
     */
    private static double overhang(ServerPlayer bot, BlockPos support, Vec3 step) {
        if (Math.abs(step.x) >= Math.abs(step.z)) {
            double edge = step.x > 0 ? support.getX() + 1.0 : support.getX();
            return step.x > 0 ? bot.getX() - edge : edge - bot.getX();
        }
        double edge = step.z > 0 ? support.getZ() + 1.0 : support.getZ();
        return step.z > 0 ? bot.getZ() - edge : edge - bot.getZ();
    }

    /** Unit step toward the target on the horizontal plane, or null when on top of it. */
    private static Vec3 towards(ServerPlayer bot, Entity target) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 0.6) return null;
        return new Vec3(dx / length, 0, dz / length);
    }

    /**
     * A block the bot is willing to spend on scaffolding. Combat resources and hazards are
     * excluded so a bot never bridges away its obsidian or drops a TNT under itself.
     */
    public static int findBuildingBlock(ServerPlayer bot) {
        return Inv.find(bot.getInventory(), Bridger::isBuildingBlock);
    }

    public static boolean isBuildingBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        Block block = blockItem.getBlock();

        if (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN
            || block == Blocks.RESPAWN_ANCHOR || block == Blocks.COBWEB
            || block == Blocks.TNT || block == Blocks.SAND || block == Blocks.GRAVEL
            || block == Blocks.RED_SAND || block == Blocks.MAGMA_BLOCK
            || block == Blocks.SCAFFOLDING) {
            return false;
        }
        if (stack.getItem() == Items.END_CRYSTAL) return false;

        var state = block.defaultBlockState();
        // Full cubes only: slabs, stairs and fences make unreliable pillars.
        return Block.isShapeFullBlock(state.getCollisionShape(
            net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    public static void clear() {
        STATES.clear();
    }
}
