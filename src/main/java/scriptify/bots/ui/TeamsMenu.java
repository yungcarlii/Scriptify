package scriptify.bots.ui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import scriptify.bots.bot.BotFaction;
import scriptify.bots.bot.BotManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Teams, as a screen.
 *
 * <p>Two levels only: the list of teams, and one team. Anything deeper turns a menu into a file
 * browser. Membership is edited from the team screen by adding whoever is standing nearby,
 * which is how teams actually get made - you gather the bots you want and then name them.</p>
 */
public final class TeamsMenu {

    private TeamsMenu() {}

    private static final double NEARBY_RADIUS = 32.0;

    public static void open(ServerPlayer player) {
        SimpleGui gui = Menus.create(player, 6, "Teams");
        paint(gui, player);
        gui.open();
    }

    private static void paint(SimpleGui gui, ServerPlayer player) {
        Menus.clearContent(gui);

        List<String> teams = new ArrayList<>(BotFaction.getAllFactions());
        teams.sort(String::compareToIgnoreCase);

        gui.setSlot(4, Menus.header(scriptify.ui.DyedItems.banner(DyeColor.WHITE), "Teams",
            teams.isEmpty() ? "No teams yet." : teams.size() + " teams.",
            "Click one to manage it.").build());

        int slot = Menus.CONTENT_START;
        for (String team : teams) {
            if (slot >= Menus.CONTENT_END) break;
            int column = slot % Menus.WIDTH;
            if (column == 0 || column == Menus.WIDTH - 1) {
                slot++;
                continue;
            }
            int members = BotFaction.getMembers(team).size();
            int enemies = BotFaction.getHostileFactions(team).size();
            String name = team;
            gui.setSlot(slot, Menus.button(scriptify.ui.DyedItems.banner(DyeColor.WHITE), team, ChatFormatting.LIGHT_PURPLE,
                    members + (members == 1 ? " member" : " members"),
                    enemies + (enemies == 1 ? " enemy team" : " enemy teams"),
                    "",
                    "Left-click  manage",
                    "Shift-right-click  disband")
                .setCallback((i, clickType, action, ignored) -> {
                    if (clickType == ClickType.MOUSE_RIGHT_SHIFT) {
                        BotFaction.deleteFaction(name);
                        Menus.notify(player, "Disbanded " + name);
                        paint(gui, player);
                        return;
                    }
                    Sfx.click(player);
                    openTeam(player, name);
                }).build());
            slot++;
        }

        gui.setSlot(Menus.CONTENT_END + 1, Menus.button(Items.WRITABLE_BOOK, "New team",
                ChatFormatting.GREEN, "Click to name it.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                promptNewTeam(player);
            }).build());

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

    private static void openTeam(ServerPlayer player, String team) {
        SimpleGui gui = Menus.create(player, 6, "Team · " + team);
        paintTeam(gui, player, team);
        gui.open();
    }

    private static void paintTeam(SimpleGui gui, ServerPlayer player, String team) {
        Menus.clearContent(gui);

        List<String> members = new ArrayList<>(BotFaction.getMembers(team));
        members.sort(String::compareToIgnoreCase);

        gui.setSlot(4, Menus.header(scriptify.ui.DyedItems.banner(DyeColor.WHITE), team,
            members.size() + (members.size() == 1 ? " member" : " members"),
            "Shift-right-click a member to remove them.").build());

        int slot = Menus.CONTENT_START;
        for (String member : members) {
            if (slot >= Menus.CONTENT_END - Menus.WIDTH) break;
            int column = slot % Menus.WIDTH;
            if (column == 0 || column == Menus.WIDTH - 1) {
                slot++;
                continue;
            }
            String name = member;
            gui.setSlot(slot, Menus.button(Items.PLAYER_HEAD, member, ChatFormatting.WHITE,
                    "Shift-right-click  remove from team")
                .setCallback((i, clickType, action, ignored) -> {
                    if (clickType != ClickType.MOUSE_RIGHT_SHIFT) return;
                    BotFaction.removeMember(team, name);
                    Menus.notify(player, name + " left " + team);
                    paintTeam(gui, player, team);
                }).build());
            slot++;
        }

        gui.setSlot(Menus.CONTENT_END + 1, Menus.button(Items.BEACON, "Add nearby bots",
                ChatFormatting.GREEN,
                "Every bot within " + (int) NEARBY_RADIUS + " blocks",
                "joins this team.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                addNearby(player, team);
                paintTeam(gui, player, team);
            }).build());

