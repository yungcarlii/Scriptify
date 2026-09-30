package scriptify.bots.ai;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.utils.Inv;

/**
 * Decides how and when a bot heals.
 *
 * <p>The old logic was three unrelated rules that fought each other: eat below 50% health,
 * throw a healing potion below the retreat threshold, and separately retreat below the same
 * threshold. Because eating locks you in place for 1.6 seconds, bots would routinely start
 * chewing a steak with an enemy mid-combo, get interrupted, and start again - which is what
 * "very dumb when healing" looks like from the outside. Nothing checked whether the heal
 * was even useful (they would eat a golden apple while absorption was still up) and nothing
 * considered whether the bot was safe enough to commit to it.</p>
 *
 * <p>The planner scores the situation instead:</p>
 * <ul>
 *   <li>instant heals (splash health, drinkable health) are used under pressure, because
 *       they cost a fraction of a second;</li>
 *   <li>golden apples are used when the bot can afford the animation, and never while their
 *       absorption or regeneration is still running;</li>
 *   <li>plain food is only eaten when disengaged, or when hunger is about to stop sprinting
 *       and healing entirely;</li>
 *   <li>if the bot needs a slow heal but is in danger it leaves first - pearling out if it
 *       can, disengaging on foot if it cannot - rather than eating in someone's face;</li>
 *   <li>unless it is <em>surrounded</em>, in which case leaving is not a thing that exists and
 *       the bot heals where it stands. See the note in {@link #plan}.</li>
 * </ul>
 *
 * <p>Every threshold is offset per bot, so a group does not all reach for a potion on the
 * same tick at exactly the same health.</p>
 */
public final class HealPlanner {

    private HealPlanner() {}

    public enum Action {
        NONE,
        /** Throw a splash/lingering healing potion at own feet - fastest heal under fire. */
        SPLASH_HEAL,
        /** Drink a health/regeneration potion. */
        DRINK_HEAL,
        /** Eat a golden or enchanted golden apple. */
        EAT_GAPPLE,
        /** Eat ordinary food (hunger, out-of-combat regen). */
        EAT_FOOD,
        /** Needs a slow heal but is not safe: back off, then re-evaluate. */
        DISENGAGE,
        /**
         * Throw an ender pearl out of the fight, and heal where it lands.
         *
         * <p>This is what separates somebody who plays from somebody who is losing. Backing away
         * on foot from a fight you are losing does not work - whoever is winning simply walks
         * after you, and the bot ends up eating a golden apple with a sword in its face, which is
         * what the old disengage looked like. A pearl buys twenty blocks instantly, and twenty
         * blocks is the difference between a heal that lands and one that gets interrupted.</p>
         *
         * <p>Nothing further needs arranging: once the pearl lands, the planner is asked again,
         * the danger test now says the bot is alone, and the slow heal it wanted all along is
         * simply the answer. Getting out and healing are the same decision, one tick apart.</p>
         */
        PEARL_OUT
    }

    public record Plan(Action action, int slot) {
        public static final Plan NOTHING = new Plan(Action.NONE, -1);
        public boolean isNothing() { return action == Action.NONE; }
    }

    /**
     * How many enemies within reach counts as surrounded.
     *
     * <p>Three, because the thing that matters is not the number, it is that there is no longer a
     * direction with nobody in it. Two people can both be in front of you; three rarely are.
     */
    private static final int SURROUNDED_ENEMIES = 3;

    public static Plan plan(ServerPlayer bot, BotProfile profile, Entity target,
                            double distance, boolean underPressure) {
        return plan(bot, profile, target, distance, underPressure, 0);
    }

    /**
     * @param nearbyEnemies how many attackable enemies are within a few blocks, which is what
     *                      separates a duel from being surrounded - see {@link #SURROUNDED_ENEMIES}
     */
    public static Plan plan(ServerPlayer bot, BotProfile profile, Entity target,
                            double distance, boolean underPressure, int nearbyEnemies) {
        return plan(bot, profile, target, distance, underPressure, nearbyEnemies, false);
    }

