package scriptify.bots.bot;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.WeaponSelector;
import scriptify.bots.utils.Inv;

/**
 * Idle equipment management: wear the best armour, hold a sensible weapon.
 *
 * <p>Two fixes over the original:</p>
 * <ul>
 *   <li>the armour scan ran over the whole inventory including the armour slots themselves,
 *       re-evaluating the piece already being worn on every pass;</li>
 *   <li>the weapon scan used a flat damage table in which a mace scored 6 - above an iron
 *       sword - so bots would idle holding a mace, which is close to the worst possible
 *       melee weapon without fall distance behind it. Weapon scoring now goes through
 *       {@link WeaponSelector}, which knows the difference.</li>
 * </ul>
 */
public class BotEquipment {

    public static void autoEquip(ServerPlayer bot) {
        if (!BotUtils.canAct(bot)) return;

        BotSettings settings = BotSettings.get();
        if (settings.isAutoEquipArmor()) {
            for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                equipBestForSlot(bot, slot, settings);
            }
        }
        if (settings.isAutoEquipWeapon()) {
            equipBestWeapon(bot, settings);
        }
    }

    // ===================== armour =====================

    private static void equipBestForSlot(ServerPlayer bot, EquipmentSlot slot,
                                         BotSettings settings) {
        var inventory = bot.getInventory();

        ItemStack current = bot.getItemBySlot(slot);
        double currentValue = armorValue(current, slot);

        int bestSlot = -1;
        double bestValue = currentValue;

        for (int i = 0; i < Inv.MAIN_END; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !isArmorForSlot(stack.getItem(), slot)) continue;
            double value = armorValue(stack, slot);
            if (value > bestValue) {
                bestValue = value;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            ItemStack better = inventory.getItem(bestSlot).copy();
            ItemStack worse = current.copy();
            bot.setItemSlot(slot, better);
            inventory.setItem(bestSlot, worse);
            current = better;
            currentValue = bestValue;
        }

        if (settings.isDropWorseArmor()) {
            for (int i = 0; i < Inv.MAIN_END; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || !isArmorForSlot(stack.getItem(), slot)) continue;
                if (armorValue(stack, slot) < currentValue) {
                    dropBackward(bot, stack);
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    private static boolean isArmorForSlot(Item item, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> item == Items.NETHERITE_HELMET || item == Items.DIAMOND_HELMET
                || item == Items.IRON_HELMET || item == Items.CHAINMAIL_HELMET
                || item == Items.GOLDEN_HELMET || item == Items.LEATHER_HELMET
                || item == Items.TURTLE_HELMET;
            case CHEST -> item == Items.NETHERITE_CHESTPLATE || item == Items.DIAMOND_CHESTPLATE
                || item == Items.IRON_CHESTPLATE || item == Items.CHAINMAIL_CHESTPLATE
                || item == Items.GOLDEN_CHESTPLATE || item == Items.LEATHER_CHESTPLATE
                || item == Items.ELYTRA;
            case LEGS -> item == Items.NETHERITE_LEGGINGS || item == Items.DIAMOND_LEGGINGS
                || item == Items.IRON_LEGGINGS || item == Items.CHAINMAIL_LEGGINGS
                || item == Items.GOLDEN_LEGGINGS || item == Items.LEATHER_LEGGINGS;
            case FEET -> item == Items.NETHERITE_BOOTS || item == Items.DIAMOND_BOOTS
                || item == Items.IRON_BOOTS || item == Items.CHAINMAIL_BOOTS
                || item == Items.GOLDEN_BOOTS || item == Items.LEATHER_BOOTS;
            default -> false;
        };
    }

    /** Material tier plus a bonus for protection enchantments and remaining durability. */
    private static double armorValue(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();

        double base;
        if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE
            || item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) base = 100;
        else if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE
            || item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) base = 80;
        else if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE
            || item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) base = 60;
        else if (item == Items.TURTLE_HELMET) base = 55;
        else if (item == Items.CHAINMAIL_HELMET || item == Items.CHAINMAIL_CHESTPLATE
            || item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS) base = 50;
        else if (item == Items.GOLDEN_HELMET || item == Items.GOLDEN_CHESTPLATE
            || item == Items.GOLDEN_LEGGINGS || item == Items.GOLDEN_BOOTS) base = 40;
        else if (item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE
            || item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS) base = 20;
        else if (item == Items.ELYTRA) base = 5;
        else return 0;

        base += Inv.enchantLevel(stack, "protection") * 6.0;

        // Nearly broken armour is worth less than intact armour one tier down.
        if (stack.getMaxDamage() > 0) {
            double durability = 1.0 - ((double) stack.getDamageValue() / stack.getMaxDamage());
            if (durability < 0.1) base *= 0.5;
        }
        return base;
    }

    // ===================== weapon =====================

    private static void equipBestWeapon(ServerPlayer bot, BotSettings settings) {
        var inventory = bot.getInventory();
        boolean preferSword = settings.isPreferSword()
            && !scriptify.bots.ai.BotProfile.of(bot.getName().getString()).mainsAxe;
        int best = WeaponSelector.bestMeleeSlot(inventory, preferSword);
        if (best >= 0) {
            best = Inv.equip(inventory, best, 0);
        }

        if (settings.isDropWorseWeapons() && best >= 0) {
            double bestScore = WeaponSelector.damage(inventory.getItem(best));
            for (int i = 0; i < Inv.MAIN_END; i++) {
                if (i == best) continue;
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                // Keep every weapon class - the situational selector uses them. Only drop
                // strictly inferior copies of the same class.
                if (!sameClass(stack, inventory.getItem(best))) continue;
                if (WeaponSelector.damage(stack) < bestScore) {
                    dropBackward(bot, stack);
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    private static boolean sameClass(ItemStack a, ItemStack b) {
        return (WeaponSelector.isSword(a.getItem()) && WeaponSelector.isSword(b.getItem()))
            || (WeaponSelector.isAxe(a.getItem()) && WeaponSelector.isAxe(b.getItem()));
    }

    /** Throws junk away behind the bot so it does not immediately walk over it again. */
    private static void dropBackward(ServerPlayer bot, ItemStack stack) {
        if (stack.isEmpty()) return;

        BotSettings settings = BotSettings.get();
        BotProfile profile = BotProfile.of(bot.getName().getString());
        double distance = settings.getDropDistance();

        float yaw = bot.getYRot() + 90 + profile.rng().nextFloat() * 180;
        ItemEntity dropped = bot.drop(stack.copy(), false, true);
        if (dropped != null) {
            double yawRad = Math.toRadians(yaw);
            double speed = 0.3 * distance;
            dropped.setDeltaMovement(-Math.sin(yawRad) * speed, 0.2, Math.cos(yawRad) * speed);
            dropped.setPickUpDelay(60);
        }
    }
}
