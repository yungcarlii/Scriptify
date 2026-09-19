package scriptify.bots.ui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.bot.BotKits;
import scriptify.bots.bot.BotManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Kits, as a screen rather than a command to memorise.
 *
 * <p>Handing gear out is the single most common thing anyone does with this mod, and it was the
 * one thing the panel refused to do - the button existed only to tell you the command to type.
 * A menu that names a command is worse than no button: it takes a click to discover that
 * clicking does nothing.</p>
 *
 * <p>Naming a new kit needs typed text, which a chest screen cannot take. That part opens an
 * anvil instead, which is the one vanilla screen with a text field in it.</p>
 */
public final class KitsMenu {

    private KitsMenu() {}

    /**
     * Radius used by the "give to everyone nearby" action.
     *
     * <p>The stick's radius, deliberately. Two ways of doing the same thing that disagree about
     * how far "nearby" reaches is a bug waiting for somebody to notice it in footage.</p>
     */
    private static final double NEARBY_RADIUS = scriptify.bots.tool.BotTool.RADIUS;

    /** Opens the kit list. When {@code botName} is set, picking a kit gives it to that bot. */
    public static void open(ServerPlayer player, String botName) {
        SimpleGui gui = Menus.create(player, 6, botName == null ? "Kits" : "Kits · " + botName);
        paint(gui, player, botName, 0);
        gui.open();
    }

    public static void open(ServerPlayer player) {
        open(player, null);
    }

    private static void paint(SimpleGui gui, ServerPlayer player, String botName, int page) {
        Menus.clearContent(gui);

        List<String> kits = new ArrayList<>(BotKits.getKitNames());
        kits.sort(String::compareToIgnoreCase);

        gui.setSlot(4, Menus.header(Items.CHEST, "Kits",
            kits.isEmpty() ? "No kits saved yet." : kits.size() + " saved.",
            botName == null
                ? "Click a kit to hand it out."
                : "Click a kit to give it to " + botName + ".").build());

        int total = Math.max(1, (kits.size() + Menus.PER_PAGE - 1) / Menus.PER_PAGE);
        page = Math.max(0, Math.min(page, total - 1));

        int slot = Menus.CONTENT_START;
        for (int i = page * Menus.PER_PAGE; i < kits.size() && slot < Menus.CONTENT_END; i++) {
            int column = slot % Menus.WIDTH;
            if (column == 0 || column == Menus.WIDTH - 1) {
                slot++;
                i--;
                continue;
            }
            String kit = kits.get(i);
            gui.setSlot(slot, kitButton(gui, player, botName, kit, page).build());
            slot++;
        }

        // ---- actions -------------------------------------------------------------------
        gui.setSlot(Menus.CONTENT_END + 1, Menus.button(Items.WRITABLE_BOOK,
                "Save my inventory as a kit", ChatFormatting.GREEN,
                "Copies exactly what you are carrying,",
                "armour and offhand included.",
                "",
                "Click to name it.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                promptNewKit(player, botName, false);
            }).build());

