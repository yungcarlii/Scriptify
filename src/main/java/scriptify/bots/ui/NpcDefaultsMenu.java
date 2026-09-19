package scriptify.bots.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.npc.NpcRegistry;
import scriptify.bots.npc.NpcSettings;

import java.util.function.Consumer;

/**
 * What every new bot starts as.
 *
 * <p>Four rows, one row of toggles, laid out the way the reference mod does it - because the
 * layout is genuinely good: the dyes read as a row of lights, and a glance tells you what is on
 * without hovering anything.</p>
 */
public final class NpcDefaultsMenu {

    private NpcDefaultsMenu() {}

    public static void open(ServerPlayer player) {
        SimpleGui gui = Menus.panel(player, 4, "NPC Defaults");
        paint(gui, player);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer player) {
        Menus.fill(gui);

        gui.setSlot(4, Menus.header(Items.END_ROD, "NPC Defaults",
            "Applied to every bot spawned from now on.",
            "Bots already out keep what they have.").build());

        NpcSettings s = NpcRegistry.defaults();

        // Row of lights: the four everyday toggles, then the two that change what a death means.
        Runnable repaint = () -> paint(gui, player);
        toggle(gui, player, 10, scriptify.ui.DyedItems.dye(DyeColor.LIME), "Hittable",
            "Whether anything can damage it.", s::hittable, v -> s.hittable = v, repaint);
        toggle(gui, player, 11, scriptify.ui.DyedItems.dye(DyeColor.LIME), "Collidable",
            "Whether it pushes and gets pushed.", s::collidable, v -> s.collidable = v, repaint);
        toggle(gui, player, 12, scriptify.ui.DyedItems.dye(DyeColor.LIME), "Crouching",
            "Holds a crouch permanently.", s::crouching, v -> s.crouching = v, repaint);
        toggle(gui, player, 13, scriptify.ui.DyedItems.dye(DyeColor.LIME), "Shows in tab",
            "Whether it appears in the player list.", s::listed, v -> s.listed = v, repaint);

        toggle(gui, player, 14, Items.TOTEM_OF_UNDYING, "Immortal",
            "Survives everything.", s::immortal, v -> s.immortal = v, repaint);
        toggle(gui, player, 15, scriptify.ui.DyedItems.dye(DyeColor.LIME), "Drops on death",
            "Leaves its gear on the floor.", s::dropsOnDeath, v -> s.dropsOnDeath = v, repaint);

        gui.setSlot(16, Menus.button(Items.CHEST, "Default kit", ChatFormatting.GOLD,
                s.kit == null ? "None - spawns empty handed." : "Currently: " + s.kit,
                "",
                "Click to pick one.")
            .setCallback((i, t, a, ignored) -> {
                Sfx.click(player);
                KitsMenu.open(player);
            }).build());

        gui.setSlot(31, Menus.button(Items.BARRIER, "Reset to defaults", ChatFormatting.RED,
                "Puts every value above back to stock.")
            .setCallback((i, t, a, ignored) -> {
                NpcRegistry.resetDefaults();
                Menus.notify(player, "Defaults reset");
                paint(gui, player);
            }).build());

        gui.setSlot(27, Menus.backButton()
            .setCallback((i, t, a, ignored) -> {
                Sfx.click(player);
                MainMenu.open(player);
            }).build());
        gui.setSlot(35, Menus.closeButton()
            .setCallback((i, t, a, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }

    /**
     * A light.
     *
     * <p>Grey when off, lime when on, with the state spelled out in the name as well as shown in
     * the colour - a row of dyes is only readable at a glance once you already know the mod, and
     * the word makes it readable the first time.</p>
     */
    static void toggle(SimpleGui gui, ServerPlayer player, int slot, Item onIcon,
                       String name, String hint,
                       java.util.function.BooleanSupplier get, Consumer<Boolean> set,
                       Runnable repaint) {
        boolean on = get.getAsBoolean();
        GuiElementBuilder builder = new GuiElementBuilder(on ? onIcon : scriptify.ui.DyedItems.dye(DyeColor.GRAY))
            .setName(Msg.title(name + "  ", Msg.BLUE).append(Msg.state(on)))
            .addLoreLine(Msg.hint(hint))
            .addLoreLine(Msg.blank())
            .addLoreLine(Msg.action("Click to turn " + (on ? "off" : "on")));
        if (on) builder.glow();

        gui.setSlot(slot, builder.setCallback((i, t, a, ignored) -> {
            set.accept(!on);
            NpcRegistry.markDirty();
            Sfx.click(player);
            repaint.run();
        }).build());
    }
}
