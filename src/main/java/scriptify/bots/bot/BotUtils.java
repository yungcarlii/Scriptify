package scriptify.bots.bot;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.CobwebTactics;
import scriptify.bots.ai.HealPlanner;
import scriptify.bots.ai.Rng;
import scriptify.bots.utils.Inv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumables and survival: eating, potions, totems, mending, cobweb escapes.
 *
 * <p>Healing decisions now come from {@link HealPlanner} instead of three overlapping
 * threshold checks, and every animation-locked action (eating, drinking) is committed to
 * deliberately rather than started and cancelled every other tick.</p>
 *
 * <p>Shield control has moved out of this class entirely - it lives in {@link BotCombat}.
 * Having both files toggle the shield was the cause of the visible shield flicker: one
 * would raise it, the other would lower it on the same tick.</p>
 */
public class BotUtils {

    private static final Map<String, BotState> botStates = new HashMap<>();

    public static class BotState {
        public int eatCooldown = 0;
        public boolean isEating = false;
        public int eatingTicks = 0;
        public int eatingSlot = -1;
        public int eatCommitTicks = 0;

        public int potionCooldown = 0;
        public int buffPotionCooldown = 0;
        public boolean isThrowingPotion = false;
        public int throwingPotionTicks = 0;
        public int throwReleaseAt = 0;
        public int throwFinishAt = 0;
        public float throwPitch = 80f;
        public final List<Integer> potionsToThrow = new ArrayList<>();

        public boolean isMending = false;
        public int mendingCooldown = 0;
        public int xpBottlesThrown = 0;
        public int xpBottlesNeeded = 0;
        public int xpThrowDelay = 0;

        /** Tick of the last heal evaluation, so the inventory is not rescanned every tick. */
        public long lastHealCheck = 0;
        public float healthAtLastCheck = 20.0f;

        /** Mid-throw of an escape pearl: aiming this tick, releasing in a few. */
        public boolean isPearling = false;
        public int pearlTicks = 0;

        public boolean isEscapingCobweb = false;
        public int cobwebEscapeTicks = 0;
        public int cobwebEscapeCooldown = 0;
        /** Where the escape is up to: see the phase constants on {@link BotUtils}. */
        public int webPhase = WEB_PHASE_START;
        /** The cell water was poured into, so the bot knows where to scoop it back from. */
        public BlockPos webWaterCell = null;
        public int webPhaseTicks = 0;

        /** Mirrors the combat shield state so other systems can read it. */
        public boolean isBlocking = false;
    }

    public static BotState getState(String botName) {
        return botStates.computeIfAbsent(scriptify.bots.utils.Names.key(botName), k -> new BotState());
    }

    public static void removeState(String botName) {
        botStates.remove(scriptify.bots.utils.Names.key(botName));
    }

    public static void clear() {
        botStates.clear();
    }

    // ===================== tick =====================

    /** How often the healing decision is reconsidered when nothing sudden has happened. */
    private static final int HEAL_CHECK_INTERVAL = 10;

    // ----- cobweb escape phases ---------------------------------------------------------
    /** Nothing tried yet. */
    private static final int WEB_PHASE_START = 0;
    /** Water has been poured; waiting for it to wash the webs away. */
    private static final int WEB_PHASE_WATER = 1;
    /** Webs are gone; picking the water back up before walking off. */
    private static final int WEB_PHASE_SCOOP = 2;
    /** No water, or the water did not work: cut the webs by hand. */
    private static final int WEB_PHASE_CUT = 3;

    /** How long to spend lining the bucket up before giving up and cutting instead. */
    private static final int POUR_PATIENCE_TICKS = 20;

    /** How long to give the water before deciding it has not worked and cutting instead. */
    private static final int WATER_PATIENCE_TICKS = 40;

    /** And how long to spend trying to get it back, before leaving it and moving on. */
    private static final int SCOOP_PATIENCE_TICKS = 30;

    /** Aim tolerance for a click that has to land on one specific block, in degrees. */
    private static final double BLOCK_AIM_TOLERANCE = 7.0;

