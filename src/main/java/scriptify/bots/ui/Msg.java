package scriptify.bots.ui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;

/**
 * One voice for everything the mod says.
 *
 * <p>Chat lines open with a chevron rather than a bracketed tag. A prefix like {@code [SBots]}
 * spends a quarter of the line telling you which mod is talking, every single line; a chevron
 * says the same thing in one character and leaves the rest of the width for the message.</p>
 *
 * <p>The palette is hex rather than the sixteen legacy colours, because the legacy set has no
 * violet that sits next to a blue without one of them looking wrong. Everything is one family -
 * violet for the mod's own voice, blue for detail, a warm rose for refusals - so a line reads
 * as belonging to this mod before you have read a word of it.</p>
 */
public final class Msg {

    private Msg() {}

    /** The mod's colour: a soft violet. */
    public static final int VIOLET = 0xB49BFF;
    /** Detail and secondary text: a cool blue. */
    public static final int BLUE = 0x7FB2FF;
    /** Something worked. */
    public static final int OK = 0x9AE7FF;
    /** Something did not. Kept in the cool family so it reads as "no", not "error". */
    public static final int BAD = 0xFF8FB0;
    /** Dimmed, for anything the eye should skip. */
    public static final int DIM = 0x7A7A99;
    /** Interactive, clickable. */
    public static final int ACTION = 0x8CD4FF;

    /** Legacy formatting for the few APIs that will not take a hex colour. */
    public static final ChatFormatting BRAND = ChatFormatting.LIGHT_PURPLE;

    public static MutableComponent coloured(String text, int rgb) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    /**
     * The mod's chat prefix, taken from {@link scriptify.ui.Msg} rather than defined again here.
     *
     * <p>The bot half used to open its lines with a bare chevron while everything else in the mod
     * said "Scriptify ›". Two voices in one chat log is the clearest possible tell that a merged mod
     * is really two mods wearing a coat.</p>
     */
    private static MutableComponent chevron() {
        return scriptify.ui.Msg.line("").withStyle(ChatFormatting.RESET);
    }

    // ===================== chat =====================

    public static MutableComponent line(String text) {
        return chevron().append(coloured(text, BLUE));
    }

    public static MutableComponent line(String text, ChatFormatting colour) {
        return chevron().append(Component.literal(text).withStyle(colour));
    }

    public static MutableComponent success(String text) {
        return chevron().append(coloured(text, OK));
    }

    public static MutableComponent failure(String text) {
        return chevron().append(coloured(text, BAD));
    }

    /** A heading: brighter, bolder, used to open a block of output. */
    public static MutableComponent heading(String text) {
        return chevron().append(coloured(text, VIOLET).withStyle(ChatFormatting.BOLD));
    }

    /** An indented detail line under a heading. */
    public static MutableComponent detail(String text) {
        return coloured("  ", DIM).append(coloured(text, DIM));
    }

    /** A labelled value: dim label, bright value. */
    public static MutableComponent field(String label, String value) {
        return coloured("  " + label + "  ", DIM).append(coloured(value, BLUE));
    }

    public static void chat(ServerPlayer player, MutableComponent message) {
        player.sendSystemMessage(message, false);
    }

    /** Action-bar confirmation: acknowledges something without adding a chat line. */
    public static void actionBar(ServerPlayer player, String text, ChatFormatting colour) {
        player.sendSystemMessage(Component.literal(text).withStyle(colour), true);
    }

    public static void actionBar(ServerPlayer player, String text, int rgb) {
        player.sendSystemMessage(coloured(text, rgb), true);
    }

    // ===================== menus =====================

    /**
     * A menu title, in the bracketed form the screens use.
     *
     * <p>The chevrons frame the name so a container title reads as a panel rather than as a
     * chest that happens to be called something.</p>
     */
    public static MutableComponent screenTitle(String text) {
        return coloured("» ", VIOLET)
            .append(coloured(text, VIOLET).withStyle(ChatFormatting.BOLD))
            .append(coloured(" «", VIOLET));
    }

    /** A button title. Explicitly un-italicised, since item names slant by default. */
    public static MutableComponent title(String text, ChatFormatting colour) {
        return Component.literal(text).withStyle(colour).withStyle(style -> style.withItalic(false));
    }

    public static MutableComponent title(String text, int rgb) {
        return coloured(text, rgb).withStyle(style -> style.withItalic(false));
    }

    /** A dimmed detail line under a button title. */
    public static MutableComponent hint(String text) {
        return coloured(text, DIM).withStyle(style -> style.withItalic(false));
    }

    /** A "click to do X" line - the same colour everywhere so it reads as an instruction. */
    public static MutableComponent action(String text) {
        return coloured(text, ACTION).withStyle(style -> style.withItalic(false));
    }

    /** A labelled value inside a tooltip. */
    public static MutableComponent stat(String label, String value, ChatFormatting valueColour) {
        return coloured(label + "  ", DIM)
            .append(Component.literal(value).withStyle(valueColour))
            .withStyle(style -> style.withItalic(false));
    }

    /** A blank spacer line inside a tooltip. */
    public static MutableComponent blank() {
        return Component.literal("").withStyle(style -> style.withItalic(false));
    }

    /** On or off, spelled out and coloured, for a toggle's name. */
    public static MutableComponent state(boolean on) {
        return coloured(on ? "ON" : "OFF", on ? OK : DIM)
            .withStyle(style -> style.withItalic(false).withBold(true));
    }
}
