package scriptify.bots.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotSettings;
import scriptify.bots.utils.Names;

import java.util.HashMap;
import java.util.Map;

/**
 * How a bot moves in a fight, expressed as short-lived <em>intents</em> rather than a
 * position recomputed every tick.
 *
 * <p><b>The problem this solves.</b> Movement used to be a pure function of the current
 * frame: stand on a ring around the target, orbit slowly, strafe, and if hurt, turn around
 * and walk away in a straight line. Three things about that read as obviously non-human:</p>
 * <ul>
 *   <li>a target that recomputes every tick produces continuous drifting rather than
 *       decisions - real players commit to a move for half a second at a time;</li>
 *   <li>constant orbiting at a fixed radius is a behaviour no player has ever exhibited;</li>
 *   <li>backing away while facing away from an opponent is the single most alien thing a
 *       Minecraft player can do. Nobody turns their back mid-fight, and nobody reverses in a
 *       straight line. They strafe, they reset their sprint, they jockey in and out of range
 *       - and if they are actually leaving, they turn and <em>run</em>, committed.</li>
 * </ul>
 *
 * <p><b>The model.</b> Each bot holds one intent with a countdown. Intents are chosen from
 * role, health, cooldown state and the shape of the fight, then held long enough to read as
 * a decision. The centrepiece is {@link Intent#JOCKEY}: advance while the attack cooldown is
 * charged, step just outside while it recharges. That in-and-out rhythm is what 1.9+ melee
 * actually looks like, and it falls out of the mechanics rather than being animated on top.</p>
 *
 * <p><b>Nothing here creates space slowly.</b> Every movement that opens distance - a reset, a
 * block, a reserve holding position - is taken at full speed and weighted sideways rather than
 * backwards, because you cannot sprint in reverse and a player who tries is simply giving away
 * the fight. Sustained retreat exists only as {@link Intent#FLEE}, which is gated on actually
 * being in trouble and commits properly: turn, pick ground, run.</p>
 */
public final class Maneuver {

    private Maneuver() {}

    public enum Intent {
        /** Close the distance and get on top of the target. */
        PUSH,
        /** In range: step in and out with the attack cooldown, strafing. */
        JOCKEY,
        /** Brief break of contact to reset sprint knockback, still facing the target. */
        RESET,
        /** Swing wide and come in from an angle instead of straight down the middle. */
        FLANK,
        /** Dive the group's focus target and try to finish it. */
        CRIT_HUNT,
        /** Go help an ally who is being beaten on. */
        PEEL,
        /** Stand ground behind a shield and wait out the pressure. */
        SHIELD_UP,
        /** Hold at range: bow, potions, wait for a slot to open. */
        HOLD,
        /** Actually leaving the fight: turn, sprint, commit. */
        FLEE
    }

    public static final class State {
        public Intent intent = Intent.PUSH;
        public int ticksLeft;
        /** Side of the target this bot is approaching from; flipped on re-decide, not per tick. */
        public int lane = 1;
        /** Chosen approach offset in radians, held for the life of the intent. */
        public double laneAngle;
        public float healthAtDecision = 20.0f;
        public int sinceHit;
        /** Holding back because the front line is full, rather than as a reserve. */
        public boolean queued;
    }

    /** Movement instruction produced for this tick. */
    public record Order(Vec3 goal, double speed, double strafe, boolean faceTarget) {}

    private static final Map<String, State> STATES = new HashMap<>();

    public static State state(String botName) {
        return STATES.computeIfAbsent(Names.key(botName), k -> new State());
    }

    public static void remove(String botName) {
        STATES.remove(Names.key(botName));
    }

    public static void clear() {
        STATES.clear();
    }

    public static Intent intentOf(String botName) {
        return state(botName).intent;
    }

    // ===================== decision =====================

    /**
     * Advances the intent clock and re-decides when the current intent expires or the
     * situation invalidates it.
     */
    /** Bots that fit in the front line round one target. */
    private static final int FRONT_LINE = 9;

