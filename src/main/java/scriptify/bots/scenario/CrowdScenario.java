package scriptify.bots.scenario;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.Rng;

import java.util.ArrayList;
import java.util.List;

/**
 * A crowd watching something.
 *
 * <p>What makes a crowd a crowd is that everyone is looking at roughly the same thing and none
 * of them is looking at it in quite the same way. Everyone gets the same target and their own
 * drift around it, so the group has a shared focus without a shared pose.</p>
 *
 * <p>The larger reactions - a full turn, a hard yes or no, a hop - are deliberately rare. In a
 * crowd of thirty at six percent a second, something is always happening somewhere, which is
 * exactly how a real crowd looks. Raise it and the whole crowd starts twitching in unison.</p>
 */
public final class CrowdScenario {

    private CrowdScenario() {}

    public static void tick(MinecraftServer server, Level world,
                            ScenarioInstance instance, long serverTick) {
        List<Entity> targets = resolveTargets(server, instance);

        for (String name : instance.bots) {
            ServerPlayer bot = server.getPlayerList().getPlayer(name);
            if (bot == null || !bot.isAlive()) continue;

            BotProfile profile = BotProfile.of(name);
            Roamer.halt(bot);

            // A spin takes the head over entirely; everything else lays over the watching aim.
            if (!Gestures.apply(bot, server, profile)) {
                watch(bot, profile, instance, targets, serverTick);
                Gestures.apply(bot, server, profile);
            }

            react(server, bot, profile, instance);
        }
    }

    /**
     * Look at an assigned target with a personal offset.
     *
     * <p>The offset is derived from the bot's own phase rather than re-rolled, so a given bot
     * consistently watches from slightly above the shoulder, or a little to the left. Re-rolled
     * jitter reads as a nervous tic; a fixed bias reads as where that person happens to be
     * standing.</p>
     */
    private static void watch(ServerPlayer bot, BotProfile profile,
                              ScenarioInstance instance, List<Entity> targets, long serverTick) {
        if (targets.isEmpty()) {
            Roamer.idleLook(bot, profile, instance.roamer(bot.getName().getString()));
            return;
        }

        // With several targets, each watcher settles on one and only occasionally switches.
        int index = Math.floorMod(profile.phase + (int) (serverTick / 200), targets.size());
        Entity target = targets.get(index);

        double jitter = instance.settings.lookJitterDegrees;
        double bias = (profile.phase / 19.0 - 0.5) * 2.0;
        Vec3 at = target.getEyePosition().add(
            bias * jitter * 0.04,
            bias * jitter * 0.02,
            bias * jitter * 0.04);
        AimController.lookAtPos(bot, at, profile, false);
    }

    private static void react(MinecraftServer server, ServerPlayer bot,
                              BotProfile profile, ScenarioInstance instance) {
        String name = bot.getName().getString();
        if (Gestures.busy(name)) return;

        var settings = instance.settings;

        if (Rng.chance(profile.rng(), settings.reactionChance / 20.0)) {
            Gestures.Kind kind = switch (profile.rng().nextInt(4)) {
                case 0 -> Gestures.Kind.SPIN;
                case 1 -> Gestures.Kind.HOP;
                case 2 -> Gestures.Kind.NOD;
                default -> Gestures.Kind.SHAKE;
            };
            int ticks = switch (kind) {
                case SPIN -> Rng.range(profile.rng(), 14, 24);
                case HOP -> 40;
                default -> Rng.range(profile.rng(), 16, 28);
            };
            Gestures.start(bot, profile, kind, ticks);
            return;
        }

        if (Rng.chance(profile.rng(), settings.sneakChance / 20.0)) {
            Gestures.start(bot, profile,
                profile.rng().nextBoolean() ? Gestures.Kind.SNEAK_HOLD : Gestures.Kind.SNEAK_BURST,
                20);
        }
    }

    /** Named watch targets, falling back to the scenario point when nobody is named. */
    private static List<Entity> resolveTargets(MinecraftServer server, ScenarioInstance instance) {
        List<Entity> targets = new ArrayList<>();
        for (String name : instance.watchTargets) {
            ServerPlayer player = server.getPlayerList().getPlayer(name);
            if (player != null && player.isAlive()) targets.add(player);
        }
        return targets;
    }
}
