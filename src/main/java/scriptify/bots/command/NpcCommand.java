package scriptify.bots.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotActions;
import scriptify.bots.bot.BotKits;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotNameGenerator;
import scriptify.bots.npc.NpcRegistry;
import scriptify.bots.npc.NpcSettings;
import scriptify.bots.ui.MainMenu;
import scriptify.bots.ui.Msg;
import scriptify.bots.ui.NpcDefaultsMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /bot} tree: managing bots as things in the world, rather than as fighters.
 *
 * <p>Everything here is deliberately short to type and reads as a sentence -
 * {@code /bot massaction jump 30} - because these are commands you use while standing in the
 * middle of what you are building, not ones you look up.</p>
 */
public final class NpcCommand {

    private NpcCommand() {}

    /** Default radius for the "everything around me" commands. */
    private static final double DEFAULT_RADIUS = 32.0;

    public static LiteralArgumentBuilder<CommandSourceStack> node(String literal) {
        return Commands.literal(literal)
            .executes(NpcCommand::openMenu)

            .then(Commands.literal("spawn")
                .executes(ctx -> spawn(ctx, 1))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(NpcCommand::spawnNamed)))

            .then(Commands.literal("massspawn")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10000))
                    .executes(NpcCommand::massSpawn)))

            .then(Commands.literal("clear").executes(NpcCommand::clear))
            .then(Commands.literal("list").executes(NpcCommand::list))
            .then(Commands.literal("reload").executes(NpcCommand::reload))

            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(BotCommand.BOT_SUGGESTIONS)
                    .executes(NpcCommand::remove)))

            .then(Commands.literal("tphere")
                .executes(ctx -> tpHere(ctx, DEFAULT_RADIUS))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 512))
                    .executes(ctx -> tpHere(ctx, DoubleArgumentType.getDouble(ctx, "radius")))))

            .then(Commands.literal("defaultsettings").executes(NpcCommand::defaultSettings))

            .then(Commands.literal("hide")
                .executes(ctx -> setListedNear(ctx, false)))
            .then(Commands.literal("show")
                .executes(ctx -> setListedNear(ctx, true)))

            .then(Commands.literal("kitall")
                .then(Commands.argument("kit", StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (String kit : BotKits.getKitNames()) b.suggest(kit);
                        return b.buildFuture();
                    })
                    .executes(ctx -> kitAll(ctx, DEFAULT_RADIUS))
                    .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 512))
                        .executes(ctx -> kitAll(ctx,
                            DoubleArgumentType.getDouble(ctx, "radius"))))))

            .then(Commands.literal("massaction")
                .then(Commands.argument("action", StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (NpcSettings.Action a : NpcSettings.Action.values()) {
                            b.suggest(a.name().toLowerCase(Locale.ROOT));
                        }
                        return b.buildFuture();
                    })
                    .executes(ctx -> massAction(ctx, DEFAULT_RADIUS))
                    .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 512))
                        .executes(ctx -> massAction(ctx,
                            DoubleArgumentType.getDouble(ctx, "radius"))))))

            .then(Commands.literal("group")
                .then(Commands.literal("create")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(NpcCommand::groupCreate)))
                .then(Commands.literal("delete")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(NpcCommand::groupDelete)))
                .then(Commands.literal("add")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> groupAddNear(ctx, DEFAULT_RADIUS))
                        .then(Commands.argument("radius",
                                DoubleArgumentType.doubleArg(1, 512))
                            .executes(ctx -> groupAddNear(ctx,
                                DoubleArgumentType.getDouble(ctx, "radius"))))))
                .then(Commands.literal("list").executes(NpcCommand::groupList)));
    }

    // ===================== helpers =====================

    /** "1 bot", "3 bots". Small thing; getting it wrong makes a mod feel unfinished. */
    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getPlayer();
    }

    /** Every bot within the radius of the command source, in the same world. */
    private static List<ServerPlayer> botsNear(CommandContext<CommandSourceStack> ctx,
                                                     double radius) {
        MinecraftServer server = ctx.getSource().getServer();
        Vec3 origin = ctx.getSource().getPosition();
        var world = ctx.getSource().getLevel();

        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (!BotManager.isBot(candidate.getName().getString())) continue;
            if (candidate.level() != world) continue;
            if (candidate.position().distanceTo(origin) > radius) continue;
            out.add(candidate);
        }
        return out;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx,
                             net.minecraft.network.chat.MutableComponent text) {
        ctx.getSource().sendSuccess(() -> text, false);
    }

    // ===================== commands =====================

    private static int openMenu(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = player(ctx);
        if (player == null) {
            reply(ctx, Msg.failure("Only a player can open the panel."));
            return 0;
        }
        MainMenu.open(player);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, int count) {
        MinecraftServer server = ctx.getSource().getServer();
        Vec3 at = ctx.getSource().getPosition();
        String name = BotNameGenerator.generateUniqueName();

        if (!BotManager.spawnBot(server, name, ctx.getSource(), at, false)) {
            reply(ctx, Msg.failure("Could not spawn - is HeroBot installed?"));
            return 0;
        }
        reply(ctx, Msg.success("Spawned " + name));
        return 1;
    }

    private static int spawnNamed(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String name = StringArgumentType.getString(ctx, "name");
        Vec3 at = ctx.getSource().getPosition();

        if (!BotManager.spawnBot(server, name, ctx.getSource(), at, true)) {
            reply(ctx, Msg.failure("Could not spawn " + name + " - name taken?"));
            return 0;
        }
        reply(ctx, Msg.success("Spawned " + name));
        return 1;
    }

    private static int massSpawn(CommandContext<CommandSourceStack> ctx) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int cap = scriptify.bots.bot.BotSettings.get().getMaxMassSpawn();
        if (count > cap) {
            reply(ctx, Msg.failure("Cap is " + cap + " - raise it in settings."));
            return 0;
        }
        BotManager.queueMassSpawn(ctx.getSource().getServer(), count,
            ctx.getSource().getPosition(), ctx.getSource());
        reply(ctx, Msg.line("Spawning " + count + " bots around you"));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        int count = BotManager.removeAllBots(ctx.getSource().getServer(), ctx.getSource());
        reply(ctx, Msg.success("Removed " + plural(count, "bot")));
        return count;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!BotManager.removeBot(ctx.getSource().getServer(), name, ctx.getSource())) {
            reply(ctx, Msg.failure(name + " is not a bot."));
            return 0;
        }
        reply(ctx, Msg.success("Removed " + name));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var names = new ArrayList<>(BotManager.botNames());

        if (names.isEmpty()) {
            reply(ctx, Msg.line("No bots."));
            return 0;
        }
        reply(ctx, Msg.heading(plural(names.size(), "bot")));
        for (String name : names) {
            var group = NpcRegistry.groupOf(name);
            reply(ctx, Msg.field(name, group == null ? "no group" : "group " + group.name));
        }
        return names.size();
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        scriptify.bots.bot.BotSettings.load();
        BotManager.reloadBots();
        NpcRegistry.init(ctx.getSource().getServer());
        reply(ctx, Msg.success("Reloaded"));
        return 1;
    }

    private static int tpHere(CommandContext<CommandSourceStack> ctx, double radius) {
        Vec3 to = ctx.getSource().getPosition();
        List<ServerPlayer> bots = botsNear(ctx, Double.MAX_VALUE);
        int moved = 0;
        for (ServerPlayer bot : bots) {
            bot.teleportTo(ctx.getSource().getLevel(), to.x, to.y, to.z,
                java.util.Set.of(), bot.getYRot(), bot.getXRot(), false);
            moved++;
        }
        reply(ctx, Msg.success("Brought " + plural(moved, "bot") + " here"));
        return moved;
    }

    private static int defaultSettings(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = player(ctx);
        if (player == null) {
            reply(ctx, Msg.failure("Only a player can open the panel."));
            return 0;
        }
        NpcDefaultsMenu.open(player);
        return 1;
    }

    private static int setListedNear(CommandContext<CommandSourceStack> ctx, boolean listed) {
        List<ServerPlayer> bots = botsNear(ctx, Double.MAX_VALUE);
        for (ServerPlayer bot : bots) {
            NpcRegistry.overrides(bot.getName().getString()).listed = listed;
        }
        NpcRegistry.markDirty();
        reply(ctx, Msg.success((listed ? "Showing " : "Hiding ")
            + plural(bots.size(), "bot") + " in tab"));
        return bots.size();
    }

    private static int kitAll(CommandContext<CommandSourceStack> ctx, double radius) {
        String kit = StringArgumentType.getString(ctx, "kit");
        if (!BotKits.kitExists(kit)) {
            reply(ctx, Msg.failure("No kit called " + kit));
            return 0;
        }
        int given = 0;
        for (ServerPlayer bot : botsNear(ctx, radius)) {
            if (BotKits.giveKit(kit, bot)) given++;
        }
        reply(ctx, Msg.success("Gave " + kit + " to " + plural(given, "bot")));
        return given;
    }

    private static int massAction(CommandContext<CommandSourceStack> ctx, double radius) {
        String raw = StringArgumentType.getString(ctx, "action");
        NpcSettings.Action action = null;
        for (NpcSettings.Action a : NpcSettings.Action.values()) {
            if (a.name().equalsIgnoreCase(raw)) action = a;
        }
        if (action == null) {
            reply(ctx, Msg.failure("No action called " + raw));
            return 0;
        }

        List<ServerPlayer> bots = botsNear(ctx, radius);
        for (ServerPlayer bot : bots) {
            NpcRegistry.overrides(bot.getName().getString()).action = action;
        }
        NpcRegistry.markDirty();
        reply(ctx, Msg.success(plural(bots.size(), "bot") + " set to "
            + action.label.toLowerCase(Locale.ROOT)));
        return bots.size();
    }

    // ===================== groups =====================

    private static int groupCreate(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        NpcRegistry.createGroup(name);
        reply(ctx, Msg.success("Group " + name + " created"));
        return 1;
    }

    private static int groupDelete(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!NpcRegistry.deleteGroup(name)) {
            reply(ctx, Msg.failure("No group called " + name));
            return 0;
        }
        reply(ctx, Msg.success("Group " + name + " deleted"));
        return 1;
    }

    private static int groupAddNear(CommandContext<CommandSourceStack> ctx, double radius) {
        String name = StringArgumentType.getString(ctx, "name");
        List<ServerPlayer> bots = botsNear(ctx, radius);
        for (ServerPlayer bot : bots) {
            NpcRegistry.addToGroup(name, bot.getName().getString());
        }
        reply(ctx, Msg.success(plural(bots.size(), "bot") + " added to " + name));
        return bots.size();
    }

    private static int groupList(CommandContext<CommandSourceStack> ctx) {
        var groups = NpcRegistry.groups();
        if (groups.isEmpty()) {
            reply(ctx, Msg.line("No groups yet."));
            return 0;
        }
        reply(ctx, Msg.heading(plural(groups.size(), "group")));
        for (var group : groups) {
            reply(ctx, Msg.field(group.name, group.members.size() + " members"));
        }
        return groups.size();
    }
}
