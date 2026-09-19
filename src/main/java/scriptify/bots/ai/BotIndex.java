package scriptify.bots.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One shared picture of where everybody is, rebuilt once a tick.
 *
 * <p>Every bot used to answer "who is near me?" by asking the world for the entities in a box
 * around itself. That box can be 128 blocks across, the query walks every chunk section it
 * overlaps, and with two hundred and fifty bots it ran two hundred and fifty times a sweep -
 * all of them scanning the same crowd, in the same arena, on the same tick, and each allocating
 * its own result list.</p>
 *
 * <p>This builds that list once. Bots are bucketed into a coarse grid, so a lookup touches the
 * handful of cells covering its range instead of the whole world. In an arena the crowd is
 * genuinely dense and the candidate count stays high - the saving is not in the number of
 * candidates but in doing the gathering once instead of N times.</p>
 */
public final class BotIndex {

    private BotIndex() {}

    /** Grid cell size. Big enough that a typical query touches few cells, small enough to cut. */
    private static final int CELL = 16;

    /**
     * Cells to tolerate before sweeping the empty ones.
     *
     * <p>A thousand is far more than any crowd occupies - two hundred and fifty bots standing one per
     * cell is a quarter of it - so on a fight this never fires, and the reuse it protects is never
     * paid for. It only trips when bots have covered real ground.
     */
    private static final int PRUNE_THRESHOLD = 1024;

    private static final Map<Long, List<ServerPlayer>> GRID = new HashMap<>();
    private static final List<ServerPlayer> ALL = new ArrayList<>();
    /**
     * The real people, separated out as the index is built.
     *
     * <p>Kept apart because the question "is anyone actually watching this bot?" is asked of
     * every bot every second by the load governor, and the answer is a handful of comparisons
     * against this list rather than a scan of a crowd that is almost entirely bots.</p>
     */
    private static final List<ServerPlayer> HUMANS = new ArrayList<>();
    private static long builtTick = -1;

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * Rebuilds the index. Called once per tick from the ticker, before any bot thinks.
     *
     * <p>Cheap: one pass over the online players, reusing the same lists so a full rebuild every
     * tick does not churn the heap.</p>
     */
    public static void rebuild(MinecraftServer server, long serverTick) {
        if (builtTick == serverTick) return;
        builtTick = serverTick;

        // Buckets are emptied and kept rather than dropped, so a rebuild every tick reuses the same
        // lists instead of churning the heap. That trade only holds while the number of cells stays
        // near the number in use: a cell is created wherever a bot has ever stood and nothing removed
        // them, so a roster that walks a few thousand blocks leaves a map of empty lists behind it and
        // every later rebuild pays to iterate them. Prune when the map has grown well past what is
        // occupied, which on a stationary arena never happens at all.
        if (GRID.size() > PRUNE_THRESHOLD) {
            GRID.values().removeIf(List::isEmpty);
        }
        for (List<ServerPlayer> bucket : GRID.values()) {
            bucket.clear();
        }
        ALL.clear();
        HUMANS.clear();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive()) continue;
            ALL.add(player);
            if (!scriptify.bots.bot.BotManager.isBot(player.getName().getString())) {
                HUMANS.add(player);
            }
            int cx = (int) Math.floor(player.getX()) >> 4;
            int cz = (int) Math.floor(player.getZ()) >> 4;
            GRID.computeIfAbsent(cellKey(cx, cz), k -> new ArrayList<>(8)).add(player);
        }
    }

    /**
     * Every player within {@code radius} of the given one, excluding itself.
     *
     * <p>Returns players only - which is all the targeting code has ever cared about, since bots
     * and the people they fight are both players. Mobs are not candidates.</p>
     */
    public static void collectNear(ServerPlayer origin, double radius,
                                   List<Entity> out) {
        out.clear();
        Level world = origin.level();
        int cells = Math.max(1, (int) Math.ceil(radius / CELL));
        int ox = (int) Math.floor(origin.getX()) >> 4;
        int oz = (int) Math.floor(origin.getZ()) >> 4;
        double radiusSq = radius * radius;

        for (int dx = -cells; dx <= cells; dx++) {
            for (int dz = -cells; dz <= cells; dz++) {
                List<ServerPlayer> bucket = GRID.get(cellKey(ox + dx, oz + dz));
                if (bucket == null || bucket.isEmpty()) continue;
                for (ServerPlayer other : bucket) {
                    if (other == origin) continue;
                    if (other.level() != world) continue;
                    if (other.distanceToSqr(origin) > radiusSq) continue;
                    out.add(other);
                }
            }
        }
    }

    /**
     * The real players online, as of this tick.
     *
     * <p>Live list, not a copy - it is read on a hot path and rebuilt every tick, so handing
     * out a defensive copy would allocate once per bot per second for no benefit. Read it, do
     * not keep it.</p>
     */
    public static List<ServerPlayer> humans() {
        return HUMANS;
    }

    /** How many players are indexed, for diagnostics. */
    public static int size() {
        return ALL.size();
    }

    public static void clear() {
        GRID.clear();
        ALL.clear();
        HUMANS.clear();
        builtTick = -1;
    }
}
