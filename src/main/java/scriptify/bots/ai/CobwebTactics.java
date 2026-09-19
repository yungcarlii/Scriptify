package scriptify.bots.ai;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
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
        public int bubblePlaced;
        /** Ticks to hold the aim before clicking again, so placements are not spammed. */
        public int bubblePlaceDelay;
    }

    /**
     * Webs that make a cocoon worth the name.
     *
     * <p>One web is not a cocoon. It stops the bot moving and nothing else - the attacker walks
     * around it and carries on, and the bot has spent its emergency on being easier to hit. The
     * cell the bot stands in and the cell its head is in are the minimum, because together they
     * are what stops knockback and slows a weapon reaching through.</p>
     */
    private static final int MIN_BUBBLE_WEBS = 2;

    /** And no more than this, or it stops reading as panic and starts reading as construction. */
    private static final int MAX_BUBBLE_WEBS = 4;

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

    public static void tickTimers(String botName) {
        WebState st = state(botName);
        if (st.cooldown > 0) st.cooldown--;
        if (st.bubbleCooldown > 0) st.bubbleCooldown--;
    }

    public static boolean isBusy(String botName) {
        WebState st = state(botName);
        return st.pendingCell != null || st.bubbling;
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

        // Only click once the head has actually arrived - this is what makes it accurate.
        if (aimedAt(bot, aim.lookPoint(), 6.0)) {
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
     * Emergency cocoon. Triggered when the bot is about to die, has webs, and is actually
     * under threat - not merely low on health somewhere quiet.
     */
    public static boolean shouldBubble(ServerPlayer bot, Entity target, BotProfile profile,
                                       BotSettings settings, double distance) {
        if (!settings.isCobwebBubbleEnabled()) return false;
        WebState st = state(bot.getName().getString());
        if (st.bubbling || st.bubbleCooldown > 0) return false;
        if (!Inv.has(bot.getInventory(), Items.COBWEB)) return false;
        if (target == null || distance > 8.0) return false;

        float healthFraction = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());
        double trigger = 0.25 + profile.rng().nextGaussian() * 0.03;
        if (healthFraction > trigger) return false;

        // Only worth it if there is something to gain from the time it buys.
        return HealPlanner.hasAnyHeal(bot.getInventory())
            || Inv.has(bot.getInventory(), Items.TOTEM_OF_UNDYING);
    }

    public static void beginBubble(String botName) {
        WebState st = state(botName);
        st.bubbling = true;
        st.bubbleTicks = 0;
        st.bubblePlaced = 0;
    }

    /**
     * Builds the cocoon: own cell first (stops knockback and slows anyone reaching in), then
     * the side the attacker is on. Returns true while busy.
     */
    public static boolean tickBubble(ServerPlayer bot, Entity threat, BotProfile profile,
                                     MinecraftServer server) {
        WebState st = state(bot.getName().getString());
        if (!st.bubbling) return false;

        st.bubbleTicks++;
        if (st.bubblePlaceDelay > 0) st.bubblePlaceDelay--;
        Level world = bot.level();

        // The cocoon is a stalling tool, not a home. Four seconds is plenty, and it is long
        // enough for the second web: the head has to travel between two cells at a human turn
        // rate, and the old three-second budget was being spent on the journey.
        if (st.bubbleTicks > 80 || st.bubblePlaced >= MAX_BUBBLE_WEBS
            || !Inv.has(bot.getInventory(), Items.COBWEB)) {
            st.bubbling = false;
            st.bubbleCooldown = Rng.range(profile.rng(), 200, 400);
            return false;
        }

        BlockPos aimCell = nextBubbleCell(bot, threat, world);
        if (aimCell == null) {
            // Out of places to put one. Fine if the cocoon is built; a failure if it is not.
            st.bubbling = false;
            st.bubbleCooldown = st.bubblePlaced >= MIN_BUBBLE_WEBS
                ? Rng.range(profile.rng(), 200, 400)
                : Rng.range(profile.rng(), 120, 240);
            return false;
        }

        BlockPlacer.Aim aim = BlockPlacer.solve(world, bot, aimCell);
        if (aim == null) {
            // One unreachable cell is not a reason to abandon the cocoon: the next one along may
            // well be reachable, and giving up here is what left bots wearing a single web.
            return st.bubbleTicks <= 80;
        }

        int webSlot = Inv.findItem(bot.getInventory(), Items.COBWEB);
        Inv.equip(bot.getInventory(), webSlot, 4);
        AimController.lookAtPrecise(bot, aim.lookPoint(), profile);

        if (st.bubblePlaceDelay <= 0 && aimedAt(bot, aim.lookPoint(), 8.0)) {
            BotActions.place(bot, server);
            st.bubblePlaced++;
            // Long enough for the placement to land and for the head to start moving to the
            // next cell, short enough that a cocoon still goes up in well under a second.
            st.bubblePlaceDelay = 3;
        }
        // Stand still inside the web so we do not walk out of our own cover.
        bot.zza = 0;
        bot.xxa = 0;
        bot.setSprinting(false);
        return true;
    }

    /**
     * The next cell of the cocoon that still needs filling.
     *
     * <p>Ordered by what each web actually buys. The cell the bot stands in stops knockback; the
     * cell its head is in stops a weapon reaching through at the height a weapon swings; the
     * cell on the attacker's side is the one they have to come through. Anything already webbed
     * is skipped, so this walks the cocoon forward rather than re-solving the same spot.</p>
     */
    private static BlockPos nextBubbleCell(ServerPlayer bot, Entity threat, Level world) {
        BlockPos self = BlockPos.containing(bot.getX(), bot.getY() + 0.1, bot.getZ());

        BlockPos[] wanted = new BlockPos[3];
        wanted[0] = self;
        wanted[1] = self.above();
        if (threat != null) {
            double dx = threat.getX() - bot.getX();
            double dz = threat.getZ() - bot.getZ();
            double len = Math.hypot(dx, dz);
            if (len > 1.0E-4) {
                wanted[2] = BlockPos.containing(
                    bot.getX() + dx / len, bot.getY() + 0.1, bot.getZ() + dz / len);
            }
        }

        for (BlockPos cell : wanted) {
            if (cell == null) continue;
            if (world.getBlockState(cell).is(Blocks.COBWEB)) continue;
            if (!BlockPlacer.isReplaceable(world, cell)) continue;
            return cell;
        }
        return null;
    }

    /** True when the bot is currently stuck inside a web. */
    public static boolean inWeb(ServerPlayer bot) {
        return webHoldingBot(bot) != null;
    }

    /**
     * The cobweb block a stuck bot should actually break.
     *
     * <p>Prefers the one at eye level, then the one at the feet. That order matters: a player
     * standing in a web looks at the web <em>in front of their face</em>, not at the floor.
     * Aiming at the block under your feet points the crosshair down past it and into the
     * ground - which is why bots used to stand in a web mining the dirt below them.</p>
     */
    public static BlockPos webHoldingBot(ServerPlayer bot) {
        Level world = bot.level();
        BlockPos eyes = BlockPos.containing(bot.getEyePosition().x, bot.getEyePosition().y, bot.getEyePosition().z);
        if (world.getBlockState(eyes).is(Blocks.COBWEB)) return eyes;

        BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.1, bot.getZ());
        if (world.getBlockState(feet).is(Blocks.COBWEB)) return feet;

        BlockPos chest = feet.above();
        if (world.getBlockState(chest).is(Blocks.COBWEB)) return chest;
        return null;
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

        // The cell the eyes are inside is deliberately not a candidate, and that is the whole
        // point of this list. You cannot click the block your head is in - a ray starting inside
        // a shape does not intersect it, so the click passes straight through to whatever is
        // beyond. Aiming at it produced a point a few centimetres from the eye, which no head
        // turn can ever be "pointed at" to within any sane tolerance, so the bot stood in the web
        // turning its head and never clicking at all. What can be hit is the web in front, or the
        // one under your feet, which is exactly what a person reaches for.
        BlockPos[] candidates = {
            eyeCell.north(), eyeCell.east(), eyeCell.south(), eyeCell.west(),
            feet,
            feet.above(2),
        };

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

    private static boolean aimedAt(ServerPlayer bot, Vec3 point, double toleranceDegrees) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float wantPitch = (float) -(Math.atan2(dy, horiz) * (180.0 / Math.PI));
        return Math.abs(AimController.wrap(wantYaw - bot.getYRot())) <= toleranceDegrees
            && Math.abs(AimController.wrap(wantPitch - bot.getXRot())) <= toleranceDegrees;
    }
}
