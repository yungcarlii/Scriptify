package scriptify.bots.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.bot.BotSettings;
import scriptify.bots.utils.Inv;

/**
 * Situation-aware weapon choice.
 *
 * <p>The original picked a weapon purely by a hard-coded damage table, and separately
 * switched to "mace mode" whenever a mace existed and the target was within six blocks.
 * That produced the exact complaint about wrong weapons: a mace swung from the ground does
 * a fraction of its damage (it scales with fall distance) and has a slow cooldown, so bots
 * were effectively fighting with a bad sword. Axes were only ever used to break shields,
 * never for their higher burst, and a bow was preferred over a sword at any range past the
 * minimum.</p>
 *
 * <p>Selection now asks what the weapon is *for*:</p>
 * <ul>
 *   <li>mace only when it can actually smash - airborne with fall distance, or with a wind
 *       charge in hand to launch first, or when the target is below and falling;</li>
 *   <li>axe when the target is shielding (disable) or when the bot's style favours burst
 *       and the cooldown allows a full-power hit;</li>
 *   <li>sword for sustained melee, especially against several enemies (sweep);</li>
 *   <li>trident when it is enchanted for it, or in water;</li>
 *   <li>bow/crossbow only outside melee, with ammunition, and with line of sight.</li>
 * </ul>
 */
public final class WeaponSelector {

    private WeaponSelector() {}

    public enum Kind { SWORD, AXE, MACE, TRIDENT, BOW, CROSSBOW, NONE }

    public record Choice(Kind kind, int slot) {
        public boolean valid() { return kind != Kind.NONE && slot >= 0; }
    }

    public static final Choice NONE = new Choice(Kind.NONE, -1);

