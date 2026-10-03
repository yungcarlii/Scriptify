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
import scriptify.bots.ai.ClickSim;
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
        /** Whether the attack button is being held down on a web. */
        public boolean webCutHeld = false;
        /** Ticks before the bucket may be clicked again. */
        public int handClickCooldown = 0;

        /** Heals are not considered again before this tick: a slip, a late reaction. */
        public long healBlockedUntil = 0;
        /** Last tick a heal was wanted, to tell a new low-health moment from a continuing one. */
        public long lastHealWant = -1000;
        /** When the offhand may next be given a totem, after one has gone. */
        public long retotemAt = -1;

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

    /**
     * Past this, the bucket is aimed at the web itself rather than straight down. Looking down is
     * what a player does first and it nearly always works; the web is the fallback for the odd spot
     * where the water would land somewhere useless.
     */
    private static final int POUR_LOOK_DOWN_TICKS = 10;

    /** How long to give the water before deciding it has not worked and cutting instead. */
    private static final int WATER_PATIENCE_TICKS = 40;

    /**
     * And how long to spend trying to get it back, before leaving it and moving on.
     *
     * <p>Generous, because the bucket is worth the wait - a player never leaves one lying about -
     * and because the bot may have to take a step back toward it first.</p>
     */
    private static final int SCOOP_PATIENCE_TICKS = 60;

    /** The escape proper, before the bot stops and takes stock. */
    private static final int ESCAPE_LIMIT_TICKS = 100;

    public static void update(ServerPlayer bot, MinecraftServer server, long serverTick) {
        String botName = bot.getName().getString();
        BotSettings settings = BotSettings.get();
        BotProfile profile = BotProfile.of(botName);
        BotState state = getState(botName);
        BotCombat.CombatState combat = BotCombat.getState(botName);

        tickCooldowns(state);
        state.isBlocking = combat.isUsingShield;

        // Afloat, the default is to tread water: hold space and keep the head out. Anything that
        // wants the bot to go somewhere overrides it later this tick. Not while it is getting out of
        // a web, where it is standing in water it poured itself and wants to stay put to scoop it.
        if (!state.isEscapingCobweb) {
            scriptify.bots.ai.Swimmer.tread(bot);
        }

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
                    underPressure, combat.cachedNearbyEnemies, CobwebTactics.isSheltering(botName));
                if (!plan.isNothing() && slipsUp(bot, state, profile, combat, serverTick)) {
                    // Not this time: noticing late, forgetting, or too busy trading hits.
                } else if (applyHealPlan(bot, state, profile, settings, plan, server)) {
                    return;
                }
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
        if (state.handClickCooldown > 0) state.handClickCooldown--;
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
        BotState state = getState(bot.getName().getString());
        if (inv.getItem(Inv.OFFHAND).getItem() == Items.TOTEM_OF_UNDYING) {
            state.retotemAt = -1;
            return;
        }
        int slot = Inv.findItem(inv, Items.TOTEM_OF_UNDYING);
        if (slot < 0) return;

        // Re-totem the way a player does: open the inventory, find it, drag it across - half a second
        // for the best, three for the rest, and in the thick of a fight sometimes not until it calms
        // down. Swapping one in the instant the last popped made a bot that could not be killed
        // through its totems, which is the one way a stacked player actually dies.
        long now = bot.level().getGameTime();
        BotProfile profile = BotProfile.of(bot.getName().getString());
        if (state.retotemAt < 0) {
            double skill = profile.skill();
            boolean busy = System.currentTimeMillis() - combat.lastAttackTime < 2000;
            int delay = Rng.range(profile.rng(), 10 + (int) ((1 - skill) * 20), 20 + (int) ((1 - skill) * 40));
            if (busy && profile.rng().nextDouble() < 0.25 + (1 - skill) * 0.25) {
                delay += Rng.range(profile.rng(), 60, 160);      // too busy to open the inventory
            }
            state.retotemAt = now + delay;
            return;
        }
        if (now < state.retotemAt) return;
        Inv.swapOffhand(inv, slot);
        state.retotemAt = -1;
    }

    /**
     * Whether the bot fails to act on a heal it should take, this time.
     *
     * <p>Players do not heal on a timer. They notice they are low a moment late, they forget
     * altogether when the fight is busy, and while somebody is landing a combo on them they keep
     * swinging instead of stepping back to eat - which is exactly how a player gets crit out. A bot
     * that ate at the perfect moment every time could not be, and that was the tell.</p>
     */
    private static boolean slipsUp(ServerPlayer bot, BotState state, BotProfile profile,
                                   BotCombat.CombatState combat, long now) {
        if (now < state.healBlockedUntil) return true;
        double skill = profile.skill();
        var rng = profile.rng();
        boolean fresh = now - state.lastHealWant > 60;
        state.lastHealWant = now;
        if (fresh) {
            double forget = 0.10 + (1 - skill) * 0.20 + Math.min(0.15, combat.cachedNearbyEnemies * 0.03);
            if (rng.nextDouble() < forget) {
                state.healBlockedUntil = now + Rng.range(rng, 40, 100);
            } else {
                state.healBlockedUntil = now + Rng.range(rng, 3, 4 + (int) ((1 - skill) * 12));
            }
            return true;
        }
        boolean comboed = System.currentTimeMillis() - combat.lastAttackTime < 500;
        if (comboed && rng.nextDouble() < 0.35 - skill * 0.2) {
            state.healBlockedUntil = now + Rng.range(rng, 5, 15);
            return true;
        }
        return false;
    }

    // ===================== cobweb escape =====================

    /** Returns true when the bot started an escape and should not do anything else. */
    private static boolean handleCobwebEscape(ServerPlayer bot, BotState state,
                                              BotProfile profile, MinecraftServer server) {
        if (state.cobwebEscapeCooldown > 0) return false;
        if (!CobwebTactics.inWeb(bot)) return false;
        // A bot inside its own cocoon is there on purpose, until it has healed.
        String name = bot.getName().getString();
        if (CobwebTactics.state(name).bubbling || CobwebTactics.isSheltering(name)) return false;

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
     * because a player never leaves a bucket of water lying about. With no bucket, or when the
     * water has plainly not worked, it cuts its way out with a sword.</p>
     *
     * <p>Every click here is checked against the ray the click will really cast
     * ({@link ClickSim}) rather than against an angle. The angle checks were the water bug: looking
     * down at the web or the puddle - which is where both are - yaw stops meaning anything, the
     * check kept failing, and the bot gave up on the bucket it had just emptied.</p>
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

        // However this ends - free, out of time, nothing left in reach to cut - water that was
        // poured is collected before the bot moves on. Three of these exits used to go straight to
        // the end and forget the water existed.
        if (!CobwebTactics.inWeb(bot) || state.cobwebEscapeTicks > ESCAPE_LIMIT_TICKS) {
            leaveWeb(bot, state, profile, server);
            return;
        }

        Vec3 aim = CobwebTactics.escapeAim(bot);

        if (state.webPhase == WEB_PHASE_START && !canUseWater(bot)) {
            // No bucket, so there is nothing to wait for. Named rather than left in START, so
            // the state says what the bot is doing.
            state.webPhase = WEB_PHASE_CUT;
            state.webPhaseTicks = 0;
        }
        if (state.webPhase == WEB_PHASE_START) {
            // Lining the bucket up has to be able to fail. Without this the bot waits on an aim
            // it may never reach and never falls through to the sword it is holding.
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

        if (!tickWebCut(bot, state, profile, server, aim)) {
            leaveWeb(bot, state, profile, server);
        }
    }

    /** Out of the web, or done trying: fetch the water if any went down, otherwise finish. */
    private static void leaveWeb(ServerPlayer bot, BotState state, BotProfile profile,
                                 MinecraftServer server) {
        releaseCut(bot, state, server);
        if (state.webWaterCell != null && findPlacedWater(bot, state.webWaterCell) != null) {
            state.webPhase = WEB_PHASE_SCOOP;
            state.webPhaseTicks = 0;
            return;
        }
        endEscape(bot, state, profile, server);
    }

    /** Winds the escape up and puts the attack button down. */
    private static void endEscape(ServerPlayer bot, BotState state, BotProfile profile,
                                  MinecraftServer server) {
        BotActions.stopUsing(bot, server);
        state.webCutHeld = false;
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
     * Pours the bucket so the water runs through the webs.
     *
     * <p>Looking down first, because that is what works and what a player does. Water cannot be
     * poured <em>into</em> a web - the bucket empties beside the face it hits - so the trick is
     * where it lands: with a web at the feet, clicking its top puts the water in the cell above it;
     * with the head in a web, the click lands on that web and the water goes above the head. Either
     * way it falls straight down through every web in the column. Only when that would put the water
     * somewhere useless does the bot turn to aim at a web directly.</p>
     */
    private static void tickWaterPour(ServerPlayer bot, BotState state, BotProfile profile,
                                      MinecraftServer server, Vec3 webAim) {
        var inv = bot.getInventory();
        int bucket = Inv.findItem(inv, Items.WATER_BUCKET);
        if (bucket < 0) {
            state.webPhase = WEB_PHASE_CUT;
            state.webPhaseTicks = 0;
            return;
        }
        Inv.equip(inv, bucket, 4);

        Vec3 look = state.webPhaseTicks <= POUR_LOOK_DOWN_TICKS || webAim == null
            ? lookDownPoint(bot)
            : webAim;
        AimController.lookAtPrecise(bot, look, profile);

        if (state.handClickCooldown > 0) return;
        BlockPos cell = ClickSim.waterCell(bot);
        if (cell == null || !washesWebs(bot, cell)) return;

        BotActions.useOnce(bot, server);
        // Where the water will really be, from the same ray the click casts - not where the bot
        // was looking - so the scoop goes back to the right block.
        state.webWaterCell = cell;
        state.handClickCooldown = HAND_CLICK_GAP;
        state.webPhase = WEB_PHASE_WATER;
        state.webPhaseTicks = 0;
    }

    /**
     * A point just below the feet, a hair ahead along the current heading. Straight down, every
     * heading is equally right, and aiming at the exact centre would have the head chase a
     * different one every tick; nudging the point forward keeps the yaw where it already is.
     */
    private static Vec3 lookDownPoint(ServerPlayer bot) {
        double yaw = Math.toRadians(bot.getYRot());
        return new Vec3(bot.getX() - Math.sin(yaw) * 0.2, bot.getY() - 0.5,
            bot.getZ() + Math.cos(yaw) * 0.2);
    }

    /**
     * Whether water poured into {@code cell} will reach a web holding the bot.
     *
     * <p>Water falls straight down through anything without a collision box - air, plants, webs,
     * destroying the webs as it goes - and once it lands it spreads sideways. A pour is only worth
     * making if one of those two brings it into a web the bot is caught in.</p>
     */
    private static boolean washesWebs(ServerPlayer bot, BlockPos cell) {
        java.util.Set<BlockPos> webs = websHolding(bot);
        if (webs.isEmpty()) return false;

        net.minecraft.world.level.Level world = bot.level();
        BlockPos at = cell;
        for (int fall = 0; fall < 6; fall++) {
            if (webs.contains(at)) return true;
            BlockPos below = at.below();
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                for (net.minecraft.core.Direction side
                    : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    if (webs.contains(at.relative(side))) return true;
                }
                return false;
            }
            at = below;
        }
        return false;
    }

    /** The webs the bot is caught in. */
    private static java.util.Set<BlockPos> websHolding(ServerPlayer bot) {
        return new java.util.HashSet<>(CobwebTactics.websTouching(bot));
    }

    /** How far around itself the bot looks for the source it poured. */
    private static final int SCOOP_SEARCH = 2;

    /**
     * Ticks between two clicks with the bucket. The backend can delay a use by the bot's simulated
     * ping, and a second click that arrived after the first had already scooped would pour the water
     * straight back out.
     */
    private static final int HAND_CLICK_GAP = 6;

    /**
     * Finds the water the bot put down: the source nearest the cell it was poured into, in case the
     * flow or a knock has left the bot looking at the wrong block.
     */
    private static BlockPos findPlacedWater(ServerPlayer bot, BlockPos near) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SCOOP_SEARCH; dx <= SCOOP_SEARCH; dx++) {
            for (int dy = -1; dy <= SCOOP_SEARCH; dy++) {
                for (int dz = -SCOOP_SEARCH; dz <= SCOOP_SEARCH; dz++) {
                    BlockPos candidate = near.offset(dx, dy, dz);
                    var fluid = bot.level().getFluidState(candidate);
                    if (!fluid.isSource() || !fluid.is(net.minecraft.tags.FluidTags.WATER)) continue;
                    double distance = candidate.distToCenterSqr(near.getX() + 0.5,
                        near.getY() + 0.5, near.getZ() + 0.5);
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
     * <p>Aims at the surface of the source and clicks only when the empty bucket's own ray would
     * scoop that block - which from inside the water is any direction at all, and from above it is
     * wherever the surface is in view. Knocked out of reach, the bot takes a step back toward it
     * first rather than abandoning it.</p>
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
        // Got it back - or somebody else did, or the bucket is gone. Either way, done here.
        if (bucket < 0) return false;

        BlockPos source = findPlacedWater(bot, state.webWaterCell);
        if (source == null) return false;
        state.webWaterCell = source;

        Inv.equip(inv, bucket, 4);
        Vec3 surface = new Vec3(source.getX() + 0.5, source.getY() + 0.75, source.getZ() + 0.5);
        AimController.lookAtPrecise(bot, surface, profile);

        if (state.handClickCooldown <= 0 && source.equals(ClickSim.pickupCell(bot))) {
            BotActions.useOnce(bot, server);
            state.handClickCooldown = HAND_CLICK_GAP;
            return true;
        }
        if (bot.getEyePosition().distanceTo(surface) > bot.blockInteractionRange() - 0.5) {
            BotNavigation.moveTowardPosition(bot, Vec3.atBottomCenterOf(source), 0.6);
        }
        return true;
    }

    /**
     * Cuts the web.
     *
     * <p>A sword shears cobweb about fifteen times faster than a fist, so it is worth the swap -
     * but only if there is a sword; anything else is worse than bare hands. Breaking a block needs
     * the button <em>held</em>, and it is only held while the click would actually land on a web:
     * held any longer, the bot carries on mining whatever it is facing once the web is gone.</p>
     *
     * @return false when there is nothing to cut from here and nowhere better to look
     */
    private static boolean tickWebCut(ServerPlayer bot, BotState state, BotProfile profile,
                                      MinecraftServer server, Vec3 aim) {
        var inv = bot.getInventory();
        int swordSlot = Inv.best(inv,
            s -> scriptify.bots.ai.WeaponSelector.isSword(s.getItem()) ? 1 : 0);
        if (swordSlot < 0) swordSlot = Inv.findItem(inv, Items.SHEARS);
        if (swordSlot >= 0) Inv.equip(inv, swordSlot, 0);

        if (aim != null) AimController.lookAtPrecise(bot, aim, profile);

        // With the head inside a web the click lands on that web whatever the aim, which is
        // exactly the web worth cutting first.
        BlockPos targeted = ClickSim.targetedBlock(bot);
        boolean onWeb = targeted != null && bot.level().getBlockState(targeted).is(Blocks.COBWEB);
        if (!onWeb) {
            releaseCut(bot, state, server);
            return aim != null;
        }
        // Re-sent now and then: the backend lets go of the button if the bot is interrupted.
        if (!state.webCutHeld || state.webPhaseTicks % 20 == 0) {
            BotActions.attackContinuous(bot, server);
            state.webCutHeld = true;
        }
        return true;
    }

    private static void releaseCut(ServerPlayer bot, BotState state, MinecraftServer server) {
        if (state.webCutHeld) {
            BotActions.stopUsing(bot, server);
            state.webCutHeld = false;
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
