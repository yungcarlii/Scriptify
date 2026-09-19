package scriptify.bots.ui;

import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import scriptify.bots.bot.BotKits;
import scriptify.bots.bot.BotPath;
import scriptify.bots.npc.EditMode;
import scriptify.bots.npc.EditSession;
import scriptify.bots.npc.NpcApplier;
import scriptify.bots.npc.NpcRegistry;
import scriptify.bots.npc.NpcSettings;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Everything about one bot, on one screen, reached by hitting it.
 *
 * <p>Laid out in three labelled bands - what it is, what it does, how it moves - because sixteen
 * unsorted icons is a puzzle and three rows of five is a menu. The leading icon in each band is a
 * label rather than a button; it costs a slot and earns it back by making the rest scannable.</p>
 *
 * <p>Every button acts on the bot immediately. There is no apply step, because there is no state
 * here worth batching: this is a control panel for something standing in front of you, and the
 * feedback you want is the bot changing, not a confirmation dialog.</p>
 */
public final class NpcEditMenu {

    /** Row starts. The band label sits in the first slot of each. */
    private static final int SETTINGS_ROW = 9;
    private static final int ACTIONS_ROW = 18;
    private static final int MOVEMENT_ROW = 27;

    private NpcEditMenu() {}

    public static void open(ServerPlayer editor, String botName) {
        SimpleGui gui = Menus.panel(editor, 6, "NPC: " + botName);
        paint(gui, editor, botName);
        gui.open();
        Sfx.click(editor);
    }