    public static Intent decide(ServerPlayer bot, Entity target, BotProfile profile,
                                BotSettings settings, double distance, boolean wantsOut,
                                boolean canReachMelee) {
        String botName = bot.getName().getString();
        State st = state(botName);

        if (st.ticksLeft > 0) st.ticksLeft--;
        st.sinceHit++;

        float health = bot.getHealth();
        boolean tookRealHit = health < st.healthAtDecision - 2.5f;
        if (tookRealHit) st.sinceHit = 0;

        boolean expired = st.ticksLeft <= 0;
        boolean invalid = switch (st.intent) {
            case FLEE -> !wantsOut;
            case CRIT_HUNT -> !canReachMelee;
            case JOCKEY, RESET -> distance > settings.getMeleeRange() + 4.0;
            case PUSH, FLANK -> distance <= settings.getMeleeRange();
            default -> false;
        };

        // A solid hit interrupts whatever we were doing - that is the moment a player reacts.
        if (expired || invalid || (tookRealHit && st.intent != Intent.FLEE && profile.tech(0.6))) {
            commit(bot, target, profile, settings, st, distance, wantsOut, canReachMelee);
        }
        return st.intent;
    }

    private static void commit(ServerPlayer bot, Entity target, BotProfile profile,
                               BotSettings settings, State st, double distance,
                               boolean wantsOut, boolean canReachMelee) {
        String botName = bot.getName().getString();
        Intent previous = st.intent;
        Intent next = choose(bot, target, profile, settings, st, distance, wantsOut, canReachMelee);

        st.intent = next;
        st.healthAtDecision = bot.getHealth();
        st.ticksLeft = durationOf(next, profile);

        // A new approach angle is picked when the intent changes, not continuously, so the
        // bot walks a line instead of spiralling.
        if (next != previous) {
            st.lane = profile.rng().nextBoolean() ? 1 : -1;
            st.laneAngle = Rng.range(profile.rng(), 0.5, 1.35) * st.lane;
        }
    }

    private static Intent choose(ServerPlayer bot, Entity target, BotProfile profile,
                                 BotSettings settings, State st, double distance,
                                 boolean wantsOut, boolean canReachMelee) {
        String botName = bot.getName().getString();
        float healthFraction = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());

        // --- leaving the fight is a decision, not a drift --------------------------------
        if (wantsOut) {
            // Only actually run when there is nothing better; otherwise cover up and hold,
            // which is what a player with a shield does instead of sprinting away.
            boolean hasShield = bot.getOffhandItem().getItem() == net.minecraft.world.item.Items.SHIELD;
            if (hasShield && healthFraction > 0.15f && profile.tech(profile.shieldBias())) {
                return Intent.SHIELD_UP;
            }
            return Intent.FLEE;
        }

        // --- hurt, but staying in it ------------------------------------------------------
        // Caution belongs to health, not to health *plus* temperament. A bot on low health
        // fights carefully: it resets more, it blocks more, and it stops diving. It does not,
        // however, wander backwards - that is what the disengage path is for.
        boolean hurt = healthFraction < 0.5f;
        if (hurt && canReachMelee) {
            boolean hasShield = bot.getOffhandItem().getItem() == net.minecraft.world.item.Items.SHIELD;
            double carefulness = (0.5f - healthFraction) * 2.0;   // 0 at half health, 1 at zero
            if (hasShield && profile.tech(0.35 + carefulness * 0.4)) {
                return Intent.SHIELD_UP;
            }
            if (profile.tech(0.3 + carefulness * 0.45)) {
                return Intent.RESET;
            }
        }

        // --- group behaviour --------------------------------------------------------------
        Engagement.Role role = Engagement.roleOf(botName);
        boolean brawl = Engagement.inBrawl(botName);

        if (brawl) {
            Entity focus = Engagement.focusFor(botName);

            // Only so many bodies fit round one man. With the front line already full, a bot
            // further out holds where it is and waits for a gap - the ring of fighters with the
            // crowd pressing behind them that a big fight actually looks like. Without it a whole
            // army walked into the same three blocks, a pile no player has ever seen.
            st.queued = false;
            if (distance > settings.getMeleeRange() + 0.5
                && SquadCoordinator.contactCount(target) >= FRONT_LINE) {
                st.queued = true;
                return Intent.HOLD;
            }

            // The collapse: if the group has called a target and this bot is on it, dive.
            if (focus == target && canReachMelee
                && (role == Engagement.Role.SPEARHEAD || role == Engagement.Role.FLANKER)) {
                boolean finishable = target instanceof LivingEntity living
                    && living.getHealth() / Math.max(1.0f, living.getMaxHealth()) < 0.55f;
                // Diving costs health you may not have. Below half, a bot lets someone else
                // lead the collapse and plays the edges instead.
                boolean healthyEnoughToDive = healthFraction > 0.5f;
                if (healthyEnoughToDive && (finishable || profile.tech(0.55))) {
                    return Intent.CRIT_HUNT;
                }
            }

            if (role == Engagement.Role.SCREEN && Engagement.struggingAlly(botName) != null) {
                return Intent.PEEL;
            }
            if (role == Engagement.Role.RESERVE) {
                // Reserves do not queue politely forever - they look for a way in, and mostly
                // find one. Holding is the exception, not the default.
                return profile.tech(0.65) ? Intent.FLANK : Intent.HOLD;
            }
            if (role == Engagement.Role.FLANKER && distance > settings.getMeleeRange()) {
                return Intent.FLANK;
            }
        }

