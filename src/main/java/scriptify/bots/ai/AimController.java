package scriptify.bots.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Humanised head movement.
 *
 * <p>The original mod snapped the head straight onto the target every tick
 * ({@code setYaw/setPitch} with a 90 deg/tick cap, i.e. instant), and several subsystems
 * additionally slammed the pitch to exactly 90 to throw potions. The result was the
 * unmistakable "robot head" look: zero travel time, zero overshoot, perfect tracking.</p>
 *
 * <p>This controller instead models a hand on a mouse:</p>
 * <ul>
 *   <li>rotation is rate-limited per tick, with ease-in/ease-out so long flicks accelerate
 *       and settle instead of moving at a constant speed;</li>
 *   <li>flicks overshoot slightly and correct back, like a real player;</li>
 *   <li>a small amount of drift/tremor is always present, scaled by how much the bot is
 *       moving and inversely by its skill;</li>
 *   <li>the head lags the aim point on fast-moving targets rather than tracking perfectly;</li>
 *   <li>the bot occasionally glances away (situational awareness) when not mid-swing.</li>
 * </ul>
 */
public final class AimController {

    private AimController() {}

    private static final Map<String, AimState> STATES = new HashMap<>();

    public static class AimState {
        public float targetYaw;
        public float targetPitch;
        public float yawVelocity;
        public float pitchVelocity;
        public float overshootYaw;
        public int overshootTicks;
        public int glanceTicks;
        public float glanceYaw;
        public int settleTicks;
        public boolean hasTarget;
        /** Where the hand has wandered off the target, in blocks: a random walk, not noise. */
        public double errX;
        public double errY;
        public double errZ;
        /** How likely, per tick, a look elsewhere is right now. */
        public double glanceChance = 0.003;
    }

    public static AimState state(String botName) {
        return STATES.computeIfAbsent(scriptify.bots.utils.Names.key(botName), k -> new AimState());
    }

    public static void remove(String botName) {
        STATES.remove(scriptify.bots.utils.Names.key(botName));
    }

    public static void clear() {
        STATES.clear();
    }

    /** Aim at an entity, leading it slightly the way a human tracks a strafing player. */
    public static void lookAt(ServerPlayer bot, Entity target, BotProfile profile) {
        if (target == null) return;
        // Within reach a player watches the man they are fighting. Further off - waiting their turn,
        // walking in - they look about: at the crowd, the ground, whoever just shouted. An army that
        // all stared at one player the whole time was the clearest sign it was not players.
        double dist = bot.distanceTo(target);
        state(bot.getName().getString()).glanceChance = dist > 5.0 ? 0.025 : 0.004;
        Vec3 aim = aimPoint(bot, target, profile);
        lookAtPos(bot, aim, profile, false);
    }

    /** A stable 0-1 aim skill per bot, from its name, so the same bot always aims the same way. */
    private static double skillOf(ServerPlayer bot) {
        return BotProfile.of(bot.getName().getString()).skill();
    }

    /** Aim precisely (used for block placement, where sloppiness means a misplaced block). */
    public static void lookAtPrecise(ServerPlayer bot, Vec3 pos, BotProfile profile) {
        lookAtPos(bot, pos, profile, true);
    }

    public static void lookAtPos(ServerPlayer bot, Vec3 pos, BotProfile profile, boolean precise) {
        AimState st = state(bot.getName().getString());

        Vec3 eye = bot.getEyePosition();
        double dx = pos.x - eye.x;
        double dy = pos.y - eye.y;
        double dz = pos.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float desiredYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float desiredPitch = (float) -(Math.atan2(dy, horiz) * (180.0 / Math.PI));

        st.targetYaw = desiredYaw;
        st.targetPitch = desiredPitch;
        st.hasTarget = true;

        apply(bot, profile, st, precise);
    }

    /**
     * Advances the head one tick towards the stored aim point. Called by the aim path above
     * and, for bots that have nothing to look at, left alone so the head simply holds still.
     */
    private static void apply(ServerPlayer bot, BotProfile profile, AimState st, boolean precise) {
        float yaw = bot.getYRot();
        float pitch = bot.getXRot();

        float wantYaw = st.targetYaw;
        float wantPitch = st.targetPitch;

        // Occasional glance away - a human checks their surroundings mid-fight.
        if (!precise) {
            if (st.glanceTicks > 0) {
                st.glanceTicks--;
                wantYaw += st.glanceYaw;
            } else if (Rng.chance(profile.rng(), st.glanceChance)) {
                boolean far = st.glanceChance > 0.01;
                st.glanceTicks = far ? Rng.range(profile.rng(), 6, 20) : Rng.range(profile.rng(), 2, 6);
                double reach = far ? 70.0 : 35.0;
                st.glanceYaw = (float) Rng.range(profile.rng(), -reach, reach);
            }
        }

        float yawDelta = wrap(wantYaw - yaw);
        float pitchDelta = wrap(wantPitch - pitch);

        // Base turn rate, eased: big corrections start fast and decelerate near the target.
        double baseSpeed = profile.aimSpeed() * (precise ? 1.6 : 1.0);
        double yawStep = ease(yawDelta, baseSpeed, precise);
        double pitchStep = ease(pitchDelta, baseSpeed * 0.75, precise);

        // Flick overshoot: on a large correction, aim a few degrees past and come back.
        if (!precise && Math.abs(yawDelta) > 45 && st.overshootTicks <= 0
            && profile.tech(0.5)) {
            st.overshootYaw = (float) (Math.signum(yawDelta) * Rng.range(profile.rng(), 2.0, 6.0));
            st.overshootTicks = Rng.range(profile.rng(), 2, 4);
        }
        if (st.overshootTicks > 0) {
            st.overshootTicks--;
            yawStep += st.overshootYaw * 0.35;
        }

        // Tremor / drift. Always present, larger when sprinting or unskilled.
        if (!precise) {
            double tremor = 0.35 * (bot.isSprinting() ? 1.5 : 1.0);
            yawStep += profile.rng().nextGaussian() * tremor;
            pitchStep += profile.rng().nextGaussian() * tremor * 0.6;
        }

        float newYaw = yaw + (float) yawStep;
        float newPitch = clampPitch(pitch + (float) pitchStep);

        bot.setYRot(newYaw);
        bot.setYHeadRot(newYaw);
        bot.setYBodyRot(newYaw);
        bot.setXRot(newPitch);
    }

