package scriptify.bots.ai;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import scriptify.bots.bot.BotActions;
import scriptify.bots.bot.BotSettings;
import scriptify.bots.utils.Inv;

import java.util.HashMap;
import java.util.Map;

/**
 * Cobweb use, both offensive and defensive.
 *
 * <p><b>Why the old version missed.</b> It pointed the head at the target's feet coordinate
 * and spammed "use" three times over six ticks. Pointing at a coordinate is not the same as
 * pointing at a placeable face: if the ray landed on the target's hitbox, on the wrong
 * block, or beyond reach, nothing was placed and the cooldown was burnt anyway. It also had
 * a flat 20 tick cooldown and only fired when the target was moving faster than 0.08, so it
 * was both rare and unreliable.</p>
 *
 * <p><b>Now:</b> the placement is resolved properly through {@link BlockPlacer} - it picks
 * the cell the target is about to occupy (leading their velocity), finds a supporting face,
 * verifies the ray reaches it, aims precisely, and only then uses the item. Failures are
 * detected and retried against a different cell instead of eating the cooldown.</p>
 *
 * <p><b>Bubbling.</b> When a bot is about to die it can wrap itself in web: webs stop
 * knockback, slow anyone reaching in, and buy the ~1.5 seconds needed to eat a gapple or
 * pop a potion. The bot places at its own feet and on the side its attackers are on, heals
 * inside the cocoon, then leaves by breaking the web (or with water/pearl if it has one).</p>
 */
public final class CobwebTactics {

    private CobwebTactics() {}

    private static final Map<String, WebState> STATES = new HashMap<>();

    public static class WebState {
        public int cooldown;
        public int placeTicks;
        public BlockPos pendingCell;
        public int attempts;
        /** Webs placed against the current opponent, to cap it per engagement. */
        public int placedThisFight;
        public int lastTargetId = -1;

        public boolean bubbling;
        public int bubbleTicks;
        public int bubbleCooldown;
        /** Ticks to hold the aim before clicking again, so placements are not spammed. */
        public int bubblePlaceDelay;

        /** Which web of the cocoon is being placed: {@link #STEP_FEET} and on. */
        public int bubbleStep;
        /** Ticks spent on the current web, so one awkward cell cannot stall the whole cocoon. */
        public int stepTicks;
        /** How many webs this cocoon is going to be: two, or three with one on the attacker's side. */
        public int bubbleGoal;
        /** Where the bot is standing, fixed once the first web holds it there. */
        public BlockPos bubbleFeet;
        /** Toward the attacker, decided when the cocoon starts. */
        public Direction bubbleSide;
        /** Ticks since the cocoon was started, counted even on ticks nothing else runs. */
        public int bubbleAge;

        /** Built, and healing inside it. */
        public boolean sheltering;
        public int shelterTicks;
        public int shelterLimit;

        /**
         * Whether this stretch of low health has already had its decision.
         *
         * <p>Webbing yourself is a call a player makes once when things go wrong, not a dice roll
         * they keep making every tick until it comes up. Reset when health recovers.</p>
         */
        public boolean bubbleConsidered;
    }

    // ----- the cocoon, in the order it goes up --------------------------------------------
    /** The cell the bot stands in: this is the web that stops knockback. */
    private static final int STEP_FEET = 0;
    /** Beside the bot, toward the attacker: they have to come through it to reach in. */
    private static final int STEP_SIDE = 1;
    /**
     * Head height, placed by clicking the top of the web at the feet. Always last: once the head is
     * inside a web, every click lands on that web, so nothing else can be placed from in there.
     */
    private static final int STEP_HEAD = 2;
    private static final int STEP_DONE = 3;

    /** Webs a bot needs in its bag before a cocoon is worth starting. */
    private static final int MIN_BUBBLE_WEBS = 2;

    /** Share of cocoons that get the third web, on the side the attacker is on. */
    private static final double THIRD_WEB_CHANCE = 0.5;

    /** Health below which webbing yourself becomes an option at all. */
    private static final double BUBBLE_HEALTH = 0.28;

    /** The attacker has to be this close - on top of you, not across the arena. */
    private static final double BUBBLE_THREAT_RANGE = 4.5;

    /**
     * How often a bot that meets every condition actually does it.
     *
     * <p>Plenty of players in exactly that spot just keep swinging or run. A bot that webs itself
     * every single time it gets low is doing something no player does.</p>
     */
    private static final double BUBBLE_WILLINGNESS = 0.55;

