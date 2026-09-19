package scriptify.bots.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.ai.Engagement;
import scriptify.bots.ai.Maneuver;
import scriptify.bots.bot.BotCombat;
import scriptify.bots.bot.BotManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The roster.
 *
 * <p>Every bot as a head, with what it is doing right now written underneath: health, its role
 * in whatever fight it is in, and who it is on. It is the screen that tells you whether the
 * group behaviour is doing what you think it is doing.</p>
 */
public final class BotsMenu {

    private BotsMenu() {}

    public static void open(ServerPlayer player, int page) {
        SimpleGui gui = Menus.create(player, 6, "Bots");
        paint(gui, player, page);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer player, int page) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        List<String> names = new ArrayList<>(BotManager.botNames());
        names.sort(String::compareToIgnoreCase);

        int perPage = Menus.PER_PAGE - 14;   // content area minus the frame columns
        int totalPages = Math.max(1, (names.size() + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, totalPages - 1));

        Menus.clearContent(gui);

        gui.setSlot(4, Menus.header(Items.PLAYER_HEAD, "Roster",
            names.size() + " bots",
            "Click a head for orders and details.").build());

        int index = current * perPage;
        for (int slot = Menus.CONTENT_START; slot < Menus.CONTENT_END; slot++) {
            int column = slot % Menus.WIDTH;
            if (column == 0 || column == Menus.WIDTH - 1) continue;
            if (index >= names.size()) break;

            String rosterName = names.get(index++);
            ServerPlayer bot = BotManager.getBot(server, rosterName);
            gui.setSlot(slot, entry(player, gui, bot, rosterName, current));
        }

        if (names.isEmpty()) {
            gui.setSlot(22, Menus.button(Items.STRUCTURE_VOID, "No bots", ChatFormatting.GRAY,
                "Spawn some from the main menu.").build());
        }

        if (current > 0) {
            gui.setSlot(Menus.PREV_SLOT, Menus.pageButton("Previous", current, totalPages)
                .setCallback((i, type, action, ignored) -> {
                    Sfx.click(player);
                    paint(gui, player, current - 1);
                }).build());
        }
        if (current < totalPages - 1) {
            gui.setSlot(Menus.NEXT_SLOT, Menus.pageButton("Next", current + 2, totalPages)
                .setCallback((i, type, action, ignored) -> {
                    Sfx.click(player);
                    paint(gui, player, current + 1);
                }).build());
        }

        gui.setSlot(Menus.BACK_SLOT, Menus.backButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                MainMenu.open(player);
            }).build());
        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }

    private static eu.pb4.sgui.api.elements.GuiElement entry(ServerPlayer viewer,
                                                             SimpleGui gui,
                                                             ServerPlayer bot,
                                                             String rosterName, int page) {
        if (bot == null) {
            return Menus.button(Items.SKELETON_SKULL, rosterName, ChatFormatting.DARK_GRAY,
                "Offline - waiting to rejoin.").build();
        }

        String name = bot.getName().getString();
        var target = BotCombat.getTarget(name);

        float healthFraction = bot.getHealth() / Math.max(1.0f, bot.getMaxHealth());
        ChatFormatting healthColour = healthFraction > 0.6f ? ChatFormatting.GREEN
            : healthFraction > 0.3f ? ChatFormatting.YELLOW : ChatFormatting.RED;

        var builder = Menus.head(bot.getGameProfile(), name, ChatFormatting.WHITE)
            .addLoreLine(Msg.stat("Health",
                String.format(java.util.Locale.US, "%.1f / %.1f",
                    bot.getHealth(), bot.getMaxHealth()), healthColour))
            .addLoreLine(Msg.stat("Target",
                target != null ? target.getName().getString() : "none",
                target != null ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));

        if (Engagement.inBrawl(name)) {
            builder.addLoreLine(Msg.stat("In a brawl",
                Engagement.roleOf(name).name().toLowerCase(java.util.Locale.ROOT),
                ChatFormatting.GOLD));
        }
        builder.addLoreLine(Msg.stat("Doing",
            Maneuver.intentOf(name).name().toLowerCase(java.util.Locale.ROOT), ChatFormatting.GRAY));

        builder.addLoreLine(Msg.blank())
            .addLoreLine(Msg.action("Click for orders"));

        return builder.setCallback((i, type, action, ignored) -> {
            Sfx.click(viewer);
            BotMenu.open(viewer, name, page);
        }).build();
    }
}