    /**
     * Ease curve: the step taken this tick is proportional to how far there is left to go,
     * capped by the bot's maximum turn rate. Produces acceleration at the start of a flick
     * and a soft settle at the end, instead of a linear sweep or a snap.
     */
    private static double ease(float delta, double maxRate, boolean precise) {
        double magnitude = Math.abs(delta);
        // Tracking a player closes about a third of the gap a tick: a hand settling on a target, not a
        // lock snapping on. Lining up a block or a bucket is deliberate and quicker to settle.
        double proportional = magnitude * (precise ? 0.42 : 0.32) + (precise ? 0.6 : 0.5);
        double step = Math.min(magnitude, Math.min(proportional, maxRate));
        return Math.signum(delta) * step;
    }

    /** Look roughly away from a target (retreating). Uses the same smoothing. */
    public static void lookAway(ServerPlayer bot, Entity target, BotProfile profile) {
        Vec3 eye = bot.getEyePosition();
        Vec3 tp = target.getEyePosition();
        double dx = eye.x - tp.x;
        double dz = eye.z - tp.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) { dx = 1; dz = 0; len = 1; }
        Vec3 away = new Vec3(eye.x + dx / len * 8.0, eye.y - 0.5, eye.z + dz / len * 8.0);
        lookAtPos(bot, away, profile, false);
    }

    /**
     * The point the bot actually aims at: the target's hitbox, offset by a personal bias and
     * lagging behind fast movement so tracking is imperfect.
     */
    /**
     * Where on the target the hand is actually pointing this tick.
     *
     * <p>A person tracking a moving player does three things a machine does not. They react to what
     * they saw, so the crosshair trails the target by a few ticks rather than leading it. Their hand
     * drifts, and drifts further the faster the target crosses their view - a random walk, not
     * jitter, so the error wanders and comes back rather than buzzing. And they each have a habit:
     * this one aims at the head, that one at the chest. The old version led every target exactly,
     * which on a strafing player is the signature of aim assist.</p>
     */
    private static Vec3 aimPoint(ServerPlayer bot, Entity target, BotProfile profile) {
        AimState st = state(bot.getName().getString());
        var box = target.getBoundingBox();
        double height = box.maxY - box.minY;
        double skill = skillOf(bot);

        Vec3 vel = target.getDeltaMovement();
        double lateral = Math.hypot(vel.x, vel.z);
        double lag = 1.0 + (1.0 - skill) * 3.0;

        double sigma = 0.025 + (1.0 - skill) * 0.04 + lateral * 0.25;
        var rng = profile.rng();
        st.errX = st.errX * 0.9 + rng.nextGaussian() * sigma;
        st.errY = st.errY * 0.9 + rng.nextGaussian() * sigma * 0.6;
        st.errZ = st.errZ * 0.9 + rng.nextGaussian() * sigma;

        double habit = 0.55 + skill * 0.35;          // chest for some, head for others
        return new Vec3(
            target.getX() - vel.x * lag + st.errX,
            box.minY + height * habit + st.errY,
            target.getZ() - vel.z * lag + st.errZ
        );
    }

    /**
     * Pulls the head towards a pitch over several ticks instead of slamming it, used for
     * throwing potions and XP bottles.
     */
    public static void lookPitch(ServerPlayer bot, float desiredPitch, BotProfile profile) {
        float delta = wrap(desiredPitch - bot.getXRot());
        double step = ease(delta, profile.aimSpeed() * 1.2, true);
        bot.setXRot(clampPitch(bot.getXRot() + (float) step));
    }

    /** True once the head is close enough to the aim point to act on it. */
    public static boolean onTarget(ServerPlayer bot, Entity target, double toleranceDegrees) {
        Vec3 eye = bot.getEyePosition();
        Vec3 tp = target.getEyePosition();
        double dx = tp.x - eye.x;
        double dy = tp.y - eye.y;
        double dz = tp.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float wantPitch = (float) -(Math.atan2(dy, horiz) * (180.0 / Math.PI));

        if (Math.abs(wrap(wantPitch - bot.getXRot())) > toleranceDegrees * 1.5) return false;

        // Looking almost straight up or down, yaw stops describing where you are pointing: a
        // few centimetres of drift swings the required yaw by tens of degrees while the
        // crosshair barely moves. Testing it there is how a bot diving onto someone from
        // directly overhead decides it is "not aimed yet" and never swings.
        if (Math.abs(wantPitch) > 70.0f) return true;

        float wantYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(wrap(wantYaw - bot.getYRot())) <= toleranceDegrees;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    public static float wrap(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }
}