    /** A web gets this long to go down before the cocoon moves on to the next one. */
    private static final int STEP_PATIENCE = 14;

    /** The whole build, after which whatever is up is what the bot has. */
    private static final int BUILD_LIMIT = 50;

    /** Healthy enough to come out, once any heal in progress has finished. */
    private static final double SHELTER_HEALED = 0.6;

    /** Nobody carpets the floor: at most this many webs against one opponent. */
    private static final int MAX_WEBS_PER_FIGHT = 2;

    /** True when a cobweb already sits within {@code radius} blocks of the target. */
    private static boolean webNear(Level world, Entity target, int radius) {
        BlockPos centre = BlockPos.containing(target.getX(), target.getY() + 0.1, target.getZ());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (world.getBlockState(centre.offset(dx, dy, dz)).is(Blocks.COBWEB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static WebState state(String botName) {
        return STATES.computeIfAbsent(scriptify.bots.utils.Names.key(botName), k -> new WebState());
    }

    public static void remove(String botName) {
        STATES.remove(scriptify.bots.utils.Names.key(botName));
    }

    public static void clear() {
        STATES.clear();
    }

    /**
     * Counts down, and runs the parts of the cocoon that have to happen whether or not the combat
     * brain gets as far as asking - it returns early for a lost target or a heal in progress, and a
     * cocoon left waiting on that would hold the bot in its webs indefinitely.
     */
    public static void tickTimers(ServerPlayer bot) {
        WebState st = state(bot.getName().getString());
        if (st.cooldown > 0) st.cooldown--;
        if (st.bubbleCooldown > 0) st.bubbleCooldown--;

        if (st.bubbling && ++st.bubbleAge > BUILD_LIMIT + 20) {
            finishBubble(bot, st);
        }
        if (st.sheltering) {
            tickShelter(bot, st);
        }

        float health = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());
        if (st.bubbleConsidered && health > 0.5f) {
            st.bubbleConsidered = false;
        }
    }

    public static boolean isBusy(String botName) {
        WebState st = state(botName);
        return st.pendingCell != null || st.bubbling || st.sheltering;
    }

    /** Whether the bot is sitting in its own cocoon, healing. */
    public static boolean isSheltering(String botName) {
        WebState st = STATES.get(scriptify.bots.utils.Names.key(botName));
        return st != null && st.sheltering;
    }

    // ===================== offensive webbing =====================

    /**
     * Decides whether to start a web placement this tick. Frequency is driven by the bot's
     * style (a TRAPPER webs constantly, a RUSHER almost never) rather than a fixed timer.
     */
    /**
     * Whether to web, right now.
     *
     * <p>Webbing used to be rolled every tick against a style weight, which at twenty ticks a
     * second meant a bot would carpet the ground under you - the single least believable thing
     * in the mod. Real players spend a web when it buys something specific: stopping someone
     * running, holding a target still for a combo, or breaking away when they are losing. Not
     * as a default action.</p>
     *
     * <p>So this now demands an actual reason, checks at most twice a second, refuses to stack
     * webs on top of each other, caps how many go down per opponent, and sits behind a cooldown
     * measured in tens of seconds.</p>
     */
    public static boolean shouldWeb(ServerPlayer bot, Entity target, BotProfile profile,
                                    BotSettings settings, double distance) {
        if (!settings.isCobwebEnabled()) return false;

        WebState st = state(bot.getName().getString());
        if (st.cooldown > 0 || st.pendingCell != null || st.bubbling) return false;
        if (distance > 5.5 || distance < 2.0) return false;
        if (!Inv.has(bot.getInventory(), Items.COBWEB)) return false;

        // Fresh opponent, fresh allowance.
        if (target.getId() != st.lastTargetId) {
            st.lastTargetId = target.getId();
            st.placedThisFight = 0;
        }
        if (st.placedThisFight >= MAX_WEBS_PER_FIGHT) return false;

        // Never stack webs. If the target is already in or beside one, another achieves
        // nothing except looking like spam.
        if (webNear(bot.level(), target, 2)) return false;

        // There has to be a point to it.
        boolean targetFleeing = false;
        boolean targetNearlyDead = false;
        if (target instanceof LivingEntity living) {
            Vec3 velocity = living.getDeltaMovement();
            double speed = Math.hypot(velocity.x, velocity.z);
            // Moving quickly, and moving away from us rather than at us.
            double awayX = living.getX() - bot.getX();
            double awayZ = living.getZ() - bot.getZ();
            boolean movingAway = velocity.x * awayX + velocity.z * awayZ > 0;
            targetFleeing = speed > 0.18 && movingAway;
            targetNearlyDead = living.getHealth() / Math.max(1.0f, living.getMaxHealth()) < 0.3f;
        }
        boolean losing = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth()) < 0.4f;

        if (!targetFleeing && !targetNearlyDead && !losing) return false;

        // Even with a reason, it is a judgement call rather than a reflex.
        return profile.tech(profile.webBias());
    }

    /** Starts a placement attempt; the actual placing happens over the next few ticks. */
    public static void begin(ServerPlayer bot, Entity target, BotProfile profile) {
        WebState st = state(bot.getName().getString());
        st.pendingCell = chooseCell(bot, target, profile);
        st.placeTicks = 0;
        st.attempts = 0;
        if (st.pendingCell == null) {
            st.cooldown = 20;
        }
    }

    /**
     * Continues an in-progress placement. Returns true while the bot is busy with it.
     */
    public static boolean tickPlacement(ServerPlayer bot, Entity target, BotProfile profile,
                                        MinecraftServer server, BotSettings settings) {
        WebState st = state(bot.getName().getString());
        if (st.pendingCell == null) return false;

        Level world = bot.level();
        st.placeTicks++;

        // Someone already filled it, or we gave up.
        if (world.getBlockState(st.pendingCell).is(Blocks.COBWEB) || st.placeTicks > 14) {
            finish(st, profile, settings);
            return false;
        }

        int webSlot = Inv.findItem(bot.getInventory(), Items.COBWEB);
        if (webSlot < 0) {
            finish(st, profile, settings);
            return false;
        }

        BlockPlacer.Aim aim = BlockPlacer.solve(world, bot, st.pendingCell);
        if (aim == null) {
            // Retry against a different cell rather than burning the whole cooldown.
            st.attempts++;
            if (st.attempts >= 3) {
                finish(st, profile, settings);
                return false;
            }
            st.pendingCell = chooseCell(bot, target, profile);
            return st.pendingCell != null;
        }

        Inv.equip(bot.getInventory(), webSlot, 4);
        AimController.lookAtPrecise(bot, aim.lookPoint(), profile);

        // Only click once the click would actually put the web in that cell - not merely once the
        // head is near the angle. A click whose ray catches the opponent, or clips a different face
        // on the way, does nothing, and this is where that used to burn the attempt.
        if (st.pendingCell.equals(ClickSim.placementCell(bot))) {
            BotActions.place(bot, server);
            // Give the world a tick to register before deciding it failed.
            if (st.placeTicks >= 3) {
                finish(st, profile, settings);
            }
        }
        return true;
    }

    private static void finish(WebState st, BotProfile profile, BotSettings settings) {
        st.pendingCell = null;
        st.placeTicks = 0;
        st.attempts = 0;
        st.placedThisFight++;
        int base = settings.getCobwebCooldownTicks();
        st.cooldown = Rng.gaussianTicks(profile.rng(), base, base * 0.3, base / 2, base * 2);
    }

    /**
     * Picks which cell to fill: the one the target is moving into, at foot height, falling
     * back to their current feet and then to their head.
     */
    private static BlockPos chooseCell(ServerPlayer bot, Entity target, BotProfile profile) {
        Level world = bot.level();
        Vec3 vel = target.getDeltaMovement();
        double lead = Rng.range(profile.rng(), 2.0, 5.0);

        BlockPos[] candidates = {
            BlockPos.containing(target.getX() + vel.x * lead, target.getY() + 0.1,
                target.getZ() + vel.z * lead),
            BlockPos.containing(target.getX(), target.getY() + 0.1, target.getZ()),
            BlockPos.containing(target.getX(), target.getY() + 1.2, target.getZ()),
        };

        BlockPos botFeet = BlockPos.containing(bot.getX(), bot.getY() + 0.1, bot.getZ());
        for (BlockPos candidate : candidates) {
            if (candidate.equals(botFeet)) continue;                  // do not web yourself
            if (candidate.equals(botFeet.above())) continue;
            if (!BlockPlacer.isReplaceable(world, candidate)) continue;
            if (BlockPlacer.solve(world, bot, candidate) == null) continue;
            return candidate;
        }
        return null;
    }

    // ===================== defensive bubbling =====================

    /**
     * Whether to web yourself in, right now.
     *
     * <p>This used to fire on low health alone - any heal in the bag counted, a steak included, and
     * the attacker only had to be somewhere within eight blocks - and it was asked every tick, so a
     * bot that got low did it every time and again ten seconds later. Real players web themselves
     * rarely and for a reason: somebody is on top of them <em>and</em> hitting them, they are about
     * to die, and they have something that will actually save them once they are sealed in - a golden
     * apple, a healing potion, a totem. Even then it is a call, made once, not a reflex.</p>
     *
     * @param underPressure whether the bot has taken a hit in the last couple of seconds
     */
    public static boolean shouldBubble(ServerPlayer bot, Entity target, BotProfile profile,
                                       BotSettings settings, double distance,
                                       boolean underPressure) {
        if (!settings.isCobwebBubbleEnabled()) return false;
        WebState st = state(bot.getName().getString());
        if (st.bubbling || st.sheltering || st.bubbleCooldown > 0 || st.bubbleConsidered) {
            return false;
        }
        if (target == null || !target.isAlive() || distance > BUBBLE_THREAT_RANGE) return false;
        if (!underPressure) return false;
        // Somewhere a web will hold: standing, on dry land, not already stuck in one.
        if (!bot.onGround() || bot.isInWater() || inWeb(bot)) return false;
        if (Inv.count(bot.getInventory(), Items.COBWEB) < MIN_BUBBLE_WEBS) return false;

        float healthFraction = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());
        if (healthFraction > BUBBLE_HEALTH) return false;
        if (!hasRealHeal(bot)) return false;

        // One decision per stretch of low health. A "no" is kept for a while too, rather than the
        // question being asked again on the very next tick until it comes up yes.
        st.bubbleConsidered = true;
        if (!profile.tech(BUBBLE_WILLINGNESS)) {
            st.bubbleCooldown = Rng.range(profile.rng(), 200, 400);
            return false;
        }
        return true;
    }

