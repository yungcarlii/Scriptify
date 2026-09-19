package scriptify.bots.npc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.bot.BotManager;
import scriptify.bots.scenario.Roamer;
import scriptify.bots.ui.Msg;
import scriptify.bots.ui.Sfx;
import scriptify.bots.utils.Names;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Editing a bot by hitting it.
 *
 * <p>Naming a bot on the command line is fine when there are three of them. In an arena it is not:
 * you are looking at the one you want to change and you do not know which of two hundred names it
 * answers to. Edit mode turns the bot itself into the handle - punch it, get its panel.</p>
 *
 * <p>Deliberately a mode rather than a permanent behaviour. Bots are meant to be hit; a server where
 * every punch opened a menu would be unusable, so this is switched on for one player at a time and
 * switched off again when they are done.</p>
 */
public final class EditMode {

    /**
     * How far a wandering bot will pick its next spot, in blocks.
     *
     * <p>Far enough to look like it is going somewhere, near enough that a bot placed for a scene
     * stays roughly where it was put.</p>
     */
    private static final double WANDER_RADIUS = 12.0D;

    /** A walk, not a march: fast enough to read as going somewhere, slow enough to look idle. */
    private static final double WANDER_SPEED = 0.6D;
    /** Roughly one pause every ten seconds, so a wandering bot stops to look around. */
    private static final double WANDER_PAUSE_CHANCE = 0.005D;
    private static final int WANDER_PAUSE_MIN = 20;
    private static final int WANDER_PAUSE_MAX = 70;

    /** Who has edit mode on. */
    private static final Set<UUID> EDITORS = new HashSet<>();

    /** Bots told to wander, and the roaming state each one carries. */
    private static final Map<String, Roamer.State> WANDERING = new HashMap<>();

    private EditMode() {}

    // ===================== the mode =====================

    public static boolean isEditing(ServerPlayer player) {
        return EDITORS.contains(player.getUUID());
    }

    /** @return the state it is now in. */
    public static boolean toggle(ServerPlayer player) {
        boolean on = !isEditing(player);
        if (on) {
            EDITORS.add(player.getUUID());
        } else {
            EDITORS.remove(player.getUUID());
        }
        Sfx.toggle(player, on);
        Msg.chat(player, on
            ? Msg.success("Edit mode on - hit a bot to open its panel.")
            : Msg.line("Edit mode off."));
        return on;
    }

    public static void set(ServerPlayer player, boolean on) {
        if (on != isEditing(player)) {
            toggle(player);
        }
    }

    public static void forgetPlayer(ServerPlayer player) {
        EDITORS.remove(player.getUUID());
    }

    /**
     * Whether this punch should open a panel instead of landing.
     *
     * <p>Returns true when the hit is swallowed. The caller cancels the attack, so a bot being
     * edited is never damaged by the click that opened its editor - which matters because half the
     * bots worth editing are the ones set to be hittable.</p>
     */
    public static boolean interceptAttack(ServerPlayer attacker, Entity target) {
        if (!isEditing(attacker) || !(target instanceof ServerPlayer bot)) {
            return false;
        }
        if (!BotManager.isBot(bot.getName().getString())) {
            return false;
        }
        scriptify.bots.ui.NpcEditMenu.open(attacker, bot.getName().getString());
        return true;
    }

    // ===================== wander =====================

    public static boolean isWandering(String botName) {
        return WANDERING.containsKey(Names.key(botName));
    }

    /** @return the state it is now in. */
    public static boolean toggleWander(String botName) {
        String key = Names.key(botName);
        if (WANDERING.remove(key) != null) {
            return false;
        }
        WANDERING.put(key, new Roamer.State());
        return true;
    }

    public static void stopWander(String botName) {
        WANDERING.remove(Names.key(botName));
    }

    /**
     * Steps every wandering bot.
     *
     * <p>Driven from the bot ticker rather than from a scenario, because wander is a property of one
     * bot somebody switched on, not a staged scene with a roster and an anchor. It reuses the
     * scenario roamer for the actual walking so a wandering bot moves like one in a crowd rather
     * than like a second, slightly different implementation of the same idea.</p>
     */
    public static void tick(MinecraftServer server, long serverTick) {
        if (WANDERING.isEmpty()) {
            return;
        }
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            String key = Names.key(bot.getName().getString());
            Roamer.State state = WANDERING.get(key);
            if (state == null || !bot.isAlive()) {
                continue;
            }
            // A bot that has been handed a path or a scenario is being driven by that instead;
            // wandering over the top would fight it for the controls.
            if (scriptify.bots.bot.BotPath.isFollowing(bot.getName().getString())
                || scriptify.bots.scenario.ScenarioManager.owns(bot.getName().getString())) {
                continue;
            }
            BotProfile profile = BotProfile.of(bot.getName().getString());
            // The roamer walks toward a goal and reports when it needs a new one; choosing where to
            // go next is the caller's job, which is what makes the same code serve a crowd scenario
            // and a single bot told to wander.
            if (state.goal == null) {
                state.destination = Roamer.pickSpot(bot, profile, bot.position(), WANDER_RADIUS);
                state.goal = state.destination;
            }
            Roamer.Step step = Roamer.step(bot, profile, state,
                WANDER_SPEED, WANDER_PAUSE_CHANCE, WANDER_PAUSE_MIN, WANDER_PAUSE_MAX);
            if (step == Roamer.Step.ARRIVED || step == Roamer.Step.GIVE_UP) {
                state.goal = null;
            }
        }
    }

    public static void clear() {
        EDITORS.clear();
        WANDERING.clear();
    }
}
