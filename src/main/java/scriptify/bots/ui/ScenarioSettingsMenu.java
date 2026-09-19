package scriptify.bots.ui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.scenario.Scenario;
import scriptify.bots.scenario.ScenarioManager;
import scriptify.bots.scenario.ScenarioSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;

/**
 * Settings for the next scenario of a given type.
 *
 * <p>The banner says so in as many words, because it is the one thing about this screen that
 * could surprise someone: these values are stamped into a scenario when it is built and never
 * consulted again. Editing them changes what you build next, not what is already out there
 * running - a staged scene should not quietly change behaviour because you were tuning
 * something else.</p>
 */
public final class ScenarioSettingsMenu {

    private ScenarioSettingsMenu() {}

    /**
     * One tunable value.
     *
     * <p>Everything is carried as a double and formatted on the way out; an int field simply
     * uses a step of one. One row type instead of three keeps the screen consistent.</p>
     */
    private record Entry(String name, Item icon, String hint,
                         Function<ScenarioSettings, Double> get,
                         ObjDoubleConsumer<ScenarioSettings> set,
                         double min, double max, double step, boolean integral) {

        String format(ScenarioSettings s) {
            double value = get.apply(s);
            return integral ? String.valueOf((int) Math.round(value))
                : String.format(java.util.Locale.US, "%.2f", value);
        }
    }

    private static Entry num(String name, Item icon, String hint,
                             Function<ScenarioSettings, Double> get,
                             ObjDoubleConsumer<ScenarioSettings> set,
                             double min, double max, double step) {
        return new Entry(name, icon, hint, get, set, min, max, step, step >= 1.0);
    }

    private static List<Entry> entriesFor(Scenario type) {
        List<Entry> entries = new ArrayList<>();

        entries.add(num("Roam radius", Items.COMPASS, "How far from the anchor they go",
            s -> s.radius, (s, v) -> s.radius = v, 4, 128, 4));
        entries.add(num("Walk speed", Items.LEATHER_BOOTS, "1.0 is the normal bot pace",
            s -> s.speed, (s, v) -> s.speed = v, 0.2, 1.4, 0.1));

        switch (type) {
            case SPAWN -> {
                entries.add(num("Population", Items.PLAYER_HEAD, "Bots kept alive at once",
                    s -> (double) s.population, (s, v) -> s.population = (int) v, 1, 40, 1));
                entries.add(num("Arrival gap", Items.CLOCK, "Seconds between arrivals",
                    s -> (double) s.spawnIntervalSeconds,
                    (s, v) -> s.spawnIntervalSeconds = (int) v, 2, 120, 2));
                entries.add(num("Lifespan min", scriptify.ui.DyedItems.candle(DyeColor.WHITE), "Seconds before they can die",
                    s -> (double) s.lifetimeMinSeconds,
                    (s, v) -> s.lifetimeMinSeconds = (int) v, 30, 1800, 30));
                entries.add(num("Lifespan max", scriptify.ui.DyedItems.candle(DyeColor.BLACK), "Longest they last, in seconds",
                    s -> (double) s.lifetimeMaxSeconds,
                    (s, v) -> s.lifetimeMaxSeconds = (int) v, 60, 3600, 30));
                entries.add(num("Death: fall", Items.FEATHER, "Relative weight, 0 to disable",
                    s -> (double) s.deathFall, (s, v) -> s.deathFall = (int) v, 0, 100, 5));
                entries.add(num("Death: starving", Items.ROTTEN_FLESH, "Relative weight",
                    s -> (double) s.deathStarve, (s, v) -> s.deathStarve = (int) v, 0, 100, 5));
                entries.add(num("Death: wither", Items.WITHER_SKELETON_SKULL, "Relative weight",
                    s -> (double) s.deathWither, (s, v) -> s.deathWither = (int) v, 0, 100, 5));
                entries.add(num("Death: player", Items.IRON_SWORD, "Relative weight",
                    s -> (double) s.deathPlayer, (s, v) -> s.deathPlayer = (int) v, 0, 100, 5));
                entries.add(num("Death: drowning", Items.WATER_BUCKET, "Relative weight",
                    s -> (double) s.deathDrown, (s, v) -> s.deathDrown = (int) v, 0, 100, 5));
                entries.add(num("Death: burning", Items.FLINT_AND_STEEL, "Relative weight",
                    s -> (double) s.deathBurn, (s, v) -> s.deathBurn = (int) v, 0, 100, 5));
            }
            case CONVERSATION -> {
                entries.add(num("Turn min", Items.CLOCK, "Shortest speaking turn, in ticks",
                    s -> (double) s.turnMinTicks, (s, v) -> s.turnMinTicks = (int) v, 20, 400, 10));
                entries.add(num("Turn max", Items.CLOCK, "Longest speaking turn, in ticks",
                    s -> (double) s.turnMaxTicks, (s, v) -> s.turnMaxTicks = (int) v, 40, 600, 10));
                entries.add(num("Nodding", Items.SUNFLOWER, "Chance a second that a listener nods",
                    s -> s.nodChance, (s, v) -> s.nodChance = v, 0, 1, 0.05));
                entries.add(num("Fidgeting", Items.STRING, "Chance a second of a shift or a hop",
                    s -> s.fidgetChance, (s, v) -> s.fidgetChance = v, 0, 1, 0.05));
            }
            case CROWD -> {
                entries.add(num("Look drift", Items.SPYGLASS, "Degrees off the target they wander",
                    s -> s.lookJitterDegrees, (s, v) -> s.lookJitterDegrees = v, 0, 40, 1));
                entries.add(num("Reactions", Items.FIREWORK_ROCKET,
                    "Chance a second of a spin, nod or hop",
                    s -> s.reactionChance, (s, v) -> s.reactionChance = v, 0, 1, 0.02));
                entries.add(num("Sneak fidget", Items.LEATHER, "Chance a second of a crouch",
                    s -> s.sneakChance, (s, v) -> s.sneakChance = v, 0, 1, 0.02));
            }
            case PRISON_CELL -> {
                entries.add(num("Cell scan limit", Items.IRON_BARS,
                    "Largest cell mapped, in blocks",
                    s -> (double) s.cellScanLimit, (s, v) -> s.cellScanLimit = (int) v, 50, 2000, 50));
                entries.add(num("Staring", Items.ENDER_EYE, "Chance a second of stopping dead",
                    s -> s.stareChance, (s, v) -> s.stareChance = v, 0, 1, 0.05));
                entries.add(num("Restless share", Items.RABBIT_FOOT,
                    "Share who pace fast and hop instead",
                    s -> s.restlessShare, (s, v) -> s.restlessShare = v, 0, 1, 0.1));
            }
        }
        return entries;
    }