    public static void update(ServerPlayer bot, MinecraftServer server, long serverTick) {
        String botName = bot.getName().getString();
        BotSettings settings = BotSettings.get();
        BotProfile profile = BotProfile.of(botName);
        BotState state = getState(botName);
        BotCombat.CombatState combat = BotCombat.getState(botName);

        tickCooldowns(state);
        state.isBlocking = combat.isUsingShield;

        handleSwimming(bot);

        if (settings.isAutoTotemEnabled() && profile.onPhase(serverTick, 5)) {
            handleAutoTotem(bot, combat);
        }

        // In-progress animations own the bot until they finish.
        if (state.isThrowingPotion) {
            tickPotionThrow(bot, state, profile, server);
            return;
        }
        if (state.isEating) {
            tickEating(bot, state, profile, server);
            return;
        }
        if (state.isPearling) {
            tickPearlOut(bot, state, profile, combat, server);
            return;
        }
        if (state.isEscapingCobweb) {
            tickCobwebEscape(bot, state, profile, server);
            return;
        }

        if (handleCobwebEscape(bot, state, profile, server)) return;

        Entity target = combat.target;
        double distance = target != null ? bot.distanceTo(target) : Double.MAX_VALUE;
        boolean underPressure = target != null && target.isAlive()
            && System.currentTimeMillis() - combat.lastAttackTime < 4000;

        if (settings.isAutoPotionEnabled() || settings.isAutoEatEnabled()) {
            // Planning a heal reads the whole inventory several times over. Doing that every
            // tick for every bot is pure waste, since the answer only changes when health,
            // hunger or the situation moves. Re-check on a stagger, or immediately after
            // taking a real hit - which is the case that actually needs a fast response.
            boolean tookHit = bot.getHealth() < state.healthAtLastCheck - 1.0f;
            boolean due = serverTick - state.lastHealCheck >= HEAL_CHECK_INTERVAL;
            if (tookHit || due) {
                state.lastHealCheck = serverTick;
                state.healthAtLastCheck = bot.getHealth();
                // Combat's own count, rather than a second scan: it is already kept on a twenty
                // tick timer for weapon choice, and being surrounded is not a thing that changes
                // faster than that. It is what tells the planner to heal where it stands instead
                // of looking for a way out that is not there.
                HealPlanner.Plan plan = HealPlanner.plan(bot, profile, target, distance,
                    underPressure, combat.cachedNearbyEnemies);
                if (applyHealPlan(bot, state, profile, settings, plan, server)) return;
            }
        }

        if (settings.isAutoPotionEnabled()) {
            handleBuffPotions(bot, state, profile, combat, server);
        }
        if (settings.isAutoMendEnabled()) {
            handleAutoMend(bot, state, profile, settings, combat, server);
        }
    }

    private static void tickCooldowns(BotState state) {
        if (state.eatCooldown > 0) state.eatCooldown--;
        if (state.potionCooldown > 0) state.potionCooldown--;
        if (state.buffPotionCooldown > 0) state.buffPotionCooldown--;
        if (state.mendingCooldown > 0) state.mendingCooldown--;
        if (state.cobwebEscapeCooldown > 0) state.cobwebEscapeCooldown--;
        if (state.eatCommitTicks > 0) state.eatCommitTicks--;
        if (state.isPearling) state.pearlTicks++;
        if (state.xpThrowDelay > 0) state.xpThrowDelay--;
    }

    // ===================== healing =====================

