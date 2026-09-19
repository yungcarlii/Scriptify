package scriptify.bots.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotSettings;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The {@code /bots} dashboard.
 *
 * <p>Laid out as a bordered six-row grid: a status header, a row of destinations, then a row
 * of the switches you actually reach for mid-session. Toggles flip in place and the menu
 * repaints, so what you see is always live state rather than what it was when you opened it.</p>
 */
public final class MainMenu {

    private MainMenu() {}

    public static void open(ServerPlayer player) {
        SimpleGui gui = Menus.create(player, 6, "Control Panel");
        paint(gui, player);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer player) {
        BotSettings settings = BotSettings.get();
        int bots = BotManager.getBotCount();

        gui.setSlot(4, Menus.header(Items.NETHER_STAR, "SBots",
            bots + (bots == 1 ? " bot active" : " bots active"),
            "Server-side bot toolkit.",
            "Everything here works on a vanilla client.").build());

        // ---- destinations ------------------------------------------------------------
        gui.setSlot(11, Menus.button(Items.EGG, "Spawn", ChatFormatting.GREEN,
                "Pick a personality, or spawn a batch.",
                "Sweat, Rusher, Smasher, Rookie.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                SpawnMenu.open(player);
            }).build());

        gui.setSlot(12, Menus.button(Items.PLAYER_HEAD, "Bots", ChatFormatting.AQUA,
                bots + " online",
                "Inspect personalities, give orders, remove.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                BotsMenu.open(player, 0);
            }).build());

        gui.setSlot(13, Menus.button(Items.COMPARATOR, "Settings", ChatFormatting.GOLD,
                "Every tunable, grouped by what it affects.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                SettingsMenu.open(player);
            }).build());

        gui.setSlot(14, Menus.button(scriptify.ui.DyedItems.banner(DyeColor.WHITE), "Teams", ChatFormatting.LIGHT_PURPLE,
                "Factions, alliances and group orders.",
                "Who fights whom, and who they fight with.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                TeamsMenu.open(player);
            }).build());

        gui.setSlot(15, Menus.button(Items.CHEST, "Kits", ChatFormatting.YELLOW,
                "Saved loadouts you can hand to bots.",
                "Save a loadout, then hand it out.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                KitsMenu.open(player);
            }).build());

        gui.setSlot(16, Menus.button(Items.PAINTING, "Scenarios", ChatFormatting.LIGHT_PURPLE,
                "Staged behaviour: spawns, conversations,",
                "crowds, prison cells, wandering routes.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                ScenariosMenu.open(player);
            }).build());

        gui.setSlot(10, Menus.button(Items.STICK, "Tools", ChatFormatting.AQUA,
                "Sticks and shields that do to a bot",
                "what a command would, without its name.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                ToolsMenu.open(player);
            }).build());

        // ---- live switches -----------------------------------------------------------
        quickToggle(gui, player, 20, Items.IRON_SWORD, "Combat",
            settings::isCombatEnabled, settings::setCombatEnabled,
            "Master switch for all fighting.");

        quickToggle(gui, player, 21, Items.SPYGLASS, "Auto target",
            settings::isAutoTargetEnabled, settings::setAutoTargetEnabled,
            "Bots pick their own fights.");

        quickToggle(gui, player, 22, Items.ARMOR_STAND, "Fight each other",
            settings::isTargetOtherBots, settings::setTargetOtherBots,
            "Bots treat other bots as valid targets.");

        quickToggle(gui, player, 23, Items.SHIELD, "Squad tactics",
            settings::isSquadTacticsEnabled, settings::setSquadTacticsEnabled,
            "Focus fire, flanking, peeling, spacing.");

        quickToggle(gui, player, 24, Items.GLASS, "Line of sight",
            settings::isRequireLineOfSight, settings::setRequireLineOfSight,
            "Bots cannot attack through walls.");

        // ---- danger ------------------------------------------------------------------
        gui.setSlot(31, Menus.button(Items.BARRIER, "Remove all bots", ChatFormatting.RED,
                bots + " would be removed",
                "",
                "Shift click to confirm.")
            .setCallback((i, type, action, ignored) -> {
                if (!type.shift) {
                    Menus.deny(player, "Shift click to confirm removing all bots");
                    return;
                }
                BotManager.removeAllBots(player.level().getServer(),
                    player.createCommandSourceStack());
                Menus.notify(player, "Removed every bot.");
                paint(gui, player);
            }).build());

        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }

    /** A switch that flips the setting, confirms, and repaints so the state stays honest. */
    private static void quickToggle(SimpleGui gui, ServerPlayer player, int slot,
                                    net.minecraft.world.item.Item icon, String label,
                                    BooleanSupplier getter, Consumer<Boolean> setter,
                                    String description) {
        gui.setSlot(slot, Menus.toggle(icon, label, getter.getAsBoolean(), description)
            .setCallback((i, type, action, ignored) -> {
                boolean next = !getter.getAsBoolean();
                setter.accept(next);
                BotSettings.flush();
                Menus.notifyToggle(player, label + ": " + (next ? "on" : "off"), next);
                paint(gui, player);
            }).build());
    }
}