        gui.setSlot(Menus.CONTENT_END + 2, Menus.button(Items.IRON_SWORD, "Enemies",
                ChatFormatting.RED,
                "Click a team to toggle hostility with it.")
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                openEnemies(player, team);
            }).build());

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

    private static void openEnemies(ServerPlayer player, String team) {
        SimpleGui gui = Menus.create(player, 6, "Enemies of " + team);
        paintEnemies(gui, player, team);
        gui.open();
    }

    private static void paintEnemies(SimpleGui gui, ServerPlayer player, String team) {
        Menus.clearContent(gui);

        gui.setSlot(4, Menus.header(Items.IRON_SWORD, "Enemies of " + team,
            "Green means they fight each other.",
            "Click to toggle.").build());

        List<String> others = new ArrayList<>(BotFaction.getAllFactions());
        others.remove(team);
        others.sort(String::compareToIgnoreCase);

        var hostile = BotFaction.getHostileFactions(team);

        int slot = Menus.CONTENT_START;
        for (String other : others) {
            if (slot >= Menus.CONTENT_END) break;
            int column = slot % Menus.WIDTH;
            if (column == 0 || column == Menus.WIDTH - 1) {
                slot++;
                continue;
            }
            boolean enemy = hostile.contains(other);
            String name = other;
            gui.setSlot(slot, Menus.toggle(scriptify.ui.DyedItems.banner(DyeColor.WHITE), other, enemy,
                    enemy ? "Hostile - they attack on sight." : "Neutral.")
                .setCallback((i, type, action, ignored) -> {
                    BotFaction.setHostile(team, name, !enemy);
                    Sfx.click(player);
                    paintEnemies(gui, player, team);
                }).build());
            slot++;
        }

        gui.setSlot(Menus.BACK_SLOT, Menus.backButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                openTeam(player, team);
            }).build());
        gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
            .setCallback((i, type, action, ignored) -> {
                Sfx.click(player);
                gui.close();
            }).build());
    }

    private static void addNearby(ServerPlayer player, String team) {
        var server = player.level().getServer();
        if (server == null) return;

        int added = 0;
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            String name = candidate.getName().getString();
            if (!BotManager.isBot(name)) continue;
            if (candidate.level() != player.level()) continue;
            if (candidate.position().distanceTo(player.position()) > NEARBY_RADIUS) continue;
            if (BotFaction.addMember(team, name)) added++;
        }

        if (added == 0) {
            Menus.deny(player, "No bots within " + (int) NEARBY_RADIUS + " blocks");
            return;
        }
        Menus.notify(player, added + (added == 1 ? " bot" : " bots") + " joined " + team);
        Sfx.spawn(player);
    }

    private static void promptNewTeam(ServerPlayer player) {
        AnvilInputGui input = new AnvilInputGui(player, false) {
            @Override
            public void onClose() {
                TeamsMenu.open(player);
            }
        };
        input.setTitle(Msg.title("Name the team", ChatFormatting.WHITE));
        input.setDefaultInputValue("team");
        input.setSlot(0, new eu.pb4.sgui.api.elements.GuiElementBuilder(Items.NAME_TAG)
            .setName(Msg.title("Type a name", ChatFormatting.WHITE))
            .addLoreLine(Msg.hint("Then click the result on the right."))
            .build());
        input.setSlot(2, Menus.button(scriptify.ui.DyedItems.concrete(DyeColor.LIME), "Create team", ChatFormatting.GREEN)
            .setCallback((i, type, action, ignored) -> {
                String name = input.getInput() == null ? "" : input.getInput().trim();
                if (name.isEmpty()) {
                    Menus.deny(player, "Give it a name first");
                    return;
                }
                if (BotFaction.createFaction(name)) {
                    Menus.notify(player, "Created team " + name);
                    Sfx.spawn(player);
                } else {
                    Menus.deny(player, name + " already exists");
                }
                input.close();
            }).build());
        input.open();
    }
}
