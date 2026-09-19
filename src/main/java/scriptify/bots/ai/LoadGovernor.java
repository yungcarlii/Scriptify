package scriptify.bots.ai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.utils.Names;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides how much thinking the server can afford this tick, and shares it out.
 *
 * <p><b>The problem.</b> A tick is fifty milliseconds. Spend more than that and the whole world
 * runs late - which is not a stutter, it is slow motion: mobs crawl, bots drift, and the server
 * reports a healthy heap the entire time, because none of it was ever about memory. Two hundred
 * and fifty bots each running a combat brain every tick is more work than fifty milliseconds
 * holds, on any machine, and no amount of RAM changes that. The bot count was therefore capped
 * by whatever the slowest machine could manage, and a fast one wasted its headroom.</p>
 *
 * <p><b>What this does instead.</b> It measures the tick time the server is actually achieving
 * and widens or narrows a stride - how many ticks pass between one bot's decisions - to hold
 * that measurement under budget. Nothing is configured in bots per core; the loop finds the
 * number the machine can sustain, so a sixteen-core host runs the same arena at full detail that
 * a two-core host runs decimated, and both stay at twenty ticks a second. That is the whole
 * idea: use what the server gave you, and no more.</p>
 *
 * <p><b>Why stride, and not a cheaper brain.</b> Movement inputs and head angles persist on the
 * entity between ticks - a bot told to walk forward keeps walking. So a bot that thinks every
 * third tick still moves every tick, at full smoothness; what it loses is reaction speed, a few
 * hundredths of a second of it. Distributed by phase offset, the cost lands on a different third
 * of the crowd each tick rather than on all of it at once.</p>
 *
 * <p><b>Where the detail goes.</b> Reaction speed only matters where somebody is looking, so
 * bots near a real player keep thinking every tick however loaded the server is, and the back of
 * a two-hundred-bot arena is what gets thinned. The footage stays sharp where the camera is
 * pointed, which is the only place it can be judged.</p>
 */
public final class LoadGovernor {

    private LoadGovernor() {}

    // ===================== the budget =====================

    /**
     * Tick time we aim to stay under, in milliseconds.
     *
     * <p>Forty, not fifty. Fifty is the point at which the server is already late, and a
     * controller aiming at the edge of the cliff spends half its time over it. Ten milliseconds
     * of headroom absorbs the things that arrive without warning - a chunk save, a player
     * joining, a hopper chain - without the bots having to react to them at all.</p>
     */
    private static final float TARGET_TICK_MS = 40.0f;

    /**
     * Below this we start giving detail back.
     *
     * <p>The gap between the two thresholds is the point of them. A single threshold makes the
     * controller oscillate: it widens the stride, the tick time drops below the line, it narrows
     * again, and the bots visibly pulse between sharp and sluggish once a second. Eight
     * milliseconds of deadband is wider than the noise in the measurement.</p>
     */
    private static final float RELAX_TICK_MS = 32.0f;

    /** How often the stride is allowed to change, in ticks. */
    private static final int ADJUST_INTERVAL = 20;

    /**
     * The widest stride we will ever impose.
     *
     * <p>Six ticks is three tenths of a second between decisions. Beyond that a bot stops
     * reacting to being hit inside the window where a hit still matters, and it reads as broken
     * rather than as distant. If six is not enough to hold the budget then the arena is beyond
     * the machine, and thinning further would trade the look of every bot for a tick rate it
     * still will not reach.</p>
     */
    private static final int MAX_STRIDE = 6;

    private static int stride = 1;
    /**
     * Never Long.MIN_VALUE, however much it looks like the right sentinel for "not yet".
     *
     * <p>Every one of these is read as {@code serverTick - last >= interval}, and that subtraction
     * overflows against MIN_VALUE and comes out negative - so the answer is permanently "not due",
     * and the thing it guards never runs once. Zero is a real tick that has already happened, so
     * the first check is simply due, which is what was meant.</p>
     */
    private static long lastAdjust = 0;
    private static float smoothed = 0f;

    // ===================== machine capability =====================

    /**
     * Bots we will run at full detail before the measured loop has said anything.
     *
     * <p>Purely a starting position. The controller settles within a second either way, but for
     * that first second a small host would otherwise take one full-detail sweep of the entire
     * crowd - and that sweep is the freeze people notice, because it lands exactly when they
     * have just spawned two hundred bots and are watching. Sized off the core count, since that
     * is what bounds a single tick thread's throughput; memory is irrelevant here and is
     * deliberately not consulted.</p>
     */
    private static final int FULL_DETAIL_BOTS = Math.max(24,
        Runtime.getRuntime().availableProcessors() * 16);

