package scriptify.bots.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotActions;
import scriptify.bots.utils.Inv;
import scriptify.bots.utils.Names;

import java.util.HashMap;
import java.util.Map;

/**
 * The water bucket clutch: thrown into the air, land in water you put down yourself.
 *
 * <p>The commonest thing a big army does under a mace. Every launch sends a handful of players up,
 * and the ones carrying a bucket look straight down on the way back and click it at the last moment -
 * the patches of water dotted across a battlefield afterwards are those landings, and the ones who
 * miss the timing are the "doomed to fall" in chat. A bot that simply hit the floor every time was
 * the obvious tell in a crowd that otherwise behaved.</p>
 *
 * <p>Three steps, the way a player does it: on the way down, bucket in hand and eyes on the ground
 * under your feet; click the moment the click would land on it; once down, scoop the water back up.
 * The click is only sent when {@link ClickSim} says it will put the water where it is wanted, so a
 * bot falling too fast for its reach misses exactly as a player would.</p>
 */
public final class WaterClutch {

    private WaterClutch() {}

    /** A fall that would cost at least this much health is worth clutching. */
    private static final double WORTH_CLUTCHING = 5.0;

    /** How far below the feet the ground is looked for. */
    private static final double LOOK_DOWN = 40.0;

    /** Ticks after landing to get the water back before leaving it. */
    private static final int SCOOP_PATIENCE = 30;

    private static final class State {
        /** In the air and lined up for the clutch. */
        boolean falling = true;
        /** Where the water went; non-null while it is still to be picked back up. */
        BlockPos poured;
        int scoopTicks;
    }

    private static final Map<String, State> STATES = new HashMap<>();

    public static void remove(String botName) {
        STATES.remove(Names.key(botName));
    }

    public static void clear() {
        STATES.clear();
    }

    /**
     * Runs the clutch for one tick.
     *
     * @return true while it has the bot's head and hands, in which case nothing else may move them
     */
    public static boolean tick(ServerPlayer bot, BotProfile profile, MinecraftServer server) {
        // The hot path for a whole army: on the ground with nothing to scoop is one map miss. The name
        // is only built once the bot is in the air or already mid-clutch.
        if (STATES.isEmpty() && (bot.onGround() || bot.getDeltaMovement().y > -0.3)) return false;
        String key = Names.key(bot.getName().getString());
        State st = STATES.get(key);
        if (bot.onGround() && st == null) return false;

        boolean down = bot.onGround() || bot.isInWater();
        if (st != null && (down || !st.falling)) {
            st.falling = false;
            if (st.poured == null) {
                STATES.remove(key);
                return false;
            }
            return scoop(bot, key, st, profile, server);
        }
        if (bot.isFallFlying() || bot.getAbilities().flying) return false;
        if (st == null && bot.getDeltaMovement().y > -0.3) return false;
        // Water boils away in the Nether; nobody tries it there twice.
        if (bot.level().dimension() == net.minecraft.world.level.Level.NETHER) return false;

        BlockHitResult ground = groundBelow(bot);
        if (ground == null) return st != null;
        BlockPos landing = ground.getBlockPos();
        if (!bot.level().getFluidState(landing).isEmpty()) return st != null;    // landing in water anyway

        if (st == null) {
            double damage = bot.fallDistance + (bot.getY() - ground.getLocation().y) - 3.0;
            if (damage < WORTH_CLUTCHING) return false;
            if (Inv.findItem(bot.getInventory(), Items.WATER_BUCKET) < 0) return false;
            st = new State();
            STATES.put(key, st);
        }

        if (st.poured == null) {
            int bucket = Inv.findItem(bot.getInventory(), Items.WATER_BUCKET);
            if (bucket < 0) return true;
            Inv.equip(bot.getInventory(), bucket, 8);
            // Eyes on the middle of the block you are about to land on.
            AimController.lookAtPrecise(bot,
                new Vec3(landing.getX() + 0.5, landing.getY() + 1.0, landing.getZ() + 0.5), profile);
            BlockPos cell = ClickSim.waterCell(bot);
            if (cell != null && cell.equals(landing.above())) {
                BotActions.useOnce(bot, server);
                st.poured = cell;
            }
        }
        return true;
    }

    /** Down in the water: pick it back up before going anywhere. */
    private static boolean scoop(ServerPlayer bot, String key, State st, BotProfile profile,
                                 MinecraftServer server) {
        st.scoopTicks++;
        var fluid = bot.level().getFluidState(st.poured);
        int bucket = Inv.findItem(bot.getInventory(), Items.BUCKET);
        if (!fluid.isSource() || !fluid.is(FluidTags.WATER) || bucket < 0
            || st.scoopTicks > SCOOP_PATIENCE) {
            STATES.remove(key);
            return false;
        }
        Inv.equip(bot.getInventory(), bucket, 8);
        AimController.lookAtPrecise(bot, Vec3.atCenterOf(st.poured), profile);
        if (st.poured.equals(ClickSim.pickupCell(bot))) {
            BotActions.useOnce(bot, server);
            STATES.remove(key);
        }
        return true;
    }

    /** The first solid thing or fluid straight below the feet, or null when nothing is in reach. */
    private static BlockHitResult groundBelow(ServerPlayer bot) {
        Vec3 feet = bot.position();
        BlockHitResult hit = bot.level().clip(new ClipContext(feet, feet.add(0, -LOOK_DOWN, 0),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }
}