    /**
     * @param inCover the bot is sealed in its own cocoon, which it built precisely so it could heal
     *                where it stands - so it does, exactly as it would if surrounded. Pearling out
     *                of it or backing off would throw away the reason it webbed up.
     */
    public static Plan plan(ServerPlayer bot, BotProfile profile, Entity target,
                            double distance, boolean underPressure, int nearbyEnemies,
                            boolean inCover) {
        Inventory inv = bot.getInventory();

        float max = Math.max(1.0f, bot.getMaxHealth());
        float healthFraction = bot.getHealth() / max;
        int hunger = bot.getFoodData().getFoodLevel();

        boolean regenerating = hasEffect(bot, net.minecraft.world.effect.MobEffects.REGENERATION, 40);
        boolean absorbing = bot.getAbsorptionAmount() > 0.5f;
        boolean hasTarget = target != null && target.isAlive();

        // Personal thresholds spread around the configured values, so a squad does not all
        // reach for a potion on the same tick at exactly the same health.
        var settings = scriptify.bots.bot.BotSettings.get();
        double panic = settings.getRetreatHealthPercent() + profile.rng().nextGaussian() * 0.04;
        double hurt = Math.max(0.5, settings.getRetreatHealthPercent() + 0.28)
            + profile.rng().nextGaussian() * 0.05;
        panic = Math.max(settings.getCriticalHealthPercent(), Math.min(0.5, panic));
        hurt = Math.max(panic + 0.1, Math.min(0.85, hurt));

        boolean danger = hasTarget && underPressure && distance < 6.0;

        // Being outnumbered at arm's length is a different problem from being in danger, and every
        // rule below this point was written for the second one. In a duel "leave, then heal" is
        // right: there is a direction with nobody in it, and a pearl or a few blocks of ground buys
        // the second and a half an apple costs.
        //
        // Surrounded, none of that is true. There is nowhere to pearl to that is not also full of
        // people, backing off on foot just turns your back on half of them, and waiting for an
        // opening that is not coming is how a bot dies at full inventory. What people actually do -
        // and it is the clearest single thing in the reference footage, a player eating a golden
        // apple in the middle of a crowd with no attempt to get clear first - is heal *in place* and
        // spend the absorption on breaking out afterwards. The hits land either way; the difference
        // is whether you are healing while they do.
        boolean surrounded = nearbyEnemies >= SURROUNDED_ENEMIES || inCover;

        // --- Surrounded: heal where you stand ----------------------------------------------
        if (surrounded && healthFraction <= hurt) {
            // Instant first, as always - it is strictly better and costs nothing in time.
            int splash = findHealPotion(inv, true);
            if (splash >= 0 && hasFloorBelow(bot)) {
                return new Plan(Action.SPLASH_HEAL, splash);
            }
            int gapple = findGapple(inv);
            if (gapple >= 0 && !absorbing) {
                // Any apple, not just an enchanted one. The plain-apple caution below exists to
                // avoid eating in one person's face when stepping back was available; it was not.
                return new Plan(Action.EAT_GAPPLE, gapple);
            }
            int drink = findHealPotion(inv, false);
            if (drink >= 0) {
                return new Plan(Action.DRINK_HEAL, drink);
            }
            // Nothing to heal with. Fighting out is the only move left, and it is a better one
            // than turning around - so deliberately no DISENGAGE here.
        }

        // --- Emergency: something instant, right now ---------------------------------------
        if (healthFraction <= panic) {
            int splash = findHealPotion(inv, true);
            // A splash potion thrown at your own feet also heals an enemy standing on top of
            // you, so only use it when there is a little room - and only when there is ground
            // under the bot for it to break on.
            if (splash >= 0 && (!danger || distance > 2.2) && hasFloorBelow(bot)) {
                return new Plan(Action.SPLASH_HEAL, splash);
            }
            // Leave before healing, not after failing to. Everything below this point locks the
            // bot in place for a second and a half - long enough for the person swinging at it to
            // land four more hits and take the potion with them. A player in that position throws
            // the pearl first and drinks where it lands, and the planner is asked again the moment
            // it does: the danger test then says the bot is alone, and the slow heal it wanted all
            // along is simply the answer, one throw later.
            // Not when surrounded: a pearl thrown out of the middle of a crowd lands in more of
            // the crowd, and the block above has already decided healing here is the answer.
            if (danger && !surrounded) {
                int pearl = Inv.findItem(inv, Items.ENDER_PEARL);
                if (pearl >= 0) return new Plan(Action.PEARL_OUT, pearl);
            }

            int drink = findHealPotion(inv, false);
            // Without a pearl there is no getting out, and drinking under fire beats not drinking.
            if (drink >= 0) {
                return new Plan(Action.DRINK_HEAL, drink);
            }
            int gapple = findGapple(inv);
            if (gapple >= 0 && !absorbing) {
                // Enchanted golden apples are worth eating even under fire; plain ones are
                // only worth it with a moment of space.
                boolean notch = inv.getItem(gapple).getItem() == Items.ENCHANTED_GOLDEN_APPLE;
                if (notch || !danger) {
                    return new Plan(Action.EAT_GAPPLE, gapple);
                }
            }
            if (danger && !surrounded) {
                return new Plan(Action.DISENGAGE, -1);
            }
        }

        // --- Hurt but stable ---------------------------------------------------------------
        if (healthFraction <= hurt && !regenerating) {
            int gapple = findGapple(inv);
            if (gapple >= 0 && !absorbing && !danger) {
                return new Plan(Action.EAT_GAPPLE, gapple);
            }
            int splash = findHealPotion(inv, true);
            if (splash >= 0 && healthFraction < hurt - 0.15 && distance > 2.2
                && hasFloorBelow(bot)) {
                return new Plan(Action.SPLASH_HEAL, splash);
            }
            if (!danger && hunger < 20) {
                int food = findFood(inv);
                if (food >= 0) return new Plan(Action.EAT_FOOD, food);
            }
            if (danger && !surrounded && healthFraction < panic + 0.12) {
                return new Plan(Action.DISENGAGE, -1);
            }
        }

        // --- Hunger management -------------------------------------------------------------
        // Below 7 hunger the bot stops sprinting and stops regenerating, which matters more
        // than staying in the fight.
        int eatBelow = scriptify.bots.bot.BotSettings.get().getMinHungerToEat();
        boolean starving = hunger <= 6;
        if (starving || (!danger && hunger <= eatBelow)) {
            int food = findFood(inv);
            if (food >= 0) return new Plan(Action.EAT_FOOD, food);
        }

        return Plan.NOTHING;
    }

