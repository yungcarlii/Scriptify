package scriptify.bots.ui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.scenario.ScenarioInstance;
import scriptify.bots.scenario.ScenarioManager;

import java.util.List;
import java.util.Locale;

/**
 * The scenarios currently running.
 *
 * <p>Each one shows as its own block, so the list reads at a glance - four grass blocks is four
 * spawns without reading a word. Frozen ones say so: a scenario doing nothing because nobody is
 * near it looks identical to a broken one otherwise, and that is worth a line.</p>
 */
public final class ScenarioListMenu {

    private ScenarioListMenu() {}

    public static void open(ServerPlayer player) {
        SimpleGui gui = Menus.create(player, 6, "Running scenarios");
        paint(gui, player);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer player) {
        Menus.clearContent(gui);

        List<ScenarioInstance> all = ScenarioManager.all();

        gui.setSlot(4, Menus.header(Items.PAINTING, "Running scenarios",
            all.isEmpty() ? "Nothing staged." : all.size() + " running.",
            "Shift-right-click one to stop it.").build());

        int slot = Menus.CONTENT_START;
        for (ScenarioInstance instance : all) {
            if (slot >= Menus.CONTENT_END) break;
            int column = slot % Menus.WIDTH;
            if (column == 0 || column == Menus.WIDTH - 1) {
                slot++;
                continue;
            }

            var anchor = instance.anchor();
            String where = anchor == null
                ? "Follows its bots"
                : String.format(Locale.US, "At %.0f %.0f %.0f", anchor.x, anchor.y, anchor.z);

            int id = instance.id;
            gui.setSlot(slot, Menus.button(instance.type.icon,
                    "#" + id + "  " + instance.type.displayName,
                    instance.live ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                    where,
                    instance.bots.size() + (instance.bots.size() == 1 ? " bot" : " bots"),
                    instance.live ? "Running" : "Frozen - nobody is nearby",
                    "",
                    "Shift-right-click  stop and remove")
                .setCallback((i, clickType, action, ignored) -> {
                    if (clickType != ClickType.MOUSE_RIGHT_SHIFT) {
                        Menus.notify(player, "Shift-right-click to stop scenario #" + id);
                        return;
                    }
                    ScenarioManager.remove(id);
                    Menus.notify(player, "Stopped scenario #" + id);
                    Sfx.click(player);
                    paint(gui, player);
                }).build());
            slot++;
        }

        if (!all.isEmpty()) {
            gui.setSlot(Menus.CONTENT_END + 1, Menus.button(Items.BARRIER, "Stop all",
                    ChatFormatting.RED,
                    "Removes every scenario.",
                    "",
                    "Shift-right-click to confirm.")
                .setCallback((i, clickType, action, ignored) -> {
                    if (clickType != ClickType.MOUSE_RIGHT_SHIFT) {
                        Menus.deny(player, "Shift-right-click to confirm");
                        return;
                    }
                    int count = ScenarioManager.removeAll();
                    Menus.notify(player, "Stopped " + count + " scenarios");
                    paint(gui, player);
                }).build());
        }

        gui.setSlot(Menus.BACK_SLOT, Menus.backButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                ScenariosMenu.open(player);
            }).build());
        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }
}
