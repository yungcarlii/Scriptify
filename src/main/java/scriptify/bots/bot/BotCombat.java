package scriptify.bots.bot;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.Bridger;
import scriptify.bots.ai.CobwebTactics;
import scriptify.bots.ai.Rng;
import scriptify.bots.ai.SquadCoordinator;
import scriptify.bots.ai.TargetSelector;
import scriptify.bots.ai.Vision;
import scriptify.bots.ai.WeaponSelector;
import scriptify.bots.utils.Inv;

import java.util.HashMap;
import java.util.Map;

/**
 * Combat brain.
 *
 * <p>This is a rewrite rather than a patch of the original {@code BotCombat}. The old file
 * was a 2,300 line decision tree in which every branch used shared constants: one attack
 * cooldown for all bots, one shield-raise window, one crit timing, one retreat threshold.
 * That is what made a group of bots read as a single machine - they were, quite literally,
 * running the same numbers at the same time.</p>
 *
 * <p>The rewrite keeps the feature set (melee, ranged, mace, shields, cobwebs, revenge
 * targeting, factions) and changes how decisions are made:</p>
 * <ul>
 *   <li>every timing is drawn from the bot's own RNG around a personal mean;</li>
 *   <li>every attack requires line of sight, so bots cannot hit through walls;</li>
 *   <li>weapons are chosen for the situation, not by a damage table;</li>
 *   <li>positioning is negotiated with the squad instead of everyone walking the same line;</li>
 *   <li>techniques (w-tap, crit timing, shield feint, axe shield-break, wind-charge mace,
 *       cobweb traps, bow swap) are probabilistic and gated on skill.</li>
 * </ul>
 */
public class BotCombat {

    private static final Map<String, CombatState> combatStates = new HashMap<>();

    public static class CombatState {
        public Entity target = null;
        public String forcedTargetName = null;

        public int attackCooldown = 0;
        public Entity lastAttacker = null;
        public long lastAttackTime = 0;
        public float lastHealth = 20.0f;

        public WeaponSelector.Kind weapon = WeaponSelector.Kind.SWORD;

        // Ranged
        public boolean isDrawingBow = false;
        public int bowDrawTicks = 0;
        public int bowReleaseAt = 0;

        // Shield
        public boolean isUsingShield = false;
        public int shieldToggleCooldown = 0;
        public int shieldHoldTicks = 0;
        public int shieldReactionTicks = 0;
        public boolean shieldWanted = false;
        public boolean shieldBroken = false;
        public long shieldBrokenTime = 0;
        public int feintTicks = 0;

        // Melee technique state
        public int critFallTicks = 0;
        public boolean critJumpPending = false;
        public int techniqueCooldown = 0;
        public int aimErrorTicks = 0;
        public float aimError = 0;

        // Repositioning
        public boolean isRetreating = false;
        public double disengageThreshold = -1;
        public scriptify.bots.ai.Maneuver.Intent intent = scriptify.bots.ai.Maneuver.Intent.PUSH;

        // Wind-charge mace combo
        public int maceComboPhase = -1;
        public int maceComboTicks = 0;

        // Consumable cooldowns owned by combat
        public int windChargeCooldown = 0;
        public int pearlCooldown = 0;

        // Cached scan results, refreshed on a timer rather than every tick
        public WeaponSelector.Choice cachedWeapon = WeaponSelector.NONE;
        public long weaponScanTick = 0;
        public int cachedNearbyEnemies = 0;
        public long nearbyScanTick = 0;

        // Rallying: no enemy in sight, but the war is on and the fight is somewhere.
        public Vec3 rallyPoint = null;
        public long rallyScanTick = 0;
    }

    public static CombatState getState(String botName) {
        return combatStates.computeIfAbsent(scriptify.bots.utils.Names.key(botName), k -> new CombatState());
    }

    public static void removeState(String botName) {
        combatStates.remove(scriptify.bots.utils.Names.key(botName));
        CobwebTactics.remove(botName);
        Bridger.remove(botName);
    }

    /**
     * True while a scripted technique owns the bot and other systems should stand down.
     *
     * <p>The key has to be normalised. Combat state is stored under the lower-cased name and
     * this read used the raw one, so for any bot whose name is not already lower case - which
     * is all of them - the lookup missed and this answered "no technique running" every time.
     * The equipment pass and the consumable logic therefore kept working mid-combo, putting
     * the sword back in the bot's hand between the wind charge and the mace. That is the
     * hand-shuffle that happened before a bot ever got off the ground.</p>
     */
    public static boolean isTechniqueActive(String botName) {
        CombatState state = combatStates.get(scriptify.bots.utils.Names.key(botName));
        return state != null && state.maceComboPhase >= 0;
    }

    public static Entity getTarget(String botName) {
        return getState(botName).target;
    }

    public static void setTarget(String botName, String targetName) {
        getState(botName).forcedTargetName = targetName;
    }

    public static void clearTarget(String botName) {
        CombatState state = getState(botName);
        state.forcedTargetName = null;
        state.target = null;
        state.lastAttacker = null;
        state.lastAttackTime = 0;
        state.isRetreating = false;
        state.maceComboPhase = -1;
    }

    // ===================== main entry =====================

