package scriptify.bots.ui;

import com.mojang.authlib.GameProfile;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Shared chrome for every SBots screen - background, buttons, toggles, page arrows, feedback -
 * so the whole mod looks like one product rather than a pile of separate menus.
 */
public final class Menus {

    public static final int WIDTH = 9;
    public static final int BACK_SLOT = 45;
    public static final int CLOSE_SLOT = 49;
    public static final int PREV_SLOT = 48;
    public static final int NEXT_SLOT = 50;
    /** Content area of a 6-row menu when the bottom row is reserved for navigation. */
    public static final int CONTENT_START = 9;
    public static final int CONTENT_END = 45;
    public static final int PER_PAGE = CONTENT_END - CONTENT_START;

    private Menus() {}

    public static MenuType<?> typeForRows(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /**
     * A panel: the same screen, titled the way the mod titles things.
     *
     * <p>Chevrons around the name rather than a mod tag and a separator, so the title reads as
     * the name of a panel instead of a path through a menu tree.</p>
     */
    public static SimpleGui panel(ServerPlayer player, int rows, String title) {
        SimpleGui gui = new SimpleGui(typeForRows(rows), player, false);
        gui.setTitle(Msg.screenTitle(title));
        gui.setLockPlayerInventory(true);
        fill(gui);
        return gui;
    }

    /** Creates a locked, titled screen with the standard background already laid in. */
    public static SimpleGui create(ServerPlayer player, int rows, String title) {
        SimpleGui gui = new SimpleGui(typeForRows(rows), player, false);
        gui.setTitle(Component.literal("Scriptify").withStyle(Msg.BRAND, ChatFormatting.BOLD)
            .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(title).withStyle(ChatFormatting.WHITE)));
        gui.setLockPlayerInventory(true);
        fill(gui);
        return gui;
    }

