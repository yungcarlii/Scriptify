package scriptify.bots.ai;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A small, budgeted A* over block positions.
 *
 * <p>The original had no pathfinding at all: bots pushed themselves at the target with a
 * velocity nudge and jumped when a raycast-free heuristic said "block in front". That works
 * in an open arena and fails at anything else - inside corridors, around pillars, over
 * fences, or with a one-block gap in the floor.</p>
 *
 * <p>Design constraints, in order of importance:</p>
 * <ol>
 *   <li><b>Cost.</b> A search is bounded by a node budget (default ~900), and the number of
 *       searches that may start on one tick is bounded too - see {@link #tryReserve}. Both
 *       limits are needed: the node budget caps one search, and without the second a roster of
 *       path-followers simply starts a dozen of them on the same tick. There is no
 *       every-tick, every-bot pathing.</li>
 *   <li><b>Thread safety.</b> Level access must happen on the server thread, so the search
 *       runs synchronously inside the budget rather than being handed to a worker.</li>
 *   <li><b>Good enough.</b> If the goal is unreachable within budget the best node found so
 *       far is returned, so the bot still makes progress and the caller can fall back to
 *       steering or to bridging.</li>
 * </ol>
 *
 * <p>Movement model: walk on four horizontals, step up one block, drop up to three, and
 * treat ladders/vines/water as passable at a higher cost.</p>
 */
public final class Pathfinder {

    private Pathfinder() {}

    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int MAX_DROP = 3;

    private static final ThreadLocal<PriorityQueue<Node>> OPEN =
        ThreadLocal.withInitial(() -> new PriorityQueue<>(256));
    private static final ThreadLocal<List<BlockPos>> NEIGHBOURS =
        ThreadLocal.withInitial(() -> new ArrayList<>(8));
    private static final ThreadLocal<Object2DoubleMap<BlockPos>> BEST =
        ThreadLocal.withInitial(() -> {
            Object2DoubleOpenHashMap<BlockPos> map = new Object2DoubleOpenHashMap<>(512);
            map.defaultReturnValue(Double.POSITIVE_INFINITY);
            return map;
        });

    /**
     * Searches allowed to start on one tick, across the whole server.
     *
     * <p>This class has always said a search runs "for one bot at a time, on that bot's scheduled
     * tick", and nothing enforced it. The only limit was per bot - a cooldown of fifteen ticks and
     * up - so two hundred and fifty path-followers meant on the order of seventeen searches landing
     * on the same tick, each free to spend the full node budget. The budget bounds one search; this
     * bounds how many of them a tick has to carry.
     *
     * <p>Four is enough that a bot waits at most a few ticks for its turn, which it spends walking
     * the route it already has. A bot refused here keeps steering, exactly as it does when a search
     * returns nothing.
     */
    private static final int SEARCHES_PER_TICK = 4;

    private static int searchesThisTick;

    /** Called once a tick, before any bot thinks. */
    public static void beginTick() {
        searchesThisTick = 0;
    }

    /**
     * Claims one of this tick's searches.
     *
     * @return false when the tick's budget is spent and the caller should keep its current route
     */
    public static boolean tryReserve() {
        if (searchesThisTick >= SEARCHES_PER_TICK) {
            return false;
        }
        searchesThisTick++;
        return true;
    }

    public record Result(List<Vec3> waypoints, boolean complete) {
        public boolean isEmpty() { return waypoints.isEmpty(); }
    }

    public static final Result EMPTY = new Result(List.of(), false);

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final Node parent;
        final double g;
        final double f;

        Node(BlockPos pos, Node parent, double g, double f) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    /**
     * Searches for a walkable route.
     *
     * @param budget maximum nodes expanded; the search stops and returns its best effort
     * @param goalRadius how close to the goal counts as arrival
     */
    public static Result find(Level world, BlockPos start, BlockPos goal, int budget, double goalRadius) {
        if (start.equals(goal)) return EMPTY;

        // Reused across searches. A search is a burst of a few hundred expansions on the server
        // thread and then nothing, so the containers are cleared and kept rather than rebuilt: a
        // single search used to allocate a queue, a map, and one ArrayList per expansion, and the
        // whole point of the node budget is that this runs while other bots are waiting on the tick.
        PriorityQueue<Node> open = OPEN.get();
        Object2DoubleMap<BlockPos> best = BEST.get();
        List<BlockPos> neighbours = NEIGHBOURS.get();
        open.clear();
        best.clear();

        Node startNode = new Node(start, null, 0, heuristic(start, goal));
        open.add(startNode);
        best.put(start, 0.0);

        Node closest = startNode;
        double closestH = heuristic(start, goal);
        int expanded = 0;

        while (!open.isEmpty() && expanded < budget) {
            Node current = open.poll();
            expanded++;

            double h = heuristic(current.pos, goal);
            if (h < closestH) {
                closestH = h;
                closest = current;
            }
            if (h <= goalRadius) {
                return new Result(reconstruct(current), true);
            }

            neighbours(world, current.pos, neighbours);
            for (int i = 0; i < neighbours.size(); i++) {
                BlockPos next = neighbours.get(i);
                double stepCost = cost(world, current.pos, next);
                double g = current.g + stepCost;
                // defaultReturnValue is +inf, so an unvisited cell is always an improvement and the
                // "have we seen this" check costs no boxing and no second lookup.
                if (best.getDouble(next) <= g) continue;
                best.put(next, g);
                open.add(new Node(next, current, g, g + heuristic(next, goal)));
            }
        }

        // Out of budget: hand back the best partial route so the bot still moves.
        if (closest != startNode) {
            return new Result(reconstruct(closest), false);
        }
        return EMPTY;
    }

    private static List<Vec3> reconstruct(Node node) {
        List<Vec3> points = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) {
            points.add(new Vec3(n.pos.getX() + 0.5, n.pos.getY(), n.pos.getZ() + 0.5));
        }
        Collections.reverse(points);
        if (!points.isEmpty()) points.remove(0);   // the bot is already standing here
        return points;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = (a.getY() - b.getY()) * 1.3;   // vertical movement is expensive
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double cost(Level world, BlockPos from, BlockPos to) {
        double base = 1.0;
        int dy = to.getY() - from.getY();
        if (dy > 0) base += 0.6;                    // jumping costs momentum
        if (dy < 0) base += 0.15 * -dy;             // falling costs a little (fall damage risk)
        if (isLiquid(world, to)) base += 1.6;       // swimming is slow
        if (isClimbable(world, to)) base += 0.8;
        if (world.getBlockState(to).is(Blocks.COBWEB)) base += 12.0;  // never route through webs
        return base;
    }

    private static void neighbours(Level world, BlockPos pos, List<BlockPos> out) {
        out.clear();
        for (int[] dir : HORIZONTAL) {
            BlockPos flat = pos.offset(dir[0], 0, dir[1]);

            if (standable(world, flat)) {
                out.add(flat);
                continue;
            }
            // Step / jump up one.
            BlockPos up = flat.above();
            if (standable(world, up) && passable(world, pos.above(2))) {
                out.add(up);
                continue;
            }
            // Drop down, up to MAX_DROP.
            for (int drop = 1; drop <= MAX_DROP; drop++) {
                BlockPos down = flat.below(drop);
                if (standable(world, down)) {
                    out.add(down);
                    break;
                }
                if (!passable(world, down)) break;
            }
        }
        // Ladders and water allow vertical movement in place.
        if (isClimbable(world, pos) || isLiquid(world, pos)) {
            if (passable(world, pos.above())) out.add(pos.above());
            if (passable(world, pos.below())) out.add(pos.below());
        }
    }

    /** A cell a player-sized entity can stand in: two free blocks over solid footing. */
    private static boolean standable(Level world, BlockPos pos) {
        if (!passable(world, pos) || !passable(world, pos.above())) return false;
        if (isLiquid(world, pos) || isClimbable(world, pos)) return true;
        BlockState floor = world.getBlockState(pos.below());
        return !floor.isAir() && !floor.getCollisionShape(world, pos.below()).isEmpty();
    }

    private static boolean passable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;
        if (isDangerous(state)) return false;
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isDangerous(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
            || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
            || state.is(Blocks.CACTUS);
    }

    private static boolean isLiquid(Level world, BlockPos pos) {
        return world.getBlockState(pos).is(Blocks.WATER);
    }

    private static boolean isClimbable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.is(Blocks.LADDER) || state.is(Blocks.VINE)
            || state.is(Blocks.SCAFFOLDING) || state.is(Blocks.TWISTING_VINES)
            || state.is(Blocks.TWISTING_VINES_PLANT) || state.is(Blocks.WEEPING_VINES)
            || state.is(Blocks.WEEPING_VINES_PLANT);
    }
}
