package scriptify.bots.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.bot.BotActions;
import scriptify.bots.bot.BotCombat;
import scriptify.bots.bot.BotManager;

import java.util.Locale;

/**
 * One bot, in detail: who it is, what it is doing, and what you can tell it to do.
 *
 * <p>Bots all fight to the same rules, so this screen is about what this one is doing right
 * now and what you can tell it to do.</p>
 */
public final class BotMenu {

    private BotMenu() {}

    public static void open(ServerPlayer viewer, String botName, int returnPage) {
        SimpleGui gui = Menus.create(viewer, 6, botName);
        paint(gui, viewer, botName, returnPage);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer viewer, String botName,
                              int returnPage) {
        MinecraftServer server = viewer.level().getServer();
        if (server == null) return;

        ServerPlayer bot = BotManager.getBot(server, botName);
        if (bot == null) {
            gui.setSlot(22, Menus.button(Items.SKELETON_SKULL, botName, ChatFormatting.DARK_GRAY,
                "This bot is no longer online.").build());
            navigation(gui, viewer, returnPage);
            return;
        }

        var target = BotCombat.getTarget(botName);

        float healthFraction = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());
        ChatFormatting healthColour = healthFraction > 0.6f ? ChatFormatting.GREEN
            : healthFraction > 0.3f ? ChatFormatting.YELLOW : ChatFormatting.RED;

        // ---- identity ------------------------------------------------------------------
        var identity = Menus.head(bot.getGameProfile(), botName, Msg.BRAND)
            .glow()
            .addLoreLine(Msg.stat("Health",
                String.format(Locale.US, "%.1f / %.1f", bot.getHealth(), bot.getMaxHealth()),
                healthColour))
            .addLoreLine(Msg.stat("Target",
                target != null ? target.getName().getString() : "none",
                target != null ? ChatFormatting.RED : ChatFormatting.DARK_GRAY))
            .addLoreLine(Msg.stat("Doing",
                scriptify.bots.ai.Maneuver.intentOf(botName).name().toLowerCase(Locale.ROOT),
                ChatFormatting.GRAY));

        if (scriptify.bots.ai.Engagement.inBrawl(botName)) {
            identity.addLoreLine(Msg.stat("In a brawl",
                scriptify.bots.ai.Engagement.roleOf(botName).name().toLowerCase(Locale.ROOT),
                ChatFormatting.GOLD));
        }
        identity.addLoreLine(Msg.blank())
            .addLoreLine(Msg.hint("All bots share one behaviour."))
            .addLoreLine(Msg.hint("Tune them in Settings."));
        gui.setSlot(4, identity.build());

        // ---- orders --------------------------------------------------------------------
        gui.setSlot(20, Menus.button(Items.ENDER_PEARL, "Summon here", ChatFormatting.GREEN,
                "Teleports the bot to you.")
            .setCallback((i, type, action, ignored) -> {
                BotActions.execute(server, String.format(Locale.US, "tp %s %.2f %.2f %.2f",
                    botName, viewer.getX(), viewer.getY(), viewer.getZ()));
                Menus.notify(viewer, "Summoned " + botName);
            }).build());

        gui.setSlot(21, Menus.button(Items.IRON_SWORD, "Attack me", ChatFormatting.RED,
                "Sets you as this bot's target.",
                "",
                "Bots will not attack creative or",
                "spectator players.")
            .setCallback((i, type, action, ignored) -> {
                BotCombat.setTarget(botName, viewer.getName().getString());
                Menus.notify(viewer, botName + " is coming for you");
                paint(gui, viewer, botName, returnPage);
            }).build());

        gui.setSlot(22, Menus.button(scriptify.ui.DyedItems.banner(DyeColor.WHITE), "Stand down", ChatFormatting.YELLOW,
                target != null
                    ? "Currently on " + target.getName().getString()
                    : "Not fighting anyone.")
            .setCallback((i, type, action, ignored) -> {
                BotCombat.clearTarget(botName);
                Menus.notify(viewer, botName + " stood down");
                paint(gui, viewer, botName, returnPage);
            }).build());

        gui.setSlot(23, Menus.button(Items.CHEST, "Give a kit", ChatFormatting.GOLD,
                "A bot fights with whatever it is",
                "carrying, so gear is how you decide",
                "what it can do.",
                "",
                "Click to pick one.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(viewer);
                KitsMenu.open(viewer, botName);
            }).build());

        gui.setSlot(32, Menus.button(Items.BARRIER, "Remove", ChatFormatting.RED,
                "Disconnects this bot.",
                "",
                "Shift click to confirm.")
            .setCallback((i, type, action, ignored) -> {
                if (!type.shift) {
                    Menus.deny(viewer, "Shift click to confirm");
                    return;
                }
                BotManager.removeBot(server, botName, viewer.createCommandSourceStack());
                Menus.notify(viewer, "Removed " + botName);
                BotsMenu.open(viewer, returnPage);
            }).build());

        navigation(gui, viewer, returnPage);
    }

    private static void navigation(SimpleGui gui, ServerPlayer viewer, int returnPage) {
        gui.setSlot(Menus.BACK_SLOT, Menus.backButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(viewer);
                BotsMenu.open(viewer, returnPage);
            }).build());
        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(viewer);
                gui.close();
            }).build());
    }

}
