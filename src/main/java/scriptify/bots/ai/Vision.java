package scriptify.bots.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Line-of-sight checks with a short-lived cache.
 *
 * <p>The original mod never tested whether anything was between a bot and its victim, which
 * is why bots happily punched through walls: the attack was executed as a command
 * ({@code /player <name> attack once}) rather than as a reach-checked interaction, so the
 * block in the way was never consulted. Every attack path now has to clear {@link #canSee}
 * first.</p>
 *
 * <p>Raycasts are not free, so results are cached per (bot, target) pair for a few ticks.
 * The cache is keyed by entity id and swept whenever it grows past a sane size.</p>
 */
public final class Vision {

    private Vision() {}

    private static final Map<Long, CacheEntry> CACHE = new HashMap<>();
    private static final int CACHE_TTL_TICKS = 3;
    private static final int MAX_CACHE = 4096;
    private static long tick;

    private record CacheEntry(boolean visible, long tick) {}

    public static void onServerTick(long serverTick) {
        tick = serverTick;
        if (CACHE.size() > MAX_CACHE) {
            CACHE.entrySet().removeIf(e -> serverTick - e.getValue().tick() > CACHE_TTL_TICKS);

            // A large fight can refresh more than MAX_CACHE pairs every few ticks, leaving no
            // expired entries for the sweep above. Trim the cache even in that case so a busy
            // server has a stable memory ceiling instead of retaining every pair ever queried.
            if (CACHE.size() > MAX_CACHE) {
                var entries = CACHE.entrySet().iterator();
                int targetSize = MAX_CACHE * 3 / 4;
                while (CACHE.size() > targetSize && entries.hasNext()) {
                    entries.next();
                    entries.remove();
                }
            }
        }
    }

    private static long key(Entity a, Entity b) {
        return ((long) a.getId() << 32) ^ (b.getId() & 0xFFFFFFFFL);
    }

    /**
     * True when the bot has an unobstructed shot at the target. Three rays are used
     * (eyes, chest, feet) so a target crouched behind a half-block is still reachable,
     * matching what a human can actually hit.
     */
    public static boolean canSee(ServerPlayer bot, Entity target) {
        if (target == null || !target.isAlive()) return false;
        if (bot.level() != target.level()) return false;

        long k = key(bot, target);
        CacheEntry cached = CACHE.get(k);
        if (cached != null && tick - cached.tick() <= CACHE_TTL_TICKS) {
            return cached.visible();
        }

        Level world = bot.level();
        Vec3 eye = bot.getEyePosition();
        AABB box = target.getBoundingBox();
        double midY = (box.minY + box.maxY) * 0.5;

        boolean visible =
            clear(world, bot, eye, target.getEyePosition())
            || clear(world, bot, eye, new Vec3(target.getX(), midY, target.getZ()))
            || clear(world, bot, eye, new Vec3(target.getX(), box.minY + 0.15, target.getZ()));

        CACHE.put(k, new CacheEntry(visible, tick));
        return visible;
    }

    /** Raw, uncached ray between two points. Used for block placement and bridging. */
    public static boolean clear(Level world, Entity source, Vec3 from, Vec3 to) {
        HitResult hit = world.clip(new ClipContext(
            from, to,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            source
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * How exposed the bot is: true when there is open sky-ish space behind it, used by the
     * retreat logic so bots do not back off cliffs.
     */
    public static boolean hasFooting(ServerPlayer bot, Vec3 direction, double distance) {
        Level world = bot.level();
        Vec3 dir = direction.normalize();
        Vec3 probe = new Vec3(
            bot.getX() + dir.x * distance,
            bot.getY(),
            bot.getZ() + dir.z * distance
        );
        for (int drop = 1; drop <= 4; drop++) {
            var pos = net.minecraft.core.BlockPos.containing(probe.x, probe.y - drop, probe.z);
            if (!world.getBlockState(pos).isAir()) return true;
        }
        return false;
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