    public static void update(ServerPlayer bot, MinecraftServer server, long serverTick) {
        String botName = bot.getName().getString();
        BotSettings settings = BotSettings.get();
        BotProfile profile = BotProfile.of(botName);
        CombatState state = getState(botName);

        tickCooldowns(state);
        CobwebTactics.tickTimers(bot);

        if (!settings.isCombatEnabled()) {
            releaseShield(bot, state, server);
            return;
        }

        trackDamage(bot, state, settings);

        // A scripted combo owns the bot until it finishes.
        if (state.maceComboPhase >= 0) {
            tickMaceCombo(bot, state, profile, server);
            return;
        }

        var utils = BotUtils.getState(botName);

        // Cutting out of a cobweb, or mid-pearl: that code owns the head and the hands, and
        // combat has to leave both alone.
        //
        // It did not, and the consequence was the reported bug rather than a theoretical one.
        // The escape pointed the crosshair at the web; combat, which runs later in the same tick,
        // dragged it straight back onto the enemy; and because the click only fires once the head
        // has arrived, it never fired. So a webbed bot stood there with a sword in its hand,
        // looking at the person hitting it, apparently mining the scenery at random - which is
        // exactly what it looked like from the outside, and exactly what it was.
        if (utils.isEscapingCobweb || utils.isPearling) {
            return;
        }

        if (utils.isEating || utils.isThrowingPotion || utils.isMending) {
            // Consumables are handled by BotUtils; combat only keeps the head pointed
            // somewhere sensible and holds position.
            if (state.target != null && state.target.isAlive() && !utils.isThrowingPotion) {
                // Keep them in view while backing off to eat. Eating with your back turned is
                // how you get hit in the back of the head.
                AimController.lookAt(bot, state.target, profile);
                // Healing inside a cocoon: there is nowhere to go, and trying only drags at the web.
                if (!CobwebTactics.isSheltering(botName)) {
                    double gap = bot.distanceTo(state.target);
                    var order = scriptify.bots.ai.Maneuver.resolve(bot, state.target, profile,
                        BotSettings.get(), gap, 0f, serverTick);
                    BotNavigation.moveCombat(bot, order.goal(), order.speed() * 0.8, profile, 0.6,
                        BotSettings.get().getSquadSpacing());
                }
            }
            return;
        }

        Entity target = TargetSelector.select(bot, profile, settings, server,
            state.forcedTargetName, state.lastAttacker, serverTick);
        state.target = target;

        if (target == null) {
            onNoTarget(bot, state, server);
            return;
        }

        // Final validity gate. Everything below this line acts on the target, so a target
        // that stopped being legal since it was selected (creative, spectator, invulnerable,
        // joined our faction) has to be dropped here, on this tick, not on the next sweep.
        if (!TargetSelector.canStillAttack(bot, target, settings)) {
            state.target = null;
            state.lastAttacker = null;
            onNoTarget(bot, state, server);
            return;
        }

        SquadCoordinator.declare(botName, target);
        BotNavigation.resetIdle(botName);

        double distance = bot.distanceTo(target);
        boolean visible = !settings.isRequireLineOfSight() || Vision.canSee(bot, target);

        // --- emergency: cobweb cocoon -----------------------------------------------------
        if (CobwebTactics.tickBubble(bot, target, profile, server)) return;
        if (CobwebTactics.isSheltering(botName)) {
            shelter(bot, target, state, profile, settings, distance, visible, server);
            return;
        }
        boolean pressured = System.currentTimeMillis() - state.lastAttackTime < BUBBLE_PRESSURE_MS;
        if (CobwebTactics.shouldBubble(bot, target, profile, settings, distance, pressured)) {
            CobwebTactics.beginBubble(bot, target, profile);
            CobwebTactics.tickBubble(bot, target, profile, server);
            return;
        }

        // --- disengage / retreat ----------------------------------------------------------
        if (state.disengageThreshold < 0) {
            // Half style, half operator setting: the configured retreat percentage sets the
            // centre of the population, the style and a personal roll spread bots around it.
            double mean = (profile.disengageHealth() + settings.getRetreatHealthPercent()) * 0.5;
            state.disengageThreshold = Rng.gaussian(profile.rng(), mean, 0.04,
                settings.getCriticalHealthPercent(), DISENGAGE_CEILING);
        }
        float healthFraction = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());

        // Leaving a fight is for bots that are actually in trouble. Three conditions, all
        // required: genuinely low, recently hurt, and holding something worth disengaging to
        // use. A bot on good health has no business backing off, whatever its style rolled.
        boolean recentlyHurt = System.currentTimeMillis() - state.lastAttackTime < 5000;
        boolean wantsOut = settings.isRetreatEnabled()
            && healthFraction <= state.disengageThreshold
            && healthFraction <= DISENGAGE_CEILING
            && recentlyHurt
            && scriptify.bots.ai.HealPlanner.hasAnyHeal(bot.getInventory());

        state.isRetreating = wantsOut;

        // One decision drives movement for the rest of this tick. Disengaging is an intent
        // like any other, so a hurt bot can choose to cover up and hold ground instead of
        // automatically reversing out of the fight.
        boolean canReachMelee = distance <= profile.reach + 1.5;
        state.intent = scriptify.bots.ai.Maneuver.decide(bot, target, profile, settings,
            distance, wantsOut, canReachMelee);

        if (state.intent == scriptify.bots.ai.Maneuver.Intent.FLEE) {
            flee(bot, target, state, profile, settings, distance, server);
            return;
        }

        // --- somewhere we cannot walk to: build ------------------------------------------
        // Up before across. A target that is higher and over a gap is reached by towering to its
        // level first and bridging over at that height - bridge first, and the bot arrives under
        // the far ledge with its head against the underside and no room left to climb.
        // Bridging still comes before a climb that is not needed: a bot that answers "there is a
        // hole in front of me" by building a pillar straight up is answering the wrong question.
        if (settings.isBridgingEnabled()) {
            boolean gap = Bridger.shouldBridgeGap(bot, target);
            boolean climb = Bridger.shouldEngage(bot, target,
                gap || BotNavigation.pathBlocked(botName));
            // The climb can end on this very call, and the gap test above was answered while it
            // was still on - so ask again. Otherwise one tick of ordinary combat slips between
            // the two, and one tick is all a bunny hop off the top of the new pillar needs.
            if (!climb && !gap) gap = Bridger.shouldBridgeGap(bot, target);
            if (climb && Bridger.tick(bot, target, profile, server)) {
                return;
            }
            if (gap && Bridger.tickGap(bot, target, profile, server)) {
                return;
            }
        }

        // --- mace: commit to a launch before anything picks a weapon ----------------------
        // This has to be decided here rather than inside the weapon selector. A selector that
        // answers "mace" on the ground and "sword" a fraction of a block later makes the bot
        // swap hands every few ticks and never actually launch; starting the combo instead
        // hands the bot over to a script that owns the hotbar until the smash lands.
        if (shouldStartMaceCombo(bot, target, state, settings, distance)) {
            state.maceComboPhase = 0;
            state.maceComboTicks = 0;
            tickMaceCombo(bot, state, profile, server);
            return;
        }

        // --- cobwebs -----------------------------------------------------------------------
        if (CobwebTactics.tickPlacement(bot, target, profile, server, settings)) return;
        if (CobwebTactics.counterSmash(bot, target, profile, settings)) {
            CobwebTactics.tickPlacement(bot, target, profile, server, settings);
            return;
        }
        if (CobwebTactics.shouldWeb(bot, target, profile, settings, distance)) {
            CobwebTactics.begin(bot, target, profile);
            return;
        }


        // --- weapon choice -------------------------------------------------------------------
        // Both of these walk the inventory or query nearby entities, so they run on a timer.
        // The chosen weapon is re-evaluated immediately when the range band changes, because
        // that is the case where holding the stale choice would actually matter.
        int nearbyEnemies = nearbyEnemies(bot, state, settings, serverTick);
        WeaponSelector.Choice choice = weaponChoice(bot, target, state, profile, settings,
            distance, nearbyEnemies, serverTick);
        state.weapon = choice.kind();

        // --- aim ------------------------------------------------------------------------------
        aim(bot, target, state, profile);

        // --- shield ---------------------------------------------------------------------------
        handleShield(bot, target, state, profile, settings, distance, server);

