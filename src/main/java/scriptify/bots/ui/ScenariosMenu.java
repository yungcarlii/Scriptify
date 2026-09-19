package scriptify.bots.ui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.scenario.Scenario;
import scriptify.bots.scenario.ScenarioDraft;
import scriptify.bots.scenario.ScenarioInstance;
import scriptify.bots.scenario.ScenarioManager;
import scriptify.bots.scenario.ScenarioTools;

/**
 * The scenario picker.
 *
 * <p>One row, one block per scenario, the way a shop screen reads: you are choosing a thing,
 * not navigating a tree. Left-click builds one, right-click opens what the next one will be
 * built with.</p>
 */
public final class ScenariosMenu {

    private ScenariosMenu() {}

    /** Middle row of a three-row screen, centred. */
    private static final int[] SLOTS = {11, 12, 14, 15};

    public static void open(ServerPlayer player) {
        SimpleGui gui = Menus.create(player, 3, "Scenarios");
        paint(gui, player);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer player) {
        Scenario[] types = Scenario.values();
        for (int i = 0; i < types.length && i < SLOTS.length; i++) {
            Scenario type = types[i];
            gui.setSlot(SLOTS[i], entry(player, gui, type).build());
        }

        int running = ScenarioManager.all().size();
        gui.setSlot(4, Menus.header(Items.PAINTING, "Scenarios",
            running == 0 ? "Nothing staged yet." : running + " running.",
            "Left-click to build, right-click for settings.").build());

        gui.setSlot(22, Menus.button(Items.BARRIER, "Running scenarios", ChatFormatting.YELLOW,
                running + " active",
                "Stop them, or see which are frozen.")
            .setCallback((slot, type, action, ignored) -> {
                Sfx.click(player);
                ScenarioListMenu.open(player);
            }).build());

        gui.setSlot(18, Menus.backButton()
            .setCallback((slot, type, action, ignored) -> {
                Sfx.click(player);
                MainMenu.open(player);
            }).build());
        gui.setSlot(26, Menus.closeButton()
            .setCallback((slot, type, action, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }

    private static eu.pb4.sgui.api.elements.GuiElementBuilder entry(
        ServerPlayer player, SimpleGui gui, Scenario type) {

        String[] lore = new String[type.description.length + 3];
        System.arraycopy(type.description, 0, lore, 0, type.description.length);
        lore[type.description.length] = "";
        lore[type.description.length + 1] = "Left-click  build one";
        lore[type.description.length + 2] = "Right-click  settings for the next one";

        return Menus.button(type.icon, type.displayName, ChatFormatting.AQUA, lore)
            .setCallback((slot, clickType, action, ignored) -> {
                if (clickType == ClickType.MOUSE_RIGHT || clickType == ClickType.MOUSE_RIGHT_SHIFT) {
                    Sfx.click(player);
                    ScenarioSettingsMenu.open(player, type);
                    return;
                }
                Sfx.click(player);
                begin(player, gui, type);
            });
    }

    /**
     * Starts building a scenario of this type and tells the player what to do next.
     *
     * <p>Each type is set up differently - some by walking around placing points, some by
     * pointing a stick at bots - so the prompt is specific rather than generic. A prompt that
     * says "place a point" for a scenario built with a stick is worse than no prompt.</p>
     */
    private static void begin(ServerPlayer player, SimpleGui gui, Scenario type) {
        ScenarioManager.beginDraft(player, type);
        gui.close();

        switch (type) {
            case CONVERSATION -> {
                give(player, ScenarioTools.conversationStick());
                Msg.chat(player, Msg.line("Right-click bots with the Conversation Stick, "
                    + "then run /bots scenarios finish"));
            }
            case PRISON_CELL -> {
                give(player, ScenarioTools.prisonStick());
                Msg.chat(player, Msg.line("Right-click with the Prison Stick and wait 5s, "
                    + "then run /bots scenarios finish"));
            }
            case CROWD -> Msg.chat(player, Msg.line(
                "Run /bots scenarios crowd <radius>, then /bots scenarios finish"));
            default -> Msg.chat(player, Msg.line(
                "Place a scenario point using /bots scenarios point add, "
                    + "and if you are done use /bots scenarios finish"));
        }
        Sfx.spawn(player);
    }

    private static void give(ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /**
     * The standing reminder over the XP bar while a scenario is being built.
     *
     * <p>Setting one up means walking around the world with the menu closed, which is exactly
     * when it is easy to forget what you were doing. The action bar keeps the two commands in
     * front of the player without putting anything in chat.</p>
     */
    /** Shown after finishing, so the player can see what they made. */
    public static void announce(ServerPlayer player, ScenarioInstance instance) {
        Msg.chat(player, Msg.success("Scenario #" + instance.id + " started - " + instance.describe()));
    }
}