    /**
     * Two-tone background: a dark frame around the edge, lighter panes inside.
     *
     * <p>The tonal difference is what makes the grid read as a deliberate layout instead of a
     * wall of identical squares - the eye finds the content area without being told.</p>
     */
    /**
     * Lays in the border and leaves the middle empty.
     *
     * <p>It used to tile the interior with a second, lighter pane. Two tones of glass behind every
     * screen is decoration competing with the buttons: the frame is enough to say where the menu
     * ends, and an empty interior lets the icons be the only things in it.</p>
     */
    public static void fill(SimpleGui gui) {
        var frame = new GuiElementBuilder(scriptify.ui.DyedItems.glassPane(DyeColor.GRAY))
            .setName(Component.empty()).hideDefaultTooltip();

        int rows = gui.getSize() / WIDTH;
        for (int slot = 0; slot < gui.getSize(); slot++) {
            int column = slot % WIDTH;
            int row = slot / WIDTH;
            if (row == 0 || row == rows - 1 || column == 0 || column == WIDTH - 1) {
                gui.setSlot(slot, frame.build());
            } else {
                gui.setSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    /** Clears the content area, leaving the frame intact. Used when repainting a page. */
    public static void clearContent(SimpleGui gui) {
        for (int slot = CONTENT_START; slot < CONTENT_END; slot++) {
            int column = slot % WIDTH;
            if (column == 0 || column == WIDTH - 1) continue;
            gui.setSlot(slot, ItemStack.EMPTY);
        }
    }

    /**
     * How long after a click the same player's next one is ignored, in milliseconds.
     *
     * <p>A held mouse button repeats, and a repeating click on a toggle switches it on and off
     * several times before you let go - so the setting ends up wherever the last repeat left it,
     * which is a coin toss. A quarter of a second is longer than any repeat and shorter than any
     * deliberate second click.</p>
     */
    private static final long CLICK_GUARD_MS = 250L;

    private static final java.util.Map<java.util.UUID, Long> LAST_CLICK = new java.util.HashMap<>();

    /**
     * Whether this click is a repeat of one just made, and should be ignored.
     *
     * <p>Call it from anything that toggles. Buttons that only open another screen do not need it -
     * reopening the same screen is harmless.</p>
     */
    public static boolean tooSoon(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_CLICK.get(player.getUUID());
        if (last != null && now - last < CLICK_GUARD_MS) {
            return true;
        }
        LAST_CLICK.put(player.getUUID(), now);
        return false;
    }

    public static GuiElementBuilder button(Item icon, String title, ChatFormatting colour, String... lore) {
        GuiElementBuilder builder = new GuiElementBuilder(icon).setName(Msg.title(title, colour));
        for (String line : lore) {
            builder.addLoreLine(Msg.hint(line));
        }
        return builder;
    }

    /**
     * A boolean switch shown as a dye: grey for off, green for on.
     *
     * <p>The colour of the item is the state. That reads across a grid at a glance, where a row of
     * identical icons distinguished only by a word in the tooltip does not.</p>
     */
    public static GuiElementBuilder dyeToggle(String title, boolean enabled, String... lore) {
        return toggle(enabled ? scriptify.ui.DyedItems.dye(DyeColor.LIME) : scriptify.ui.DyedItems.dye(DyeColor.GRAY), title, enabled, lore);
    }

    /**
     * A boolean switch: state spelled out in the name so it reads without hovering, green or
     * red to match.
     *
     * <p>No enchantment glint on the enabled state. It shimmers, which draws the eye to whatever
     * happens to be switched on rather than to whatever you are looking for.</p>
     */
    public static GuiElementBuilder toggle(Item icon, String title, boolean enabled, String... lore) {
        GuiElementBuilder builder = new GuiElementBuilder(icon)
            .setName(Msg.title(title, ChatFormatting.WHITE)
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(enabled ? "ON" : "OFF")
                    .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)
                    .withStyle(style -> style.withItalic(false))));
        for (String line : lore) {
            builder.addLoreLine(Msg.hint(line));
        }
        builder.addLoreLine(Msg.blank());
        builder.addLoreLine(Msg.action("Click to " + (enabled ? "disable" : "enable")));
        return builder;
    }

    /**
     * A head wearing the bot's own skin.
     *
     * <p>Showing the actual face is the difference between picking a bot and reading a list of
     * usernames, and it doubles as a check that skin resolution is working.</p>
     */
    public static GuiElementBuilder head(GameProfile profile, String title, ChatFormatting colour) {
        return new GuiElementBuilder(Items.PLAYER_HEAD)
            .setProfile(profile)
            .setName(Msg.title(title, colour));
    }

    public static GuiElementBuilder closeButton() {
        return new GuiElementBuilder(Items.BARRIER)
            .setName(Msg.title("Close", ChatFormatting.RED));
    }

    public static GuiElementBuilder backButton() {
        return new GuiElementBuilder(Items.OAK_DOOR)
            .setName(Msg.title("Back", ChatFormatting.YELLOW));
    }

    public static GuiElementBuilder pageButton(String label, int page, int total) {
        return new GuiElementBuilder(Items.ARROW)
            .setName(Msg.title(label, ChatFormatting.YELLOW))
            .addLoreLine(Msg.hint("Page " + page + " of " + total));
    }

    /** Header item for a page: the title, plus whatever live numbers matter on that screen. */
    public static GuiElementBuilder header(Item icon, String title, String... lore) {
        GuiElementBuilder builder = new GuiElementBuilder(icon)
            .setName(Msg.title(title, Msg.BRAND))
            .glow();
        for (String line : lore) {
            builder.addLoreLine(Msg.hint(line));
        }
        return builder;
    }

    /** Action-bar confirmation plus a soft cue: a click should both read and sound like it landed. */
    public static void notify(ServerPlayer player, String message) {
        Msg.actionBar(player, message, ChatFormatting.AQUA);
        Sfx.success(player);
    }

    public static void deny(ServerPlayer player, String message) {
        Msg.actionBar(player, message, ChatFormatting.RED);
        Sfx.error(player);
    }

    public static void notifyToggle(ServerPlayer player, String message, boolean enabled) {
        Msg.actionBar(player, message, enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
        Sfx.toggle(player, enabled);
    }
}