        // --- act -------------------------------------------------------------------------------
        switch (choice.kind()) {
            case BOW, CROSSBOW -> rangedCombat(bot, target, state, profile, settings, choice,
                distance, visible, server);
            case MACE -> maceCombat(bot, target, state, profile, settings, choice, distance,
                visible, server);
            default -> meleeCombat(bot, target, state, profile, settings, choice, distance,
                visible, nearbyEnemies, serverTick, server);
        }
    }

    private static void tickCooldowns(CombatState state) {
        if (state.attackCooldown > 0) state.attackCooldown--;
        if (state.shieldToggleCooldown > 0) state.shieldToggleCooldown--;
        if (state.shieldHoldTicks > 0) state.shieldHoldTicks--;
        if (state.shieldReactionTicks > 0) state.shieldReactionTicks--;
        if (state.techniqueCooldown > 0) state.techniqueCooldown--;
        if (state.windChargeCooldown > 0) state.windChargeCooldown--;
        if (state.pearlCooldown > 0) state.pearlCooldown--;
        if (state.feintTicks > 0) state.feintTicks--;
        if (state.aimErrorTicks > 0) state.aimErrorTicks--;
        if (state.shieldBroken && System.currentTimeMillis() - state.shieldBrokenTime > 5000) {
            state.shieldBroken = false;
        }
    }

    private static void trackDamage(ServerPlayer bot, CombatState state, BotSettings settings) {
        float health = bot.getHealth();
        if (health < state.lastHealth && settings.isRevengeEnabled()) {
            Entity attacker = bot.getLastHurtByMob();
            if (attacker != null && attacker != bot && attacker instanceof LivingEntity) {
                state.lastAttacker = attacker;
                state.lastAttackTime = System.currentTimeMillis();
            }
        }
        state.lastHealth = health;

        // Revenge memory decays; it is a hint to the target scorer, not a lock.
        if (state.lastAttacker != null
            && (state.lastAttacker.isRemoved() || !state.lastAttacker.isAlive()
                || System.currentTimeMillis() - state.lastAttackTime > 12_000)) {
            state.lastAttacker = null;
        }
    }

    private static void onNoTarget(ServerPlayer bot, CombatState state, MinecraftServer server) {
        if (state.isDrawingBow) stopBow(bot, state);
        releaseShield(bot, state, server);
        state.disengageThreshold = -1;
        // A bot on a route is walked by the route. Wandering as well had the two pulling at the
        // same bot on the same tick, which read as a stagger along the path.
        if (BotPath.isFollowing(bot.getName().getString())) return;
        if (rally(bot, state, server)) return;
        BotNavigation.idleWander(bot);
    }

    /** How far a bot at war goes looking for the fight when nobody is in sight. */
    private static final double RALLY_RANGE = 160.0;

    /** How often the rally point is brought up to date, in ticks. */
    private static final int RALLY_RESCAN = 40;

    /** Close enough to the rally point to stop marching and start looking. */
    private static final double RALLY_ARRIVED = 6.0;

    /**
     * Heads for the fight when there is no one in sight to fight.
     *
     * <p>Targets are picked from what is near, and a field of two hundred and fifty means most of
     * an army starts too far from the enemy to pick anyone - so it stood still, a hundred players
     * idle in a war. A player in that army knows where the fight is: the noise, the nametags, the
     * crowd moving. So a bot whose faction is at war walks toward the nearest enemy it knows of, and
     * target selection takes over once someone is actually in reach. Only between hostile factions:
     * a bot on its own does not go looking across the map for strangers.</p>
     */
    private static boolean rally(ServerPlayer bot, CombatState state, MinecraftServer server) {
        long now = bot.level().getGameTime();
        if (now >= state.rallyScanTick) {
            state.rallyScanTick = now + RALLY_RESCAN + Math.floorMod(bot.getId(), 20);
            state.rallyPoint = nearestFactionEnemy(bot, server);
        }
        if (state.rallyPoint == null) return false;
        Vec3 here = bot.position();
        if (here.distanceTo(state.rallyPoint) < RALLY_ARRIVED) {
            state.rallyPoint = null;
            return false;
        }
        BotTicker.faceAlongPath(bot, BotProfile.of(bot.getName().getString()), here, state.rallyPoint);
        BotNavigation.moveTowardPosition(bot, state.rallyPoint, 1.0);
        return true;
    }

    /** Where the nearest player of a hostile faction is, within rally range, or null. */
    private static Vec3 nearestFactionEnemy(ServerPlayer bot, MinecraftServer server) {
        String name = bot.getName().getString();
        if (!BotSettings.get().isFactionsEnabled() || BotFaction.getFaction(name) == null) return null;
        Vec3 best = null;
        double bestDist = RALLY_RANGE * RALLY_RANGE;
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == bot || !other.isAlive() || other.level() != bot.level()
                || other.isSpectator() || other.isCreative()) continue;
            double d = other.distanceToSqr(bot);
            if (d >= bestDist) continue;
            if (!BotFaction.areEnemies(name, other.getName().getString())) continue;
            bestDist = d;
            best = other.position();
        }
        return best;
    }

    // ===================== aiming =====================

    private static void aim(ServerPlayer bot, Entity target, CombatState state, BotProfile profile) {
        AimController.lookAt(bot, target, profile);
        // A lingering aim error from a "mistake" roll is applied on top of the smooth aim,
        // rather than the original's instant 60-degree yaw teleport.
        if (state.aimErrorTicks > 0) {
            bot.setYRot(bot.getYRot() + state.aimError);
            bot.setYHeadRot(bot.getYRot());
        }
    }

    // ===================== melee =====================

    private static void meleeCombat(ServerPlayer bot, Entity target, CombatState state,
                                    BotProfile profile, BotSettings settings,
                                    WeaponSelector.Choice choice, double distance,
                                    boolean visible, int nearbyEnemies, long serverTick,
                                    MinecraftServer server) {
        String botName = bot.getName().getString();

        if (state.isDrawingBow) stopBow(bot, state);
        if (choice.valid()) Inv.equip(bot.getInventory(), choice.slot(), 0);

        double reach = profile.reach;
        // A spear reaches further than an arm, and its holder jabs from out there rather than walking
        // in to sword range - that is the whole point of carrying one.
        var held = bot.getMainHandItem();
        boolean spear = WeaponSelector.isSpear(held.getItem());
        if (spear) reach = Math.max(reach, WeaponSelector.reach(held) - 0.4);
        moveForCombat(bot, target, state, profile, settings, distance, serverTick);

        // Who hangs back is decided by the bot's role in the fight, not by a separate queue.
        // A reserve holds its ground and a screening bot is on its way to an ally; neither
        // should be swinging at a target it is not committed to.
        if (state.intent == scriptify.bots.ai.Maneuver.Intent.HOLD
            || state.intent == scriptify.bots.ai.Maneuver.Intent.PEEL) {
            // Eager hands at the back of a crowd: now and then a swing at the man just out of reach,
            // which lands on whoever is standing in between. Players in a press hit their own side
            // this way all the time; with friendly fire on it hurts, with it off it is a wasted swing.
            if (state.intent == scriptify.bots.ai.Maneuver.Intent.HOLD && visible
                && distance <= reach + 2.0 && state.attackCooldown <= 0
                && bot.getAttackStrengthScale(0.5f) > 0.9f
                && profile.tech(0.01 + (1.0 - profile.skill()) * 0.02)) {
                releaseShield(bot, state, server);
                BotActions.attack(bot, server, target);
                state.attackCooldown = attackInterval(bot, state, profile, settings, target);
            }
            return;
        }

        // Bait: a turtle sometimes drops its shield right before the enemy commits, to make
        // them swing into a counter instead of a block.
        if (state.feintTicks > 0) return;

        // In reach by either measure. Feet to feet is the bot's own sense of distance and what
        // its spacing is built on, but it counts a height difference in full: stood a block below
        // somebody on a pillar, eyes level with their chest and two blocks from them, it read as
        // out of reach and the bot stood there with its sword out. The server measures from the
        // eyes to the hitbox, so that decides it whenever the two disagree.
        //
        // And when the bot cannot step any closer - held back at an edge, or looking up at
        // someone on a ledge - it swings at whatever range the server allows, rather than
        // waiting for a gap it will never get to close.
        double gapToTarget = reachGap(bot, target);
        boolean cannotClose = BotNavigation.getState(botName).edgeHeld
            || target.getY() - bot.getY() > 0.6;
        boolean inReach = distance <= reach
            || gapToTarget <= Math.max(reach - 0.3, SHORT_REACH_FLOOR)
            || (spear && gapToTarget <= WeaponSelector.reach(held) - 0.3)
            || (cannotClose && gapToTarget <= ATTACK_REACH - 0.2);
        if (!visible || !inReach) return;
        if (state.attackCooldown > 0) return;

        // 1.9+ combat: hitting before the cooldown refills throws away most of the damage.
        // How strictly a bot respects that is a style/skill trait, not a constant.
        float required = (float) profile.cooldownDiscipline();
        if (bot.getAttackStrengthScale(0.5f) < required) return;

        // Shield break: switch to an axe to pop a blocking target.
        if (settings.isShieldBreakEnabled() && target instanceof Player p && p.isBlocking()) {
            int axeSlot = Inv.best(bot.getInventory(),
                s -> WeaponSelector.isAxe(s.getItem()) ? WeaponSelector.damage(s) : 0);
            if (axeSlot >= 0 && profile.tech(settings.getShieldBreakChance() / 100.0)) {
                Inv.equip(bot.getInventory(), axeSlot, 0);
                strike(bot, target, state, profile, settings, server);
                state.shieldBroken = true;
                state.shieldBrokenTime = System.currentTimeMillis();
                return;
            }
        }

        // Jump-crit. Rolled per swing, so the bot does not crit in a perfect rhythm. A bot
        // diving the group's focus target goes for crits almost every swing - that is the
        // whole point of collapsing on someone.
        double critRate = state.intent == scriptify.bots.ai.Maneuver.Intent.CRIT_HUNT
            ? Math.max(0.8, profile.critRate())
            : profile.critRate();
        // Never in water: the game does not allow a critical hit from water, and a player standing
        // in it does not hop for one. A bot that did was leaping out of every puddle it fought in.
        if (settings.isCriticalsEnabled() && !bot.isInWater() && profile.tech(critRate)) {
            // Not on a bridge or a ledge: a crit hop takes the knockback of the hit it lands with,
            // and a player one block from the void does not jump.
            if (bot.onGround() && !state.critJumpPending
                && !BotNavigation.getState(botName).narrowFooting) {
                bot.jumpFromGround();
                state.critJumpPending = true;
                state.critFallTicks = 0;
                // Crouching through the hop. Some players do it, most of the time nobody does,
                // so it stays rare enough to read as a habit rather than a tic.
                if (profile.tech(CRIT_CROUCH_CHANCE)) {
                    scriptify.bots.ai.Crouch.hold(bot, server, Rng.range(profile.rng(), 6, 12));
                }
                return;
            }
            if (state.critJumpPending) {
                if (bot.getDeltaMovement().y < 0) {
                    state.critFallTicks++;
                    int window = Rng.range(profile.rng(), 2, Math.max(3, settings.getCriticalFallTicks()));
                    if (state.critFallTicks >= window) {
                        strike(bot, target, state, profile, settings, server);
                        state.critJumpPending = false;
                        state.critFallTicks = 0;
                    }
                    return;
                }
                // Still rising; wait rather than throwing away the crit.
                return;
            }
        }

        strike(bot, target, state, profile, settings, server);
    }

    /**
     * Executes one attack, plus the follow-up techniques that go with it.
     */
    private static void strike(ServerPlayer bot, Entity target, CombatState state,
                               BotProfile profile, BotSettings settings, MinecraftServer server) {
        String botName = bot.getName().getString();

        if (!BotUtils.canAct(bot)) {
            BotActions.swing(bot, server);
            return;
        }
        if (!TargetSelector.canStillAttack(bot, target, settings)) {
            return;
        }
        // Deliberate miss (arena handicap setting).
        if (settings.getMissChance() > 0 && profile.rng().nextInt(100) < settings.getMissChance()) {
            BotActions.swing(bot, server);
            state.attackCooldown = attackInterval(bot, state, profile, settings, target);
            return;
        }

        // Deliberate misaim: applied as a transient error the aim controller has to correct,
        // which looks like a real flick gone wrong instead of a head teleport.
        if (settings.getMistakeChance() > 0 && profile.rng().nextInt(100) < settings.getMistakeChance()) {
            state.aimError = (float) Rng.range(profile.rng(), -25.0, 25.0);
            state.aimErrorTicks = Rng.range(profile.rng(), 2, 6);
        }

        BotActions.attack(bot, server, target);
        state.attackCooldown = attackInterval(bot, state, profile, settings, target);

        // W-tap: cancel the sprint for a moment so the next hit carries full knockback.
        if (settings.isAdvancedTechniquesEnabled() && profile.tech(profile.wtapRate())) {
            BotNavigation.startWtap(botName);
        }
    }

    /**
     * Ticks until the next swing. Built from the weapon's real cooldown, the bot's personal
     * speed factor, a random jitter, and a squad offset so a group's hits do not land in
     * unison.
     */
    private static int attackInterval(ServerPlayer bot, CombatState state,
                                      BotProfile profile, BotSettings settings, Entity target) {
        ItemStack held = Inv.held(bot.getInventory());
        double weaponTicks = WeaponSelector.cooldownTicks(held.getItem());
        double base = Math.max(settings.getAttackCooldown(), weaponTicks);

        // Hurt bots swing slower - hesitation under pressure.
        float healthFraction = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());
        if (healthFraction < 0.4f) base *= 1.25;

        // Same interval for everyone, jittered per bot so a squad does not swing in unison.
        int ticks = Rng.gaussianTicks(profile.rng(), base, base * 0.15, 2, 60);

        if (settings.isSquadTacticsEnabled() && target != null) {
            ticks += SquadCoordinator.attackStagger(bot.getName().getString(), target, profile);
        }
        return ticks;
    }

    // ===================== positioning =====================

    /**
     * Executes whatever the maneuver layer decided, blended with squad spacing.
     *
     * <p>The previous version placed the bot on a slowly rotating ring around its target and
     * recomputed that position every tick. It kept bots apart, which was the point, but it
     * also meant they were permanently orbiting - a motion no player produces. Movement now
     * comes from a held intent, so a bot pushes, jockeys, resets or flanks for a stretch of
     * time and the shape of the fight emerges from those choices.</p>
     */
    private static void moveForCombat(ServerPlayer bot, Entity target, CombatState state,
                                      BotProfile profile, BotSettings settings, double distance,
                                      long serverTick) {
        float cooldown = bot.getAttackStrengthScale(0.5f);
        var order = scriptify.bots.ai.Maneuver.resolve(bot, target, profile, settings, distance,
            cooldown, serverTick);

        double spacing = settings.isSquadTacticsEnabled() ? settings.getSquadSpacing() : 0;
        BotNavigation.moveCombat(bot, order.goal(), order.speed(), profile,
            order.strafe(), spacing);
    }

    /**
     * Actually leaving the fight.
     *
     * <p>This is the only path in the mod that turns a bot's back on an opponent, and it is
     * deliberately committed: pick a direction with ground under it, sprint, and if there is
     * a pearl or a wind charge, use it. The old behaviour - face away and walk backwards
     * whenever health dipped - was the thing that read least like a player, because reversing
     * out of a duel is something no human does.</p>
     */
    private static void flee(ServerPlayer bot, Entity target, CombatState state,
                             BotProfile profile, BotSettings settings, double distance,
                             MinecraftServer server) {
        if (state.isDrawingBow) stopBow(bot, state);

        // Cover the exit: a web behind you buys more distance than running ever will.
        if (distance < 6.0 && CobwebTactics.shouldWeb(bot, target, profile, settings, distance)) {
            CobwebTactics.begin(bot, target, profile);
            return;
        }

        float cooldown = bot.getAttackStrengthScale(0.5f);
        var order = scriptify.bots.ai.Maneuver.resolve(bot, target, profile, settings, distance,
            cooldown, 0);

        // Committed run: look where you are going, not over your shoulder.
        AimController.lookAtPos(bot, order.goal(), profile, false);
        BotNavigation.moveCombat(bot, order.goal(), order.speed(), profile, 0.1,
            settings.isSquadTacticsEnabled() ? settings.getSquadSpacing() : 0);

        // A gap-closer used in reverse is the fastest way out of a bad fight.
        if (distance < 5.0 && state.pearlCooldown <= 0 && profile.tech(0.4)) {
            int pearl = Inv.findItem(bot.getInventory(), Items.ENDER_PEARL);
            if (pearl >= 0) {
                Inv.equip(bot.getInventory(), pearl, 5);
                AimController.lookPitch(bot, (float) Rng.range(profile.rng(), -25.0, -5.0), profile);
                BotActions.useOnce(bot, server);
                state.pearlCooldown = Rng.range(profile.rng(), 120, 260);
            }
        }
    }

    /**
     * Sat in its own cocoon, healing.
     *
     * <p>No movement - it is in a web, and struggling against one is not what anybody does once they
     * have chosen to be in it. It keeps its eyes on whoever is outside and hits back at anyone who
     * reaches in, with a sword rather than with whatever it was holding to place the webs.</p>
     */
    private static void shelter(ServerPlayer bot, Entity target, CombatState state,
                                BotProfile profile, BotSettings settings, double distance,
                                boolean visible, MinecraftServer server) {
        if (state.isDrawingBow) stopBow(bot, state);
        releaseShield(bot, state, server);
        aim(bot, target, state, profile);

        if (!visible || distance > profile.reach || state.attackCooldown > 0) return;
        var inv = bot.getInventory();
        if (!WeaponSelector.isMeleeWeapon(Inv.held(inv))) {
            // Swapping resets the swing, so the hit waits for the next ticks rather than landing
            // as a weak one now.
            int melee = WeaponSelector.bestMeleeSlot(inv, settings.isPreferSword() && !profile.mainsAxe);
            if (melee >= 0) Inv.equip(inv, melee, 0);
            return;
        }
        if (bot.getAttackStrengthScale(0.5f) < profile.cooldownDiscipline()) return;
        strike(bot, target, state, profile, settings, server);
    }

    // ===================== ranged =====================

    private static void rangedCombat(ServerPlayer bot, Entity target, CombatState state,
                                     BotProfile profile, BotSettings settings,
                                     WeaponSelector.Choice choice, double distance,
                                     boolean visible, MinecraftServer server) {
        Inv.equip(bot.getInventory(), choice.slot(), 1);
        ItemStack weapon = bot.getMainHandItem();

        // Kiting: hold the preferred band, strafing the whole time.
        double optimal = settings.getRangedOptimalRange();
        if (distance > settings.getRangedMaxRange()) {
            // Out of effective range: close the gap rather than firing into the sky.
            if (state.isDrawingBow) stopBow(bot, state);
            BotNavigation.moveCombat(bot, new Vec3(target.getX(), target.getY(), target.getZ()),
                settings.getMoveSpeed(), profile, 0.3, settings.getSquadSpacing());
            return;
        }
        if (distance < settings.getRangedMinRange()) {
            if (!settings.isRangedRetreatOnClose()) {
                // Configured to brawl instead of kite: drop the bow and take the melee.
                if (state.isDrawingBow) stopBow(bot, state);
                int melee = WeaponSelector.bestMeleeSlot(bot.getInventory(), settings.isPreferSword() && !profile.mainsAxe);
                if (melee >= 0) {
                    Inv.equip(bot.getInventory(), melee, 0);
                    return;
                }
            }
            BotNavigation.moveAway(bot, target, settings.getMoveSpeed());
        } else if (distance > optimal + 6) {
            BotNavigation.moveCombat(bot, new Vec3(target.getX(), target.getY(), target.getZ()),
                settings.getMoveSpeed(), profile, 0.4, settings.getSquadSpacing());
        } else if (settings.isRangedStrafeEnabled()) {
            BotNavigation.moveCombat(bot, new Vec3(target.getX(), target.getY(), target.getZ()),
                settings.getMoveSpeed() * 0.4, profile, 0.9, settings.getSquadSpacing());
        }

        if (!visible) {
            if (state.isDrawingBow) stopBow(bot, state);
            return;
        }

        if (weapon.getItem() instanceof CrossbowItem) {
            if (CrossbowItem.isCharged(weapon)) {
                aimWithLead(bot, target, profile, 3.0);
                bot.stopUsingItem();
                BotActions.useOnce(bot, server);
                state.attackCooldown = Rng.range(profile.rng(), 8, 20);
                state.isDrawingBow = false;
            } else if (!state.isDrawingBow) {
                bot.startUsingItem(InteractionHand.MAIN_HAND);
                state.isDrawingBow = true;
                state.bowDrawTicks = 0;
            } else {
                state.bowDrawTicks++;
                if (state.bowDrawTicks > 30) state.isDrawingBow = false;
            }
            return;
        }

        // Bow: draw for a personal, jittered amount of time rather than a fixed constant.
        if (!state.isDrawingBow) {
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            state.isDrawingBow = true;
            state.bowDrawTicks = 0;
            int base = Math.max(6, settings.getBowMinDrawTime());
            state.bowReleaseAt = Rng.gaussianTicks(profile.rng(), base, 3, 6, base + 12);
            return;
        }

        state.bowDrawTicks++;
        float drawProgress = Math.min(1.0f, state.bowDrawTicks / 20.0f);
        aimWithLead(bot, target, profile, Math.max(0.6, drawProgress * 3.0));

        if (state.bowDrawTicks >= state.bowReleaseAt) {
            bot.stopUsingItem();
            state.isDrawingBow = false;
            state.bowDrawTicks = 0;
            state.attackCooldown = Rng.range(profile.rng(), 4, 12);

            // Bow-swap: a sweaty archer follows the arrow in with a melee hit.
            if (settings.isAdvancedTechniquesEnabled() && distance < 6.0 && profile.tech(0.5)) {
                int melee = WeaponSelector.bestMeleeSlot(bot.getInventory(), settings.isPreferSword() && !profile.mainsAxe);
                if (melee >= 0) Inv.equip(bot.getInventory(), melee, 0);
            }
        }
    }

    /** Predictive aim for projectiles, with a skill-scaled error. */
    private static void aimWithLead(ServerPlayer bot, Entity target, BotProfile profile,
                                    double projectileSpeed) {
        if (!BotSettings.get().isArrowPredictionEnabled()) {
            AimController.lookAt(bot, target, profile);
            return;
        }
        Vec3 eye = bot.getEyePosition();
        Vec3 tp = target.getEyePosition();
        Vec3 vel = target.getDeltaMovement();

        double dist = eye.distanceTo(tp);
        double flight = Math.min(60, dist / Math.max(0.5, projectileSpeed * 0.95));

        // Weaker bots under- or over-lead; better bots get close.
        double leadError = Rng.gaussian(profile.rng(), 1.0, 0.15, 0.5, 1.5);

        double px = tp.x + vel.x * flight * leadError;
        double py = tp.y + vel.y * flight * leadError + 0.5 * 0.05 * flight * flight;
        double pz = tp.z + vel.z * flight * leadError;

        AimController.lookAtPos(bot, new Vec3(px, py, pz), profile, false);
    }

    private static void stopBow(ServerPlayer bot, CombatState state) {
        bot.stopUsingItem();
        state.isDrawingBow = false;
        state.bowDrawTicks = 0;
    }

    // ===================== mace =====================

    /**
     * Ground mace play. A mace only pays off with fall distance behind it, so the bot either
     * launches itself with a wind charge (the combo below) or takes the height it already
     * has. It never stands on flat ground trading mace swings like a sword - that was the
     * "wrong weapon" complaint.
     */
    private static void maceCombat(ServerPlayer bot, Entity target, CombatState state,
                                   BotProfile profile, BotSettings settings,
                                   WeaponSelector.Choice choice, double distance,
                                   boolean visible, MinecraftServer server) {
        Inv.equip(bot.getInventory(), choice.slot(), 0);

        if (!bot.onGround()) {
            // Already falling with a mace in hand - a drop off terrain rather than a launch.
            // Same rule as the combo: the height has to be real, or it is just a slow sword.
            BotNavigation.moveCombat(bot, new Vec3(target.getX(), target.getY(), target.getZ()),
                settings.getMoveSpeed(), profile, 0.2, 0);

            boolean inReach = reachGap(bot, target) <= ATTACK_REACH;
            if (visible && inReach && bot.getDeltaMovement().y < 0
                && state.attackCooldown <= 0
                && (bot.fallDistance >= 1.6f || aboutToLand(bot))) {
                strike(bot, target, state, profile, settings, server);
            }
            return;
        }

        // Starting a launch is decided in update(), before the weapon is chosen, so that the
        // choice cannot oscillate. By the time we are here there is no charge to spend.

        moveForCombat(bot, target, state, profile, settings, distance, 0);
        if (visible && distance <= profile.reach && state.attackCooldown <= 0) {
            strike(bot, target, state, profile, settings, server);
        }
    }

    /**
     * Whether to commit to a wind-charge launch right now.
     *
     * <p>Deliberately not a per-tick probability. The old code rolled a 70% chance every tick
     * the bot stood near its target holding a mace, which meant the launch started on a random
     * tick and, until it did, the bot kept re-picking its weapon - the visible symptom being a
     * bot that shuffles its hotbar for a second before doing anything. A bot that brought a
     * mace uses the mace: the only things that hold it back are the charge cooldown between
     * attempts and whether it has the pieces.</p>
     */
    private static boolean shouldStartMaceCombo(ServerPlayer bot, Entity target,
                                                CombatState state, BotSettings settings,
                                                double distance) {
        if (!settings.isMaceEnabled()) return false;
        if (state.maceComboPhase >= 0) return false;
        if (state.windChargeCooldown > 0) return false;
        if (!bot.onGround()) return false;
        if (target == null || !target.isAlive()) return false;

        // Room to come down on: launching into a low ceiling just wastes the charge.
        if (distance > settings.getMaceRange()) return false;
        if (!Vision.canSee(bot, target)) return false;

        var inv = bot.getInventory();
        return Inv.findItem(inv, Items.MACE) >= 0 && Inv.has(inv, Items.WIND_CHARGE);
    }

    /**
     * Wind-charge mace combo: look down, pop a wind charge to launch, switch to the mace on
     * the way up, track the target, and smash on the way down.
     *
     * <p>This replaces the removed elytra-mace routine. That one juggled the chestplate slot,
     * swapped an elytra in and out mid-air, and left items in a "pending restore" map that
     * leaked if the bot died at the wrong moment - it was also a twelve-phase state machine
     * with fixed tick counts, so every bot performed it identically.</p>
     */
    private static void tickMaceCombo(ServerPlayer bot, CombatState state,
                                      BotProfile profile, MinecraftServer server) {
        state.maceComboTicks++;
        Entity target = state.target;

        if (target == null || !target.isAlive() || state.maceComboTicks > 70) {
            state.maceComboPhase = -1;
            return;
        }

        switch (state.maceComboPhase) {
            case 0 -> {
                int chargeSlot = Inv.findItem(bot.getInventory(), Items.WIND_CHARGE);
                if (chargeSlot < 0) {
                    state.maceComboPhase = -1;
                    return;
                }
                Inv.equip(bot.getInventory(), chargeSlot, 2);
                AimController.lookPitch(bot, 90.0f, profile);
                if (bot.getXRot() > 82.0f) {
                    BotActions.useOnce(bot, server);
                    state.windChargeCooldown = Rng.range(profile.rng(), 60, 140);
                    state.maceComboPhase = 1;
                    state.maceComboTicks = 0;
                }
            }
            case 1 -> {
                int maceSlot = Inv.findItem(bot.getInventory(), Items.MACE);
                if (maceSlot >= 0) Inv.equip(bot.getInventory(), maceSlot, 0);
                // Precise tracking for the whole dive. The humanised aim is rate limited to a
                // realistic turn speed, and a bot dropping onto someone from directly above
                // needs to swing its head to near-vertical - which takes longer than the fall
                // lasts. It would arrive still looking at the horizon and swing at nothing,
                // which is precisely the "mace bot that never hits" problem.
                AimController.lookAtPrecise(bot, diveAimPoint(target), profile);
                BotNavigation.moveCombat(bot,
                    new Vec3(target.getX(), target.getY(), target.getZ()),
                    1.0, profile, 0.15, 0);
                if (bot.getDeltaMovement().y < 0) {
                    state.maceComboPhase = 2;

                }
                if (bot.onGround() && state.maceComboTicks > 6) {
                    state.maceComboPhase = -1;  // launch failed
                }
            }
            case 2 -> {
                // Falling. Stay over the target and pick the moment.
                AimController.lookAtPrecise(bot, diveAimPoint(target), profile);
                BotNavigation.moveCombat(bot,
                    new Vec3(target.getX(), target.getY(), target.getZ()),
                    1.0, profile, 0.15, 0);

                boolean visible = Vision.canSee(bot, target);

                // Reach has to be measured the way the game measures it: from the eyes to the
                // nearest point of the target's hitbox. Comparing feet-to-feet positions was
                // letting the bot swing from over four blocks up - outside vanilla's three
                // block interaction range - so the attack raycast hit nothing at all.
                double gap = reachGap(bot, target);
                boolean inReach = gap <= ATTACK_REACH;
                boolean smashLoaded = bot.fallDistance >= 1.6f;

                // Do not throw the swing while still turning: an unaimed attack command is a
                // raycast into empty space. Tolerance is generous because the whole point of a
                // dive is that the bot is nearly on top of the target, where yaw stops meaning
                // anything - onTarget drops the yaw test at steep pitch for the same reason.
                boolean aimed = AimController.onTarget(bot, target, 20.0);

                // Deliberately not gated on the attack cooldown. Changing hotbar slot resets
                // it, and this combo changes slots twice - to the wind charge, then to the
                // mace - so a 0.6-speed weapon cannot possibly recharge inside the dive. The
                // damage here comes from the fall bonus, which the cooldown does not scale,
                // so waiting for a full bar just means never swinging at all. That gate was
                // why the smash "missed" or landed a tick after touching the ground.
                if (visible && inReach && aimed) {
                    // A well-timed player waits for real fall distance, but takes the hit
                    // early rather than losing it entirely if the ground arrives first.
                    // A sloppy one swings the moment the target is close - the mistimed mace
                    // hit that does nothing, which is exactly what a Rookie should produce.
                    boolean commit = smashLoaded || aboutToLand(bot);
                    if (commit) {
                        BotActions.attack(bot, server, target);
                        state.attackCooldown = Rng.range(profile.rng(), 10, 22);
                        state.maceComboPhase = -1;
                        return;
                    }
                }

                if (bot.onGround()) {
                    state.maceComboPhase = -1;   // window missed
                }
            }
            default -> state.maceComboPhase = -1;
        }
    }

    // ===================== shield =====================

    /**
     * Shield handling, including a feint. The original had two systems fighting over the
     * shield (one in combat, one in the utility tick) which produced the shield flicker; all
     * of it now lives here.
     */
    private static void handleShield(ServerPlayer bot, Entity target, CombatState state,
                                     BotProfile profile, BotSettings settings, double distance,
                                     MinecraftServer server) {
        if (!settings.isAutoShieldEnabled()) {
            releaseShield(bot, state, server);
            return;
        }
        if (state.weapon == WeaponSelector.Kind.BOW || state.weapon == WeaponSelector.Kind.CROSSBOW) {
            releaseShield(bot, state, server);
            return;
        }

        float healthFraction = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());

        boolean threatened = false;
        if (distance <= settings.getMeleeRange() + 1.5 && target instanceof Player player) {
            // Swing detected, or a sprinting approach - both are readable tells a human uses.
            threatened = player.swinging || (player.isSprinting() && distance < 4.0);
        }
        // Closing the last few blocks with the shield already up, rather than waiting to be swung
        // at. This is the single most visible thing a crowd does in the reference footage - a wall
        // of raised shields walking in, lowered only to hit - and it is not something the reactive
        // rule above can produce, because it only ever answers a threat that has already arrived.
        //
        // Deliberately narrow: only while actually approaching, only within a couple of blocks of
        // being in range, and only when the bot is committed to closing. A bot that holds its
        // shield up while jockeying is a bot that never swings.
        boolean walkingIn = state.intent == scriptify.bots.ai.Maneuver.Intent.PUSH
            || state.intent == scriptify.bots.ai.Maneuver.Intent.FLANK;
        if (walkingIn && distance <= settings.getMeleeRange() + APPROACH_SHIELD_REACH
            && distance > settings.getMeleeRange()) {
            threatened = true;
        }
        // Waiting behind a full front line: out of reach to swing, well inside reach of a smash or a
        // sweep, so the shield stays up - the press of raised shields behind the men in contact.
        if (state.intent == scriptify.bots.ai.Maneuver.Intent.HOLD
            && distance <= settings.getMeleeRange() + QUEUE_SHIELD_REACH) {
            threatened = true;
        }
        if (settings.isShieldMace() && distance <= 6.0
            && target instanceof Player p2 && isWindingUpMace(p2)) {
            threatened = true;
        }
        boolean defensive = healthFraction < settings.getShieldHealthThreshold();

        boolean want = (threatened || defensive) && profile.tech(0.4 + profile.shieldBias() * 0.6);

        if (want && !state.shieldWanted) {
            // Reaction time before the shield actually comes up, capped by the configured
            // prediction window so operators can make bots more or less twitchy.
            state.shieldReactionTicks = Math.min(profile.reactionTicks(),
                settings.getShieldRaiseTicks());
        }
        state.shieldWanted = want;

        if (want && state.shieldReactionTicks <= 0) {
            // Feint: sometimes drop the shield just as the enemy commits, to punish instead.
            if (settings.isAdvancedTechniquesEnabled() && state.isUsingShield
                && state.techniqueCooldown <= 0 && profile.tech(profile.cooldownDiscipline() * 0.25)) {
                releaseShield(bot, state, server);
                state.feintTicks = Rng.range(profile.rng(), 3, 7);
                state.techniqueCooldown = Rng.range(profile.rng(), 40, 120);
                return;
            }
            requestShield(bot, state, profile, server, settings.getShieldHoldTicks());
        } else if (!want && state.isUsingShield && state.shieldHoldTicks <= 0
            && state.shieldToggleCooldown <= 0) {
            releaseShield(bot, state, server);
        }
    }

    private static void requestShield(ServerPlayer bot, CombatState state, BotProfile profile,
                                      MinecraftServer server, int holdTicks) {
        if (state.shieldBroken) return;

        var inv = bot.getInventory();
        boolean offhandShield = bot.getOffhandItem().getItem() == Items.SHIELD;
        if (!offhandShield) {
            int shieldSlot = Inv.findItem(inv, Items.SHIELD);
            if (shieldSlot < 0) return;
            // Never displace a totem: dying with a shield up is worse than not blocking.
            if (BotSettings.get().isTotemPriority()
                && inv.getItem(Inv.OFFHAND).getItem() == Items.TOTEM_OF_UNDYING) {
                return;
            }
            Inv.swapOffhand(inv, shieldSlot);
        }

        if (!state.isUsingShield) {
            BotActions.useContinuous(bot, server);
            state.isUsingShield = true;
            state.shieldToggleCooldown = Rng.range(profile.rng(), 3, 9);
        }
        state.shieldHoldTicks = Math.max(state.shieldHoldTicks,
            Rng.gaussianTicks(profile.rng(), holdTicks, holdTicks * 0.3, 4, holdTicks * 2));
    }

    private static void releaseShield(ServerPlayer bot, CombatState state, MinecraftServer server) {
        if (!state.isUsingShield) return;
        BotActions.stopUsing(bot, server);
        state.isUsingShield = false;
        state.shieldHoldTicks = 0;
    }

    /**
     * Vanilla's entity interaction range. An attack beyond this is a raycast into thin air,
     * however well aimed it is.
     */
    private static final double ATTACK_REACH = 2.9;

    /** How far behind the front line a waiting bot still keeps its shield up. */
    private static final double QUEUE_SHIELD_REACH = 5.0;

    /**
     * Eye-to-hitbox gap inside which any bot will swing, however short its own reach. A personal
     * reach can roll as low as one and a half blocks, and a bot that only swings that close stood
     * at arm's length from its target holding a sword.
     */
    private static final double SHORT_REACH_FLOOR = 2.2;

    /**
     * Distance from the bot's eyes to the closest point of the target's hitbox - the same
     * quantity the server's reach check uses. Straight-line entity distance is measured
     * between feet, which badly misjudges an attacker directly above its target: the height
     * counts in full even though the target's head is right there.
     */
    private static double reachGap(ServerPlayer bot, Entity target) {
        Vec3 eye = bot.getEyePosition();
        var box = target.getBoundingBox();
        double dx = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Where to aim during a mace dive: the target's upper body, not its eyes. */
    private static Vec3 diveAimPoint(Entity target) {
        var box = target.getBoundingBox();
        return new Vec3(target.getX(), box.minY + (box.maxY - box.minY) * 0.75, target.getZ());
    }

    /**
     * True when the bot will hit the ground within the next couple of ticks.
     *
     * <p>Landing resets fall distance, so a smash that has not been thrown by then is simply
     * lost. This is the cue to take the hit now rather than hold out for a better one.</p>
     */
    private static boolean aboutToLand(ServerPlayer bot) {
        double velocityY = bot.getDeltaMovement().y;
        if (velocityY >= 0) return false;

        var world = bot.level();
        double lookahead = Math.abs(velocityY) * 2.0 + 0.3;
        for (double drop = 0.1; drop <= lookahead; drop += 0.25) {
            var pos = net.minecraft.core.BlockPos.containing(
                bot.getX(), bot.getY() - drop, bot.getZ());
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** A player holding a mace in the air and falling is about to smash. */
    private static boolean isWindingUpMace(Player player) {
        return player.getMainHandItem().getItem() == Items.MACE
            && !player.onGround()
            && player.getDeltaMovement().y < -0.08;
    }

    // ===================== helpers =====================

    /**
     * Hard ceiling on the health fraction at which a bot may disengage.
     *
     * <p>Styles and personalities roll their own thresholds, and a cautious turtle could
     * previously roll high enough to start backing off while still nearly full - which reads
     * as cowardice rather than caution. Whatever the roll, nobody leaves a fight above this.</p>
     */
    private static final double DISENGAGE_CEILING = 0.45;

    /** Share of crit hops the bot crouches through. */
    private static final double CRIT_CROUCH_CHANCE = 0.06;

    /**
     * How recently a hit has to have landed for webbing up to be an answer to it, in milliseconds.
     * Low health on its own is a reason to heal, not to seal yourself in.
     */
    private static final long BUBBLE_PRESSURE_MS = 2500;

    /**
     * How far outside melee range a bot walks in behind its shield.
     *
     * <p>Four blocks - about a second of approach. Long enough to read as covering up on the way
     * in, short enough that the shield is down again before the bot is in a position to swing.
     */
    private static final double APPROACH_SHIELD_REACH = 4.0;

    private static final int WEAPON_SCAN_INTERVAL = 6;
    private static final int NEARBY_SCAN_INTERVAL = 20;

    private static WeaponSelector.Choice weaponChoice(ServerPlayer bot, Entity target,
                                                      CombatState state, BotProfile profile,
                                                      BotSettings settings, double distance,
                                                      int nearbyEnemies, long serverTick) {
        boolean stale = serverTick - state.weaponScanTick >= WEAPON_SCAN_INTERVAL
            || !state.cachedWeapon.valid()
            // A cached slot index goes stale the moment anything reorganises the inventory,
            // and the equipment pass does exactly that on its own schedule. Holding the index
            // for six ticks regardless meant a bot could equip - and swing - an empty slot.
            || !WeaponSelector.matches(state.cachedWeapon.kind(),
                bot.getInventory().getItem(state.cachedWeapon.slot()));
        if (!stale) {
            // Crossing in or out of melee range always forces a fresh decision.
            boolean inMelee = distance <= profile.reach + 1.0;
            boolean holdingRanged = state.cachedWeapon.kind() == WeaponSelector.Kind.BOW
                || state.cachedWeapon.kind() == WeaponSelector.Kind.CROSSBOW;
            stale = inMelee == holdingRanged;
        }
        if (stale) {
            state.cachedWeapon = WeaponSelector.choose(bot, target, distance, profile,
                settings, nearbyEnemies);
            state.weaponScanTick = serverTick;
        }
        return state.cachedWeapon;
    }

    private static int nearbyEnemies(ServerPlayer bot, CombatState state,
                                     BotSettings settings, long serverTick) {
        if (serverTick - state.nearbyScanTick >= NEARBY_SCAN_INTERVAL) {
            state.cachedNearbyEnemies = countNearbyEnemies(bot, settings);
            state.nearbyScanTick = serverTick;
        }
        return state.cachedNearbyEnemies;
    }

    private static int countNearbyEnemies(ServerPlayer bot, BotSettings settings) {
        // Shared index, not a per-bot world query - see BotIndex.
        java.util.List<Entity> near = NEARBY_SCRATCH.get();
        scriptify.bots.ai.BotIndex.collectNear(bot, 4.5, near);
        if (settings.isTargetHostileMobs()) {
            var mobBox = bot.getBoundingBox().inflate(4.5);
            for (Entity e : bot.level().getEntities(bot, mobBox)) {
                if (e instanceof net.minecraft.world.entity.Mob) near.add(e);
            }
        }
        int count = 0;
        for (Entity e : near) {
            if (TargetSelector.isAttackable(bot, e, settings)) count++;
        }
        return count;
    }

    /** Reused buffer; this runs per bot on a timer and should not allocate. */
    private static final ThreadLocal<java.util.List<Entity>> NEARBY_SCRATCH =
        ThreadLocal.withInitial(() -> new java.util.ArrayList<>(16));

    public static void clear() {
        combatStates.clear();
        CobwebTactics.clear();
        Bridger.clear();
        scriptify.bots.ai.LoadGovernor.clear();
        scriptify.bots.ai.PitEscape.clear();
        scriptify.bots.ai.WaterClutch.clear();
    }
}
