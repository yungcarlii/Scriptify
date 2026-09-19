package scriptify.bots.scenario;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.Rng;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pacing a cell.
 *
 * <p>The bot does not need to be told where the walls are - it works them out. A flood fill
 * from where it stands, bounded by a block budget, gives the set of floor tiles it can actually
 * reach. That is the cell, whatever shape it happens to be: a 3x3 box, a corridor, a fenced
 * pen, the inside of a building.</p>
 *
 * <p>Then it walks between those tiles with no destination that means anything, because that is
 * what someone with nowhere to go looks like. Occasionally it stops and faces a wall.</p>
 *
 * <p>The map is computed once per bot and cached. Flood filling four hundred blocks every tick
 * for every prisoner would be the most expensive thing in the mod.</p>
 */
public final class PrisonScenario {

    private PrisonScenario() {}

    /** Cells are re-mapped this often, in case the world changed around them. */
    private static final int REMAP_INTERVAL_TICKS = 2400;

    private static final class Cell {
        List<BlockPos> floor = new ArrayList<>();
        long mappedAt;
        /**
         * Whether this one cannot sit still.
         *
         * <p>Decided once and kept, because it is a temperament rather than a mood - a bot that
         * is restless for ten seconds and then calm is just a bot with a bug. Re-mapping the
         * cell must not re-roll it, which is why it lives outside the floor list's lifetime.</p>
         */
        Boolean restless;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Cell> cells(ScenarioInstance instance) {
        if (!(instance.runtime instanceof Map)) instance.runtime = new HashMap<String, Cell>();
        return (Map<String, Cell>) instance.runtime;
    }

    public static void tick(MinecraftServer server, Level world,
                            ScenarioInstance instance, long serverTick) {
        Map<String, Cell> cells = cells(instance);

        for (String name : instance.bots) {
            ServerPlayer bot = server.getPlayerList().getPlayer(name);
            if (bot == null || !bot.isAlive()) continue;

            BotProfile profile = BotProfile.of(name);
            String key = scriptify.bots.utils.Names.key(name);

            Cell cell = cells.get(key);
            if (cell == null || serverTick - cell.mappedAt > REMAP_INTERVAL_TICKS) {
                Boolean keptTemperament = cell == null ? null : cell.restless;
                cell = mapCell(world, bot, instance.settings.cellScanLimit);
                cell.mappedAt = serverTick;
                cell.restless = keptTemperament != null ? keptTemperament
                    : Rng.chance(profile.rng(), instance.settings.restlessShare);
                cells.put(key, cell);
            }

            if (cell.floor.isEmpty()) {
                Roamer.halt(bot);
                Roamer.idleLook(bot, profile, instance.roamer(name));
                continue;
            }

            if (Boolean.TRUE.equals(cell.restless)) {
                prowl(bot, profile, instance, cell, server);
            } else {
                pace(bot, profile, instance, cell);
            }
        }
    }

    private static void pace(ServerPlayer bot, BotProfile profile,
                             ScenarioInstance instance, Cell cell) {
        Roamer.State roam = instance.roamer(bot.getName().getString());

        // Stopping to stare at nothing is half of what pacing looks like.
        if (roam.pauseTicks <= 0
            && Rng.chance(profile.rng(), instance.settings.stareChance / 20.0)) {
            roam.pauseTicks = Rng.range(profile.rng(), 30, 110);
        }

        if (roam.goal == null) {
            BlockPos spot = cell.floor.get(profile.rng().nextInt(cell.floor.size()));
            roam.setGoal(new Vec3(spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5));
        }

        Roamer.Step step = Roamer.step(bot, profile, roam, instance.settings.speed, 0, 0, 0);
        if (step == Roamer.Step.ARRIVED || step == Roamer.Step.GIVE_UP) {
            roam.setGoal(null);
        } else if (step == Roamer.Step.PAUSED) {
            // Facing the wall rather than the middle of the room reads as confinement.
            faceNearestWall(bot, profile);
        }
    }

