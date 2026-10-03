package scriptify.bots.bot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotInput;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.utils.Names;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bots that tag along behind a player.
 *
 * <p>Followers fall in behind the leader in rows, each with its own place, rather than all heading
 * for the same point or crowding round them, and the column keeps its distance: one bot walks a
 * couple of blocks back, a hundred leave a proper gap, so the player is never walking in a scrum of
 * their own escort.
 * A follower never picks its leader as a target, and gets on with any fight it is in before
 * catching up again.</p>
 */
public final class BotFollow {

    private BotFollow() {}

    /** Bot key -> name of the player it follows. */
    private static final Map<String, String> LEADERS = new HashMap<>();

    /**
     * Which way each leader is heading, as a unit vector: where they last walked, not where they are
     * looking. A player standing still and glancing about should not swing a whole column of
     * followers round behind their back every time they turn their head.
     */
    private static final Map<String, double[]> HEADINGS = new HashMap<>();

    /** Space between neighbours in a row and between rows. */
    private static final double SPACING = 1.4;

    /** Closer than this to its spot, a follower stops and waits. */
    private static final double SETTLE = 1.2;

    /** Further than this from its spot, it sprints to catch up rather than walking. */
    private static final double CATCH_UP = 8.0;

    public static void follow(String bot, String player) {
        LEADERS.put(Names.key(bot), player);
        BotPath.stopFollowing(bot);
    }

    public static boolean stop(String bot) {
        return LEADERS.remove(Names.key(bot)) != null;
    }

    public static String leaderOf(String bot) {
        return LEADERS.get(Names.key(bot));
    }

    public static boolean isFollowing(String bot) {
        return LEADERS.containsKey(Names.key(bot));
    }

    /** Whether this bot is following that player, who it therefore must not attack. */
    public static boolean follows(String bot, String player) {
        String leader = LEADERS.get(Names.key(bot));
        return leader != null && leader.equalsIgnoreCase(player);
    }

    public static void clear() {
        LEADERS.clear();
        HEADINGS.clear();
    }

    /** The bots following this player, in a stable order, so each one keeps its spot. */
    private static List<String> followersOf(String player) {
        List<String> out = new ArrayList<>();
        LEADERS.forEach((bot, leader) -> {
            if (leader.equalsIgnoreCase(player)) out.add(bot);
        });
        out.sort(null);
        return out;
    }

    /**
     * The gap left round the leader: a couple of blocks for a handful of bots, growing with the size
     * of the crowd so more followers stand further off rather than packing in tighter.
     */
    static double gapFor(int followers) {
        return 2.0 + Math.sqrt(followers) * 0.4;
    }

    /** Where the leader is heading: the way they are walking, or the way they last walked. */
    private static double[] headingOf(ServerPlayer leader) {
        String key = Names.key(leader.getName().getString());
        double[] heading = HEADINGS.get(key);
        Vec3 v = leader.getDeltaMovement();
        double speed = Math.hypot(v.x, v.z);
        if (speed > 0.06) {
            heading = new double[] {v.x / speed, v.z / speed};
            HEADINGS.put(key, heading);
        } else if (heading == null) {
            // Never seen moving: behind where they are facing.
            double yaw = Math.toRadians(leader.getYRot());
            heading = new double[] {-Math.sin(yaw), Math.cos(yaw)};
            HEADINGS.put(key, heading);
        }
        return heading;
    }

    /** Walks a following bot toward its spot by the leader. Called when it has nothing to fight. */
    public static void tick(ServerPlayer bot, MinecraftServer server) {
        String leaderName = leaderOf(bot.getName().getString());
        if (leaderName == null) return;
        ServerPlayer leader = server.getPlayerList().getPlayerByName(leaderName);
        if (leader == null || !leader.isAlive() || leader.level() != bot.level()) {
            BotInput.release(bot);
            return;
        }

        List<String> crowd = followersOf(leaderName);
        int index = Math.max(0, crowd.indexOf(Names.key(bot.getName().getString())));
        double gap = gapFor(crowd.size());
        double[] heading = headingOf(leader);

        // Rows behind the leader, a few abreast, widening a little as the column grows.
        int perRow = Math.max(2, (int) Math.ceil(Math.sqrt(crowd.size()) * 0.8));
        int row = index / perRow;
        int col = index % perRow;
        double back = gap + row * SPACING;
        double across = (col - (perRow - 1) / 2.0) * SPACING;
        Vec3 spot = new Vec3(
            leader.getX() - heading[0] * back - heading[1] * across,
            leader.getY(),
            leader.getZ() - heading[1] * back + heading[0] * across);

        BotProfile profile = BotProfile.of(bot.getName().getString());
        Vec3 here = bot.position();
        double off = Math.hypot(spot.x - here.x, spot.z - here.z);
        if (off < SETTLE) {
            BotInput.release(bot);
            AimController.lookAtPos(bot, leader.getEyePosition(), profile, false);
            return;
        }
        BotTicker.faceAlongPath(bot, profile, here, spot);
        BotNavigation.moveTowardPosition(bot, spot, off > CATCH_UP ? 1.0 : 0.8);
    }
}
