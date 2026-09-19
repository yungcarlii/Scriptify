package scriptify.bots.scenario;

/**
 * Tuning for one scenario.
 *
 * <p>Every field has a sane default, so a scenario created without touching the settings menu
 * still behaves. The menu edits a <em>template</em> per scenario type; creating a scenario
 * takes a {@link #copy()} of that template and keeps it forever. That is deliberate and is why
 * this is a plain mutable class rather than a reference to shared state: editing the template
 * afterwards must not reach back and change scenarios that are already running, because a
 * scenario is something you set up once and leave alone.</p>
 */
public class ScenarioSettings {

    // ---- shared ------------------------------------------------------------------------
    /** How far from its anchor a bot in this scenario will roam. */
    public double radius = 24.0;
    /** Walking pace, 1.0 being the normal bot speed. */
    public double speed = 0.8;

    // ---- spawn -------------------------------------------------------------------------
    /** Bots kept alive at once. */
    public int population = 6;
    /** Seconds between arrivals. */
    public int spawnIntervalSeconds = 12;
    /**
     * How far a new arrival heads off from spawn, in blocks.
     *
     * <p>People who join a server go somewhere. They do not mill about the spawn platform
     * looking at the walls, and a bot that does is instantly readable as a bot.</p>
     */
    public double travelDistance = 600.0;
    /** Lifespan window before a bot meets its end, in seconds. */
    public int lifetimeMinSeconds = 180;
    public int lifetimeMaxSeconds = 420;
    /** Relative weights for how a spawn-scenario bot dies. Zero disables a cause. */
    public int deathFall = 25;
    public int deathStarve = 10;
    public int deathWither = 8;
    public int deathPlayer = 25;
    public int deathDrown = 15;
    public int deathBurn = 17;

    // ---- conversation ------------------------------------------------------------------
    /** Ticks a single "turn" of talking lasts before the group's attention moves on. */
    public int turnMinTicks = 60;
    public int turnMaxTicks = 160;
    /** Chance per second that a listener nods along. */
    public double nodChance = 0.25;
    /** Chance per second of a shift-fidget or a hop. */
    public double fidgetChance = 0.10;

    // ---- crowd -------------------------------------------------------------------------
    /** How far the head drifts off the target, in degrees, while watching. */
    public double lookJitterDegrees = 9.0;
    /** Chance per second of one of the larger reactions (spin, hard nod, hop). */
    public double reactionChance = 0.06;
    /** Chance per second of a small sneak fidget. */
    public double sneakChance = 0.08;

    // ---- prison ------------------------------------------------------------------------
    /** Largest cell the space analysis will map, in blocks. */
    public int cellScanLimit = 400;
    /** Chance per second of stopping to face a wall for a moment. */
    public double stareChance = 0.10;
    /**
     * Share of prisoners who cannot sit still - crossing the cell constantly and bunny hopping,
     * rather than pacing and staring. Rolled once per bot and kept.
     */
    public double restlessShare = 0.5;


    public ScenarioSettings copy() {
        ScenarioSettings c = new ScenarioSettings();
        c.radius = radius;
        c.speed = speed;
        c.population = population;
        c.travelDistance = travelDistance;
        c.spawnIntervalSeconds = spawnIntervalSeconds;
        c.lifetimeMinSeconds = lifetimeMinSeconds;
        c.lifetimeMaxSeconds = lifetimeMaxSeconds;
        c.deathFall = deathFall;
        c.deathStarve = deathStarve;
        c.deathWither = deathWither;
        c.deathPlayer = deathPlayer;
        c.deathDrown = deathDrown;
        c.deathBurn = deathBurn;
        c.turnMinTicks = turnMinTicks;
        c.turnMaxTicks = turnMaxTicks;
        c.nodChance = nodChance;
        c.fidgetChance = fidgetChance;
        c.lookJitterDegrees = lookJitterDegrees;
        c.reactionChance = reactionChance;
        c.sneakChance = sneakChance;
        c.cellScanLimit = cellScanLimit;
        c.stareChance = stareChance;
        c.restlessShare = restlessShare;
        return c;
    }

    /** Total of the death-cause weights, so a zeroed-out set can be detected. */
    public int deathWeightTotal() {
        return deathFall + deathStarve + deathWither + deathPlayer + deathDrown + deathBurn;
    }
}