    /**
     * The restless one: never stops, crosses the cell end to end, hops the whole way.
     *
     * <p>Same cell, same map, opposite temperament. It picks the furthest floor tile it can
     * rather than a random one, so it is always crossing the space instead of shuffling within
     * it, never pauses, and bunny hops - which in a confined space reads as someone with far
     * too much energy and nowhere to put it.</p>
     */
    private static void prowl(ServerPlayer bot, BotProfile profile,
                              ScenarioInstance instance, Cell cell,
                              net.minecraft.server.MinecraftServer server) {
        Roamer.State roam = instance.roamer(bot.getName().getString());

        if (roam.goal == null) {
            BlockPos spot = furthestFrom(bot, cell, profile);
            roam.setGoal(new Vec3(spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5));
        }

        // No pause chance at all: this one does not stop.
        Roamer.Step step = Roamer.step(bot, profile, roam,
            Math.max(instance.settings.speed, 1.0), 0, 0, 0);

        Roamer.bhop(bot);

        if (step == Roamer.Step.ARRIVED || step == Roamer.Step.GIVE_UP) {
            roam.setGoal(null);
        }

        // The occasional hop on the spot, for when the crossing is short.
        if (!Gestures.busy(bot.getName().getString()) && Rng.chance(profile.rng(), 0.01)) {
            Gestures.start(bot, profile, Gestures.Kind.HOP, Rng.range(profile.rng(), 20, 45));
        }
        Gestures.apply(bot, server, profile);
    }

    /**
     * The far side of the cell.
     *
     * <p>Sampled rather than exhaustive - a cell can be hundreds of tiles and this runs every
     * time a leg finishes. A dozen candidates is plenty to land somewhere genuinely far.</p>
     */
    private static BlockPos furthestFrom(ServerPlayer bot, Cell cell, BotProfile profile) {
        BlockPos best = cell.floor.get(0);
        double bestDist = -1;
        for (int i = 0; i < 12; i++) {
            BlockPos candidate = cell.floor.get(profile.rng().nextInt(cell.floor.size()));
            double dist = candidate.distToCenterSqr(bot.getX(), bot.getY(), bot.getZ());
            if (dist > bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    /** Turns toward whichever side of the cell is closest. */
    private static void faceNearestWall(ServerPlayer bot, BotProfile profile) {
        Level world = bot.level();
        BlockPos at = bot.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (int step = 1; step <= 6; step++) {
                BlockPos probe = at.relative(dir, step);
                if (!world.getBlockState(probe).getCollisionShape(world, probe).isEmpty()) {
                    if (step < bestDistance) {
                        bestDistance = step;
                        best = probe;
                    }
                    break;
                }
            }
        }
        if (best == null) return;
        AimController.lookAtPos(bot,
            new Vec3(best.getX() + 0.5, bot.getEyePosition().y - 0.2, best.getZ() + 0.5),
            profile, false);
    }

    /**
     * Flood fills the standable floor reachable from the bot, up to a block budget.
     *
     * <p>Only horizontal steps and single-block rises are followed, which is what a person can
     * walk without jumping down somewhere they cannot get back from. If the fill hits the
     * budget the space is open rather than a cell, and whatever was mapped is used anyway -
     * the bot then paces a patch of the open world, which is the correct-looking failure.</p>
     */
    private static Cell mapCell(Level world, ServerPlayer bot, int budget) {
        Cell cell = new Cell();
        BlockPos start = bot.blockPosition().below();
        if (world.getBlockState(start).getCollisionShape(world, start).isEmpty()) {
            BlockPos ground = Roamer.groundAt(world, bot.getX(), bot.getY(), bot.getZ());
            if (ground == null) return cell;
            start = ground;
        }

        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty() && cell.floor.size() < budget) {
            BlockPos pos = queue.poll();
            cell.floor.add(pos);

            for (net.minecraft.core.Direction dir
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                for (int rise = 1; rise >= -1; rise--) {
                    BlockPos next = pos.relative(dir).above(rise);
                    if (seen.contains(next)) continue;
                    if (!world.isLoaded(next)) continue;
                    if (!standable(world, next)) continue;
                    seen.add(next);
                    queue.add(next);
                    break;
                }
            }
        }
        return cell;
    }

    /** A block you can stand on with room for a body above it. */
    private static boolean standable(Level world, BlockPos floor) {
        if (world.getBlockState(floor).getCollisionShape(world, floor).isEmpty()) return false;
        BlockPos feet = floor.above();
        BlockPos head = floor.above(2);
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
            && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
    }
}