    /** Base melee damage of an item, before enchantments. */
    public static double baseDamage(Item item) {
        if (item == Items.NETHERITE_AXE) return 10;
        if (item == Items.DIAMOND_AXE) return 9;
        if (item == Items.IRON_AXE) return 9;
        if (item == Items.STONE_AXE) return 9;
        if (item == Items.GOLDEN_AXE) return 7;
        if (item == Items.WOODEN_AXE) return 7;
        if (item == Items.NETHERITE_SWORD) return 8;
        if (item == Items.DIAMOND_SWORD) return 7;
        if (item == Items.IRON_SWORD) return 6;
        if (item == Items.STONE_SWORD) return 5;
        if (item == Items.GOLDEN_SWORD) return 4;
        if (item == Items.WOODEN_SWORD) return 4;
        if (item == Items.TRIDENT) return 9;
        if (item == Items.MACE) return 6;
        // Anything else - spears, and modded weapons - says what it does in its own attributes.
        double bonus = attributeBonus(item, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        return Double.isNaN(bonus) ? 0 : 1.0 + bonus;
    }

    /**
     * The flat bonus an item's default attributes add to one of the wielder's stats, or NaN.
     * A sword-like item carries its damage and attack speed here; plain items carry nothing.
     */
    private static double attributeBonus(Item item,
                                         net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var modifiers = item.components().get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) return Double.NaN;
        double total = 0;
        boolean found = false;
        for (var entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attribute)
                && entry.modifier().operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                total += entry.modifier().amount();
                found = true;
            }
        }
        return found ? total : Double.NaN;
    }

    /** A spear: a jab that runs on through everyone in a line, and a charge on the run. */
    public static boolean isSpear(Item item) {
        return item.components().has(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
    }

    /** How far a weapon reaches for a hit, or the ordinary three blocks for one with no range of its own. */
    public static double reach(ItemStack stack) {
        var range = stack.get(net.minecraft.core.component.DataComponents.ATTACK_RANGE);
        return range != null ? range.maxRange() : 3.0;
    }

    /** Ticks between full-power hits (vanilla attack speed). */
    public static double cooldownTicks(Item item) {
        if (item == Items.MACE) return 20.0 / 0.6;            // attack speed 0.6
        if (isAxe(item)) return 20.0;                          // attack speed 1.0
        if (isSword(item)) return 20.0 / 1.6;                  // attack speed 1.6
        if (item == Items.TRIDENT) return 20.0 / 1.1;
        double speed = attributeBonus(item, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
        if (!Double.isNaN(speed)) return 20.0 / Math.max(0.25, 4.0 + speed);
        return 20.0 / 4.0;
    }

    /** Damage including sharpness-family enchantments. */
    public static double damage(ItemStack stack) {
        double base = baseDamage(stack.getItem());
        if (base <= 0) return 0;
        int sharpness = Inv.enchantLevel(stack, "sharpness");
        if (sharpness > 0) base += 0.5 * sharpness + 0.5;
        int impaling = Inv.enchantLevel(stack, "impaling");
        if (impaling > 0) base += 0.5 * impaling;
        return base;
    }

    /** Sustained damage per second, used to compare weapon classes fairly. */
    public static double dps(ItemStack stack) {
        double d = damage(stack);
        if (d <= 0) return 0;
        return d / (cooldownTicks(stack.getItem()) / 20.0);
    }

    public static boolean isSword(Item item) {
        return item == Items.NETHERITE_SWORD || item == Items.DIAMOND_SWORD
            || item == Items.IRON_SWORD || item == Items.STONE_SWORD
            || item == Items.GOLDEN_SWORD || item == Items.WOODEN_SWORD;
    }

    public static boolean isAxe(Item item) {
        return item instanceof AxeItem;
    }

    public static boolean isMace(Item item) {
        return item == Items.MACE;
    }

    public static boolean isRanged(Item item) {
        return item instanceof BowItem || item instanceof CrossbowItem;
    }

    public static boolean isMeleeWeapon(ItemStack stack) {
        return baseDamage(stack.getItem()) > 0;
    }

    /**
     * Picks the weapon for right now.
     *
     * @param nearbyEnemies how many hostiles are within a few blocks (sweep value)
     */
    public static Choice choose(ServerPlayer bot, Entity target, double distance,
                                BotProfile profile, BotSettings settings, int nearbyEnemies) {
        Inventory inv = bot.getInventory();

        int swordSlot = Inv.best(inv, s -> isSword(s.getItem()) ? damage(s) : 0);
        int axeSlot = Inv.best(inv, s -> isAxe(s.getItem()) ? damage(s) : 0);
        int maceSlot = Inv.findItem(inv, Items.MACE);
        int tridentSlot = Inv.findItem(inv, Items.TRIDENT);
        int bowSlot = Inv.best(inv, s -> s.getItem() instanceof BowItem ? 1 : 0);
        int crossbowSlot = Inv.best(inv, s -> s.getItem() instanceof CrossbowItem ? 1 : 0);

        boolean hasAmmo = Inv.find(inv, s -> s.getItem() instanceof net.minecraft.world.item.ArrowItem) >= 0;
        boolean targetBlocking = target instanceof Player p && p.isBlocking();

        // --- Mace: only when it will actually hit hard -----------------------------------
        // Deliberately no "standing on the ground next to a wind charge" case. That used to
        // live here, and because it flipped on and off with a hard six block boundary the bot
        // swapped mace/sword every time the distance wobbled across it - the weapon shuffle.
        // Launching is a scripted combo now (BotCombat#tickMaceCombo) and it owns the bot for
        // its whole duration, so nothing else touches the hotbar while it runs.
        if (maceSlot >= 0 && settings.isMaceEnabled()) {
            // fallDistance only accumulates on the way down, so it already establishes that
            // the bot is falling. Also testing vertical velocity made the choice flicker:
            // velocity flips sign at the apex and on every knockback, so a bot bouncing around
            // after a smash swapped mace/sword several times a second.
            boolean falling = !bot.onGround() && bot.fallDistance > 1.2;
            boolean noAlternative = swordSlot < 0 && axeSlot < 0;
            if (falling || noAlternative) {
                return new Choice(Kind.MACE, maceSlot);
            }
        }

        // --- Ranged: outside melee only ---------------------------------------------------
        boolean rangedOk = settings.isRangedEnabled() && hasAmmo
            && distance > settings.getRangedMinRange()
            && Vision.canSee(bot, target);
        if (rangedOk) {
            if (crossbowSlot >= 0) return new Choice(Kind.CROSSBOW, crossbowSlot);
            if (bowSlot >= 0) return new Choice(Kind.BOW, bowSlot);
        }

        // --- Axe: shield break, or the bot mains one ---------------------------------------
        if (axeSlot >= 0) {
            // Swapping to an axe to break a shield is ordinary PvP and every bot does it.
            if (targetBlocking && settings.isShieldBreakEnabled()) {
                return new Choice(Kind.AXE, axeSlot);
            }
            // Otherwise the axe is a personal trait, not a damage calculation. Comparing raw
            // damage made every bot an axe main, because a matched-tier axe always wins that
            // comparison - it just loses the fight, because the sword swings 60% faster.
            if (swordSlot < 0 || profile.mainsAxe) {
                return new Choice(Kind.AXE, axeSlot);
            }
        }

        // --- Trident: water or riptide/impaling builds -------------------------------------
        if (tridentSlot >= 0) {
            ItemStack trident = inv.getItem(tridentSlot);
            boolean wet = bot.isInWater() || target.isInWater();
            if (wet && (Inv.hasEnchant(trident, "impaling") || swordSlot < 0)) {
                return new Choice(Kind.TRIDENT, tridentSlot);
            }
        }

        if (swordSlot >= 0) return new Choice(Kind.SWORD, swordSlot);
        if (axeSlot >= 0) return new Choice(Kind.AXE, axeSlot);
        if (maceSlot >= 0) return new Choice(Kind.MACE, maceSlot);
        if (tridentSlot >= 0) return new Choice(Kind.TRIDENT, tridentSlot);

        // Nothing better than fists; still report the best-scoring item so the bot holds
        // something sensible rather than a stack of dirt.
        int anySlot = Inv.best(inv, WeaponSelector::damage);
        return anySlot >= 0 ? new Choice(Kind.SWORD, anySlot) : NONE;
    }

    /**
     * Whether a stack is still the weapon a {@link Choice} was made for.
     *
     * <p>A Choice carries a raw slot index. Slots are not stable: equipping anything swaps
     * stacks between the hotbar and the main inventory, so a cached index can end up pointing
     * at a different item - or an empty slot - a few ticks later.</p>
     */
    public static boolean matches(Kind kind, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return switch (kind) {
            case SWORD -> isSword(item) || baseDamage(item) > 0;
            case AXE -> isAxe(item);
            case MACE -> isMace(item);
            case TRIDENT -> item == Items.TRIDENT;
            case BOW -> item instanceof BowItem;
            case CROSSBOW -> item instanceof CrossbowItem;
            case NONE -> false;
        };
    }

    /**
     * Best melee weapon regardless of situation - used for idle auto-equip.
     *
     * <p>The sword bonus is decisive rather than a nudge. A +2 tie-breaker was not enough to
     * beat a matched-tier axe, so bots idled with an axe out and then fought with it.</p>
     */
    public static int bestMeleeSlot(Inventory inv, boolean preferSword) {
        return Inv.best(inv, stack -> {
            double d = damage(stack);
            if (d <= 0) return 0;
            if (isMace(stack.getItem())) d *= 0.55;   // weak without a fall, do not idle-hold it
            if (preferSword && isSword(stack.getItem())) d += 100.0;
            return d;
        });
    }
}
