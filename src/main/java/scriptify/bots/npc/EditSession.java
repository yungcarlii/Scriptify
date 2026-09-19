package scriptify.bots.npc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotPath;
import scriptify.bots.ui.Msg;
import scriptify.bots.ui.Sfx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The two jobs that need the world rather than a menu: drawing a path, and recording a walk.
 *
 * <p>Both are things you do by moving around, so neither can live in a container screen - you cannot
 * walk while one is open. They take over the hotbar instead, which is the vanilla idiom for "you are
 * in a mode now" and needs no client mod to express.</p>
 *
 * <p>The hotbar is <em>borrowed</em>, not cleared: what was in it is put back when the session ends,
 * however it ends. Anything less would make the feature cost you your inventory to try.</p>
 */
public final class EditSession {

    /** Hotbar slots the session takes over, chosen to sit at the ends and leave the middle alone. */
    private static final int SLOT_PRIMARY = 0;
    private static final int SLOT_UNDO = 1;
    private static final int SLOT_FINISH = 7;
    private static final int SLOT_CANCEL = 8;

    /**
     * Ticks between samples while recording a walk.
     *
     * <p>Four is about three samples a second: close enough to keep the shape of a corner, far
     * enough apart that a minute of walking is ninety points rather than twelve hundred.</p>
     */
    private static final int RECORD_INTERVAL_TICKS = 1;

    /**
     * PATH draws a route and hands it straight to a bot; NAMED_PATH draws one and only saves it,
     * for the command that creates a path by name and starts bots on it later.
     */
    private enum Kind { PATH, NAMED_PATH, RECORD }

    private static final Map<UUID, EditSession> SESSIONS = new HashMap<>();

    private final Kind kind;
    private final String botName;
    private final String pathName;
    private final ItemStack[] savedHotbar = new ItemStack[9];
    private final List<Vec3> points = new ArrayList<>();
    /** Frames of a performance, when this is a recording session rather than a path. */
    private final List<MovementReplay.Frame> frames = new ArrayList<>();
    private boolean recording;
    private int sampleCountdown;

    private EditSession(Kind kind, String botName, String pathName) {
        this.kind = kind;
        this.botName = botName;
        this.pathName = pathName;
    }

    // ===================== starting =====================

