package scriptify.bots.ai;

import java.util.Random;

/**
 * Randomness helpers used across the AI.
 *
 * <p>Every bot owns its own {@link Random} (see {@link BotProfile}) so that two bots that
 * tick in the same server tick, with the same gear, in the same situation, still roll
 * different numbers. Nothing in the combat code is allowed to use a shared static RNG for
 * per-bot decisions - that is what made the original mod's bots fire in lockstep.</p>
 */
public final class Rng {

    private Rng() {}

    /** Gaussian sample clamped to [min, max]. */
    public static double gaussian(Random r, double mean, double stdDev, double min, double max) {
        double v = mean + r.nextGaussian() * stdDev;
        return Math.max(min, Math.min(max, v));
    }

    /** Gaussian sample rounded to a tick count, clamped to [min, max]. */
    public static int gaussianTicks(Random r, double mean, double stdDev, int min, int max) {
        int v = (int) Math.round(mean + r.nextGaussian() * stdDev);
        return Math.max(min, Math.min(max, v));
    }

    /** Uniform int in [min, max] inclusive. */
    public static int range(Random r, int min, int max) {
        if (max <= min) return min;
        return min + r.nextInt(max - min + 1);
    }

    /** Uniform double in [min, max]. */
    public static double range(Random r, double min, double max) {
        if (max <= min) return min;
        return min + r.nextDouble() * (max - min);
    }

    /** True with probability {@code chance} (0..1). */
    public static boolean chance(Random r, double chance) {
        if (chance <= 0) return false;
        if (chance >= 1) return true;
        return r.nextDouble() < chance;
    }

    /** Multiplies a base value by a random factor in [1-spread, 1+spread]. */
    public static double jitter(Random r, double base, double spread) {
        return base * (1.0 + (r.nextDouble() * 2.0 - 1.0) * spread);
    }

    /** Jittered tick count, never below 1. */
    public static int jitterTicks(Random r, int base, double spread) {
        return Math.max(1, (int) Math.round(jitter(r, base, spread)));
    }

    /** Linear interpolation. */
    public static double lerp(double a, double b, double t) {
        return a + (b - a) * Math.max(0.0, Math.min(1.0, t));
    }
}
