package scriptify.bots.bot;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Fills a route with walkers one at a time: {@code /bots bot-management path populate}.
 *
 * <p>Putting a crowd on a route all at once has them set off in a clump and stay one, all the way
 * round. A street has people turning up on it now and then, so each walker is spawned at the start of
 * the route after a gap of its own - around the interval asked for, never exactly it.</p>
 */
public final class PathPopulator {

    private PathPopulator() {}

    private static final class Job {
        final String path;
        final CommandSourceStack source;
        final int intervalTicks;
        final int total;
        int remaining;
        long nextTick;

        Job(String path, int count, int intervalTicks, CommandSourceStack source) {
            this.path = path;
            this.total = count;
            this.remaining = count;
            this.intervalTicks = intervalTicks;
            this.source = source;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();
    private static final Random RNG = new Random();

    /** Queues {@code count} walkers for a route, the first straight away. */
    public static void start(String path, int count, int intervalTicks, CommandSourceStack source) {
        JOBS.add(new Job(path, count, intervalTicks, source));
    }

    /** Drops the walkers still waiting to be spawned onto a route. @return how many there were */
    public static int cancel(String path) {
        int dropped = 0;
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (job.path.equals(path)) {
                dropped += job.remaining;
                it.remove();
            }
        }
        return dropped;
    }

    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        long now = server.getTickCount();
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (now < job.nextTick) continue;
            BotPath.PathData route = BotPath.getPath(job.path);
            if (route == null || route.points.isEmpty()) {
                it.remove();
                continue;
            }
            String name = BotNameGenerator.generateUniqueName();
            if (BotManager.spawnBot(server, name, job.source, route.points.get(0), false)) {
                // The roster's spelling: the backend may have joined it under different capitals.
                BotPath.startFollowing(BotManager.canonical(name), job.path);
            }
            if (--job.remaining <= 0) {
                it.remove();
                job.source.sendSuccess(() -> Component.literal(
                    "Path '" + job.path + "' populated with " + job.total + " bots."), false);
                continue;
            }
            // Half to one and a half times the interval, so the gaps do not tick like a metronome.
            job.nextTick = now + Math.max(1, Math.round(job.intervalTicks * (0.5 + RNG.nextDouble())));
        }
    }

    public static void clear() {
        JOBS.clear();
    }
}
