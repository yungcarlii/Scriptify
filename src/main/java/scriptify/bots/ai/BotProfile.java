package scriptify.bots.ai;

import scriptify.bots.bot.BotSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Per-bot runtime state: a private random source and a tick offset. Nothing else.
 *
 * <p>Every bot fights to the same rules, read live from {@link BotSettings}, exactly like the
 * stock mod - there are no personalities, no skill bands and no per-bot tuning. Change a
 * setting and every bot changes with it.</p>
 *
 * <p>What each bot <em>does</em> keep is its own {@link Random} and a fixed phase offset. That
 * is not personality, it is anti-synchronisation: without it a squad rolls identical numbers on
 * identical ticks and fires in perfect unison, which is the single most obvious tell that you
 * are not fighting players. Same behaviour, independent dice.</p>
 */
public class BotProfile {

    private static final Map<String, BotProfile> PROFILES = new HashMap<>();

    public final String botName;
    public final Random random;
    /** Fixed 0..19 tick offset, so scheduled work never lands on the same tick for all bots. */
    public final int phase;

    /**
     * A small minority of players main an axe. Rolled once, for the life of the bot.
     *
     * <p>This is the one place a bot differs in *what* it does rather than *when*. Weapon
     * choice used to be a damage comparison, and because a matched-tier axe always out-damages
     * the sword (diamond: 9 against 7) every bot in every fight held an axe. A sword is the
     * normal PvP weapon; the axe is a trait, not a calculation.</p>
     */
    public final boolean mainsAxe;

    /**
     * The distance this bot swings from, in blocks. Rolled once, for the life of the bot.
     *
     * <p>Everyone hitting at exactly the same range is one of the things that gives a group of
     * bots away - real players have different ping, different habits, and different ideas about
     * how close is close enough. Giving each bot its own number turns a line of identical
     * engagement distances into a spread.</p>
     */
    public final double reach;

    private BotProfile(String botName, long seed) {
        this.botName = botName;
        this.random = new Random(seed);
        this.phase = random.nextInt(20);
        this.mainsAxe = random.nextDouble() < AXE_MAIN_CHANCE;
        this.reach = REACH_MIN + random.nextDouble() * (REACH_MAX - REACH_MIN);
    }

    /**
     * How good this bot is, 0 to 1, fixed for its name. Steadier aim, quicker heals and fewer
     * slips at the top; the bottom of the range is the player who forgets to eat until it is too late.
     */
    public double skill() {
        return Math.floorMod(botName.hashCode() * 31 + 7, 1000) / 1000.0;
    }

    /** The window bot reach is rolled from. */
    private static final double REACH_MIN = 1.5;
    private static final double REACH_MAX = 3.0;

    /** Share of bots that main an axe instead of a sword. */
    private static final double AXE_MAIN_CHANCE = 0.07;

    public static BotProfile of(String botName) {
        return PROFILES.computeIfAbsent(scriptify.bots.utils.Names.key(botName),
            key -> new BotProfile(botName,
                (key.hashCode() * 0x9E3779B97F4A7C15L) ^ System.nanoTime()));
    }

    public static void remove(String botName) {
        PROFILES.remove(scriptify.bots.utils.Names.key(botName));
    }

    public static void clear() {
        PROFILES.clear();
    }

    public Random rng() {
        return random;
    }

    /** True on the tick of the given period that belongs to this bot's phase slot. */
    public boolean onPhase(long serverTick, int period) {
        if (period <= 1) return true;
        return (serverTick + phase) % period == 0;
    }

    // ===================== shared tuning =====================
    // Read from settings on every call, so edits in the GUI or by command take effect at once.

    /** Degrees per tick the head may turn. */
    public double aimSpeed() {
        return BotSettings.get().getAimSpeed();
    }

    /** Delay before acting on something new, jittered so bots do not react in lockstep. */
    public int reactionTicks() {
        int mean = BotSettings.get().getReactionTicks();
        return Rng.gaussianTicks(random, mean, Math.max(1.0, mean * 0.4), 1, mean * 3);
    }

    /** Distance the bot tries to hold in melee. */
    /**
     * How far back the bot likes to sit.
     *
     * <p>Tied to its own reach rather than the global melee range. A bot that only swings from
     * a block and a half away has to press in to that distance; left standing at the shared
     * standoff it would circle its target forever without ever throwing a hit.</p>
     */
    public double preferredGap() {
        return reach * 0.85;
    }

    public double strafeIntensity() {
        return BotSettings.get().getStrafeAmount();
    }

    public double shieldBias() {
        return BotSettings.get().isAutoShieldEnabled() ? 0.75 : 0.0;
    }

    public double wtapRate() {
        return BotSettings.get().isWtapEnabled() ? 0.7 : 0.0;
    }

    /** How strictly the attack cooldown is respected before swinging. */
    public double cooldownDiscipline() {
        return 0.85;
    }

    public double critRate() {
        return BotSettings.get().isCriticalsEnabled()
            ? BotSettings.get().getCritChance() / 100.0
            : 0.0;
    }

    public double disengageHealth() {
        return BotSettings.get().getRetreatHealthPercent();
    }

    public double webBias() {
        return BotSettings.get().getWebChance() / 100.0;
    }

    /**
     * A plain probability roll on this bot's own dice.
     *
     * <p>The chance itself is the same for every bot; only the roll is private. That is what
     * lets a group share one behaviour without acting as one body.</p>
     */
    public boolean tech(double chance) {
        return Rng.chance(random, chance);
    }
}