    public static void open(ServerPlayer player, Scenario type) {
        SimpleGui gui = Menus.create(player, 6, type.displayName + " settings");
        paint(gui, player, type);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer player, Scenario type) {
        Menus.clearContent(gui);

        gui.setSlot(4, Menus.header(type.icon, type.displayName + " settings",
            "These apply to the NEXT scenario you build.",
            "Scenarios already running keep their own.").build());

        ScenarioSettings settings = ScenarioManager.template(type);
        List<Entry> entries = entriesFor(type);

        int slot = Menus.CONTENT_START + 1;
        for (Entry entry : entries) {
            if (slot >= Menus.CONTENT_END - 1) break;
            if (slot % Menus.WIDTH == Menus.WIDTH - 1) slot += 2;

            gui.setSlot(slot, Menus.button(entry.icon(),
                    entry.name() + ": " + entry.format(settings), ChatFormatting.WHITE,
                    entry.hint(),
                    "",
                    "Left-click  increase",
                    "Right-click  decrease")
                .setCallback((index, clickType, action, ignored) -> {
                    boolean down = clickType == ClickType.MOUSE_RIGHT
                        || clickType == ClickType.MOUSE_RIGHT_SHIFT;
                    double current = entry.get().apply(settings);
                    double next = current + (down ? -entry.step() : entry.step());
                    next = Math.max(entry.min(), Math.min(entry.max(), next));
                    // Doubles accumulate error over repeated clicks; snap to the step grid so
                    // the displayed value stays the value.
                    next = Math.round(next / entry.step()) * entry.step();
                    entry.set().accept(settings, next);
                    Sfx.click(player);
                    paint(gui, player, type);
                }).build());
            slot++;
        }

        gui.setSlot(Menus.CONTENT_END + 1, Menus.button(Items.LAVA_BUCKET, "Reset to defaults",
                ChatFormatting.RED, "Restores every value on this screen.")
            .setCallback((index, clickType, action, ignored) -> {
                ScenarioManager.resetTemplate(type);
                Menus.notify(player, type.displayName + " settings reset");
                paint(gui, player, type);
            }).build());

        gui.setSlot(Menus.BACK_SLOT, Menus.backButton()
            .setCallback((index, clickType, action, ignored) -> {
                Sfx.click(player);
                ScenariosMenu.open(player);
            }).build());
        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((index, clickType, action, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }
}
