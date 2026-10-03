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
 * <p>Each follower keeps its own spot in a loose crowd round the leader rather than all heading for
 * the same point, and the crowd keeps its distance: one bot walks a couple of blocks off, a hundred
 * leave a proper ring of space, so the player is never standing in a scrum of their own escort.
 * A follower never picks its leader as a target, and gets on with any fight it is in before
 * catching up again.</p>
 */
public final class BotFollow {

    private BotFollow() {}

    /** Bot key -> name of the player it follows. */
    private static final Map<String, String> LEADERS = new HashMap<>();

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
        return 2.0 + Math.sqrt(followers) * 0.6;
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
        // A sunflower round the leader: even spacing that grows outward, starting at the gap.
        double radius = gap + Math.sqrt(index) * 0.9;
        double angle = index * 2.399963;
        Vec3 spot = new Vec3(leader.getX() + Math.cos(angle) * radius, leader.getY(),
            leader.getZ() + Math.sin(angle) * radius);

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
