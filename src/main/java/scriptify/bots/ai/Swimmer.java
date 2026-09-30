package scriptify.bots.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/**
 * Moving through water the way a player does: by holding keys, not by being thrown about.
 *
 * <p><b>What was wrong.</b> Water movement used to be a velocity push plus a full ground jump every
 * eight ticks, plus another upward push from the utility tick. A ground jump in water is not
 * something the game ever does - holding space in water adds a small upward nudge each tick - so the
 * bots leapt out of the water like dolphins and fell back in, over and over, and barely made
 * headway between leaps.</p>
 *
 * <p><b>What a player actually does.</b> At the surface, they hold W and space: space keeps the head
 * out of the water with a gentle bob, W paddles them along at a little under walking pace, and
 * pushing into a bank lifts them out onto it. Crossing any distance underwater they sprint, which
 * the game turns into the swimming pose and a much faster stroke that follows where they look. They
 * come up when they run short of air. This class decides which keys those are and
 * {@link BotInput} holds them, so the game's own swimming rules produce the motion.</p>
 */
public final class Swimmer {

    private Swimmer() {}

    /**
     * Water this far up the body means swimming rather than wading.
     *
     * <p>A block of water is 0.89 deep, and a player standing in it simply walks - their head is well
     * clear. Past a block, standing on the bottom puts the eyes under, and that is swimming.</p>
     */
    private static final double SWIM_DEPTH = 1.05;

    /** Off the ground with this much water around the body, the bot is afloat. */
    private static final double FLOAT_DEPTH = 0.4;

    /** Below this share of air a player stops whatever they are doing and surfaces. */
    private static final double SURFACE_FOR_AIR = 0.4;

    /** Further than this underwater, and a player sprint-swims rather than paddles. */
    private static final double SPRINT_SWIM_DISTANCE = 5.0;

    /** A goal this far below the bot is worth diving for. */
    private static final double DIVE_DEPTH = 1.5;

    /** Within this of a goal up on land, the bot is coming ashore rather than crossing. */
    private static final double LANDING_DISTANCE = 6.0;

    /** Whether the bot is swimming, as opposed to dry or wading. */
    public static boolean isSwimming(ServerPlayer bot) {
        if (!bot.isInWater()) return false;
        double depth = bot.getFluidHeight(FluidTags.WATER);
        return depth > SWIM_DEPTH || (!bot.onGround() && depth > FLOAT_DEPTH);
    }

    /**
     * Swims toward a point.
     *
     * @param speed the caller's pace, 1.0 being full; below it the bot paddles more gently
     */
    public static void swimToward(ServerPlayer bot, Vec3 goal, double speed) {
        double dx = goal.x - bot.getX();
        double dz = goal.z - bot.getZ();
        double flat = Math.hypot(dx, dz);

        float forward = 0.0f;
        float strafe = 0.0f;
        if (flat > 0.3) {
            // The key presses that move this way given where the bot is facing. Minecraft's frame:
            // forward is (-sin yaw, cos yaw), and a positive strafe is to the left.
            double yaw = Math.toRadians(bot.getYRot());
            double ux = dx / flat;
            double uz = dz / flat;
            double f = -ux * Math.sin(yaw) + uz * Math.cos(yaw);
            double s = ux * Math.cos(yaw) + uz * Math.sin(yaw);
            double pace = Math.max(0.35, Math.min(1.0, speed));
            forward = (float) (f * pace);
            strafe = (float) (s * pace);
        }

        boolean eyesUnder = bot.isUnderWater();
        boolean needAir = bot.getAirSupply() < bot.getMaxAirSupply() * SURFACE_FOR_AIR;
        double rise = goal.y - bot.getY();
        boolean dive = !needAir && rise < -DIVE_DEPTH;
        // Coming ashore: the goal is up on land and near, or the bot is already against the bank.
        // A player stops the stroke here and holds space to haul out - the prone swimming pose
        // cannot climb a ledge, and a bot that kept it pressed its face into the bank.
        boolean landing = (rise > 0.3 && flat < LANDING_DISTANCE) || bot.horizontalCollision;

        // Sprint-swimming: underwater, a way to go, and facing roughly that way - you cannot sprint
        // sideways or backwards. Once swimming it carries on until the trip is nearly over, rather
        // than dropping in and out of the stroke as the bot bobs across the surface.
        boolean stroke = !needAir && !landing && forward > 0.6f
            && (bot.isSwimming() ? flat > 3.0 : eyesUnder && flat > SPRINT_SWIM_DISTANCE);

        // Space: held at the surface to keep the head out and to climb out onto banks; let go to
        // dive, and while stroking underwater, where it would only drag the bot up to the surface.
        boolean jump = needAir || (!dive && !(stroke && eyesUnder));

        bot.setSprinting(stroke);
        BotInput.hold(bot, forward, strafe, jump);
    }

    /**
     * Treading water: stay where you are with your head out.
     *
     * <p>What a player does when they have stopped in deep water - hold space and nothing else. The
     * default for any bot afloat, laid down before anything else decides where it should go, so a bot
     * that is eating, deciding, or simply idle does not sink and drown.</p>
     */
    public static void tread(ServerPlayer bot) {
        if (!isSwimming(bot)) {
            leave(bot);
            return;
        }
        bot.setSprinting(false);
        BotInput.hold(bot, 0.0f, 0.0f, true);
    }

    /** Takes the hands off the keys once the bot is out of the water. */
    public static void leave(ServerPlayer bot) {
        if (BotInput.isHolding(bot)) {
            BotInput.release(bot);
        }
    }
}
