package scriptify.bots.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What a right or left click would do if the bot made it right now.
 *
 * <p>Every hand action a bot takes is a real click by the backend, cast along wherever the head is
 * pointing at that moment. So the honest test for "am I aimed?" is not an angle - it is to cast the
 * same ray the click will cast and see what it lands on. The angle tests this replaces had two
 * blind spots that were the whole of the water-bucket bug:</p>
 * <ul>
 *   <li><b>Looking down.</b> Near vertical, yaw stops meaning anything: a few centimetres of drift
 *       swings the "wanted" yaw by tens of degrees while the crosshair barely moves. A bot looking
 *       straight at the water it had to pick up could fail the test indefinitely.</li>
 *   <li><b>Head in a block.</b> A ray that starts inside a web hits that web, whatever the head is
 *       pointed at, so where a click lands is not where the bot is looking.</li>
 * </ul>
 * <p>Both are answered by the ray itself, because it is the same ray.</p>
 */
public final class ClickSim {

    private ClickSim() {}

    /** The ray a click casts: from the eyes, along the view, as far as the bot can reach. */
    public static BlockHitResult ray(ServerPlayer bot, ClipContext.Fluid fluid) {
        Vec3 eye = bot.getEyePosition();
        Vec3 end = eye.add(bot.getViewVector(1.0f).scale(bot.blockInteractionRange()));
        return bot.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, bot));
    }

    /**
     * Whether somebody's hitbox is in front of the block the ray reached.
     *
     * <p>The backend traces entities as well as blocks, so an opponent standing in the way takes the
     * click: a web or a bucket aimed past them does nothing at all.</p>
     */
    public static boolean entityInTheWay(ServerPlayer bot, BlockHitResult hit) {
        Vec3 eye = bot.getEyePosition();
        Vec3 to = hit.getType() == HitResult.Type.MISS
            ? eye.add(bot.getViewVector(1.0f).scale(bot.entityInteractionRange()))
            : hit.getLocation();
        double reach = bot.entityInteractionRange();
        double limit = Math.min(eye.distanceToSqr(to), reach * reach);
        AABB sweep = bot.getBoundingBox().expandTowards(to.subtract(eye)).inflate(1.0);
        return ProjectileUtil.getEntityHitResult(bot, eye, to, sweep,
            entity -> !entity.isSpectator() && entity.isPickable(), limit) != null;
    }

    /** The block a left click would hit, or null when it would hit nothing or somebody. */
    public static BlockPos targetedBlock(ServerPlayer bot) {
        BlockHitResult hit = ray(bot, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK || entityInTheWay(bot, hit)) return null;
        return hit.getBlockPos();
    }

    /**
     * The cell a held block would be placed in, following the game's rule: into the clicked block if
     * it can be replaced, otherwise against the face that was hit.
     */
    public static BlockPos placementCell(ServerPlayer bot) {
        BlockHitResult hit = ray(bot, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK || entityInTheWay(bot, hit)) return null;
        Level world = bot.level();
        BlockPos clicked = hit.getBlockPos();
        BlockPos cell = world.getBlockState(clicked).canBeReplaced()
            ? clicked
            : clicked.relative(hit.getDirection());
        return world.getBlockState(cell).canBeReplaced() ? cell : null;
    }

    /**
     * The cell a water bucket would empty into, following {@code BucketItem}: beside the face that
     * was hit, unless the clicked block holds water itself. A cobweb is neither - water cannot be
     * poured into one, only next to it, and flows in from there.
     */
    public static BlockPos waterCell(ServerPlayer bot) {
        BlockHitResult hit = ray(bot, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK || entityInTheWay(bot, hit)) return null;
        Level world = bot.level();
        BlockPos clicked = hit.getBlockPos();
        BlockPos cell = world.getBlockState(clicked).getBlock() instanceof LiquidBlockContainer
            ? clicked
            : clicked.relative(hit.getDirection());
        BlockState there = world.getBlockState(cell);
        boolean fits = there.isAir() || there.canBeReplaced(Fluids.WATER)
            || there.getBlock() instanceof LiquidBlockContainer;
        return fits ? cell : null;
    }

    /** The water source an empty bucket would scoop, or null. */
    public static BlockPos pickupCell(ServerPlayer bot) {
        BlockHitResult hit = ray(bot, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK || entityInTheWay(bot, hit)) return null;
        BlockPos pos = hit.getBlockPos();
        var fluid = bot.level().getFluidState(pos);
        return fluid.isSource() && fluid.is(FluidTags.WATER) ? pos : null;
    }
}