        // --- one-on-one -------------------------------------------------------------------
        if (distance > settings.getMeleeRange() + 3.0) {
            // Mostly walk straight in; occasionally take an angle so approaches are not identical.
            return profile.tech(0.75) ? Intent.PUSH : Intent.FLANK;
        }
        if (canReachMelee) {
            // Just traded a hit and want the knockback reset, or the style likes to break off.
            if (st.sinceHit < 6 && profile.tech(profile.wtapRate() * 0.5)) {
                return Intent.RESET;
            }
            return Intent.JOCKEY;
        }
        return Intent.PUSH;
    }

    /**
     * How long an intent is held. Long enough to look like a decision, short enough to stay
     * responsive - and jittered per bot so a squad never switches in unison.
     */
    private static int durationOf(Intent intent, BotProfile profile) {
        int base = switch (intent) {
            case PUSH -> 22;
            case JOCKEY -> 26;
            case RESET -> 9;
            case FLANK -> 30;
            case CRIT_HUNT -> 34;
            case PEEL -> 40;
            case SHIELD_UP -> 24;
            case HOLD -> 30;
            case FLEE -> 55;
        };
        return Rng.gaussianTicks(profile.rng(), base, base * 0.35, 5, base * 2);
    }

    // ===================== execution =====================

    /**
     * Turns the current intent into a concrete movement order.
     *
     * @param cooldownProgress the bot's attack cooldown, 0..1 - drives the jockey rhythm
     */
    public static Order resolve(ServerPlayer bot, Entity target, BotProfile profile,
                                BotSettings settings, double distance, float cooldownProgress,
                                long serverTick) {
        String botName = bot.getName().getString();
        State st = state(botName);

        // The bot's own reach, not the shared one: positioning has to agree with the
        // distance this particular bot actually swings from.
        double reach = profile.reach;
        double speed = settings.getMoveSpeed();
        Vec3 targetPos = new Vec3(target.getX(), target.getY(), target.getZ());

        return switch (st.intent) {
            case PUSH -> new Order(targetPos, speed, profile.strafeIntensity() * 0.5, true);

            case JOCKEY -> {
                // The heart of it: step in when the swing is ready, hold just outside while it
                // recharges. The out-step is small and taken at full speed - real players make
                // a quick adjustment, they do not stroll backwards - and because the cooldown
                // is personal the bots never sway in sync.
                double pocket = Math.min(reach * 0.95, profile.preferredGap());
                boolean ready = cooldownProgress > 0.82f;
                double wanted = ready ? pocket * 0.7 : pocket * 1.05;

                Vec3 goal = pointAt(bot, target, wanted, st.laneAngle * 0.35);
                yield new Order(goal, speed, Math.max(0.5, profile.strafeIntensity()), true);
            }

            case RESET -> {
                // Break contact for a few ticks *while still facing them*. Weighted heavily
                // sideways rather than backwards: you cannot sprint in reverse, so a sideways
                // break is both faster and what players actually do. Full speed, brief.
                Vec3 goal = pointAt(bot, target, reach * 1.35, st.lane * 1.5);
                yield new Order(goal, speed, 1.0, true);
            }

            case FLANK -> {
                // Come in off the centre line. The angle was chosen once, when the intent
                // started, so this is an arc into the fight rather than an endless orbit.
                Vec3 goal = pointAt(bot, target, Math.max(reach, distance * 0.55), st.laneAngle);
                yield new Order(goal, speed, profile.strafeIntensity() * 0.7, true);
            }

            case CRIT_HUNT -> {
                // All-in: no spacing discipline, get on top of them and jump on their head.
                yield new Order(targetPos, speed * 1.05, 0.25, true);
            }

            case PEEL -> {
                ServerPlayer ally = Engagement.struggingAlly(botName);
                Vec3 goal = ally != null
                    ? new Vec3(ally.getX(), ally.getY(), ally.getZ())
                    : targetPos;
                yield new Order(goal, speed, 0.3, true);
            }

            case SHIELD_UP -> {
                // Hold ground behind the shield. It circles rather than reverses: standing
                // still is a free hit, but walking backwards while blocking is slower than
                // whoever is chasing and just feeds them the fight.
                Vec3 goal = pointAt(bot, target, Math.max(reach * 0.95, distance * 0.95),
                    st.lane * 1.2);
                yield new Order(goal, speed * 0.9, 0.8, true);
            }

            case HOLD -> {
                // Reserves wait at whatever range they already have, moving laterally. They do
                // not trudge away from the fight to reach some nominal standoff distance -
                // that was the behaviour that read as bots aimlessly backing off.
                double standoff = Math.max(distance, profile.preferredGap());
                if (st.queued) {
                    // Waiting on a full front line: every bot at its own depth behind it, so the
                    // crowd is several bodies thick rather than one solid wall at arm's length.
                    double depth = 1.0 + Math.floorMod(botName.hashCode(), 7) * 0.5;
                    standoff = Math.max(settings.getMeleeRange() + depth, Math.min(distance, standoff));
                }
                Vec3 goal = pointAt(bot, target, standoff, st.laneAngle * 0.8);
                yield new Order(goal, speed * 0.85, 0.7, true);
            }

            case FLEE -> {
                // The one case where turning around is correct - and it is committed: pick a
                // direction away from the fight and run, rather than shuffling backwards.
                Vec3 away = fleeDirection(bot, target);
                Vec3 goal = new Vec3(
                    bot.getX() + away.x * 14.0,
                    bot.getY(),
                    bot.getZ() + away.z * 14.0);
                yield new Order(goal, speed * 1.15, 0.15, false);
            }
        };
    }

    /**
     * A point at {@code radius} from the target, rotated {@code angleOffset} radians off the
     * bot-to-target line. Offsetting from the current bearing (rather than from world axes)
     * keeps approaches relative to where the bot already is, so it never teleports around the
     * target to reach its slot.
     */
    private static Vec3 pointAt(ServerPlayer bot, Entity target, double radius,
                                 double angleOffset) {
        double dx = bot.getX() - target.getX();
        double dz = bot.getZ() - target.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4) {
            dx = 1;
            dz = 0;
            length = 1;
        }
        double bearing = Math.atan2(dz, dx) + angleOffset;
        return new Vec3(
            target.getX() + Math.cos(bearing) * radius,
            target.getY(),
            target.getZ() + Math.sin(bearing) * radius);
    }

    /**
     * Where to run. Straight backwards is usually into whatever the bot was cornered against,
     * so candidate directions are scored on footing and on getting away from the fight's
     * centre of mass rather than just from one opponent.
     */
    private static Vec3 fleeDirection(ServerPlayer bot, Entity target) {
        Vec3 fromTarget = new Vec3(bot.getX() - target.getX(), 0, bot.getZ() - target.getZ())
            .normalize();

        Engagement.Battle battle = Engagement.battleOf(bot.getName().getString());
        Vec3 fromCentre = fromTarget;
        if (battle != null) {
            double dx = bot.getX() - battle.center.x;
            double dz = bot.getZ() - battle.center.z;
            if (dx * dx + dz * dz > 0.01) {
                fromCentre = new Vec3(dx, 0, dz).normalize();
            }
        }

        Vec3 preferred = fromTarget.add(fromCentre).normalize();
        if (preferred.lengthSqr() < 0.01) preferred = fromTarget;

        // Try the preferred direction, then progressively wider deviations, until one has
        // ground under it. Running off a ledge is a worse outcome than staying in the fight.
        for (double sweep : new double[]{0, 0.6, -0.6, 1.2, -1.2, 2.0, -2.0}) {
            double angle = Math.atan2(preferred.z, preferred.x) + sweep;
            Vec3 candidate = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            if (Vision.hasFooting(bot, candidate, 2.5)) {
                return candidate;
            }
        }
        return preferred;
    }
}
