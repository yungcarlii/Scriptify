package scriptify.bots.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.bot.BotSettings;
import scriptify.bots.config.SettingsRegistry;

import java.util.List;
import java.util.Locale;

/**
 * Settings, grouped by what they affect.
 *
 * <p>Built entirely from {@link SettingsRegistry}, so a new setting appears here the moment it
 * is registered - there is no second list to keep in sync. Numbers adjust in place with left
 * and right click, shift for larger steps, which is faster than any text field.</p>
 */
public final class SettingsMenu {

    private SettingsMenu() {}

    private static final int[] CATEGORY_SLOTS = {11, 12, 13, 14, 20, 21, 22};

    public static void open(ServerPlayer player) {
        SimpleGui gui = Menus.create(player, 6, "Settings");

        gui.setSlot(4, Menus.header(Items.COMPARATOR, "Settings",
            SettingsRegistry.all().size() + " tunables",
            "Grouped by what they affect.").build());

        List<String> categories = SettingsRegistry.categories();
        for (int i = 0; i < categories.size() && i < CATEGORY_SLOTS.length; i++) {
            String category = categories.get(i);
            List<SettingsRegistry.Entry> entries = SettingsRegistry.inCategory(category);
            long on = entries.stream()
                .filter(e -> e.kind == SettingsRegistry.Kind.BOOL && e.getBool()).count();
            long switches = entries.stream()
                .filter(e -> e.kind == SettingsRegistry.Kind.BOOL).count();

            gui.setSlot(CATEGORY_SLOTS[i], Menus.button(icon(category), category, ChatFormatting.AQUA,
                    entries.size() + " settings",
                    switches > 0 ? on + " of " + switches + " switches on" : "")
                .addLoreLine(Msg.blank())
                .addLoreLine(Msg.action("Click to open"))
                .setCallback((slot, type, action, ignored) -> {
                    Sfx.click(player);
                    category(player, category, 0);
                }).build());
        }

        gui.setSlot(31, Menus.button(Items.STRUCTURE_VOID, "Reset to defaults", ChatFormatting.RED,
                "Restores every setting to its default.",
                "",
                "Shift click to confirm.")
            .setCallback((slot, type, action, ignored) -> {
                if (!type.shift) {
                    Menus.deny(player, "Shift click to confirm a full reset");
                    return;
                }
                resetAll();
                Menus.notify(player, "Settings restored to defaults");
                open(player);
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
        gui.open();
    }

    private static void category(ServerPlayer player, String category, int page) {
        SimpleGui gui = Menus.create(player, 6, category);
        paintCategory(gui, player, category, page);
        gui.open();
    }

    private static void paintCategory(SimpleGui gui, ServerPlayer player, String category,
                                      int page) {
        List<SettingsRegistry.Entry> entries = SettingsRegistry.inCategory(category);
        int perPage = Menus.PER_PAGE - 14;
        int totalPages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, totalPages - 1));

        Menus.clearContent(gui);
        gui.setSlot(4, Menus.header(icon(category), category,
            entries.size() + " settings").build());

        int index = current * perPage;
        for (int slot = Menus.CONTENT_START; slot < Menus.CONTENT_END; slot++) {
            int column = slot % Menus.WIDTH;
            if (column == 0 || column == Menus.WIDTH - 1) continue;
            if (index >= entries.size()) break;

            SettingsRegistry.Entry entry = entries.get(index++);
            gui.setSlot(slot, element(gui, player, entry, category, current));
        }

        if (current > 0) {
            gui.setSlot(Menus.PREV_SLOT, Menus.pageButton("Previous", current, totalPages)
                .setCallback((i, type, action, ignored) -> {
                    Sfx.click(player);
                    paintCategory(gui, player, category, current - 1);
                }).build());
        }
        if (current < totalPages - 1) {
            gui.setSlot(Menus.NEXT_SLOT, Menus.pageButton("Next", current + 2, totalPages)
                .setCallback((i, type, action, ignored) -> {
                    Sfx.click(player);
                    paintCategory(gui, player, category, current + 1);
                }).build());
        }

        gui.setSlot(Menus.BACK_SLOT, Menus.backButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                open(player);
            }).build());
        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }

    private static eu.pb4.sgui.api.elements.GuiElement element(SimpleGui gui,
                                                               ServerPlayer player,
                                                               SettingsRegistry.Entry entry,
                                                               String category, int page) {
        if (entry.kind == SettingsRegistry.Kind.BOOL) {
            return Menus.toggle(entry.icon, entry.label, entry.getBool(), entry.description)
                .setCallback((i, type, action, ignored) -> {
                    entry.adjust(1);
                    BotSettings.flush();
                    Menus.notifyToggle(player, entry.label + ": " + entry.valueString(),
                        entry.getBool());
                    paintCategory(gui, player, category, page);
                }).build();
        }

        GuiElementBuilder builder = new GuiElementBuilder(entry.icon)
            .setName(Msg.title(entry.label, ChatFormatting.WHITE)
                .append(net.minecraft.network.chat.Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(net.minecraft.network.chat.Component.literal(entry.valueString())
                    .withStyle(ChatFormatting.AQUA)
                    .withStyle(style -> style.withItalic(false))))
            .addLoreLine(Msg.hint(entry.description))
            .addLoreLine(Msg.blank())
            .addLoreLine(Msg.stat("Range", range(entry), ChatFormatting.DARK_GRAY))
            .addLoreLine(Msg.blank())
            .addLoreLine(Msg.action("Left click  +" + step(entry)))
            .addLoreLine(Msg.action("Right click -" + step(entry)))
            .addLoreLine(Msg.action("Hold shift for x5"));

        return builder.setCallback((i, type, action, ignored) -> {
            int steps = type.shift ? 5 : 1;
            entry.adjust(type.isRight ? -steps : steps);
            BotSettings.flush();
            Menus.notify(player, entry.label + ": " + entry.valueString());
            paintCategory(gui, player, category, page);
        }).build();
    }

    private static String step(SettingsRegistry.Entry entry) {
        return entry.kind == SettingsRegistry.Kind.INT
            ? String.valueOf((int) entry.step)
            : String.format(Locale.US, "%.2f", entry.step);
    }

    private static String range(SettingsRegistry.Entry entry) {
        return entry.kind == SettingsRegistry.Kind.INT
            ? (int) entry.min + " to " + (int) entry.max
            : String.format(Locale.US, "%.2f to %.2f", entry.min, entry.max);
    }

    private static void resetAll() {
        for (SettingsRegistry.Entry entry : SettingsRegistry.all()) {
            switch (entry.kind) {
                case BOOL -> entry.setBool(entry.defaultBool());
                case INT -> entry.setInt(entry.defaultInt());
                case DOUBLE -> entry.setDouble(entry.defaultDouble());
            }
        }
        BotSettings.flush();
    }

    private static net.minecraft.world.item.Item icon(String category) {
        return switch (category) {
            case SettingsRegistry.COMBAT -> Items.NETHERITE_SWORD;
            case SettingsRegistry.TARGETING -> Items.SPYGLASS;
            case SettingsRegistry.TACTICS -> Items.COBWEB;
            case SettingsRegistry.SURVIVAL -> Items.GOLDEN_APPLE;
            case SettingsRegistry.MOVEMENT -> Items.COMPASS;
            case SettingsRegistry.GEAR -> Items.DIAMOND_CHESTPLATE;
            default -> Items.COMMAND_BLOCK;
        };
    }
}