    /**
     * Something worth sealing yourself in to use. Ordinary food is not: a steak takes as long to eat
     * as a golden apple and heals a fraction of it, and nobody webs up to eat one.
     */
    private static boolean hasRealHeal(ServerPlayer bot) {
        var inv = bot.getInventory();
        return HealPlanner.findGapple(inv) >= 0
            || HealPlanner.findHealPotion(inv, true) >= 0
            || HealPlanner.findHealPotion(inv, false) >= 0
            || Inv.has(inv, Items.TOTEM_OF_UNDYING)
            || inv.getItem(Inv.OFFHAND).is(Items.TOTEM_OF_UNDYING);
    }

    public static void beginBubble(ServerPlayer bot, Entity threat, BotProfile profile) {
        WebState st = state(bot.getName().getString());
        st.bubbling = true;
        st.bubbleTicks = 0;
        st.bubbleAge = 0;
        st.stepTicks = 0;
        st.bubblePlaceDelay = 0;
        st.bubbleStep = STEP_FEET;
        st.bubbleFeet = null;
        st.bubbleSide = sideToward(bot, threat);
        st.bubbleGoal = st.bubbleSide != null && profile.tech(THIRD_WEB_CHANCE) ? 3 : 2;
    }

    /**
     * Builds the cocoon, one web at a time: feet, then the attacker's side, then the head. Returns
     * true while busy.
     *
     * <p><b>Why it used to stop at one.</b> The web at head height has to be placed against the
     * top of the web at the feet, and the placement code only accepted blocks with a collision box to
     * place against - a web has none. So the head cell never had a solution, and the loop, which
     * always went back to the first unfinished cell, sat on it for four seconds and gave up. Webs are
     * now valid supports, and a cell that cannot be done in a moment is skipped for the next.</p>
     *
     * <p>Every click is checked against the ray the click will actually cast (see {@link ClickSim}),
     * so a web is only thrown when it will land where it is wanted.</p>
     */
    public static boolean tickBubble(ServerPlayer bot, Entity threat, BotProfile profile,
                                     MinecraftServer server) {
        WebState st = state(bot.getName().getString());
        if (!st.bubbling) return false;

        st.bubbleTicks++;
        st.stepTicks++;
        if (st.bubblePlaceDelay > 0) st.bubblePlaceDelay--;
        Level world = bot.level();
        holdStill(bot);

        if (st.bubbleTicks > BUILD_LIMIT || !Inv.has(bot.getInventory(), Items.COBWEB)) {
            finishBubble(bot, st);
            return st.sheltering;
        }

        // Until the first web is down the bot can still be knocked about, so its cell follows it.
        // After that it is stuck where it is, and the rest of the cocoon is built around that spot.
        if (st.bubbleStep == STEP_FEET) {
            st.bubbleFeet = BlockPos.containing(bot.getX(), bot.getY() + 0.1, bot.getZ());
        }

        BlockPos cell = cellFor(st);
        while (cell != null && (isWeb(world, cell) || !BlockPlacer.isReplaceable(world, cell)
            || st.stepTicks > STEP_PATIENCE)) {
            if (st.bubbleStep == STEP_FEET && !isWeb(world, cell)) {
                // No web at the feet means no cocoon: nothing else is worth placing without it.
                finishBubble(bot, st);
                return false;
            }
            advance(st);
            cell = cellFor(st);
        }
        if (cell == null) {
            finishBubble(bot, st);
            return true;
        }

        BlockPlacer.Aim aim = BlockPlacer.solveVisible(world, bot, cell);
        if (aim == null) {
            // Not from here, not yet. The step's patience runs out and the next cell is tried.
            return true;
        }

        int webSlot = Inv.findItem(bot.getInventory(), Items.COBWEB);
        Inv.equip(bot.getInventory(), webSlot, 4);
        AimController.lookAtPrecise(bot, aim.lookPoint(), profile);

        if (st.bubblePlaceDelay <= 0 && cell.equals(ClickSim.placementCell(bot))) {
            BotActions.place(bot, server);
            // A few ticks between clicks: long enough for the web to land and the head to move on,
            // short enough that the whole cocoon goes up in well under a second.
            st.bubblePlaceDelay = Rng.range(profile.rng(), 2, 4);
        }
        return true;
    }