    /** How far below the bot a floor still counts for a potion thrown at its own feet. */
    private static final int SPLASH_FLOOR_SEARCH = 3;

    /**
     * Whether there is anything under the bot for a splash potion to break on.
     *
     * <p>A healing splash is thrown straight down at your own feet, so it only heals you if it
     * lands there. Thrown from the air over a drop it sails away and breaks somewhere below,
     * out of range - the bot spends the potion, stays hurt, and does it again with the next
     * one. Bots end up airborne often enough for this to matter: knocked back off an edge,
     * mid crit-hop, or partway through a mace dive.</p>
     */
    public static boolean hasFloorBelow(ServerPlayer bot) {
        if (bot.onGround()) return true;

        var world = bot.level();
        net.minecraft.core.BlockPos pos = bot.blockPosition();
        for (int depth = 0; depth <= SPLASH_FLOOR_SEARCH; depth++) {
            net.minecraft.core.BlockPos below = pos.below(depth);
            var state = world.getBlockState(below);
            // Water counts: the cloud still bursts at the surface and catches the bot.
            if (!state.getCollisionShape(world, below).isEmpty() || !state.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasEffect(ServerPlayer bot,
                                    net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect,
                                    int minDuration) {
        var instance = bot.getEffect(effect);
        return instance != null && instance.getDuration() > minDuration;
    }

    /** Healing potion slot; {@code splash} selects thrown vs drinkable. */
    public static int findHealPotion(Inventory inv, boolean splash) {
        return Inv.find(inv, stack -> {
            var item = stack.getItem();
            boolean thrown = item instanceof SplashPotionItem || item instanceof LingeringPotionItem;
            if (thrown != splash) return false;
            if (!thrown && !(item instanceof PotionItem)) return false;
            return isHealing(stack);
        });
    }

    public static boolean isHealing(ItemStack stack) {
        var contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        for (var effect : contents.getAllEffects()) {
            var type = effect.getEffect();
            if (type == MobEffects.INSTANT_HEALTH || type == MobEffects.REGENERATION) {
                return true;
            }
        }
        var potion = contents.potion();
        if (potion.isPresent()) {
            String id = potion.get().getRegisteredName().toLowerCase(java.util.Locale.ROOT);
            return id.contains("healing") || id.contains("regen");
        }
        return false;
    }

    public static int findGapple(Inventory inv) {
        int notch = Inv.findItem(inv, Items.ENCHANTED_GOLDEN_APPLE);
        if (notch >= 0) return notch;
        return Inv.findItem(inv, Items.GOLDEN_APPLE);
    }

    /** Best ordinary food, ignoring gapples (those are a combat resource, not a meal). */
    public static int findFood(Inventory inv) {
        return Inv.best(inv, stack -> {
            var item = stack.getItem();
            if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) return 0;
            if (item == Items.CHORUS_FRUIT || item == Items.POISONOUS_POTATO
                || item == Items.ROTTEN_FLESH || item == Items.SPIDER_EYE
                || item == Items.PUFFERFISH) {
                return 0;
            }
            var food = stack.get(DataComponents.FOOD);
            if (food == null) return 0;
            return food.nutrition() + food.saturation();
        });
    }

    public static boolean hasAnyHeal(Inventory inv) {
        return findFood(inv) >= 0 || findGapple(inv) >= 0
            || findHealPotion(inv, true) >= 0 || findHealPotion(inv, false) >= 0;
    }
}
