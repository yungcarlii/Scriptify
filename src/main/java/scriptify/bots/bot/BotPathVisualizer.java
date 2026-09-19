package scriptify.bots.bot;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws patrol paths with particles.
 *
 * <p>Rewritten for cost. The original drew, for every visible path, twelve particles in a
 * ring plus a marker at every point, plus two particles per block of every segment - to
 * every player in the overworld, every ten ticks, whether or not anyone was near enough to
 * see them. A twenty-point path was several hundred particle packets per player per second.</p>
 *
 * <p>It now only draws to players who are actually close to the path, caps the work per
 * segment, and skips entirely when nobody has a path visible.</p>
 */
public class BotPathVisualizer {

    private static int tickCounter = 0;
    private static final int UPDATE_INTERVAL = 10;
    private static final int COLOR_GREEN = 0x00FF00;
    private static final double VIEW_DISTANCE = 48.0;
    private static final double VIEW_DISTANCE_SQ = VIEW_DISTANCE * VIEW_DISTANCE;
    private static final int MAX_SEGMENT_PARTICLES = 16;

    public static void update(MinecraftServer server) {
        if (++tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        var visible = BotPath.getVisiblePaths();
        if (visible.isEmpty()) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        for (String pathName : visible) {
            BotPath.PathData path = BotPath.getPath(pathName);
            if (path == null || path.points.isEmpty()) continue;

            for (ServerPlayer player : players) {
                // Bots do not need to look at the pretty lines.
                if (BotManager.isBot(player.getName().getString())) continue;
                if (!(player.level() instanceof ServerLevel world)) continue;
                drawFor(world, player, path);
            }
        }
    }

    private static void drawFor(ServerLevel world, ServerPlayer player, BotPath.PathData path) {
        DustParticleOptions dust = new DustParticleOptions(COLOR_GREEN, 1.0f);
        List<Vec3> points = path.points;

        for (int i = 0; i < points.size(); i++) {
            Vec3 point = points.get(i);
            if (!near(player, point)) continue;

            world.sendParticles(player, dust, true,
                false, point.x, point.y + 1.0, point.z, 4, 0.1, 0.4, 0.1, 0.0);

            Vec3 next = null;
            if (i + 1 < points.size()) next = points.get(i + 1);
            else if (path.loop) next = points.get(0);
            if (next != null) drawSegment(world, player, dust, point, next);
        }
    }

    private static void drawSegment(ServerLevel world, ServerPlayer player,
                                    DustParticleOptions dust, Vec3 start, Vec3 end) {
        double distance = start.distanceTo(end);
        int steps = Math.min(MAX_SEGMENT_PARTICLES, Math.max(1, (int) distance));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = start.x + (end.x - start.x) * t;
            double y = start.y + (end.y - start.y) * t + 0.4;
            double z = start.z + (end.z - start.z) * t;
            world.sendParticles(player, dust, true, false, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    private static boolean near(ServerPlayer player, Vec3 point) {
        double dx = player.getX() - point.x;
        double dy = player.getY() - point.y;
        double dz = player.getZ() - point.z;
        return dx * dx + dy * dy + dz * dz < VIEW_DISTANCE_SQ;
    }
}
