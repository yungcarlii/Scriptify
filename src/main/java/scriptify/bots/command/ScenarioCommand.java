package scriptify.bots.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotManager;
import scriptify.bots.scenario.Scenario;
import scriptify.bots.scenario.ScenarioDraft;
import scriptify.bots.scenario.ScenarioInstance;
import scriptify.bots.scenario.ScenarioManager;
import scriptify.bots.ui.Msg;
import scriptify.bots.ui.ScenariosMenu;

import java.util.Locale;

/**
 * The {@code /bots scenarios} subtree.
 *
 * <p>Kept out of {@link BotCommand} because it is a self-contained feature with its own state
 * machine - starting a draft, filling it in, finishing it - and because that file is long
 * enough that adding another twenty nodes to it stops being readable.</p>
 */
public final class ScenarioCommand {

    private ScenarioCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("scenarios")
            .executes(ScenarioCommand::openMenu)

            .then(Commands.literal("new")
                .then(Commands.argument("type", StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (Scenario type : Scenario.values()) {
                            b.suggest(type.name().toLowerCase(Locale.ROOT));
                        }
                        return b.buildFuture();
                    })
                    .executes(ScenarioCommand::begin)))

            .then(Commands.literal("point")
                .then(Commands.literal("add").executes(ScenarioCommand::pointAdd))
                .then(Commands.literal("undo").executes(ScenarioCommand::pointUndo)))

            .then(Commands.literal("add")
                .then(Commands.argument("bot", StringArgumentType.word())
                    .executes(ScenarioCommand::addBot)))

            .then(Commands.literal("finish").executes(ScenarioCommand::finish))
            .then(Commands.literal("cancel").executes(ScenarioCommand::cancel))

            .then(Commands.literal("crowd")
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 256))
                    .executes(ScenarioCommand::crowd)))

            .then(Commands.literal("assigncrowd")
                .then(Commands.argument("targets", StringArgumentType.greedyString())
                    .executes(ScenarioCommand::assignCrowd)))

            .then(Commands.literal("list").executes(ScenarioCommand::list))
            .then(Commands.literal("remove")
                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                    .executes(ScenarioCommand::remove)))
            .then(Commands.literal("removeall").executes(ScenarioCommand::removeAll));
    }

    // ===================== building =====================

    private static int openMenu(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Only a player can open the scenario menu."));
            return 0;
        }
        ScenariosMenu.open(player);
        return 1;
    }

    /**
     * Starts a draft without the menu.
     *
     * <p>The menu is the nice way in, but a scenario is a thing you might want to set up from a
     * command block or a script, and a feature only reachable by clicking is a feature that
     * cannot be automated or tested.</p>
     */
    private static int begin(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Only a player can build a scenario."));
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "type");
        Scenario type = Scenario.byName(raw);
        if (type == null) {
            ctx.getSource().sendFailure(Component.literal("No scenario type called '" + raw + "'"));
            return 0;
        }

        ScenarioManager.beginDraft(player, type);
        switch (type) {
            case CONVERSATION -> give(player, scriptify.bots.scenario.ScenarioTools.conversationStick());
            case PRISON_CELL -> give(player, scriptify.bots.scenario.ScenarioTools.prisonStick());
            default -> { }
        }
        Msg.chat(player, Msg.line("Building a " + type.displayName + " scenario. "
            + "Place a scenario point using /bots scenarios point add, "
            + "and if you are done use /bots scenarios finish"));
        return 1;
    }

    private static void give(ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    private static int pointAdd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        ScenarioDraft draft = ScenarioManager.draft(player);
        if (draft == null) {
            Msg.chat(player, Msg.failure("Nothing being built - open /bots scenarios first."));
            return 0;
        }
        if (!draft.type.usesPoints) {
            Msg.chat(player, Msg.failure(draft.type.displayName + " is not built from points."));
            return 0;
        }

        // The command source position, not the player's own. They are the same when a player
        // types this, and different under "execute positioned" or from a command block - which
        // is the only way to script a route without walking it.
        Vec3 at = ctx.getSource().getPosition();
        if (!draft.addPoint(at)) {
            Msg.chat(player, Msg.failure(draft.type.displayName + " takes a single point. "
                + "Use /bots scenarios point undo to move it."));
            return 0;
        }
        Msg.chat(player, Msg.success(String.format(Locale.US,
            "Point %d at %.0f %.0f %.0f", draft.points.size(), at.x, at.y, at.z)));
        return 1;
    }

    private static int pointUndo(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        ScenarioDraft draft = ScenarioManager.draft(player);
        if (draft == null || !draft.removeLastPoint()) {
            Msg.chat(player, Msg.failure("No point to remove."));
            return 0;
        }
        Msg.chat(player, Msg.success(draft.points.size() + " points left"));
        return 1;
    }

    /**
     * Adds a bot to the draft by name.
     *
     * <p>The Conversation Stick is the pleasant way to do this, but pointing at things does not
     * work from a command block, and it does not work for a bot you cannot see. Same effect,
     * typed.</p>
     */
    private static int addBot(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        ScenarioDraft draft = ScenarioManager.draft(player);
        if (draft == null) {
            Msg.chat(player, Msg.failure("Nothing being built."));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "bot");
        if (!BotManager.isBot(name)) {
            Msg.chat(player, Msg.failure(name + " is not a bot."));
            return 0;
        }
        if (!draft.bots.add(name)) {
            draft.bots.remove(name);
            Msg.chat(player, Msg.success("Removed " + name + " (" + draft.bots.size() + ")"));
            return 1;
        }
        Msg.chat(player, Msg.success("Added " + name + " (" + draft.bots.size() + ")"));
        return 1;
    }

    private static int finish(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        ScenarioDraft draft = ScenarioManager.draft(player);
        if (draft == null) {
            Msg.chat(player, Msg.failure("Nothing being built."));
            return 0;
        }

        String problem = draft.validate();
        if (problem != null) {
            Msg.chat(player, Msg.failure(problem));
            return 0;
        }

        ScenarioInstance instance = ScenarioManager.create(draft, player.level());
        instance.watchTargets.addAll(takePendingTargets(player));
        ScenarioManager.cancelDraft(player);
        ScenariosMenu.announce(player, instance);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        if (ScenarioManager.draft(player) == null) {
            Msg.chat(player, Msg.failure("Nothing being built."));
            return 0;
        }
        ScenarioManager.cancelDraft(player);
        Msg.chat(player, Msg.success("Cancelled"));
        return 1;
    }

    // ===================== crowd =====================

    /**
     * Sweeps every bot within a radius into a crowd draft.
     *
     * <p>A crowd is defined by who is standing there, not by a route, so it is gathered in one
     * command rather than built up point by point.</p>
     */
    private static int crowd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        double radius = DoubleArgumentType.getDouble(ctx, "radius");

        ScenarioDraft draft = ScenarioManager.draft(player);
        if (draft == null || draft.type != Scenario.CROWD) {
            draft = ScenarioManager.beginDraft(player, Scenario.CROWD);
        }
        Vec3 centre = ctx.getSource().getPosition();
        draft.addPoint(centre);
        draft.settings.radius = radius;

        int added = 0;
        for (ServerPlayer candidate : player.level().getServer()
            .getPlayerList().getPlayers()) {
            if (!BotManager.isBot(candidate.getName().getString())) continue;
            if (candidate.level() != player.level()) continue;
            if (candidate.position().distanceTo(centre) > radius) continue;
            if (draft.bots.add(candidate.getName().getString())) added++;
        }

        if (added == 0) {
            Msg.chat(player, Msg.failure("No bots within " + (int) radius + " blocks."));
            return 0;
        }
        Msg.chat(player, Msg.success(added + " bots gathered. Point them at someone with "
            + "/bots scenarios assigncrowd <names>, then finish."));
        return 1;
    }

    /**
     * Sets what a crowd is watching.
     *
     * <p>Works on a draft or on a crowd already running, because "everyone look over there" is
     * something you want to change while it is happening.</p>
     */
    private static int assignCrowd(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        String raw = StringArgumentType.getString(ctx, "targets");

        java.util.List<String> names = new java.util.ArrayList<>();
        for (String part : raw.split("[,\\s]+")) {
            if (!part.isBlank()) names.add(part.trim());
        }
        if (names.isEmpty()) {
            Msg.chat(player, Msg.failure("Name at least one target."));
            return 0;
        }

        ScenarioInstance running = null;
        for (ScenarioInstance instance : ScenarioManager.all()) {
            if (instance.type == Scenario.CROWD) running = instance;
        }

        ScenarioDraft draft = ScenarioManager.draft(player);
        if (draft != null && draft.type == Scenario.CROWD) {
            pendingTargets.put(player.getUUID(), names);
            Msg.chat(player, Msg.success("Crowd will watch: " + String.join(", ", names)));
            return 1;
        }
        if (running != null) {
            running.watchTargets.clear();
            running.watchTargets.addAll(names);
            Msg.chat(player, Msg.success("Crowd #" + running.id + " now watching: "
                + String.join(", ", names)));
            return 1;
        }

        Msg.chat(player, Msg.failure("No crowd to assign - run /bots scenarios crowd <radius>."));
        return 0;
    }

    /** Watch targets named before the crowd was finished, applied at creation. */
    private static final java.util.Map<java.util.UUID, java.util.List<String>> pendingTargets =
        new java.util.HashMap<>();

    public static java.util.List<String> takePendingTargets(ServerPlayer player) {
        java.util.List<String> names = pendingTargets.remove(player.getUUID());
        return names == null ? java.util.List.of() : names;
    }

    // ===================== management =====================

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var all = ScenarioManager.all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No scenarios running."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Scenarios (" + all.size() + "):"), false);
        for (ScenarioInstance instance : all) {
            String line = " #" + instance.id + "  " + instance.describe()
                + (instance.live ? "" : "  (frozen - not loaded)");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        if (!ScenarioManager.remove(id)) {
            ctx.getSource().sendFailure(Component.literal("No scenario #" + id));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Removed scenario #" + id), true);
        return 1;
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) {
        int count = ScenarioManager.removeAll();
        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + count + " scenarios"), true);
        return count;
    }
}