    private static BlockPos cellFor(WebState st) {
        if (st.bubbleFeet == null) return null;
        return switch (st.bubbleStep) {
            case STEP_FEET -> st.bubbleFeet;
            case STEP_SIDE -> st.bubbleSide == null ? null : st.bubbleFeet.relative(st.bubbleSide);
            case STEP_HEAD -> st.bubbleFeet.above();
            default -> null;
        };
    }

    private static void advance(WebState st) {
        st.bubbleStep++;
        if (st.bubbleStep == STEP_SIDE && st.bubbleGoal < 3) st.bubbleStep++;
        st.stepTicks = 0;
        st.bubblePlaceDelay = 0;
    }

    /** Ends the build, and settles in to heal if there is a cocoon to heal in. */
    private static void finishBubble(ServerPlayer bot, WebState st) {
        st.bubbling = false;
        boolean built = st.bubbleFeet != null && isWeb(bot.level(), st.bubbleFeet);
        BotProfile profile = BotProfile.of(bot.getName().getString());
        if (built) {
            st.sheltering = true;
            st.shelterTicks = 0;
            st.shelterLimit = Rng.range(profile.rng(), 70, 140);
        }
        // A player who has just webbed up does not do it again for a good while.
        st.bubbleCooldown = Rng.range(profile.rng(), 900, 1600);
    }

