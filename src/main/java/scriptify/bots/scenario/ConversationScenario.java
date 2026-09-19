package scriptify.bots.scenario;

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
 * Two or more bots standing around talking.
 *
 * <p>The trick to a convincing conversation is not the animation, it is the <em>structure</em>:
 * one person is talking and the others are looking at them. Take turns, and let the listeners
 * nod, and a group of motionless player models reads as a conversation. Have everyone gesture
 * at once and it reads as a glitch.</p>
 *
 * <p>So there is a speaker, held for a few seconds at a time; listeners watch the speaker, and
 * the speaker looks around the group the way someone addressing several people does.</p>
 */
public final class ConversationScenario {

    private ConversationScenario() {}

    private static final String KEY_TURN = "turn";
    private static final String KEY_SPEAKER = "speaker";

    public static void tick(MinecraftServer server, Level world,
                            ScenarioInstance instance, long serverTick) {
        List<ServerPlayer> group = present(server, instance);
        if (group.size() < 2) return;

        var settings = instance.settings;

        // --- whose turn is it? ------------------------------------------------------------
        if (instance.tickTimer(KEY_TURN)) {
            changeSpeaker(instance, group, settings);
        }
        int speakerIndex = Math.floorMod(instance.timer(KEY_SPEAKER), group.size());
        ServerPlayer speaker = group.get(speakerIndex);

        for (ServerPlayer bot : group) {
            BotProfile profile = BotProfile.of(bot.getName().getString());
            Roamer.halt(bot);

            if (bot == speaker) {
                speak(server, instance, group, bot, profile, serverTick);
            } else {
                listen(server, instance, speaker, bot, profile);
            }
        }
    }

    private static void changeSpeaker(ScenarioInstance instance, List<ServerPlayer> group,
                                      ScenarioSettings settings) {
        java.util.Random rng = new java.util.Random();
        int min = Math.max(10, settings.turnMinTicks);
        int max = Math.max(min + 1, settings.turnMaxTicks);
        instance.setTimer(KEY_TURN, Rng.range(rng, min, max));

        // Move on to someone else, not just anyone - being interrupted by yourself is odd.
        int current = instance.timer(KEY_SPEAKER);
        int next = current;
        if (group.size() > 1) {
            while (next == current) next = rng.nextInt(group.size());
        }
        instance.setTimer(KEY_SPEAKER, next);
    }

    /** The speaker looks around the group, and gestures while making a point. */
    private static void speak(MinecraftServer server, ScenarioInstance instance,
                              List<ServerPlayer> group, ServerPlayer bot,
                              BotProfile profile, long serverTick) {
        // Address one listener at a time, changing every couple of seconds.
        List<ServerPlayer> others = new ArrayList<>(group);
        others.remove(bot);
        if (others.isEmpty()) return;

        int index = (int) ((serverTick / 45) % others.size());
        lookAtFace(bot, others.get(index), profile);

        var settings = instance.settings;
        if (!Gestures.busy(bot.getName().getString())
            && Rng.chance(profile.rng(), settings.fidgetChance / 20.0)) {
            // Talking with your hands, minus the hands.
            Gestures.start(bot, profile, profile.rng().nextBoolean()
                ? Gestures.Kind.NOD : Gestures.Kind.SHAKE, Rng.range(profile.rng(), 14, 26));
        }
        Gestures.apply(bot, server, profile);
    }

    /** Listeners watch the speaker, nod along, and shift about. */
    private static void listen(MinecraftServer server, ScenarioInstance instance,
                               ServerPlayer speaker, ServerPlayer bot,
                               BotProfile profile) {
        lookAtFace(bot, speaker, profile);

        var settings = instance.settings;
        String name = bot.getName().getString();
        if (!Gestures.busy(name)) {
            if (Rng.chance(profile.rng(), settings.nodChance / 20.0)) {
                Gestures.start(bot, profile, Gestures.Kind.NOD, Rng.range(profile.rng(), 12, 22));
            } else if (Rng.chance(profile.rng(), settings.fidgetChance / 20.0)) {
                // A second of crouch-fidgeting, or a couple of seconds of hopping.
                Gestures.start(bot, profile,
                    profile.rng().nextBoolean() ? Gestures.Kind.SNEAK_BURST : Gestures.Kind.HOP,
                    profile.rng().nextBoolean() ? 20 : 40);
            }
        }
        Gestures.apply(bot, server, profile);
    }

    /**
     * Look at the other player's face rather than their feet, smoothly.
     *
     * <p>The aim controller is already rate limited to a human turn speed, which is exactly
     * what is wanted here - heads swinging instantly between speakers is the giveaway.</p>
     */
    private static void lookAtFace(ServerPlayer bot, ServerPlayer other,
                                   BotProfile profile) {
        Vec3 face = other.getEyePosition();
        AimController.lookAtPos(bot, face, profile, false);
    }

    private static List<ServerPlayer> present(MinecraftServer server,
                                                    ScenarioInstance instance) {
        List<ServerPlayer> group = new ArrayList<>();
        for (String name : instance.bots) {
            ServerPlayer bot = server.getPlayerList().getPlayer(name);
            if (bot != null && bot.isAlive()) group.add(bot);
        }
        return group;
    }
}