    private static boolean applyHealPlan(ServerPlayer bot, BotState state, BotProfile profile,
                                         BotSettings settings, HealPlanner.Plan plan,
                                         MinecraftServer server) {
        if (plan.isNothing()) return false;

        switch (plan.action()) {
            case SPLASH_HEAL -> {
                if (state.potionCooldown > 0) return false;
                beginPotionThrow(bot, state, profile, plan.slot(), true);
                state.potionCooldown = Rng.range(profile.rng(), 20, 45);
                return true;
            }
            case DRINK_HEAL -> {
                if (state.potionCooldown > 0) return false;
                beginEating(bot, state, profile, plan.slot(), server);
                state.potionCooldown = Rng.range(profile.rng(), 20, 45);
                return true;
            }
            case EAT_GAPPLE, EAT_FOOD -> {
                if (!settings.isAutoEatEnabled() && plan.action() == HealPlanner.Action.EAT_FOOD) {
                    return false;
                }
                if (state.eatCooldown > 0) return false;
                beginEating(bot, state, profile, plan.slot(), server);
                return true;
            }
            case PEARL_OUT -> {
                BotCombat.CombatState combat = BotCombat.getState(bot.getName().getString());
                // Retreat regardless: if the pearl is still on cooldown the bot should be
                // backing off on foot rather than standing its ground on the strength of a
                // pearl it cannot throw.
                combat.isRetreating = true;
                if (combat.pearlCooldown > 0) return false;
                state.isPearling = true;
                state.pearlTicks = 0;
                return true;
            }
            case DISENGAGE -> {
                // The combat side reads the retreat flag; nothing to do here beyond letting
                // it know a heal is wanted but unsafe right now.
                BotCombat.getState(bot.getName().getString()).isRetreating = true;
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** How long the bot will spend lining a pearl up before throwing it anyway. */
    private static final int PEARL_AIM_TICKS = 12;

    /** And the point past which the attempt is abandoned. */
    private static final int PEARL_GIVE_UP_TICKS = 24;

    /** Degrees of slop tolerated on a pearl. It is a long throw; it does not need to be exact. */
    private static final double PEARL_AIM_TOLERANCE = 18.0;

    /**
     * Throws the escape pearl.
     *
     * <p>Aimed away from whoever is hitting the bot, and upward: a pearl thrown flat travels a
     * handful of blocks and puts the bot barely out of reach, which is not out of the fight. The
     * head is given a moment to arrive before the throw, because the aim controller turns at a
     * human rate and a pearl released while the head is still coming round lands somewhere
     * between where the bot was and where it wanted to go - usually still inside the fight.</p>
     */
    private static void tickPearlOut(ServerPlayer bot, BotState state, BotProfile profile,
                                     BotCombat.CombatState combat, MinecraftServer server) {
        var inv = bot.getInventory();
        int pearl = Inv.findItem(inv, Items.ENDER_PEARL);
        if (pearl < 0 || state.pearlTicks > PEARL_GIVE_UP_TICKS) {
            state.isPearling = false;
            state.pearlTicks = 0;
            return;
        }
        Inv.equip(inv, pearl, 5);

        Entity threat = combat.target;
        Vec3 away;
        if (threat != null && threat.isAlive()) {
            double dx = bot.getX() - threat.getX();
            double dz = bot.getZ() - threat.getZ();
            double length = Math.hypot(dx, dz);
            away = length < 1.0E-4
                ? bot.getViewVector(1.0f)
                : new Vec3(dx / length, 0, dz / length);
        } else {
            away = bot.getViewVector(1.0f);
        }

        // A shallow climb. Steep enough to carry, flat enough to keep the distance travelled
        // roughly where the bot is looking rather than dropping it on its own head.
        Vec3 eye = bot.getEyePosition();
        Vec3 aim = new Vec3(eye.x + away.x * 12.0, eye.y + 4.0, eye.z + away.z * 12.0);
        AimController.lookAtPos(bot, aim, profile, false);

        // Do not walk into the person you are leaving.
        bot.setSprinting(false);

        boolean lined = aimedAtPoint(bot, aim, PEARL_AIM_TOLERANCE);
        if (!lined && state.pearlTicks < PEARL_AIM_TICKS) return;

        BotActions.useOnce(bot, server);
        combat.pearlCooldown = Rng.range(profile.rng(), 140, 260);
        state.isPearling = false;
        state.pearlTicks = 0;
    }

    private static void beginEating(ServerPlayer bot, BotState state, BotProfile profile,
                                    int slot, MinecraftServer server) {
        int hotbar = Inv.equip(bot.getInventory(), slot, 8);
        if (hotbar < 0) return;

        state.isEating = true;
        state.eatingTicks = 0;
        state.eatingSlot = hotbar;
        // Commit: do not abandon the animation for a couple of ticks even if the situation
        // changes, otherwise the bot spends its life starting to eat and stopping.
        state.eatCommitTicks = Rng.range(profile.rng(), 24, 36);
        BotActions.useContinuous(bot, server);
    }

    private static void tickEating(ServerPlayer bot, BotState state, BotProfile profile,
                                   MinecraftServer server) {
        state.eatingTicks++;

        if (state.eatingSlot >= 0 && state.eatingSlot < 9) {
            scriptify.bots.utils.InventoryHelper.setSelectedSlot(bot.getInventory(), state.eatingSlot);
        }

        ItemStack held = bot.getMainHandItem();
        boolean consumable = held.get(DataComponents.FOOD) != null
            || held.getItem() instanceof net.minecraft.world.item.PotionItem;
        if (!consumable) {
            finishEating(bot, state, profile, server);
            return;
        }
        if (!bot.isUsingItem()) {
            bot.startUsingItem(InteractionHand.MAIN_HAND);
        }

        // Vanilla food takes 32 ticks; leave a margin, then stop.
        if (state.eatingTicks >= 34 || (state.eatCommitTicks <= 0 && !bot.isUsingItem())) {
            finishEating(bot, state, profile, server);
        }
    }

    private static void finishEating(ServerPlayer bot, BotState state, BotProfile profile,
                                     MinecraftServer server) {
        BotActions.stopUsing(bot, server);
        state.isEating = false;
        state.eatingTicks = 0;
        state.eatingSlot = -1;
        state.eatCooldown = Rng.range(profile.rng(), 6, 20);
    }

    // ===================== potions =====================

    private static void beginPotionThrow(ServerPlayer bot, BotState state, BotProfile profile,
                                         int slot, boolean atSelf) {
        int hotbar = Inv.equip(bot.getInventory(), slot, 8);
        if (hotbar < 0) return;

        state.isThrowingPotion = true;
        state.throwingPotionTicks = 0;
        // A human does not snap to exactly straight down and release on the same frame.
        state.throwPitch = atSelf
            ? (float) Rng.range(profile.rng(), 68.0, 88.0)
            : (float) Rng.range(profile.rng(), 15.0, 45.0);
        state.throwReleaseAt = Rng.range(profile.rng(), 3, 7);
        // Rolled once, not re-rolled every tick, so the gap between potions in a chain is a
        // stable random value rather than a moving target.
        state.throwFinishAt = state.throwReleaseAt + Rng.range(profile.rng(), 4, 10);
    }

    /**
     * Throws a potion over several ticks: turn the head down smoothly, release, then queue
     * any follow-up potions behind a randomised gap.
     *
     * <p>The original slammed {@code setPitch(90)} and released on tick 2 of a fixed 5 tick
     * cycle, for every potion, on every bot - the single most obviously robotic behaviour in
     * the mod.</p>
     */
    private static void tickPotionThrow(ServerPlayer bot, BotState state, BotProfile profile,
                                        MinecraftServer server) {
        state.throwingPotionTicks++;
        AimController.lookPitch(bot, state.throwPitch, profile);

        if (state.throwingPotionTicks == state.throwReleaseAt) {
            // The ground can disappear between deciding to throw and releasing - knocked off a
            // ledge, or the block underneath mined out. Hold the potion rather than spending it
            // into the air; the planner will ask again once there is something to land on.
            if (state.throwPitch > 60.0f && !HealPlanner.hasFloorBelow(bot)) {
                state.isThrowingPotion = false;
                state.throwingPotionTicks = 0;
                state.potionsToThrow.clear();
                return;
            }
            BotActions.useOnce(bot, server);
        }

        if (state.throwingPotionTicks >= state.throwFinishAt) {
            if (!state.potionsToThrow.isEmpty()) {
                int next = state.potionsToThrow.remove(0);
                beginPotionThrow(bot, state, profile, next, true);
            } else {
                state.isThrowingPotion = false;
                state.throwingPotionTicks = 0;
            }
        }
    }

    /** Buff potions (strength, speed, fire resistance) before or during a fight. */
    private static void handleBuffPotions(ServerPlayer bot, BotState state, BotProfile profile,
                                          BotCombat.CombatState combat, MinecraftServer server) {
        if (combat.target == null) return;
        if (state.buffPotionCooldown > 0 || state.isThrowingPotion) return;
        // Same rule as a healing splash: these are thrown at the bot's own feet, so they are
        // wasted if there is nothing under it to break on.
        if (!HealPlanner.hasFloorBelow(bot)) return;

        List<Integer> wanted = new ArrayList<>(3);
        if (!HealPlanner.hasEffect(bot, MobEffects.STRENGTH, 100)) {
            int slot = findSplashBuff(bot, "strength");
            if (slot >= 0) wanted.add(slot);
        }
        if (!HealPlanner.hasEffect(bot, MobEffects.SPEED, 100)) {
            int slot = findSplashBuff(bot, "swiftness");
            if (slot < 0) slot = findSplashBuff(bot, "speed");
            if (slot >= 0) wanted.add(slot);
        }
        if (!HealPlanner.hasEffect(bot, MobEffects.FIRE_RESISTANCE, 100)) {
            int slot = findSplashBuff(bot, "fire_resistance");
            if (slot >= 0) wanted.add(slot);
        }
        if (wanted.isEmpty()) return;

        // Buffing takes time; only do it with a little breathing room.
        double distance = bot.distanceTo(combat.target);
        if (distance < 3.5 && !profile.tech(0.2)) return;

        int first = wanted.remove(0);
        state.potionsToThrow.clear();
        state.potionsToThrow.addAll(wanted);
        beginPotionThrow(bot, state, profile, first, true);
        state.buffPotionCooldown = Rng.range(profile.rng(), 120, 260);
    }

    private static int findSplashBuff(ServerPlayer bot, String effectName) {
        return Inv.find(bot.getInventory(), stack -> {
            var item = stack.getItem();
            if (!(item instanceof SplashPotionItem) && !(item instanceof LingeringPotionItem)) {
                return false;
            }
            var contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null) return false;
            var potion = contents.potion();
            return potion.isPresent() && potion.get().getRegisteredName()
                .toLowerCase(java.util.Locale.ROOT).contains(effectName);
        });
    }

    // ===================== mending =====================

    /**
     * Repairs mending gear with XP bottles.
     *
     * <p>The original threw a bottle <em>every tick</em> while staring straight down and
     * running backwards, for as many bottles as it had calculated, regardless of whether an
     * enemy was swinging at it. Now it only happens with space to breathe, in short bursts
     * with irregular gaps, and stops the moment a fight restarts.</p>
     */
    private static void handleAutoMend(ServerPlayer bot, BotState state, BotProfile profile,
                                       BotSettings settings, BotCombat.CombatState combat,
                                       MinecraftServer server) {
        if (state.mendingCooldown > 0) return;

        boolean threatened = combat.target != null && combat.target.isAlive()
            && bot.distanceTo(combat.target) < 12.0;
        if (threatened) {
            state.isMending = false;
            return;
        }

        int bottleSlot = Inv.findItem(bot.getInventory(), Items.EXPERIENCE_BOTTLE);
        if (bottleSlot < 0) {
            state.isMending = false;
            return;
        }

        int damageToRepair = 0;
        var inv = bot.getInventory();
        for (int slot = Inv.ARMOR_START; slot < 40; slot++) {
            ItemStack piece = inv.getItem(slot);
            if (piece.isEmpty() || piece.getMaxDamage() <= 0) continue;
            if (!Inv.hasEnchant(piece, "mending")) continue;
            double durability = 1.0 - ((double) piece.getDamageValue() / piece.getMaxDamage());
            if (durability < settings.getMendDurabilityThreshold()) {
                damageToRepair += piece.getDamageValue() - (int) (piece.getMaxDamage() * 0.1);
            }
        }

        if (damageToRepair <= 0) {
            state.isMending = false;
            state.xpBottlesThrown = 0;
            state.xpBottlesNeeded = 0;
            return;
        }

        if (!state.isMending) {
            state.isMending = true;
            state.xpBottlesThrown = 0;
            // Roughly two durability per XP point, ~7 XP per bottle.
            state.xpBottlesNeeded = Math.max(3, Math.min(40, damageToRepair / 14 + 2));
        }

        if (state.xpBottlesThrown >= state.xpBottlesNeeded) {
            state.isMending = false;
            state.mendingCooldown = Rng.range(profile.rng(), 100, 200);
            return;
        }

        if (state.xpThrowDelay > 0) return;

        Inv.equip(inv, bottleSlot, 8);
        AimController.lookPitch(bot, (float) Rng.range(profile.rng(), 60.0, 88.0), profile);
        BotActions.useOnce(bot, server);
        state.xpBottlesThrown++;
        state.xpThrowDelay = Rng.range(profile.rng(), 3, 9);
    }

    // ===================== totem =====================

    private static void handleAutoTotem(ServerPlayer bot, BotCombat.CombatState combat) {
        if (combat.isUsingShield) return;   // do not fight the shield for the offhand slot
        var inv = bot.getInventory();
        if (inv.getItem(Inv.OFFHAND).getItem() == Items.TOTEM_OF_UNDYING) return;

        int slot = Inv.findItem(inv, Items.TOTEM_OF_UNDYING);
        if (slot >= 0) {
            Inv.swapOffhand(inv, slot);
        }
    }

    // ===================== cobweb escape =====================

    /** Returns true when the bot started an escape and should not do anything else. */
    private static boolean handleCobwebEscape(ServerPlayer bot, BotState state,
                                              BotProfile profile, MinecraftServer server) {
        if (state.cobwebEscapeCooldown > 0) return false;
        if (!CobwebTactics.inWeb(bot)) return false;
        // A bot inside its own cocoon is there on purpose.
        if (CobwebTactics.state(bot.getName().getString()).bubbling) return false;

        state.isEscapingCobweb = true;
        state.cobwebEscapeTicks = 0;
        state.webPhase = WEB_PHASE_START;
        state.webPhaseTicks = 0;
        state.webWaterCell = null;
        return true;
    }

    /**
     * Getting out of a cobweb, the way somebody who has been webbed before gets out of one.
     *
     * <p>Water first, if the bot is carrying any. A bucket poured into the web washes the whole
     * cluster away at once and does not care how many webs there are, which is why it is the
     * answer to being webbed rather than a curiosity - and the bot picks it back up afterwards,
     * because leaving a puddle in the middle of an arena is somebody else's problem otherwise.
     * With no bucket, or when the water has plainly not worked, it cuts its way out with a
     * sword.</p>
     *
     * <p>Everything here goes through {@link CobwebTactics#escapeAim}, which returns a point on
     * a web that a ray from the eyes genuinely reaches. Aiming at the centre of the block
     * underfoot - the old behaviour - points past it into the floor, and that is what had bots
     * standing inside a web mining the dirt beneath them.</p>
     */
    private static void tickCobwebEscape(ServerPlayer bot, BotState state, BotProfile profile,
                                         MinecraftServer server) {
        state.cobwebEscapeTicks++;
        state.webPhaseTicks++;

        // Putting the water back is the one phase that runs after the webs have gone.
        if (state.webPhase == WEB_PHASE_SCOOP) {
            if (tickWaterScoop(bot, state, profile, server)) return;
            endEscape(bot, state, profile, server);
            return;
        }

        boolean stillWebbed = CobwebTactics.inWeb(bot);
        if (!stillWebbed) {
            // Out. If the way out was a bucket of water, go and collect it.
            if (state.webWaterCell != null) {
                BotActions.stopUsing(bot, server);
                state.webPhase = WEB_PHASE_SCOOP;
                state.webPhaseTicks = 0;
                return;
            }
            endEscape(bot, state, profile, server);
            return;
        }
        if (state.cobwebEscapeTicks > 100) {
            endEscape(bot, state, profile, server);
            return;
        }

        Vec3 aim = CobwebTactics.escapeAim(bot);
        if (aim == null) {
            endEscape(bot, state, profile, server);
            return;
        }

        if (state.webPhase == WEB_PHASE_START && !canUseWater(bot)) {
            // No bucket, so there is nothing to wait for. Named rather than left in START, so
            // the state says what the bot is doing.
            state.webPhase = WEB_PHASE_CUT;
            state.webPhaseTicks = 0;
        }
        if (state.webPhase == WEB_PHASE_START) {
            // Lining the bucket up has to be able to fail. Without this the bot waits on an aim
            // it may never reach - a web at an awkward angle, a head knocked off course - and
            // never falls through to the sword it is holding.
            if (state.webPhaseTicks <= POUR_PATIENCE_TICKS) {
                tickWaterPour(bot, state, profile, server, aim);
                return;
            }
            state.webPhase = WEB_PHASE_CUT;
            state.webPhaseTicks = 0;
        }
        if (state.webPhase == WEB_PHASE_WATER) {
            // The water is placed and the bot is still webbed. Give it a moment - the flow takes
            // a few ticks to spread - and then stop waiting and cut.
            if (state.webPhaseTicks < WATER_PATIENCE_TICKS) return;
            state.webPhase = WEB_PHASE_CUT;
            state.webPhaseTicks = 0;
        }

        tickWebCut(bot, state, profile, server, aim);
    }

    /** Winds the escape up and puts the attack button down. */
    private static void endEscape(ServerPlayer bot, BotState state, BotProfile profile,
                                  MinecraftServer server) {
        BotActions.stopUsing(bot, server);
        state.isEscapingCobweb = false;
        state.cobwebEscapeTicks = 0;
        state.webPhase = WEB_PHASE_START;
        state.webPhaseTicks = 0;
        state.webWaterCell = null;
        state.cobwebEscapeCooldown = Rng.range(profile.rng(), 10, 30);
    }

    /**
     * Whether pouring water is an option.
     *
     * <p>Not in the nether, where a bucket empties into steam and the bot would stand there
     * having solved nothing.</p>
     */
    private static boolean canUseWater(ServerPlayer bot) {
        if (!Inv.has(bot.getInventory(), Items.WATER_BUCKET)) return false;
        return bot.level().dimension() != net.minecraft.world.level.Level.NETHER;
    }

    /**
     * Pours the bucket into the web.
     *
     * <p>Aimed at the web itself rather than at a face beside it. A cobweb does not block a
     * fluid, so the bucket empties into that cell, and flowing water destroys cobweb - which is
     * the entire trick, and why this clears a whole cluster where a sword clears one block.</p>
     */
    private static void tickWaterPour(ServerPlayer bot, BotState state, BotProfile profile,
                                      MinecraftServer server, Vec3 aim) {
        var inv = bot.getInventory();
        int bucket = Inv.findItem(inv, Items.WATER_BUCKET);
        if (bucket < 0) {
            state.webPhase = WEB_PHASE_CUT;
            return;
        }
        Inv.equip(inv, bucket, 4);
        AimController.lookAtPrecise(bot, aim, profile);
        if (!aimedAtPoint(bot, aim, BLOCK_AIM_TOLERANCE)) return;

        BotActions.useOnce(bot, server);
        state.webWaterCell = BlockPos.containing(aim.x, aim.y, aim.z);
        state.webPhase = WEB_PHASE_WATER;
        state.webPhaseTicks = 0;
    }

    /** How far around itself the bot looks for the source it poured. */
    private static final int SCOOP_SEARCH = 2;

    /**
     * Finds the water the bot put down.
     *
     * <p>Searched for rather than remembered. The cell the bot aimed at is where it wanted the
     * water; where the water actually went is decided by the backend's own raycast, and the two
     * are often a block apart - so a bot that trusted its own aim looked for a source that was
     * never there and walked off leaving a pool behind. The nearest source to the spot it aimed
     * at is the honest answer.</p>
     */
    private static BlockPos findPlacedWater(ServerPlayer bot, BlockPos near) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SCOOP_SEARCH; dx <= SCOOP_SEARCH; dx++) {
            for (int dy = -1; dy <= SCOOP_SEARCH; dy++) {
                for (int dz = -SCOOP_SEARCH; dz <= SCOOP_SEARCH; dz++) {
                    BlockPos candidate = near.offset(dx, dy, dz);
                    if (!bot.level().getFluidState(candidate).isSource()) continue;
                    double distance = candidate.distToCenterSqr(bot.position());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Picks the water back up.
     *
     * @return true while still working at it
     */
    private static boolean tickWaterScoop(ServerPlayer bot, BotState state, BotProfile profile,
                                          MinecraftServer server) {
        if (state.webPhaseTicks > SCOOP_PATIENCE_TICKS || state.webWaterCell == null) {
            return false;
        }
        var inv = bot.getInventory();
        int bucket = Inv.findItem(inv, Items.BUCKET);
        // Somebody else scooped it, or the bucket is gone. Either way it is not worth standing
        // in the open over.
        if (bucket < 0) return false;

        BlockPos source = findPlacedWater(bot, state.webWaterCell);
        if (source == null) return false;

        Inv.equip(inv, bucket, 4);
        Vec3 aim = Vec3.atCenterOf(source);
        AimController.lookAtPrecise(bot, aim, profile);
        if (aimedAtPoint(bot, aim, BLOCK_AIM_TOLERANCE)) {
            BotActions.useOnce(bot, server);
        }
        return true;
    }

    /**
     * Cuts the web.
     *
     * <p>Two details, both of which were wrong once. A sword shears cobweb about fifteen times
     * faster than a fist, so it is worth the swap - but only if there is a sword: the old code
     * equipped whatever scored best, and with no sword in the bag that was slot zero, so a bot
     * changed to a random item and mined slower than with its hands. And breaking a block needs
     * the button <em>held</em>; single clicks barely scratch it.</p>
     */
    private static void tickWebCut(ServerPlayer bot, BotState state, BotProfile profile,
                                   MinecraftServer server, Vec3 aim) {
        var inv = bot.getInventory();
        int swordSlot = Inv.best(inv,
            s -> scriptify.bots.ai.WeaponSelector.isSword(s.getItem()) ? 1 : 0);
        // Shears cut web as fast as a sword and are the other thing a player might be holding;
        // anything else is worse than bare hands, so nothing else is swapped to.
        if (swordSlot < 0) swordSlot = Inv.findItem(inv, Items.SHEARS);
        if (swordSlot >= 0) Inv.equip(inv, swordSlot, 0);

        AimController.lookAtPrecise(bot, aim, profile);

        // Only hold the button once the head has arrived. Held while the head is still turning,
        // the mining lands on whatever the bot happened to be facing on the way past.
        if (!aimedAtPoint(bot, aim, BLOCK_AIM_TOLERANCE)) return;
        if (state.webPhaseTicks == 1 || state.webPhaseTicks % 20 == 0) {
            BotActions.attackContinuous(bot, server);
        }
    }

    /** Whether the head has arrived at a point, within a tolerance in degrees. */
    private static boolean aimedAtPoint(ServerPlayer bot, Vec3 point, double toleranceDegrees) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4 && Math.abs(dy) < 1.0E-4) return true;
        float wantYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float wantPitch = (float) -(Math.atan2(dy, horizontal) * (180.0 / Math.PI));
        return Math.abs(AimController.wrap(wantYaw - bot.getYRot())) <= toleranceDegrees
            && Math.abs(AimController.wrap(wantPitch - bot.getXRot())) <= toleranceDegrees;
    }

    // ===================== misc =====================

    private static void handleSwimming(ServerPlayer bot) {
        if (!bot.isInWater() && !bot.isUnderWater()) return;
        bot.setSwimming(true);
        if (bot.isUnderWater()) {
            bot.push(0, 0.08, 0);
        } else {
            bot.push(0, 0.04, 0);
        }
    }

    /** Whether the bot is free to swing right now. */
    public static boolean canAct(ServerPlayer bot) {
        BotState state = getState(bot.getName().getString());
        return !state.isEating && !state.isThrowingPotion && !state.isEscapingCobweb
            && !state.isPearling;
    }

    public static boolean hasFood(ServerPlayer bot) {
        return HealPlanner.findFood(bot.getInventory()) >= 0
            || HealPlanner.findGapple(bot.getInventory()) >= 0;
    }

}
