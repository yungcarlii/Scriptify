package scriptify.bots.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotNameGenerator;
import scriptify.bots.bot.BotSettings;

/**
 * Spawning.
 *
 * <p>Every bot is the same bot: they all fight to the settings in the control panel. What
 * changes a fight is the gear you hand out and how many of them there are, so this screen is
 * about counts and nothing else.</p>
 */
public final class SpawnMenu {

    private SpawnMenu() {}

    public static void open(ServerPlayer player) {
        SimpleGui gui = Menus.create(player, 6, "Spawn");
        paint(gui, player);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer player) {
        gui.setSlot(4, Menus.header(Items.EGG, "Spawn bots",
            "All bots behave identically - tune them",
            "in Settings, arm them with Kits.").build());

        single(gui, player, 20, 1);
        single(gui, player, 21, 3);
        single(gui, player, 22, 5);
        batch(gui, player, 23, 10);
        batch(gui, player, 24, 25);

        gui.setSlot(31, Menus.button(Items.CHEST, "Give them gear", ChatFormatting.GOLD,
                "A bot with nothing swings its fists.",
                "",
                "Save what you are carrying as a kit,",
                "then hand it to everyone nearby.")
            .setCallback((slot, type, action, ignored) -> {
                Sfx.click(player);
                KitsMenu.open(player);
            }).build());

        gui.setSlot(Menus.BACK_SLOT, Menus.backButton()
            .setCallback((slot, type, action, ignored) -> {
                Sfx.click(player);
                MainMenu.open(player);
            }).build());
        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((slot, type, action, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }

    /** Small counts spawn immediately, spread in a ring so they do not land inside each other. */
    private static void single(SimpleGui gui, ServerPlayer player, int slot, int count) {
        gui.setSlot(slot, Menus.button(Items.EGG, "Spawn " + count, ChatFormatting.GREEN,
                count == 1 ? "One bot, where you are standing."
                    : count + " bots, spread around you.")
            .setCallback((index, type, action, ignored) -> {
                spawn(player, count);
                paint(gui, player);
            }).build());
    }

    /** Larger counts go through the paced queue so joining players cannot stall the tick. */
    private static void batch(SimpleGui gui, ServerPlayer player, int slot, int count) {
        gui.setSlot(slot, Menus.button(Items.SPAWNER, "Spawn " + count, ChatFormatting.GREEN,
                "Spread around you, paced so the",
                "server keeps up while they join.")
            .setCallback((index, type, action, ignored) -> {
                MinecraftServer server = player.level().getServer();
                if (server == null) return;
                int cap = BotSettings.get().getMaxMassSpawn();
                if (count > cap) {
                    Menus.deny(player, "Mass spawn cap is " + cap);
                    return;
                }
                BotManager.queueMassSpawn(server, count,
                    new Vec3(player.getX(), player.getY(), player.getZ()),
                    player.createCommandSourceStack());
                Menus.notify(player, "Spawning " + count + " bots...");
                Sfx.spawn(player);
            }).build());
    }

    private static void spawn(ServerPlayer player, int count) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            String name = BotNameGenerator.generateUniqueName();
            double angle = i * 2.399963;                 // golden angle: an even, non-repeating ring
            double radius = count > 1 ? 1.5 + i * 0.35 : 0;
            Vec3 pos = new Vec3(
                player.getX() + Math.cos(angle) * radius,
                player.getY(),
                player.getZ() + Math.sin(angle) * radius);

            if (BotManager.spawnBot(server, name, player.createCommandSourceStack(), pos, false)) {
                spawned++;
            }
        }

        if (spawned == 0) {
            Menus.deny(player, "Could not spawn - is HeroBot installed?");
            return;
        }
        Menus.notify(player, "Spawned " + spawned + (spawned == 1 ? " bot" : " bots"));
        Sfx.spawn(player);
    }
}