    private static void paint(SimpleGui gui, ServerPlayer editor, String botName) {
        Menus.fill(gui);
        NpcSettings settings = NpcRegistry.overrides(botName);
        NpcSettings live = NpcRegistry.resolve(botName);
        Runnable repaint = () -> paint(gui, editor, botName);

        ServerPlayer bot = editor.level().getServer().getPlayerList().getPlayerByName(botName);
        gui.setSlot(4, bot == null
            ? Menus.header(Items.PLAYER_HEAD, botName, "Not on the server right now.").build()
            : Menus.head(bot.getGameProfile(), botName, Msg.BRAND)
                .addLoreLine(Component.literal("Hit a bot in edit mode to open this.")
                    .withStyle(ChatFormatting.DARK_GRAY)).build());

        // ---- what it is ------------------------------------------------------------------
        label(gui, SETTINGS_ROW, Items.REDSTONE_TORCH, "Settings",
            "What the bot is, rather than what it does.");
        dye(gui, editor, SETTINGS_ROW + 1, "Hittable",
            "Whether anything can damage it.",
            live::hittable, v -> settings.hittable = v, repaint);
        dye(gui, editor, SETTINGS_ROW + 2, "Collidable",
            "Whether it pushes and gets pushed.",
            live::collidable, v -> settings.collidable = v, repaint);
        dye(gui, editor, SETTINGS_ROW + 3, "Crouching",
            "Holds a crouch permanently.",
            live::crouching, v -> settings.crouching = v, repaint);
        dye(gui, editor, SETTINGS_ROW + 4, "Shows in tab",
            "Whether it appears in the player list.",
            live::listed, v -> settings.listed = v, repaint);
        toggle(gui, editor, SETTINGS_ROW + 5, Items.TOTEM_OF_UNDYING, "Immortal",
            "Survives everything.",
            live::immortal, v -> settings.immortal = v, repaint);
        dye(gui, editor, SETTINGS_ROW + 6, "Drops on death",
            "Leaves its gear on the floor.",
            live::dropsOnDeath, v -> settings.dropsOnDeath = v, repaint);

        gui.setSlot(SETTINGS_ROW + 7, Menus.button(Items.BREWING_STAND, "Copy my effects",
                ChatFormatting.LIGHT_PURPLE,
                "Puts every effect you are under onto the bot.",
                "A one-off, not a setting - it copies what you",
                "have right now and stops there.")
            .setCallback((i, t, a, ignored) -> {
                if (bot == null) {
                    Menus.deny(editor, "That bot is not here.");
                    return;
                }
                int copied = NpcApplier.copyEffects(editor, bot);
                Menus.notify(editor, copied == 0
                    ? "You have no effects to copy." : "Copied " + copied + " effect(s).");
            }).build());

        gui.setSlot(SETTINGS_ROW + 8, Menus.button(Items.CHEST, "Apply kit", ChatFormatting.GOLD,
                live.kit == null ? "No kit set." : "Currently: " + live.kit,
                "",
                "Click to pick one.")
            .setCallback((i, t, a, ignored) -> {
                Sfx.click(editor);
                KitsMenu.open(editor);
            }).build());

        // ---- what it does ----------------------------------------------------------------
        label(gui, ACTIONS_ROW, Items.BOOK, "Actions", "One-off things done to the bot.");

        gui.setSlot(ACTIONS_ROW + 1, Menus.button(Items.NAME_TAG, "Rename", ChatFormatting.AQUA,
                "Changes the name everyone sees.",
                "",
                "The account name stays as it is - it is the key",
                "every setting here is filed under, so changing it",
                "would mean respawning the bot.")
            .setCallback((i, t, a, ignored) -> promptRename(editor, botName)).build());

        gui.setSlot(ACTIONS_ROW + 2, Menus.button(Items.PLAYER_HEAD, "Change skin", ChatFormatting.AQUA,
                "Type whose skin to copy, the way renaming works.",
                "",
                "Shift click: a random look from the local pool.")
            .setCallback((i, t, a, ignored) -> {
                if (t.shift) {
                    if (scriptify.bots.api.SBotsApi.randomiseSkin(botName)) {
                        Menus.notify(editor, "Gave " + botName + " a new look.");
                    } else {
                        Menus.deny(editor, scriptify.bots.api.SBotsApi.skinPoolSize() == 0
                            ? "The skin pool is empty." : "Could not change that skin.");
                    }
                    return;
                }
                promptSkin(editor, botName);
            }).build());

        gui.setSlot(ACTIONS_ROW + 3, Menus.button(Items.ENDER_EYE, "Randomise identity",
                ChatFormatting.LIGHT_PURPLE,
                "A new name and a matching skin, together.",
                "Half an identity reads as a glitch, so both move at once.")
            .setCallback((i, t, a, ignored) -> {
                if (bot == null) {
                    Menus.deny(editor, "That bot is not here.");
                    return;
                }
                scriptify.feature.IdentityService.randomise(bot, name -> {
                    if (name.isPresent()) {
                        Menus.notify(editor, botName + " is now " + name.get() + ".");
                    } else {
                        Menus.deny(editor, "Could not find an identity to use.");
                    }
                });
            }).build());

        gui.setSlot(ACTIONS_ROW + 4, Menus.button(Items.COMPASS, "Teleport to me",
                ChatFormatting.YELLOW, "Brings the bot to where you are standing.")
            .setCallback((i, t, a, ignored) -> {
                if (bot == null) {
                    Menus.deny(editor, "That bot is not here.");
                    return;
                }
                bot.teleportTo((net.minecraft.server.level.ServerLevel) editor.level(),
                    editor.getX(), editor.getY(), editor.getZ(), java.util.Set.of(),
                    editor.getYRot(), editor.getXRot(), false);
                Menus.notify(editor, botName + " teleported to you.");
                Sfx.success(editor);
            }).build());

        gui.setSlot(ACTIONS_ROW + 5, Menus.button(Items.CLOCK, "Repeating action",
                ChatFormatting.GOLD,
                "Currently: " + describe(live.action()),
                "",
                "Click to cycle.")
            .setCallback((i, t, a, ignored) -> {
                settings.action = live.action().next();
                NpcRegistry.markDirty();
                Sfx.click(editor);
                repaint.run();
            }).build());

        // ---- how it moves ----------------------------------------------------------------
        label(gui, MOVEMENT_ROW, Items.FEATHER, "Movement", "Where the bot goes when nothing else decides.");

        boolean wandering = EditMode.isWandering(botName);
        gui.setSlot(MOVEMENT_ROW + 1, Menus.toggle(Items.COMPASS, "Wander", wandering,
                "Drifts around on its own.",
                "Stands down while a path or a scenario is running.")
            .setCallback((i, t, a, ignored) -> {
                boolean on = EditMode.toggleWander(botName);
                Sfx.toggle(editor, on);
                Menus.notify(editor, on ? botName + " is wandering." : botName + " stopped wandering.");
                repaint.run();
            }).build());

        boolean following = BotPath.isFollowing(botName);
        gui.setSlot(MOVEMENT_ROW + 2, Menus.button(Items.LEAD,
                following ? "Stop path" : "Start path",
                following ? ChatFormatting.RED : ChatFormatting.GREEN,
                following
                    ? "The bot is walking a path. Click to stop it."
                    : "Walk around dropping points, then finish.",
                following ? "" : "Your hotbar is borrowed while you draw, and given back after.")
            .setCallback((i, t, a, ignored) -> {
                if (following) {
                    BotPath.stopFollowing(botName);
                    Menus.notify(editor, botName + " stopped walking.");
                    repaint.run();
                    return;
                }
                editor.closeContainer();
                EditSession.beginPath(editor, botName);
            }).build());

        boolean replaying = scriptify.bots.npc.MovementReplay.isPlaying(botName);
        gui.setSlot(MOVEMENT_ROW + 3, Menus.button(Items.SPYGLASS,
                replaying ? "Stop replay" : "Record movement",
                replaying ? ChatFormatting.RED : ChatFormatting.AQUA,
                replaying
                    ? "The bot is repeating a recording. Click to stop it."
                    : "Walk it yourself and the bot repeats you exactly -",
                replaying ? "" : "where you looked and when you crouched, not just the route.")
            .setCallback((i, t, a, ignored) -> {
                if (replaying) {
                    scriptify.bots.npc.MovementReplay.stop(botName);
                    Menus.notify(editor, botName + " stopped repeating.");
                    repaint.run();
                    return;
                }
                editor.closeContainer();
                EditSession.beginRecord(editor, botName);
            }).build());

        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((i, t, a, ignored) -> {
                Sfx.click(editor);
                editor.closeContainer();
            }).build());
    }

    /** A band label: not clickable, there to say what the row underneath it is. */
    private static void label(SimpleGui gui, int slot, Item icon, String title, String hint) {
        gui.setSlot(slot, Menus.header(icon, title, hint).build());
    }

    /** A grey-or-green dye switch, for the plain booleans. */
    private static void dye(SimpleGui gui, ServerPlayer editor, int slot, String title, String hint,
                            Supplier<Boolean> get, Consumer<Boolean> set, Runnable repaint) {
        gui.setSlot(slot, Menus.dyeToggle(title, get.get(), hint)
            .setCallback((i, t, a, ignored) -> flip(editor, get, set, repaint)).build());
    }

    private static void toggle(SimpleGui gui, ServerPlayer editor, int slot, Item icon, String title,
                               String hint, Supplier<Boolean> get, Consumer<Boolean> set,
                               Runnable repaint) {
        gui.setSlot(slot, Menus.toggle(icon, title, get.get(), hint)
            .setCallback((i, t, a, ignored) -> flip(editor, get, set, repaint)).build());
    }

    private static void flip(ServerPlayer editor, Supplier<Boolean> get, Consumer<Boolean> set,
                             Runnable repaint) {
        // A held mouse button repeats, and without this a toggle flickers on and off under it.
        if (Menus.tooSoon(editor)) {
            return;
        }
        boolean next = !get.get();
        set.accept(next);
        NpcRegistry.markDirty();
        Sfx.toggle(editor, next);
        repaint.run();
    }

    private static String describe(NpcSettings.Action action) {
        return switch (action) {
            case NONE -> "nothing";
            case SWING -> "swinging, with the timing jittered";
            case JUMP -> "jumping continuously";
            case USE -> "holding right click";
        };
    }

    private static void promptRename(ServerPlayer editor, String botName) {
        AnvilInputGui anvil = new AnvilInputGui(editor, false);
        anvil.setTitle(Component.literal("Rename " + botName));
        anvil.setDefaultInputValue(botName);
        anvil.setSlot(0, Menus.button(Items.NAME_TAG, "New name", ChatFormatting.AQUA,
            "Type it, then click the output.").build());
        anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
                "Sets the displayed name.")
            .setCallback((i, t, a, ignored) -> {
                String wanted = anvil.getInput().trim();
                ServerPlayer bot = editor.level().getServer().getPlayerList().getPlayerByName(botName);
                if (bot == null || wanted.isEmpty()) {
                    Menus.deny(editor, "Could not rename that bot.");
                    return;
                }
                // Scriptify's own nickname system, which already rewrites the name on the wire for
                // every vanilla client. A bot is an ordinary player as far as it is concerned.
                scriptify.runtime.ScriptifyRuntime.nick().set(bot, wanted);
                Menus.notify(editor, botName + " is now shown as " + wanted + ".");
                open(editor, botName);
            }).build());
        anvil.open();
    }

    private static void promptSkin(ServerPlayer editor, String botName) {
        AnvilInputGui anvil = new AnvilInputGui(editor, false);
        anvil.setTitle(Component.literal("Skin for " + botName));
        anvil.setSlot(0, Menus.button(Items.PLAYER_HEAD, "Account name", ChatFormatting.AQUA,
            "Whose skin to copy.").build());
        anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
                "Looks the account up and applies its skin.")
            .setCallback((i, t, a, ignored) -> {
                String wanted = anvil.getInput().trim();
                ServerPlayer bot = editor.level().getServer().getPlayerList().getPlayerByName(botName);
                if (bot == null || wanted.isEmpty()) {
                    Menus.deny(editor, "Could not change that skin.");
                    return;
                }
                Menus.notify(editor, "Looking up " + wanted + "...");
                // Off the server thread and back again - the lookup is an HTTP call, and doing it
                // here would stall every player for as long as Mojang took to answer.
                scriptify.runtime.ScriptifyRuntime.skin().copySkinAsync(wanted, bot, applied -> {
                    if (applied) {
                        Menus.notify(editor, botName + " is wearing " + wanted + "'s skin.");
                    } else {
                        Menus.deny(editor, "No skin found for " + wanted + ".");
                    }
                });
                open(editor, botName);
            }).build());
        anvil.open();
    }
}
