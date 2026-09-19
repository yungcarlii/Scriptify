package scriptify.bots.ai;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;

/**
 * Works out where a bot has to look in order to place a block at a given position.
 *
 * <p>The fake-player backend places blocks by running a "use" action wherever the bot is
 * currently looking, so placement accuracy is entirely an aiming problem. The original
 * cobweb code just pointed at the target's feet and spammed use for a few ticks, which
 * missed most of the time: pointing at a coordinate is not the same as pointing at a
 * placeable face of a neighbouring block, and nothing verified the ray actually landed
 * where it needed to.</p>
 *
 * <p>This resolves a real placement: pick the air cell we want filled, find a supporting
 * neighbour, aim at the point on that neighbour's face nearest the bot's eyes, and confirm
 * with a raycast that nothing else is in the way and that it is inside reach.</p>
 */
public final class BlockPlacer {

    private BlockPlacer() {}

    /** Vanilla-ish creative/survival interaction reach with a little safety margin. */
    public static final double REACH = 4.4;

    public record Aim(Vec3 lookPoint, BlockPos support, Direction face, BlockPos placed) {}

    /** True when a block can legally be placed into this cell. */
    public static boolean isReplaceable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /** True when the cell is free of entities (needed for full, collidable blocks). */
    public static boolean isClearOfEntities(Level world, BlockPos pos) {
        return world.getEntities(null, new net.minecraft.world.phys.AABB(pos)).isEmpty();
    }

    /** A block that can support a placement on one of its faces. */
    private static boolean isSupport(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced() && !state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Finds a way to place a block at {@code target}.
     *
     * @return an aim solution, or null when there is no supporting face the bot can reach
     */
    public static Aim solve(Level world, ServerPlayer bot, BlockPos target, double reach) {
        if (!isReplaceable(world, target)) return null;

        Vec3 eye = bot.getEyePosition();
        Aim best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos support = target.relative(dir);
            if (!isSupport(world, support)) continue;

            // The face of `support` that touches `target`.
            Direction face = dir.getOpposite();
            Vec3 faceCenter = faceCenter(support, face);

            double dist = eye.distanceTo(faceCenter);
            if (dist > reach) continue;

            // Confirm the ray really lands on that face and is not blocked by anything else.
            if (!rayLandsOn(world, bot, eye, faceCenter, support, face)) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = new Aim(faceCenter, support, face, target);
            }
        }
        return best;
    }

    public static Aim solve(Level world, ServerPlayer bot, BlockPos target) {
        return solve(world, bot, target, REACH);
    }

    /**
     * Solves a placement against one specific face, rather than whichever face happens to be
     * nearest.
     *
     * <p>Bridging knows exactly which face it wants - the side of the block underfoot, facing
     * the way the bot is heading. Letting the general search pick for it meant a bot at the
     * lip could latch onto some other reachable face and put the block somewhere that did not
     * extend the bridge.</p>
     *
     * @param support the block to place against
     * @param face    which face of {@code support} the new block should sit on
     */
    public static Aim solveAgainst(Level world, ServerPlayer bot, BlockPos target,
                                   BlockPos support, Direction face) {
        if (!isReplaceable(world, target)) return null;
        if (!isSupport(world, support)) return null;
        if (!support.relative(face).equals(target)) return null;

        Vec3 eye = bot.getEyePosition();
        Vec3 faceCenter = faceCenter(support, face);
        if (eye.distanceTo(faceCenter) > REACH) return null;

        if (!rayLandsOn(world, bot, eye, faceCenter, support, face)) return null;

        return new Aim(faceCenter, support, face, target);
    }

    /**
     * Whether a ray from the eyes really lands on that face of that block.
     *
     * <p>The face matters, not just the block. Standing on top of the support and aiming at its
     * side, the ray leaves the eye and clips the <em>top</em> face on the way down - same block,
     * wrong face - and the block then goes on top of the bot's own head instead of out over the
     * gap, where it is blocked by the bot's own body and nothing is placed at all. That was
     * bridging walking off the edge and falling.</p>
     *
     * <p>Two casts, and only ever two in the case that used to be a flat refusal. The outline
     * cast is the one the game itself uses to decide what a click hits, so it is asked first and
     * its answer is authoritative. It has one blind spot: it stops on things that are not
     * obstacles. A bot cocooning itself in cobweb placed exactly one web and gave up, because
     * from the second placement onward its eyes were inside a web and every ray it cast hit
     * that. So when the outline cast says no, the question is asked again ignoring anything
     * without a collision box - which is exactly the set of blocks a placement passes through,
     * and leaves fences, walls and slabs behaving as they did.</p>
     */
    private static boolean rayLandsOn(Level world, ServerPlayer bot, Vec3 eye, Vec3 faceCenter,
                                      BlockPos support, Direction face) {
        return lands(world, bot, eye, faceCenter, support, face, ClipContext.Block.OUTLINE)
            || lands(world, bot, eye, faceCenter, support, face, ClipContext.Block.COLLIDER);
    }

    private static boolean lands(Level world, ServerPlayer bot, Vec3 eye, Vec3 faceCenter,
                                 BlockPos support, Direction face, ClipContext.Block mode) {
        BlockHitResult hit = (BlockHitResult) world.clip(new ClipContext(
            eye, faceCenter, mode, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(support)
            && hit.getDirection() == face;
    }

    /**
     * Centre of a block face, pulled a hair inside the block so floating-point error cannot
     * push the ray past it.
     */
    public static Vec3 faceCenter(BlockPos pos, Direction face) {
        double x = pos.getX() + 0.5 + face.getStepX() * 0.49;
        double y = pos.getY() + 0.5 + face.getStepY() * 0.49;
        double z = pos.getZ() + 0.5 + face.getStepZ() * 0.49;
        return new Vec3(x, y, z);
    }

    /** The block the bot is standing on. */
    public static BlockPos groundBelow(ServerPlayer bot) {
        return BlockPos.containing(bot.getX(), bot.getY() - 0.2, bot.getZ());
    }
}