    /**
     * Sitting in the cocoon.
     *
     * <p>The point of it is the heal, so the bot stays in until the heal has done its work - or the
     * webs are gone, or there is nothing left to heal with, or it has been long enough that staying
     * is just waiting to be dug out. Then it leaves the way anybody leaves a web: water, or a sword.
     * The old cocoon skipped this entirely: the moment the last web went down the escape code saw a
     * webbed bot and cut it straight back out, before it had eaten anything.</p>
     */
    private static void tickShelter(ServerPlayer bot, WebState st) {
        st.shelterTicks++;
        var utils = scriptify.bots.bot.BotUtils.getState(bot.getName().getString());
        boolean healing = utils.isEating || utils.isThrowingPotion;
        float health = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());

        boolean websGone = !inWeb(bot);
        boolean healed = health >= SHELTER_HEALED && !healing;
        boolean nothingLeft = !healing && !hasRealHeal(bot) && st.shelterTicks > 30;
        if (websGone || healed || nothingLeft || st.shelterTicks > st.shelterLimit) {
            st.sheltering = false;
        }
    }

    /**
     * Kills horizontal drift while building. The first web stops knockback outright; this is for the
     * moment before it lands.
     */
    private static void holdStill(ServerPlayer bot) {
        bot.setSprinting(false);
        Vec3 v = bot.getDeltaMovement();
        bot.setDeltaMovement(v.x * 0.3, v.y, v.z * 0.3);
    }

    private static boolean isWeb(Level world, BlockPos pos) {
        return world.getBlockState(pos).is(Blocks.COBWEB);
    }

    /** The horizontal direction of the threat, or null with nobody to face. */
    private static Direction sideToward(ServerPlayer bot, Entity threat) {
        if (threat == null) return null;
        double dx = threat.getX() - bot.getX();
        double dz = threat.getZ() - bot.getZ();
        if (Math.abs(dx) < 1.0E-3 && Math.abs(dz) < 1.0E-3) return null;
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** True when the bot is currently stuck inside a web. */
    public static boolean inWeb(ServerPlayer bot) {
        return !websTouching(bot).isEmpty();
    }

    /**
     * Every cobweb the bot's hitbox is in - which is exactly the set the game slows it for.
     *
     * <p>Taken from the hitbox rather than from a few sample points down the middle of the body.
     * The samples missed a web that only caught the bottom of the box, which is what happens to a
     * bot webbed mid-jump: it was judged free while still stuck, collected its water too early, and
     * had to pour again.</p>
     */
    public static java.util.List<BlockPos> websTouching(ServerPlayer bot) {
        Level world = bot.level();
        var box = bot.getBoundingBox().deflate(1.0E-3);
        java.util.List<BlockPos> webs = new java.util.ArrayList<>(4);
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (world.getBlockState(pos).is(Blocks.COBWEB)) webs.add(pos.immutable());
        }
        return webs;
    }

    /**
     * Where to look in order to hit a cobweb, or null when none can be hit from here.
     *
     * <p>Aiming at the centre of a block is not aiming at the block. From eyes above it, the ray
     * to the centre of the cell at your feet leaves the cell through its side and lands in the
     * floor - which is what had bots standing in a web solemnly mining the dirt underneath
     * themselves. The point wanted is on the <em>near face</em>, and it is worth confirming with
     * the same ray the click will use rather than assuming.</p>
     *
     * <p>Candidates are tried head first, because that is the web a person reaches for.</p>
     */
    public static Vec3 escapeAim(ServerPlayer bot) {
        Level world = bot.level();
        Vec3 eye = bot.getEyePosition();

        BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.1, bot.getZ());
        BlockPos eyeCell = BlockPos.containing(eye.x, eye.y, eye.z);

        // The cell the eyes are inside is deliberately not a candidate. There is nothing to aim at
        // in it: a point inside it is a few centimetres from the eye, which no head turn can be
        // "pointed at", and it needs no aiming anyway - a click from inside a web lands on that
        // web whichever way the head faces. What wants aiming is the web in front, or the one
        // under your feet, which is exactly what a person reaches for; then anything else the
        // hitbox is caught in, such as a web around the ankles of a bot webbed mid-jump.
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>(java.util.List.of(
            eyeCell.north(), eyeCell.east(), eyeCell.south(), eyeCell.west(),
            feet,
            feet.above(2)));
        candidates.addAll(websTouching(bot));

        for (BlockPos cell : candidates) {
            if (cell.equals(eyeCell)) continue;
            if (!world.getBlockState(cell).is(Blocks.COBWEB)) continue;

            // Just inside the near face, so the ray stops in this cell rather than carrying on
            // through to whatever is behind it.
            Vec3 centre = Vec3.atCenterOf(cell);
            Vec3 toEye = eye.subtract(centre);
            double length = toEye.length();
            if (length < 0.1) continue;
            Vec3 point = centre.add(toEye.scale(Math.min(0.45, length * 0.5) / length));

            // Is there anything solid in the way? A clear line comes back as a miss, because a
            // cobweb has no collision box - so the only thing this ray can stop on is a real
            // obstruction, which is the question being asked.
            //
            // Asking it the obvious way instead - an outline ray, does it land on that web - is
            // wrong in exactly the case that matters. Standing in a web, the bot's eyes are
            // inside one, an outline ray stops on the block containing its start point, and so
            // every candidate came back as "that is not the web I asked about". The method
            // returned null for a bot buried in cobweb, the escape gave up on the tick it began,
            // and it restarted about once a second for as long as the bot was stuck.
            HitResult hit = world.clip(new ClipContext(
                eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
            if (hit.getType() == HitResult.Type.MISS) return point;
        }
        return null;
    }
}