    // ===================== per-bot assignment =====================

    /** How near a real player a bot has to be to keep thinking every tick, in blocks. */
    private static final double FOREGROUND_RADIUS = 48.0;
    private static final double FOREGROUND_RADIUS_SQR = FOREGROUND_RADIUS * FOREGROUND_RADIUS;

    /** Assignments are recomputed on this period rather than every tick. */
    private static final int BAND_INTERVAL = 20;

    private static final class Band {
        int stride = 1;
        long evaluated = 0;
    }

    private static final Map<String, Band> BANDS = new HashMap<>();

    private static int botCount = 0;
    private static int thinkingThisTick = 0;

    /** Reads the clock and re-tunes the stride. Called once a tick, before any bot thinks. */
    public static void sample(MinecraftServer server, long serverTick) {
        thinkingThisTick = 0;
        smoothed = server.getCurrentSmoothedTickTime();

        if (serverTick - lastAdjust < ADJUST_INTERVAL) return;
        lastAdjust = serverTick;

        if (smoothed > TARGET_TICK_MS) {
            // One step at a time. Jumping straight to the widest stride the moment a tick runs
            // long turns one slow tick - a chunk save, somebody joining - into a visible half
            // second of sluggish bots.
            stride = Math.min(MAX_STRIDE, stride + 1);
        } else if (smoothed < RELAX_TICK_MS && stride > 1) {
            stride--;
        }
    }

    /** Called by the ticker as it counts bots, so the assignment knows the crowd size. */
    public static void noteBotCount(int count) {
        botCount = count;
    }

    /**
     * Whether this bot gets to make a decision this tick.
     *
     * <p>The phase offset does the sharing out. Every bot carries a fixed number between zero
     * and nineteen, so at a stride of three one third of the crowd thinks on each tick and no
     * single tick carries the whole cost.</p>
     */
    public static boolean shouldThink(ServerPlayer bot, BotProfile profile, long serverTick) {
        int assigned = strideFor(bot, profile, serverTick);
        if (assigned <= 1 || (serverTick + profile.phase) % assigned == 0) {
            thinkingThisTick++;
            return true;
        }
        return false;
    }

    private static int strideFor(ServerPlayer bot, BotProfile profile, long serverTick) {
        // Nothing to share out: everybody thinks, every tick, and none of this costs anything.
        if (stride <= 1 && botCount <= FULL_DETAIL_BOTS) return 1;

        Band band = BANDS.computeIfAbsent(Names.key(bot.getName().getString()), k -> new Band());
        if (serverTick - band.evaluated < BAND_INTERVAL) return band.stride;
        band.evaluated = serverTick;

        // In front of somebody: full detail regardless of load. This is the footage.
        band.stride = nearRealPlayer(bot) ? 1 : stride;
        return band.stride;
    }

    /**
     * Whether a real player is close enough to see this bot behave.
     *
     * <p>Reads the shared index rather than querying the world, and the real-player list is a
     * handful of entries next to a crowd of bots, so this is a few distance comparisons - not
     * the entity scan it looks like.</p>
     */
    private static boolean nearRealPlayer(ServerPlayer bot) {
        for (ServerPlayer human : BotIndex.humans()) {
            if (human.level() != bot.level()) continue;
            if (human.distanceToSqr(bot) <= FOREGROUND_RADIUS_SQR) return true;
        }
        return false;
    }

    public static void forget(String botName) {
        BANDS.remove(Names.key(botName));
    }

    public static void clear() {
        BANDS.clear();
        stride = 1;
        lastAdjust = 0;
    }

    // ===================== diagnostics =====================

    public static int stride() {
        return stride;
    }

    public static float tickMs() {
        return smoothed;
    }

    public static int thinking() {
        return thinkingThisTick;
    }

    public static int bots() {
        return botCount;
    }

    public static int fullDetailBudget() {
        return FULL_DETAIL_BOTS;
    }

    /** One line for {@code /bots diagnostics}. */
    public static String describe() {
        if (stride <= 1) {
            return String.format(java.util.Locale.US,
                "%d bots, all thinking every tick, %.1fms per tick", botCount, smoothed);
        }
        return String.format(java.util.Locale.US,
            "%d bots at stride %d (%d thought last tick), %.1fms per tick",
            botCount, stride, thinkingThisTick, smoothed);
    }
}