        gui.setSlot(Menus.CONTENT_END + 3, Menus.button(Items.CRAFTING_TABLE,
                "Build a kit from scratch", ChatFormatting.GREEN,
                "Opens an empty inventory to fill in.",
                "Nothing you are carrying is touched.",
                "",
                "Click to name it.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                promptNewKit(player, botName, true);
            }).build());

        if (botName == null) {
            gui.setSlot(Menus.CONTENT_END + 2, Menus.button(Items.BEACON,
                    "Everything within " + (int) NEARBY_RADIUS + " blocks", ChatFormatting.AQUA,
                    "Whichever kit you click next goes to",
                    "every bot in range, not just one.",
                    "",
                    "This is the default - click a kit.").build());
        }

        int shown = page;
        if (total > 1) {
            gui.setSlot(Menus.PREV_SLOT, Menus.pageButton("Previous", shown, total)
                .setCallback((i, type, action, ignored) -> paint(gui, player, botName, shown - 1))
                .build());
            gui.setSlot(Menus.NEXT_SLOT, Menus.pageButton("Next", shown, total)
                .setCallback((i, type, action, ignored) -> paint(gui, player, botName, shown + 1))
                .build());
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

    private static eu.pb4.sgui.api.elements.GuiElementBuilder kitButton(
        SimpleGui gui, ServerPlayer player, String botName, String kit, int page) {

        String action = botName == null
            ? "Left-click  give to bots within " + (int) NEARBY_RADIUS
            : "Left-click  give to " + botName;

        return Menus.button(Items.LEATHER_CHESTPLATE, kit, ChatFormatting.GOLD,
                action,
                "Right-click  edit what is in it",
                "Shift-left-click  take a Kit Stick for it",
                "Shift-right-click  delete this kit")
            .setCallback((i, clickType, act, ignored) -> {
                if (clickType == ClickType.MOUSE_RIGHT_SHIFT) {
                    if (BotKits.deleteKit(kit)) {
                        Menus.notify(player, "Deleted kit " + kit);
                    } else {
                        Menus.deny(player, "Could not delete " + kit);
                    }
                    paint(gui, player, botName, page);
                    return;
                }
                if (clickType == ClickType.MOUSE_RIGHT) {
                    gui.close();
                    BotKitEditMenu.open(player, kit, botName);
                    return;
                }
                if (clickType == ClickType.MOUSE_LEFT_SHIFT) {
                    String problem = scriptify.bots.tool.BotTools.give(player,
                        scriptify.bots.tool.BotTool.KIT, kit);
                    if (problem == null) {
                        Menus.notify(player, "Hold it up for five seconds near a group.");
                    } else {
                        Menus.deny(player, problem);
                    }
                    return;
                }
                Sfx.click(player);
                if (botName != null) {
                    giveTo(player, kit, botName);
                } else {
                    giveNearby(player, kit);
                }
            });
    }

    private static void giveTo(ServerPlayer player, String kit, String botName) {
        var server = player.level().getServer();
        if (server == null) return;
        ServerPlayer bot = server.getPlayerList().getPlayer(botName);
        if (bot == null) {
            Menus.deny(player, botName + " is not online");
            return;
        }
        if (BotKits.giveKit(kit, bot)) {
            Menus.notify(player, "Gave " + kit + " to " + botName);
        } else {
            Menus.deny(player, "Could not give " + kit);
        }
    }

    private static void giveNearby(ServerPlayer player, String kit) {
        int given = BotKits.giveToNearby(player, kit, NEARBY_RADIUS);
        if (given == 0) {
            Menus.deny(player, "No bots within " + (int) NEARBY_RADIUS + " blocks");
            return;
        }
        Menus.notify(player, "Gave " + kit + " to " + given + (given == 1 ? " bot" : " bots"));
        Sfx.spawn(player);
    }

    /**
     * Asks for a kit name.
     *
     * <p>An anvil, because it is the only vanilla container with a text field, and this needs a
     * word from the player that no arrangement of buttons can supply.</p>
     *
     * @param empty true to make an empty kit and open the editor on it, false to snapshot
     *              whatever the player is carrying
     */
    private static void promptNewKit(ServerPlayer player, String botName, boolean empty) {
        AnvilInputGui input = new AnvilInputGui(player, false) {
            @Override
            public void onClose() {
                // Not when the editor is what closed it: reopening the list on top would leave
                // the client showing the list and the server thinking it showed the editor.
                if (!empty) KitsMenu.open(player, botName);
            }
        };
        input.setTitle(Msg.title("Name the kit", ChatFormatting.WHITE));
        input.setDefaultInputValue("kit");
        input.setSlot(0, new eu.pb4.sgui.api.elements.GuiElementBuilder(Items.NAME_TAG)
            .setName(Msg.title("Type a name", ChatFormatting.WHITE))
            .addLoreLine(Msg.hint("Then click the result on the right."))
            .build());
        input.setSlot(2, Menus.button(scriptify.ui.DyedItems.concrete(DyeColor.LIME),
                empty ? "Create and edit" : "Save kit", ChatFormatting.GREEN,
                empty
                    ? "Opens an empty kit under this name."
                    : "Saves what you are carrying under this name.")
            .setCallback((i, type, action, ignored) -> {
                String name = input.getInput() == null ? "" : input.getInput().trim();
                if (name.isEmpty()) {
                    Menus.deny(player, "Give it a name first");
                    return;
                }
                if (empty) {
                    if (BotKits.createEmpty(name)) {
                        // Straight into the editor: naming an empty kit and then being sent back
                        // to a list to find it again is a step for the sake of a step.
                        input.close();
                        player.level().getServer().execute(() ->
                            BotKitEditMenu.open(player, name, botName));
                    } else {
                        Menus.deny(player, name + " already exists");
                    }
                    return;
                }
                if (BotKits.createKit(name, player)) {
                    Menus.notify(player, "Saved kit " + name);
                    Sfx.spawn(player);
                } else {
                    Menus.deny(player, "Could not save " + name);
                }
                input.close();
            }).build());
        input.open();
    }
}