    public static boolean active(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /** Starts drawing a path for this bot, one point at a time. */
    public static void beginPath(ServerPlayer player, String botName) {
        EditSession session = new EditSession(Kind.PATH, botName, pathNameFor(botName));
        start(player, session);
        givePathTools(player, "Hands the path to " + botName + ".");
        Msg.chat(player, Msg.heading("Drawing a path for " + botName));
        Msg.chat(player, Msg.detail("Right click the emerald to place points as you walk."));
    }

    /**
     * Starts drawing a path that is saved under a name rather than handed to a bot.
     *
     * <p>Same hotbar, same points, same file - {@code BotPath} writes every change to the stock
     * {@code paths.json}, so a route drawn this way is indistinguishable from one built with the
     * older point-at-a-time commands and works with everything that reads them.</p>
     */
    public static void beginNamedPath(ServerPlayer player, String pathName) {
        EditSession session = new EditSession(Kind.NAMED_PATH, null, pathName);
        start(player, session);
        givePathTools(player, "Saves the path as " + pathName + ".");
        Msg.chat(player, Msg.heading("Drawing the " + pathName + " path"));
        Msg.chat(player, Msg.detail("Right click the emerald to place points as you walk."));
    }

    /** Starts a recording session: walk the route yourself and the bot repeats it. */
    public static void beginRecord(ServerPlayer player, String botName) {
        EditSession session = new EditSession(Kind.RECORD, botName, pathNameFor(botName));
        start(player, session);
        player.getInventory().setItem(SLOT_PRIMARY, labelled(scriptify.ui.DyedItems.concrete(DyeColor.GREEN), "Start recording",
            ChatFormatting.GREEN, "Right click, then walk the route."));
        player.getInventory().setItem(SLOT_CANCEL, labelled(Items.BARRIER, "Cancel",
            ChatFormatting.RED, "Throws the recording away."));
        Msg.chat(player, Msg.heading("Recording a walk for " + botName));
        Msg.chat(player, Msg.detail("Right click the green block to begin."));
    }

    /** The four controls a path is drawn with, identical however the drawing was started. */
    private static void givePathTools(ServerPlayer player, String finishHint) {
        player.getInventory().setItem(SLOT_PRIMARY, labelled(Items.EMERALD, "Add point",
            ChatFormatting.GREEN, "Right click to drop a point where you stand."));
        player.getInventory().setItem(SLOT_UNDO, labelled(scriptify.ui.DyedItems.dye(DyeColor.RED), "Remove last",
            ChatFormatting.RED, "Takes back the last point."));
        player.getInventory().setItem(SLOT_FINISH, labelled(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Finish path",
            ChatFormatting.GREEN, finishHint));
        player.getInventory().setItem(SLOT_CANCEL, labelled(Items.BARRIER, "Cancel",
            ChatFormatting.RED, "Throws the path away."));
    }

    private static void start(ServerPlayer player, EditSession session) {
        end(player, false);
        for (int slot = 0; slot < 9; slot++) {
            session.savedHotbar[slot] = player.getInventory().getItem(slot).copy();
            player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        SESSIONS.put(player.getUUID(), session);
        Sfx.click(player);
    }

    /** A path per bot, so drawing a new one for the same bot replaces its old one. */
    private static String pathNameFor(String botName) {
        return "edit_" + botName.toLowerCase(Locale.ROOT);
    }

    // ===================== interaction =====================

    /**
     * Whether this player is holding a session item, so a click on a block must not place it.
     *
     * <p>The recording control is a block, and looking down while clicking it put it in the world
     * instead of starting the recording - the interaction that fires first for a block in hand is
     * the placement, not the item use.</p>
     */
    public static boolean holdingSessionItem(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /**
     * Handles a right click while a session is open.
     *
     * @return true when the click was consumed and should not also place a block or use an item
     */
    public static boolean handleUse(ServerPlayer player, ItemStack held) {
        EditSession session = SESSIONS.get(player.getUUID());
        if (session == null || held.isEmpty()) {
            return false;
        }
        if (held.is(Items.BARRIER)) {
            Msg.chat(player, Msg.failure("Cancelled."));
            end(player, false);
            return true;
        }
        if (session.kind == Kind.PATH || session.kind == Kind.NAMED_PATH) {
            return session.handlePathClick(player, held);
        }
        return session.handleRecordClick(player, held);
    }

    private boolean handlePathClick(ServerPlayer player, ItemStack held) {
        if (held.is(Items.EMERALD)) {
            this.points.add(player.position());
            Sfx.click(player);
            Msg.chat(player, Msg.line("Point " + this.points.size() + " placed."));
            return true;
        }
        if (held.is(scriptify.ui.DyedItems.dye(DyeColor.RED))) {
            if (this.points.isEmpty()) {
                Msg.chat(player, Msg.failure("No points to remove."));
                return true;
            }
            this.points.remove(this.points.size() - 1);
            Sfx.click(player);
            Msg.chat(player, Msg.line(this.points.size() + " point(s) left."));
            return true;
        }
        if (held.is(scriptify.ui.DyedItems.dye(DyeColor.LIME))) {
            finish(player);
            return true;
        }
        return false;
    }

    private boolean handleRecordClick(ServerPlayer player, ItemStack held) {
        if (!held.is(scriptify.ui.DyedItems.concrete(DyeColor.GREEN)) && !held.is(scriptify.ui.DyedItems.concrete(DyeColor.RED))) {
            return false;
        }
        if (!this.recording) {
            this.recording = true;
            this.sampleCountdown = 0;
            this.frames.add(MovementReplay.Frame.of(player));
            player.getInventory().setItem(SLOT_PRIMARY, labelled(scriptify.ui.DyedItems.concrete(DyeColor.RED),
                "Stop recording", ChatFormatting.RED, "Right click to finish the route."));
            Msg.chat(player, Msg.success("Recording - walk the route."));
            Sfx.click(player);
            return true;
        }
        finish(player);
        return true;
    }

    // ===================== ticking =====================

    /** Samples the walk of anybody currently recording. */
    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, EditSession> entry : SESSIONS.entrySet()) {
            EditSession session = entry.getValue();
            if (session.kind != Kind.RECORD || !session.recording) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            if (--session.sampleCountdown > 0) {
                continue;
            }
            session.sampleCountdown = RECORD_INTERVAL_TICKS;
            // Every tick, and standing still is kept. A pause is part of a performance; dropping it
            // is what turns a recording back into a route.
            session.frames.add(MovementReplay.Frame.of(player));
        }
    }

    // ===================== finishing =====================

    private void finish(ServerPlayer player) {
        if (this.kind == Kind.RECORD) {
            finishRecording(player);
            return;
        }
        if (this.points.size() < 2) {
            Msg.chat(player, Msg.failure("A path needs at least two points."));
            end(player, false);
            return;
        }

        BotPath.deletePath(this.pathName);
        BotPath.createPath(this.pathName);
        for (Vec3 point : this.points) {
            BotPath.addPoint(this.pathName, point);
        }
        // A drawn route is a circuit: it comes back round to the first point rather than turning
        // about and retracing itself.
        BotPath.setLoop(this.pathName, true);
        BotPath.setPathVisible(this.pathName, true);
        // A path drawn by hand is a patrol route, not an ambush: walking it should not make the bot
        // break off and fight whatever it passes.
        BotPath.setAttack(this.pathName, false);

        if (this.kind == Kind.NAMED_PATH) {
            Msg.chat(player, Msg.success("Saved " + this.pathName + " with "
                + this.points.size() + " points."));
            Msg.chat(player, Msg.detail("Start a bot on it with /bots bot-management path start "
                + this.pathName));
            Sfx.success(player);
            end(player, true);
            return;
        }

        BotPath.startFollowing(this.botName, this.pathName);
        startAtNearestPoint(player, this.botName, this.points);

        Msg.chat(player, Msg.success(this.botName + " is walking a "
            + this.points.size() + " point path."));
        Sfx.success(player);
        end(player, true);
    }

    /**
     * Hands the performance to the bot.
     *
     * <p>Replay rather than a path: the recording carries where you were looking and when you
     * crouched, and a path would throw both away.</p>
     */
    private void finishRecording(ServerPlayer player) {
        if (this.frames.size() < 2) {
            Msg.chat(player, Msg.failure("Nothing recorded."));
            end(player, false);
            return;
        }
        ServerPlayer bot = player.level().getServer().getPlayerList().getPlayerByName(this.botName);
        if (bot == null) {
            Msg.chat(player, Msg.failure(this.botName + " is not here any more."));
            end(player, false);
            return;
        }
        MovementReplay.start(bot, this.frames);
        Msg.chat(player, Msg.success(this.botName + " is repeating "
            + (this.frames.size() / 20) + "s of movement."));
        Sfx.success(player);
        end(player, true);
    }

    /**
     * Sends the bot to whichever point it is already closest to.
     *
     * <p>Otherwise a bot finishing a path starts from point one, which may be across the arena, and
     * the first thing it does is walk a straight line through everything in between.</p>
     */
    private static void startAtNearestPoint(ServerPlayer editor, String botName, List<Vec3> points) {
        ServerPlayer bot = editor.level().getServer().getPlayerList().getPlayerByName(botName);
        if (bot == null) {
            return;
        }
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int index = 0; index < points.size(); index++) {
            double distance = points.get(index).distanceToSqr(bot.position());
            if (distance < best) {
                best = distance;
                nearest = index;
            }
        }
        BotPath.setBotPathIndex(botName, nearest);
    }

    /** Ends a session and gives the hotbar back. Safe to call when there is no session. */
    public static void end(ServerPlayer player, boolean quiet) {
        EditSession session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            player.getInventory().setItem(slot,
                session.savedHotbar[slot] == null ? ItemStack.EMPTY : session.savedHotbar[slot]);
        }
        if (!quiet) {
            Sfx.click(player);
        }
    }

    public static void clear() {
        SESSIONS.clear();
    }

    private static ItemStack labelled(net.minecraft.world.item.Item item, String name,
                                      ChatFormatting colour, String hint) {
        ItemStack stack = new ItemStack(item);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            Component.literal(name).withStyle(colour)
                .withStyle(style -> style.withItalic(false)));
        stack.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(List.of(
                Component.literal(hint).withStyle(ChatFormatting.GRAY)
                    .withStyle(style -> style.withItalic(false)))));
        return stack;
    }
}
