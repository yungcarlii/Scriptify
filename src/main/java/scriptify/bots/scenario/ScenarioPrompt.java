package scriptify.bots.scenario;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The line along the bottom of the screen while a scenario is being built.
 *
 * <p>Three things were wrong with the old one, and they were all the same thing: nobody owned the
 * action bar. The draft reminder was pushed every second, every tool wrote its own confirmation
 * straight to the bar, and the two overwrote each other - so clicking a bot flashed "Added" for a
 * fraction of a second before the reminder stamped over it, which is the flicker. It also said the
 * same sentence from the first click to the last, naming a command that was not the next thing to
 * do. And when the scenario was finished the reminder simply stopped being sent, which does not
 * clear anything: the client holds the last line for another three seconds, so the prompt outlived
 * the thing it was prompting for.</p>
 *
 * <p>So the bar has one owner. It says what the next step actually is and changes as the steps are
 * completed, confirmations are handed to it rather than written over it, and finishing wipes it
 * rather than falling silent.</p>
 */
public final class ScenarioPrompt {

    private ScenarioPrompt() {}

    /**
     * How long a confirmation holds the bar against the standing prompt, in ticks.
     *
     * <p>Long enough to read a short line, short enough that the guidance is back before anyone
     * needs it again.</p>
     */
    private static final int FLASH_TICKS = 40;

    /**
     * How often the standing prompt is re-sent, in ticks.
     *
     * <p>The client holds an action bar line for sixty ticks and fades the last twenty of them.
     * Refreshing every forty replaces the line while it is still at full strength, so the text
     * simply sits there. The old code refreshed on a timer that let it start fading first, which
     * is the pulsing.</p>
     */
    private static final int REFRESH_TICKS = 40;

    private static final class Held {
        String text = "";
        int flashTicks;
        int refreshIn;
    }

    private static final Map<UUID, Held> BARS = new HashMap<>();

    /**
     * Shows a confirmation, and holds it against the standing prompt for a moment.
     *
     * <p>Use this for anything that happens as a result of something the player just did - a bot
     * added, a point placed, a stick cancelled. Writing to the action bar directly works and then
     * gets overwritten within the tick, which is what it used to do.</p>
     */
    public static void flash(ServerPlayer player, String text, ChatFormatting colour) {
        Held held = BARS.computeIfAbsent(player.getUUID(), key -> new Held());
        held.flashTicks = FLASH_TICKS;
        held.text = text;
        held.refreshIn = REFRESH_TICKS;
        player.sendSystemMessage(Component.literal(text).withStyle(colour), true);
    }

    /**
     * Wipes the bar now.
     *
     * <p>An empty line, sent deliberately. Stopping the refresh is not the same as clearing: the
     * client keeps showing the last thing it was told for another three seconds, so a scenario
     * finished at the moment the prompt was re-sent left the instructions hanging under a
     * scenario that was already running.</p>
     */
    public static void clear(ServerPlayer player) {
        BARS.remove(player.getUUID());
        player.sendSystemMessage(Component.empty(), true);
    }

    public static void forget(ServerPlayer player) {
        BARS.remove(player.getUUID());
    }

    public static void clearAll() {
        BARS.clear();
    }

    /** Pushes the standing prompt to everybody mid-draft. Called once a tick. */
    public static void tick(MinecraftServer server) {
        if (ScenarioManager.drafts().isEmpty()) {
            if (!BARS.isEmpty()) BARS.clear();
            return;
        }

        ScenarioManager.drafts().forEach((uuid, draft) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) return;

            Held held = BARS.computeIfAbsent(uuid, key -> new Held());
            if (held.flashTicks > 0) {
                held.flashTicks--;
                return;
            }

            String next = step(draft);
            // Re-sent when it changes, and otherwise only often enough to stop it fading. A line
            // pushed every tick is a line the client never settles on.
            if (!next.equals(held.text)) {
                held.text = next;
                held.refreshIn = REFRESH_TICKS;
                push(player, draft, next);
                return;
            }
            if (--held.refreshIn > 0) return;
            held.refreshIn = REFRESH_TICKS;
            push(player, draft, next);
        });
    }

    /** Ready-to-finish reads green; still-gathering reads as an instruction. */
    private static void push(ServerPlayer player, ScenarioDraft draft, String text) {
        ChatFormatting colour = draft.validate() == null
            ? ChatFormatting.GREEN
            : ChatFormatting.AQUA;
        player.sendSystemMessage(
            Component.literal(draft.type.displayName + "  " + text).withStyle(colour), true);
    }

    /**
     * What to do next, for this scenario, at this point in building it.
     *
     * <p>Written as the next action rather than as a status, because the bar is the only place a
     * first-time user is told any of this. "Conversation: 1 bots | /bots scenarios point add ·
     * finish" - the old line - names a command this type does not use, and reports a count without
     * saying what the count needs to be.</p>
     */
    private static String step(ScenarioDraft draft) {
        return switch (draft.type) {
            case SPAWN -> draft.points.isEmpty()
                ? "· stand where players should appear, then /bots scenarios point add"
                : "· spawn point set · /bots scenarios finish";

            case CONVERSATION -> switch (draft.bots.size()) {
                case 0 -> "· right click bots with the Conversation Stick · two needed";
                case 1 -> "· 1 bot · right click one more at least";
                default -> "· " + draft.bots.size() + " talking · /bots scenarios finish";
            };

            case CROWD -> draft.bots.isEmpty()
                ? "· /bots scenarios crowd <radius> to gather everyone nearby"
                : "· " + draft.bots.size() + " gathered · /bots scenarios finish";

            case PRISON_CELL -> draft.bots.isEmpty()
                ? "· hold the Prison Stick and right click · 5 seconds"
                : "· " + draft.bots.size() + " assigned · /bots scenarios finish";
        };
    }
}
