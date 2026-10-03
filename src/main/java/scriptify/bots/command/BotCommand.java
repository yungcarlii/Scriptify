package scriptify.bots.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import scriptify.bots.bot.BotCombat;
import scriptify.bots.bot.BotFaction;
import scriptify.bots.bot.BotKits;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotNameGenerator;
import scriptify.bots.bot.BotPath;
import scriptify.bots.bot.BotSettings;
import scriptify.bots.config.SettingsRegistry;

public class BotCommand {

    static final SuggestionProvider<CommandSourceStack> BOT_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String bot : BotManager.botNames()) {
                if (bot.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(bot);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> KIT_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String kit : BotKits.getKitNames()) {
                if (kit.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(kit);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> TOOL_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (var tool : scriptify.bots.tool.BotTool.values()) {
                if (tool.id().startsWith(remaining)) {
                    builder.suggest(tool.id());
                }
            }
            return builder.buildFuture();
        };

    /**
     * Suggests whatever the tool being asked for points at - kits for the kit shield, paths for the
     * path stick - rather than one list for all of them, which would offer a kit to a path stick.
     */
    private static final SuggestionProvider<CommandSourceStack> TOOL_ARGUMENT_SUGGESTIONS =
        (ctx, builder) -> {
            var tool = scriptify.bots.tool.BotTool.byId(StringArgumentType.getString(ctx, "tool"));
            if (tool == null) {
                return builder.buildFuture();
            }
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            java.util.Collection<String> options = switch (tool.binding()) {
                case KIT -> scriptify.bots.bot.BotKits.getKitNames();
                case PATH -> BotPath.getAllPaths().keySet();
                case FACTION -> scriptify.bots.bot.BotFaction.getAllFactions();
                case COUNT -> java.util.List.of("10", "50", "100", "250", "500");
                case NONE -> java.util.List.of();
            };
            for (String option : options) {
                if (option.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(option);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (var player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                String name = player.getName().getString();
                if (name.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> PATH_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String pathName : BotPath.getAllPaths().keySet()) {
                if (pathName.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(pathName);
                }
            }
            return builder.buildFuture();
        };

    private static LiteralArgumentBuilder<CommandSourceStack> cmd(String literal, String description) {
        return Commands.literal(literal)
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("§7[Tip] §a" + description), false);
                return 1;
            });
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bots")
            .executes(BotCommand::openGui)
            // Bots are a staging tool, not something every player on the server should be able to
            // spawn five hundred of. Gated on the same node the bot event actions already used, which
            // until now was the only thing checking it - the command tree itself was open to anyone.
            .requires(s -> scriptify.core.ScriptifyBootstrap.context() != null
                && scriptify.core.ScriptifyBootstrap.context().permissions()
                    .has(s, scriptify.permission.PermissionService.BOTS))

            // ========== GUI ==========
            .then(Commands.literal("gui")
                .executes(BotCommand::openGui))

            // ========== PERFORMANCE ==========
            .then(cmd("performance", "What the bots are costing the server right now")
                .executes(BotCommand::performance))

            // ========== INSPECT ==========
            .then(cmd("who", "Show a bot's personality and current playstyle")
                .then(BotSelector.bots("botname")
                    .executes(BotCommand::botProfile)))

            // ========== SPAWN ==========
            .then(Commands.literal("spawn")
                .executes(ctx -> spawn(ctx, null))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))))

            // ========== REMOVE ==========
            .then(cmd("remove", "Remove a bot")
                .then(BotSelector.bots("name")
                    .executes(BotCommand::remove)))

            // ========== REMOVEALL ==========
            .then(Commands.literal("reload")
                .executes(BotCommand::reload))
            .then(Commands.literal("removeall")
                .executes(BotCommand::removeAll))

            // ========== SCENARIOS ==========
            .then(ScenarioCommand.node())

            // ========== SETTINGS ==========
            .then(buildSettings())

            // ========== BOT-MANAGEMENT ==========
            .then(cmd("bot-management", "Bot management")
                .then(cmd("mass-spawn", "Mass spawn bots")
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 10000))
                        .executes(BotCommand::massSpawn)))
                .then(cmd("attack", "Attack a target")
                    .then(BotSelector.bots("botname")
                        .then(BotSelector.players("target")
                            .executes(BotCommand::botAttack))))
                .then(cmd("stop-attack", "Stop attack")
                    .then(BotSelector.bots("botname")
                        .executes(BotCommand::botStopAttack)))
                .then(cmd("inventory", "Show bot inventory")
                    .then(BotSelector.bots("botname")
                        .executes(BotCommand::botInventory)))
                .then(cmd("combatpath", "Patrol a path: fight any enemy that comes into view, then carry on")
                    .then(Commands.argument("path", StringArgumentType.word())
                        .suggests(PATH_SUGGESTIONS)
                        .then(BotSelector.bots("bots")
                            .executes(ctx -> combatPath(ctx, BotPath.DEFAULT_SIGHT_RANGE))
                            .then(Commands.argument("sight-range", DoubleArgumentType.doubleArg(4, 128))
                                .executes(ctx -> combatPath(ctx,
                                    DoubleArgumentType.getDouble(ctx, "sight-range")))))))
                .then(Commands.literal("list")
                    .executes(BotCommand::botList))
                .then(cmd("path", "Path management")
                    .then(cmd("create", "Create a path")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(BotCommand::pathCreate)))
                    .then(cmd("delete", "Delete a path")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathDelete)))
                    .then(cmd("add-point", "Add point to path")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathAddPoint)))
                    .then(cmd("remove-point", "Remove point from path")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("index", IntegerArgumentType.integer())
                                .executes(BotCommand::pathRemovePoint))
                            .executes(BotCommand::pathRemovePointLast)))
                    .then(cmd("clear", "Clear all path points")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathClear)))
                    .then(cmd("loop", "Toggle path looping")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(BotCommand::pathLoop))))
                    .then(cmd("start", "Start a bot on a path")
                        // One argument hands out the stick and you poke the bots you want; two does
                        // it outright, for a command block or a script that already knows the name.
                        .then(Commands.argument("path", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathStartStick)
                            .then(BotSelector.bots("bot")
                                .executes(BotCommand::pathStart))))
                    .then(cmd("stop", "Stop bot on path")
                        .then(BotSelector.bots("bot")
                            .executes(BotCommand::pathStop)))
                    .then(Commands.literal("list")
                        .executes(BotCommand::pathList))
                    .then(cmd("show", "Show/hide path particles")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("visible", BoolArgumentType.bool())
                                .executes(BotCommand::pathShow))))
                    .then(cmd("info", "Path info")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathInfo)))
                    .then(cmd("distribute", "Distribute bots along path")
                        .then(Commands.argument("path", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathDistribute)))
                    .then(cmd("start-near", "Start bots near path")
                        .then(Commands.argument("path", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1))
                                .executes(BotCommand::pathStartNear))))
                    .then(cmd("stop-all", "Stop all bots on path")
                        .then(Commands.argument("path", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .executes(BotCommand::pathStopAll)))
                    .then(cmd("walk-type", "Path walk type")
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(PATH_SUGGESTIONS)
                            .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("bhop");
                                    builder.suggest("sprint");
                                    builder.suggest("walk");
                                    return builder.buildFuture();
                                })
                                .executes(BotCommand::pathWalkType))))))

            // ========== TOOLS ==========
            .then(cmd("tools", "Open the tool rack")
                .executes(BotCommand::toolsMenu))
            .then(cmd("tool", "Take one bot tool")
                .then(Commands.argument("tool", StringArgumentType.word())
                    .suggests(TOOL_SUGGESTIONS)
                    .executes(ctx -> tool(ctx, null))
                    .then(Commands.argument("argument", StringArgumentType.word())
                        .suggests(TOOL_ARGUMENT_SUGGESTIONS)
                        .executes(ctx -> tool(ctx,
                            StringArgumentType.getString(ctx, "argument"))))))

            // ========== KIT ==========
            .then(cmd("kit", "Kit management")
                .then(cmd("create-kit", "Create kit from inventory")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommand::kitCreate)))
                .then(cmd("delete-kit", "Delete a kit")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(KIT_SUGGESTIONS)
                        .executes(BotCommand::kitDelete)))
                .then(cmd("give-kit", "Give kit to player")
                    .then(BotSelector.players("playername")
                        .then(Commands.argument("kitname", StringArgumentType.word())
                            .suggests(KIT_SUGGESTIONS)
                            .executes(BotCommand::kitGive))))
                .then(cmd("give-kit-near", "Give kit to bots within radius")
                    .then(Commands.argument("kitname", StringArgumentType.word())
                        .suggests(KIT_SUGGESTIONS)
                        .executes(ctx -> kitGiveNear(ctx, 10.0))
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 1000))
                            .executes(ctx -> kitGiveNear(ctx, DoubleArgumentType.getDouble(ctx, "radius"))))))
                .then(cmd("give-kit-near-random", "Give random kit to bots within radius")
                    .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 1000))
                        .then(Commands.argument("kits", StringArgumentType.greedyString())
                            .suggests(RANDOM_KIT_SUGGESTIONS)
                            .executes(BotCommand::kitGiveNearRandom))))
                .then(cmd("stick", "Get a Kit Stick bound to a kit")
                    .then(Commands.argument("kitname", StringArgumentType.word())
                        .suggests(KIT_SUGGESTIONS)
                        .executes(BotCommand::kitStick)))
                .then(cmd("edit", "Edit what is in a kit")
                    .then(Commands.argument("kitname", StringArgumentType.word())
                        .suggests(KIT_SUGGESTIONS)
                        .executes(BotCommand::kitEdit)))
                .then(cmd("kits", "List all kits")
                    .executes(BotCommand::kitList)))

            // ========== FACTION ==========
            .then(cmd("faction", "Faction management")
                .then(cmd("list", "List all factions")
                    .executes(BotCommand::factionList))
                .then(cmd("create", "Create a faction")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(BotCommand::factionCreate)))
                .then(cmd("delete", "Delete a faction")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .executes(BotCommand::factionDelete)))
                .then(cmd("add", "Add player to faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(BotSelector.players("player")
                            .executes(BotCommand::factionAdd))))
                .then(cmd("remove", "Remove player from faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(BotSelector.players("player")
                            .executes(BotCommand::factionRemove))))
                .then(cmd("hostile", "Set faction hostility")
                    .then(Commands.argument("faction1", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("faction2", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .then(Commands.argument("hostile", BoolArgumentType.bool())
                                .executes(ctx -> factionHostile(ctx, BoolArgumentType.getBool(ctx, "hostile"))))
                            .executes(ctx -> factionHostile(ctx, true)))))
                .then(cmd("info", "Faction info")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .executes(BotCommand::factionInfo)))
                .then(cmd("add-near", "Add nearby players to faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 10000))
                            .executes(BotCommand::factionAddNear))))
                .then(cmd("add-all", "Add all players to faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .executes(BotCommand::factionAddAll)))
                .then(cmd("give", "Give item to faction")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("item", StringArgumentType.greedyString())
                            .executes(BotCommand::factionGive))))
                .then(cmd("attack", "Faction attack target")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(BotSelector.players("target")
                            .executes(BotCommand::factionAttack))))
                .then(cmd("path", "Faction path control")
                    .then(cmd("start", "Start faction on path")
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .then(Commands.argument("path", StringArgumentType.word())
                                .suggests(PATH_SUGGESTIONS)
                                .executes(BotCommand::factionStartPath))))
                    .then(cmd("stop", "Stop faction on path")
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .executes(BotCommand::factionStopPath))))
                .then(cmd("tp", "Teleport faction bots to location")
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(FACTION_SUGGESTIONS)
                        .then(Commands.argument("location", StringArgumentType.greedyString())
                            .suggests(PLAYER_SUGGESTIONS)
                            .executes(BotCommand::factionTp))))
                .then(cmd("kit", "Faction kit management")
                    .then(cmd("give-kit", "Give kit to faction")
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .then(Commands.argument("kitname", StringArgumentType.word())
                                .suggests(KIT_SUGGESTIONS)
                                .executes(BotCommand::factionGiveKit))))
                    .then(cmd("give-kit-random", "Give random kit to faction by percentage")
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_SUGGESTIONS)
                            .then(Commands.argument("kits", StringArgumentType.greedyString())
                                .suggests(RANDOM_KIT_SUGGESTIONS)
                                .executes(BotCommand::factionGiveKitRandom))))))

        );
    }

    private static final SuggestionProvider<CommandSourceStack> RANDOM_KIT_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining();
            int tokenCount;
            String partial;
            if (remaining.isEmpty()) {
                tokenCount = 0;
                partial = "";
            } else {
                int lastSpace = remaining.lastIndexOf(' ');
                if (lastSpace >= 0) {
                    partial = remaining.substring(lastSpace + 1);
                    tokenCount = remaining.substring(0, lastSpace).split(" ", -1).length;
                    if (tokenCount == 1 && remaining.substring(0, lastSpace).isEmpty()) tokenCount = 0;
                } else {
                    partial = remaining;
                    tokenCount = 0;
                }
            }
            var wordBuilder = builder.createOffset(builder.getStart() + remaining.length() - partial.length());
            if (tokenCount % 2 == 1) {
                for (int i = 1; i <= 100; i++) {
                    wordBuilder.suggest(i + "%");
                }
            } else {
                for (String kit : BotKits.getKitNames()) {
                    if (kit.regionMatches(true, 0, partial, 0, partial.length())) {
                        wordBuilder.suggest(kit);
                    }
                }
            }
            return wordBuilder.buildFuture();
        };

    // ========== SETTINGS BUILDER ==========

    /**
     * Builds the {@code /bots settings} tree from {@link SettingsRegistry}.
     *
     * <p>This used to be sixty hand-written branches that had to be kept in step with the
     * fields, their clamps and the GUI by hand - and had already drifted, which is how a
     * registered setting ended up unreachable from chat. There is now exactly one list of
     * settings in the mod, and both the command and the panel are generated from it.</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildSettings() {
        var settings = Commands.literal("settings").executes(BotCommand::settings);

        for (SettingsRegistry.Entry entry : SettingsRegistry.all()) {
            settings.then(settingNode(entry));
        }
        return settings;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> settingNode(SettingsRegistry.Entry entry) {
        var node = Commands.literal(entry.key)
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("§7[Tip] §a" + entry.description
                    + " §7(current: " + entry.valueString()
                    + ", default: " + entry.defaultString() + ")"), false);
                return 1;
            });

        return switch (entry.kind) {
            case BOOL -> node.then(Commands.argument("value", BoolArgumentType.bool())
                .executes(ctx -> {
                    boolean value = BoolArgumentType.getBool(ctx, "value");
                    entry.setBool(value);
                    BotSettings.flush();
                    ctx.getSource().sendSuccess(() -> Component.literal(entry.key + ": " + value), true);
                    return 1;
                }));
            case INT -> node.then(Commands.argument("value",
                    IntegerArgumentType.integer((int) entry.min, (int) entry.max))
                .executes(ctx -> {
                    int value = IntegerArgumentType.getInteger(ctx, "value");
                    entry.setInt(value);
                    BotSettings.flush();
                    ctx.getSource().sendSuccess(() -> Component.literal(entry.key + ": " + value), true);
                    return 1;
                }));
            case DOUBLE -> node.then(Commands.argument("value",
                    DoubleArgumentType.doubleArg(entry.min, entry.max))
                .executes(ctx -> {
                    double value = DoubleArgumentType.getDouble(ctx, "value");
                    entry.setDouble(value);
                    BotSettings.flush();
                    ctx.getSource().sendSuccess(() -> Component.literal(entry.key + ": "
                        + entry.valueString()), true);
                    return 1;
                }));
        };
    }

    // ========== FAST COMMANDS ==========

    private static final SuggestionProvider<CommandSourceStack> FACTION_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String f : BotFaction.getAllFactions()) {
                if (f.toLowerCase(java.util.Locale.ROOT).contains(remaining)) {
                    builder.suggest(f);
                }
            }
            return builder.buildFuture();
        };

    // ========== SETTING HELPERS ==========

    // ========== COMMAND HANDLERS ==========

    private static int spawn(CommandContext<CommandSourceStack> ctx, String name) {
        var source = ctx.getSource();
        String botName = name != null ? name : BotNameGenerator.generateUniqueName();
        var server = source.getServer();
        var existingPlayer = server.getPlayerList().getPlayer(botName);
        if (existingPlayer != null && !BotManager.isBot(botName)) {
            source.sendFailure(Component.literal("Cannot create bot '" + botName + "': a real player with this name is online!"));
            return 0;
        }
        // Only a name the operator actually typed is worth a profile lookup; a generated one
        // has no account behind it and would just cost a doomed network round trip.
        boolean operatorNamed = name != null;
        if (BotManager.spawnBot(server, botName, source, null, operatorNamed)) {
            source.sendSuccess(() -> Component.literal("Bot '" + botName + "' spawned!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to spawn bot '" + botName + "' (bot already exists or name is taken)"));
            return 0;
        }
    }

    private static int massSpawn(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int maxAllowed = BotSettings.get().getMaxMassSpawn();
        if (count > maxAllowed) {
            source.sendFailure(Component.literal("Cannot spawn more than " + maxAllowed + " bots at once. Use /bots settings max-mass-spawn to increase the limit."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Spawning " + count + " bots..."), false);
        // Always spawn where the caller is. "safe-spawn" controls how the batch is *spread*,
        // not where it lands; gating the origin on it dumped whole batches at world spawn.
        BotManager.queueMassSpawn(server, count, source.getPosition(), source);
        return 1;
    }


    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        List<String> bots = BotSelector.bots(ctx, "name");
        int removed = 0;
        for (String name : bots) {
            if (BotManager.removeBot(source.getServer(), name, source)) removed++;
        }
        if (removed == 0) {
            source.sendFailure(Component.literal(BotSelector.describe(bots) + " could not be removed!"));
            return 0;
        }
        String what = bots.size() == 1 ? "Bot '" + bots.get(0) + "'" : removed + " bots";
        source.sendSuccess(() -> Component.literal(what + " removed!"), true);
        return removed;
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        int count = BotManager.getBotCount();
        BotManager.removeAllBots(source.getServer(), source);
        source.sendSuccess(() -> Component.literal("Removed " + count + " bots"), true);
        return count;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        BotSettings.flush();
        BotSettings.load();
        BotKits.reload(server);
        BotPath.init();
        BotManager.reloadBots();
        source.sendSuccess(() -> Component.literal("All configurations reloaded!"), true);
        return 1;
    }

    /** Prints every setting, grouped by category, with its current value. */
    private static int settings(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        for (var category : SettingsRegistry.byCategory().entrySet()) {
            if (category.getValue().isEmpty()) continue;
            source.sendSuccess(() -> Component.literal("§b§l" + category.getKey()), false);
            for (SettingsRegistry.Entry entry : category.getValue()) {
                source.sendSuccess(() -> Component.literal("  §7" + entry.key + ": §f"
                    + entry.valueString()), false);
            }
        }
        source.sendSuccess(() -> Component.literal("§8Use /bots for the full panel."), false);
        return 1;
    }

    private static int botAttack(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        List<String> bots = BotSelector.bots(ctx, "botname");
        List<String> targets = BotSelector.playerNames(ctx, "target");
        // Several targets are shared out in turn, so "@a[team=red] @a[team=blue]" pairs the bots
        // off across the enemy team rather than sending every one of them at the same person.
        // A bot is never handed itself.
        int assigned = 0;
        for (int i = 0; i < bots.size(); i++) {
            String bot = bots.get(i);
            String target = targets.get(i % targets.size());
            if (target.equalsIgnoreCase(bot)) {
                if (targets.size() == 1) continue;
                target = targets.get((i + 1) % targets.size());
            }
            BotCombat.setTarget(BotManager.canonical(bot), target);
            assigned++;
        }
        if (assigned == 0) {
            source.sendFailure(Component.literal("A bot cannot be told to attack itself."));
            return 0;
        }
        String who = targets.size() == 1 ? "'" + targets.get(0) + "'" : targets.size() + " targets";
        String what = BotSelector.describe(bots);
        source.sendSuccess(() -> Component.literal(what + " now attacking " + who), true);
        return assigned;
    }

    private static int botStopAttack(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        List<String> bots = BotSelector.bots(ctx, "botname");
        for (String bot : bots) {
            BotCombat.clearTarget(BotManager.canonical(bot));
        }
        source.sendSuccess(() -> Component.literal(BotSelector.describe(bots) + " stopped attacking"), true);
        return bots.size();
    }

    private static int botInventory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        List<String> bots = BotSelector.bots(ctx, "botname");
        int shown = 0;
        for (String botname : bots) {
            var bot = source.getServer().getPlayerList().getPlayer(botname);
            if (bot == null) {
                source.sendFailure(Component.literal("Bot '" + botname + "' not online!"));
                continue;
            }
            var inventory = bot.getInventory();
            source.sendSuccess(() -> Component.literal("=== Inventory: " + botname + " ==="), false);
            StringBuilder hotbar = new StringBuilder("Hotbar: ");
            for (int i = 0; i < 9; i++) {
                var stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    hotbar.append("[").append(stack.getHoverName().getString()).append(" x").append(stack.getCount()).append("] ");
                }
            }
            source.sendSuccess(() -> Component.literal(hotbar.toString().trim()), false);
            StringBuilder mainInv = new StringBuilder("Main: ");
            for (int i = 9; i < 36; i++) {
                var stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    mainInv.append("[").append(stack.getHoverName().getString()).append(" x").append(stack.getCount()).append("] ");
                }
            }
            source.sendSuccess(() -> Component.literal(mainInv.toString().trim()), false);
            StringBuilder armor = new StringBuilder("Armor: ");
            for (int i = 36; i < 40; i++) {
                var stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    armor.append("[").append(stack.getHoverName().getString()).append("] ");
                }
            }
            String armorStr = armor.toString().trim();
            if (!armorStr.equals("Armor:")) {
                source.sendSuccess(() -> Component.literal(armorStr), false);
            }
            var offhand = inventory.getItem(40);
            if (!offhand.isEmpty()) {
                source.sendSuccess(() -> Component.literal("Offhand: [" + offhand.getHoverName().getString() + " x" + offhand.getCount() + "]"), false);
            }
            source.sendSuccess(() -> Component.literal("HP: " + String.format("%.1f", bot.getHealth()) + "/" + String.format("%.1f", bot.getMaxHealth()) +
                " | Food: " + bot.getFoodData().getFoodLevel() +
                " | XP: " + bot.experienceLevel), false);
            shown++;
        }
        return shown;
    }

    private static int botList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var bots = BotManager.botNames();
        if (bots.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active PvP bots"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Active PvP bots (" + bots.size() + "):"), false);
            for (String botName : bots) {
                source.sendSuccess(() -> Component.literal(" - " + botName), false);
            }
        }
        return bots.size();
    }

    // ========== PATH HANDLERS ==========

    /**
     * Draws a path by walking it, rather than creating an empty one to add points to by command.
     *
     * <p>The point-at-a-time commands still exist and still work; this is the same route built the
     * way the bot editor builds one, and it lands in the same {@code paths.json} either way.</p>
     */
    private static int pathCreate(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("A path is drawn by walking it, so this needs a player."));
            return 0;
        }
        if (BotPath.getPath(name) != null) {
            source.sendFailure(Component.literal("Path '" + name + "' already exists"));
            return 0;
        }
        scriptify.bots.npc.EditSession.beginNamedPath(player, name);
        return 1;
    }

    private static int pathDelete(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotPath.deletePath(name)) {
            source.sendSuccess(() -> Component.literal("Path '" + name + "' deleted"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathAddPoint(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only players can add path points"));
            return 0;
        }
        Vec3 pos = new Vec3(player.getX(), player.getY(), player.getZ());
        if (BotPath.addPoint(name, pos)) {
            var path = BotPath.getPath(name);
            if (!BotPath.isPathVisible(name)) {
                BotPath.setPathVisible(name, true);
                source.sendSuccess(() -> Component.literal("Visualization enabled. To disable: /bots path show " + name + " false"), false);
            }
            source.sendSuccess(() -> Component.literal(String.format("Point #%d added to path '%s' at (%.1f, %.1f, %.1f)", path.points.size(), name, pos.x, pos.y, pos.z)), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathRemovePoint(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (BotPath.removePoint(name, index)) {
            source.sendSuccess(() -> Component.literal("Point #" + index + " removed from path '" + name + "'"), true);
            return 1;
        }
        source.sendFailure(Component.literal("Invalid path or index"));
        return 0;
    }

    private static int pathRemovePointLast(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotPath.removeLastPoint(name)) {
            source.sendSuccess(() -> Component.literal("Last point removed from path '" + name + "'"), true);
            return 1;
        }
        source.sendFailure(Component.literal("Invalid path or index"));
        return 0;
    }

    private static int pathClear(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotPath.clearPath(name)) {
            source.sendSuccess(() -> Component.literal("All points cleared from path '" + name + "'"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathLoop(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        boolean value = BoolArgumentType.getBool(ctx, "value");
        if (BotPath.setLoop(name, value)) {
            source.sendSuccess(() -> Component.literal("Path '" + name + "' loop: " + value), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        List<String> bots = BotSelector.bots(ctx, "bot");
        String path = StringArgumentType.getString(ctx, "path");
        int started = 0;
        for (String bot : bots) {
            if (BotPath.startFollowing(bot, path)) started++;
        }
        if (started == 0) {
            source.sendFailure(Component.literal("Path '" + path + "' not found or empty"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(BotSelector.describe(bots)
            + " started following path '" + path + "'"), true);
        return started;
    }

    /**
     * Puts bots on a route as a patrol. They walk it, and when an enemy comes into view they leave
     * it to fight, then walk back to where they left off and carry on.
     */
    private static int combatPath(CommandContext<CommandSourceStack> ctx, double sightRange)
        throws CommandSyntaxException {
        var source = ctx.getSource();
        String path = StringArgumentType.getString(ctx, "path");
        List<String> bots = BotSelector.bots(ctx, "bots");
        int started = 0;
        for (String bot : bots) {
            if (BotPath.startCombatPath(bot, path, sightRange)) started++;
        }
        if (started == 0) {
            source.sendFailure(Component.literal("Path '" + path + "' not found or empty"));
            return 0;
        }
        String range = sightRange == Math.rint(sightRange)
            ? String.valueOf((int) sightRange) : String.valueOf(sightRange);
        source.sendSuccess(() -> Component.literal(BotSelector.describe(bots) + " patrolling '" + path
            + "', engaging enemies within " + range + " blocks"), true);
        return started;
    }

    /** Hands out a stick bound to this path; right clicking a bot with it starts that bot. */
    private static int toolsMenu(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("The tool rack is a screen, so this needs a player."));
            return 0;
        }
        scriptify.bots.ui.ToolsMenu.open(player);
        return 1;
    }

    private static int tool(CommandContext<CommandSourceStack> ctx, String argument) {
        var source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can be handed a tool."));
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "tool");
        var tool = scriptify.bots.tool.BotTool.byId(id);
        if (tool == null) {
            source.sendFailure(Component.literal("There is no tool called '" + id + "'."));
            return 0;
        }
        String problem = scriptify.bots.tool.BotTools.give(player, tool, argument);
        if (problem != null) {
            source.sendFailure(Component.literal(problem));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(tool.displayName() + " is in your inventory."),
            false);
        return 1;
    }

    private static int pathStartStick(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String path = StringArgumentType.getString(ctx, "path");
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can be handed a stick."));
            return 0;
        }
        String problem = scriptify.bots.tool.BotTools.give(player,
            scriptify.bots.tool.BotTool.PATH, path);
        if (problem != null) {
            source.sendFailure(Component.literal(problem));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "Right click a bot with the Path Stick to put it on '" + path + "'"), false);
        return 1;
    }

    private static int pathStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        List<String> bots = BotSelector.bots(ctx, "bot");
        int stopped = 0;
        for (String bot : bots) {
            if (BotPath.stopFollowing(bot)) stopped++;
        }
        if (stopped == 0) {
            source.sendFailure(Component.literal(bots.size() == 1
                ? "Bot '" + bots.get(0) + "' is not following any path"
                : "None of those bots are following a path"));
            return 0;
        }
        String what = bots.size() == 1 ? "Bot '" + bots.get(0) + "'" : stopped + " bots";
        source.sendSuccess(() -> Component.literal(what + " stopped following path"), true);
        return stopped;
    }

    private static int pathWalkType(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String type = StringArgumentType.getString(ctx, "type");
        if (!type.equals("bhop") && !type.equals("sprint") && !type.equals("walk")) {
            source.sendFailure(Component.literal("Invalid walk type. Use: bhop, sprint, or walk"));
            return 0;
        }
        if (BotPath.setWalkType(name, type)) {
            source.sendSuccess(() -> Component.literal("Path '" + name + "' walk type: " + type), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var paths = BotPath.getAllPaths();
        if (paths.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No paths created"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("=== Paths ==="), false);
        for (var entry : paths.entrySet()) {
            String name = entry.getKey();
            var path = entry.getValue();
            source.sendSuccess(() -> Component.literal(String.format("%s: %d points, loop: %s, attack: %s, walk-type: %s", name, path.points.size(), path.loop, path.attack, path.walkType)), false);
        }
        return paths.size();
    }

    private static int pathShow(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        boolean visible = BoolArgumentType.getBool(ctx, "visible");
        if (BotPath.setPathVisible(name, visible)) {
            if (visible) {
                source.sendSuccess(() -> Component.literal("Path '" + name + "' visualization enabled"), true);
                source.sendSuccess(() -> Component.literal("To disable: /bots path show " + name + " false"), false);
            } else {
                source.sendSuccess(() -> Component.literal("Path '" + name + "' visualization disabled"), true);
            }
            return 1;
        } else {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
    }

    private static int pathInfo(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        var path = BotPath.getPath(name);
        if (path == null) {
            source.sendFailure(Component.literal("Path '" + name + "' not found"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("=== Path: " + name + " ==="), false);
        source.sendSuccess(() -> Component.literal("Points: " + path.points.size()), false);
        source.sendSuccess(() -> Component.literal("Loop: " + path.loop), false);
        source.sendSuccess(() -> Component.literal("Attack: " + path.attack), false);
        source.sendSuccess(() -> Component.literal("Walk type: " + path.walkType), false);
        for (int i = 0; i < path.points.size(); i++) {
            var point = path.points.get(i);
            int index = i;
            source.sendSuccess(() -> Component.literal(String.format("#%d: (%.1f, %.1f, %.1f)", index, point.x, point.y, point.z)), false);
        }
        return 1;
    }

    private static int pathDistribute(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String pathName = StringArgumentType.getString(ctx, "path");
        var path = BotPath.getPath(pathName);
        if (path == null) {
            source.sendFailure(Component.literal("Path '" + pathName + "' not found"));
            return 0;
        }
        if (path.points.isEmpty()) {
            source.sendFailure(Component.literal("Path '" + pathName + "' has no points"));
            return 0;
        }
        var server = source.getServer();
        var botsOnPath = new java.util.ArrayList<String>();
        for (String botName : BotManager.botNames()) {
            if (BotPath.isFollowing(botName, pathName)) {
                botsOnPath.add(botName);
            }
        }
        if (botsOnPath.isEmpty()) {
            source.sendFailure(Component.literal("No bots are following path '" + pathName + "'"));
            return 0;
        }
        int totalPoints = path.points.size();
        int botCount = botsOnPath.size();
        for (int i = 0; i < botCount; i++) {
            String botName = botsOnPath.get(i);
            int pointIndex = (i * totalPoints) / botCount;
            BotPath.setBotPathIndex(botName, pointIndex);
            var point = path.points.get(pointIndex);
            scriptify.bots.bot.BotActions.execute(server, String.format(java.util.Locale.US,
                "tp %s %.2f %.2f %.2f", botName, point.x, point.y + 1.0, point.z));
        }
        source.sendSuccess(() -> Component.literal("Distributed " + botCount + " bots along path '" + pathName + "'"), true);
        return botCount;
    }

    private static int pathStartNear(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String pathName = StringArgumentType.getString(ctx, "path");
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        var path = BotPath.getPath(pathName);
        if (path == null) {
            source.sendFailure(Component.literal("Path '" + pathName + "' not found"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by a player"));
            return 0;
        }
        var server = source.getServer();
        int started = 0;
        for (String botName : BotManager.botNames()) {
            ServerPlayer bot = server.getPlayerList().getPlayer(botName);
            if (bot != null && bot.distanceTo(player) <= radius) {
                if (BotPath.startFollowing(botName, pathName)) {
                    started++;
                }
            }
        }
        if (started > 0) {
            int finalStarted = started;
            source.sendSuccess(() -> Component.literal("Started path '" + pathName + "' for " + finalStarted + " bots within " + radius + " blocks"), true);
            return started;
        } else {
            source.sendFailure(Component.literal("No bots found within " + radius + " blocks"));
            return 0;
        }
    }

    private static int pathStopAll(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String pathName = StringArgumentType.getString(ctx, "path");
        var path = BotPath.getPath(pathName);
        if (path == null) {
            source.sendFailure(Component.literal("Path '" + pathName + "' not found"));
            return 0;
        }
        int stopped = 0;
        for (String botName : BotManager.botNames()) {
            if (BotPath.isFollowing(botName, pathName)) {
                if (BotPath.stopFollowing(botName)) {
                    stopped++;
                }
            }
        }
        if (stopped > 0) {
            int finalStopped = stopped;
            source.sendSuccess(() -> Component.literal("Stopped " + finalStopped + " bots on path '" + pathName + "'"), true);
            return stopped;
        } else {
            source.sendFailure(Component.literal("No bots are following path '" + pathName + "'"));
            return 0;
        }
    }

    // ========== KIT HANDLERS ==========

    private static int kitCreate(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player!"));
            return 0;
        }
        if (BotKits.kitExists(name)) {
            source.sendFailure(Component.literal("Kit '" + name + "' already exists!"));
            return 0;
        }
        if (BotKits.createKit(name, player)) {
            source.sendSuccess(() -> Component.literal("Kit '" + name + "' created from your inventory!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to create kit (empty inventory?)"));
            return 0;
        }
    }

    private static int kitDelete(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotKits.deleteKit(name)) {
            source.sendSuccess(() -> Component.literal("Kit '" + name + "' deleted!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Kit '" + name + "' not found!"));
            return 0;
        }
    }

    private static int kitGive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        String kitname = StringArgumentType.getString(ctx, "kitname");
        if (!BotKits.kitExists(kitname)) {
            source.sendFailure(Component.literal("Kit '" + kitname + "' not found!"));
            return 0;
        }
        List<ServerPlayer> players = BotSelector.onlinePlayers(ctx, "playername");
        int given = 0;
        for (ServerPlayer player : players) {
            if (BotKits.giveKit(kitname, player)) given++;
        }
        if (given == 0) {
            source.sendFailure(Component.literal("Failed to give kit!"));
            return 0;
        }
        String who = players.size() == 1 ? "'" + players.get(0).getName().getString() + "'"
            : given + " players";
        source.sendSuccess(() -> Component.literal("Gave kit '" + kitname + "' to " + who), true);
        return given;
    }

    private static int kitList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var kits = BotKits.getKitNames();
        if (kits.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No kits created. Use /bots kit create-kit <name> to create one."), false);
        } else {
            source.sendSuccess(() -> Component.literal("Kits (" + kits.size() + "): " + String.join(", ", kits)), false);
        }
        return 1;
    }

    // ========== FACTION HANDLERS ==========

    private static int factionList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var factions = BotFaction.getAllFactions();
        if (factions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No factions created"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Factions (" + factions.size() + "):"), false);
            for (String faction : factions) {
                var members = BotFaction.getMembers(faction);
                var enemies = BotFaction.getHostileFactions(faction);
                source.sendSuccess(() -> Component.literal(" - " + faction + " (" + members.size() + " members, " + enemies.size() + " enemies)"), false);
            }
        }
        return factions.size();
    }

    private static int factionCreate(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotFaction.createFaction(name)) {
            source.sendSuccess(() -> Component.literal("Faction '" + name + "' created!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Faction '" + name + "' already exists!"));
            return 0;
        }
    }

    private static int factionDelete(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (BotFaction.deleteFaction(name)) {
            source.sendSuccess(() -> Component.literal("Faction '" + name + "' deleted!"), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Faction '" + name + "' not found!"));
            return 0;
        }
    }

    private static int factionAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        List<String> players = BotSelector.playerNames(ctx, "player");
        int changed = 0;
        for (String player : players) {
            if (BotFaction.addMember(faction, player)) changed++;
        }
        String who = players.size() == 1 ? "'" + players.get(0) + "'" : players.size() + " players";
        if (changed == 0) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        String done = players.size() == 1 ? who : changed + " players";
        source.sendSuccess(() -> Component.literal("Added " + done + " to faction '" + faction + "'"), true);
        return changed;
    }

    private static int factionRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        List<String> players = BotSelector.playerNames(ctx, "player");
        int changed = 0;
        for (String player : players) {
            if (BotFaction.removeMember(faction, player)) changed++;
        }
        String who = players.size() == 1 ? "'" + players.get(0) + "'" : players.size() + " players";
        if (changed == 0) {
            source.sendFailure(Component.literal("Failed to remove " + who + " from faction '" + faction + "'"));
            return 0;
        }
        String done = players.size() == 1 ? who : changed + " players";
        source.sendSuccess(() -> Component.literal("Removed " + done + " from faction '" + faction + "'"), true);
        return changed;
    }

    private static int factionHostile(CommandContext<CommandSourceStack> ctx, boolean isHostile) {
        var source = ctx.getSource();
        String faction1 = StringArgumentType.getString(ctx, "faction1");
        String faction2 = StringArgumentType.getString(ctx, "faction2");
        if (BotFaction.setHostile(faction1, faction2, isHostile)) {
            if (isHostile) {
                source.sendSuccess(() -> Component.literal("Factions '" + faction1 + "' and '" + faction2 + "' are now hostile!"), true);
            } else {
                source.sendSuccess(() -> Component.literal("Factions '" + faction1 + "' and '" + faction2 + "' are now neutral"), true);
            }
            return 1;
        } else {
            source.sendFailure(Component.literal("One or both factions not found, or same faction!"));
            return 0;
        }
    }

    private static int factionInfo(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        var members = BotFaction.getMembers(faction);
        var enemies = BotFaction.getHostileFactions(faction);
        if (members.isEmpty() && enemies.isEmpty() && !BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("=== Faction: " + faction + " ==="), false);
        source.sendSuccess(() -> Component.literal("Members (" + members.size() + "): " + String.join(", ", members)), false);
        source.sendSuccess(() -> Component.literal("Hostile to (" + enemies.size() + "): " + String.join(", ", enemies)), false);
        return 1;
    }

    private static int factionAddNear(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        var entity = source.getEntity();
        if (entity == null) {
            source.sendFailure(Component.literal("This command must be run by a player!"));
            return 0;
        }
        int count = 0;
        var allBots = BotManager.botNames();
        var server = source.getServer();
        for (String botName : allBots) {
            var bot = server.getPlayerList().getPlayer(botName);
            if (bot != null && bot.distanceTo(entity) <= radius) {
                BotFaction.addMember(faction, botName);
                count++;
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Added " + finalCount + " bots to faction '" + faction + "'"), true);
        return count;
    }

    private static int factionAddAll(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        var allBots = BotManager.botNames();
        int count = 0;
        for (String botName : allBots) {
            BotFaction.addMember(faction, botName);
            count++;
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Added " + finalCount + " bots to faction '" + faction + "'"), true);
        return count;
    }

    private static int factionGive(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String itemCommand = StringArgumentType.getString(ctx, "item");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        var members = BotFaction.getMembers(faction);
        var server = source.getServer();
        int count = 0;
        for (String memberName : members) {
            if (scriptify.bots.bot.BotActions.execute(server, "give " + memberName + " " + itemCommand)) {
                count++;
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Gave items to " + finalCount + " members of faction '" + faction + "'"), true);
        return count;
    }

    private static int factionAttack(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String target = BotSelector.playerNames(ctx, "target").get(0);
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        var members = BotFaction.getMembers(faction);
        int count = 0;
        for (String memberName : members) {
            if (BotManager.isBot(memberName)) {
                BotCombat.setTarget(BotManager.canonical(memberName), target);
                count++;
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Faction '" + faction + "' (" + finalCount + " bots) attacking " + target + "!"), true);
        return count;
    }

    private static int factionStartPath(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String path = StringArgumentType.getString(ctx, "path");
        var members = BotFaction.getMembers(faction);
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found or has no members"));
            return 0;
        }
        var botPath = BotPath.getPath(path);
        if (botPath == null) {
            source.sendFailure(Component.literal("Path '" + path + "' not found"));
            return 0;
        }
        int started = 0;
        for (String member : members) {
            if (BotManager.isBot(member)) {
                if (BotPath.startFollowing(member, path)) {
                    started++;
                }
            }
        }
        if (started > 0) {
            int finalStarted = started;
            source.sendSuccess(() -> Component.literal("Started path '" + path + "' for " + finalStarted + " bots in faction '" + faction + "'"), true);
            return started;
        } else {
            source.sendFailure(Component.literal("No bots in faction '" + faction + "'"));
            return 0;
        }
    }

    private static int factionStopPath(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        var members = BotFaction.getMembers(faction);
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found or has no members"));
            return 0;
        }
        int stopped = 0;
        for (String member : members) {
            if (BotManager.isBot(member)) {
                if (BotPath.stopFollowing(member)) {
                    stopped++;
                }
            }
        }
        if (stopped > 0) {
            int finalStopped = stopped;
            source.sendSuccess(() -> Component.literal("Stopped path for " + finalStopped + " bots in faction '" + faction + "'"), true);
            return stopped;
        } else {
            source.sendFailure(Component.literal("No bots in faction '" + faction + "' were following a path"));
            return 0;
        }
    }

    private static int factionGiveKit(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        String kitname = StringArgumentType.getString(ctx, "kitname");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        if (!BotKits.kitExists(kitname)) {
            source.sendFailure(Component.literal("Kit '" + kitname + "' not found!"));
            return 0;
        }
        var members = BotFaction.getMembers(faction);
        if (members == null || members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' has no members!"));
            return 0;
        }
        int count = 0;
        for (String memberName : members) {
            if (BotManager.isBot(memberName)) {
                var bot = BotManager.getBot(source.getServer(), memberName);
                if (bot != null && BotKits.giveKit(kitname, bot)) {
                    count++;
                }
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("Gave kit '" + kitname + "' to " + finalCount + " bots in faction '" + faction + "'"), true);
        return 1;
    }

    // ========== KIT STICK ==========

    private static int kitStick(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String kitname = StringArgumentType.getString(ctx, "kitname");
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can be handed a stick."));
            return 0;
        }
        if (scriptify.bots.tool.BotTools.give(player,
            scriptify.bots.tool.BotTool.KIT, kitname) != null) {
            source.sendFailure(Component.literal("Kit '" + kitname + "' not found!"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "Hold it up for five seconds near a group to give them " + kitname + "."), false);
        return 1;
    }

    private static int kitEdit(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String kitname = StringArgumentType.getString(ctx, "kitname");
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("A kit is edited in a screen, so this needs a player."));
            return 0;
        }
        if (!BotKits.kitExists(kitname)) {
            source.sendFailure(Component.literal("Kit '" + kitname + "' not found!"));
            return 0;
        }
        scriptify.bots.ui.BotKitEditMenu.open(player, kitname, null);
        return 1;
    }

    // ========== PERFORMANCE ==========

    /**
     * What the bots are costing, in the terms that actually matter.
     *
     * <p>Deliberately reports tick time rather than memory. Every report of bots moving in slow
     * motion has come with a note that the heap was nearly empty, which is true and is not the
     * problem: a tick is fifty milliseconds of one thread, and going over it slows the world
     * whatever the other nine gigabytes are doing.</p>
     */
    private static int performance(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        source.sendSuccess(() -> Component.literal(
            scriptify.bots.ai.LoadGovernor.describe()), false);
        return 1;
    }

    // ========== GIVE-KIT-NEAR ==========

    private static int kitGiveNear(CommandContext<CommandSourceStack> ctx, double radius) {
        var source = ctx.getSource();
        String kitname = StringArgumentType.getString(ctx, "kitname");
        if (!BotKits.kitExists(kitname)) {
            source.sendFailure(Component.literal("Kit '" + kitname + "' not found!"));
            return 0;
        }
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player!"));
            return 0;
        }
        var server = source.getServer();
        int count = 0;
        for (String botName : BotManager.botNames()) {
            ServerPlayer bot = server.getPlayerList().getPlayer(botName);
            if (bot != null && bot.distanceTo(player) <= radius) {
                if (BotKits.giveKit(kitname, bot)) {
                    count++;
                }
            }
        }
        if (count > 0) {
            int finalCount = count;
            source.sendSuccess(() -> Component.literal("Gave kit '" + kitname + "' to " + finalCount + " bots within " + radius + " blocks"), true);
            return count;
        } else {
            source.sendFailure(Component.literal("No bots found within " + radius + " blocks"));
            return 0;
        }
    }

    // ========== GIVE-KIT-NEAR-RANDOM ==========

    private static int kitGiveNearRandom(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        String kitsArg = StringArgumentType.getString(ctx, "kits");
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player!"));
            return 0;
        }
        String[] parts = kitsArg.split(" ");
        if (parts.length < 2 || parts.length % 2 != 0) {
            source.sendFailure(Component.literal("Usage: /bots kit give-kit-near-random <radius> <kit> <weight>% [<kit> <weight>% ...]"));
            return 0;
        }
        java.util.LinkedHashMap<String, Integer> weights = new java.util.LinkedHashMap<>();
        for (int i = 0; i < parts.length; i += 2) {
            String kitName = parts[i];
            String weightStr = parts[i + 1];
            if (!weightStr.endsWith("%")) {
                source.sendFailure(Component.literal("Invalid weight '" + weightStr + "' for kit '" + kitName + "'. Use format: <weight>%"));
                return 0;
            }
            if (!BotKits.kitExists(kitName)) {
                source.sendFailure(Component.literal("Kit '" + kitName + "' not found!"));
                return 0;
            }
            try {
                int w = Integer.parseInt(weightStr.substring(0, weightStr.length() - 1));
                if (w <= 0) {
                    source.sendFailure(Component.literal("Weight must be positive for kit '" + kitName + "'"));
                    return 0;
                }
                weights.put(kitName, w);
            } catch (NumberFormatException e) {
                source.sendFailure(Component.literal("Invalid weight '" + weightStr + "' for kit '" + kitName + "'"));
                return 0;
            }
        }
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        var server = source.getServer();
        int count = 0;
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (String botName : BotManager.botNames()) {
            ServerPlayer bot = server.getPlayerList().getPlayer(botName);
            if (bot == null || bot.distanceTo(player) > radius) continue;
            int roll = rng.nextInt(totalWeight);
            int cumulative = 0;
            String selected = null;
            for (var entry : weights.entrySet()) {
                cumulative += entry.getValue();
                if (roll < cumulative) {
                    selected = entry.getKey();
                    break;
                }
            }
            if (selected != null && BotKits.giveKit(selected, bot)) {
                count++;
            }
        }
        if (count > 0) {
            int finalCount = count;
            source.sendSuccess(() -> Component.literal("Gave random kits to " + finalCount + " bots within " + radius + " blocks"), true);
            return count;
        } else {
            source.sendFailure(Component.literal("No bots found within " + radius + " blocks"));
            return 0;
        }
    }

    // ========== FACTION GIVE-KIT-RANDOM ==========

    private static int factionGiveKitRandom(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        String kitsArg = StringArgumentType.getString(ctx, "kits");
        String[] parts = kitsArg.split(" ");
        if (parts.length < 2 || parts.length % 2 != 0) {
            source.sendFailure(Component.literal("Usage: /bots faction give-kit-random <faction> <kit> <weight>% [<kit> <weight>% ...]"));
            return 0;
        }
        java.util.LinkedHashMap<String, Integer> weights = new java.util.LinkedHashMap<>();
        for (int i = 0; i < parts.length; i += 2) {
            String kitName = parts[i];
            String weightStr = parts[i + 1];
            if (!weightStr.endsWith("%")) {
                source.sendFailure(Component.literal("Invalid weight '" + weightStr + "' for kit '" + kitName + "'. Use format: <weight>%"));
                return 0;
            }
            if (!BotKits.kitExists(kitName)) {
                source.sendFailure(Component.literal("Kit '" + kitName + "' not found!"));
                return 0;
            }
            try {
                int w = Integer.parseInt(weightStr.substring(0, weightStr.length() - 1));
                if (w <= 0) {
                    source.sendFailure(Component.literal("Weight must be positive for kit '" + kitName + "'"));
                    return 0;
                }
                weights.put(kitName, w);
            } catch (NumberFormatException e) {
                source.sendFailure(Component.literal("Invalid weight '" + weightStr + "' for kit '" + kitName + "'"));
                return 0;
            }
        }
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        var members = BotFaction.getMembers(faction);
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' has no members!"));
            return 0;
        }
        var server = source.getServer();
        int count = 0;
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (String memberName : members) {
            if (!BotManager.isBot(memberName)) continue;
            var bot = BotManager.getBot(server, memberName);
            if (bot == null) continue;
            int roll = rng.nextInt(totalWeight);
            int cumulative = 0;
            String selected = null;
            for (var entry : weights.entrySet()) {
                cumulative += entry.getValue();
                if (roll < cumulative) {
                    selected = entry.getKey();
                    break;
                }
            }
            if (selected != null && BotKits.giveKit(selected, bot)) {
                count++;
            }
        }
        if (count > 0) {
            int finalCount = count;
            source.sendSuccess(() -> Component.literal("Gave random kits to " + finalCount + " bots in faction '" + faction + "'"), true);
            return count;
        } else {
            source.sendFailure(Component.literal("No bots in faction '" + faction + "' received a kit"));
            return 0;
        }
    }

    // ========== FACTION TP ==========

    private static int factionTp(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        String faction = StringArgumentType.getString(ctx, "faction");
        if (!BotFaction.getAllFactions().contains(faction)) {
            source.sendFailure(Component.literal("Faction '" + faction + "' not found!"));
            return 0;
        }
        String location = StringArgumentType.getString(ctx, "location");
        String[] parts = location.split(" ");
        double tx, ty, tz;
        if (parts.length == 3) {
            var player = source.getPlayer();
            double ox = (player != null) ? player.getX() : 0;
            double oy = (player != null) ? player.getY() : 0;
            double oz = (player != null) ? player.getZ() : 0;
            try {
                tx = parseCoord(parts[0], ox);
                ty = parseCoord(parts[1], oy);
                tz = parseCoord(parts[2], oz);
            } catch (NumberFormatException e) {
                source.sendFailure(Component.literal("Invalid coordinates. Usage: /bots faction tp <faction> <x y z> or <playername>"));
                return 0;
            }
        } else {
            var server = source.getServer();
            var target = server.getPlayerList().getPlayer(location);
            if (target == null) {
                source.sendFailure(Component.literal("Player or bot '" + location + "' not found!"));
                return 0;
            }
            tx = target.getX();
            ty = target.getY();
            tz = target.getZ();
        }
        var members = new java.util.ArrayList<>(BotFaction.getMembers(faction));
        members.removeIf(m -> !BotManager.isBot(m));
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("Faction '" + faction + "' has no bots!"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Teleporting " + members.size() + " bots in faction '" + faction + "'..."), false);
        var server = source.getServer();
        boolean safe = BotSettings.get().isSafeSpawn();
        int[] count = {0};
        cancelPendingTeleports();
        long generation = TELEPORT_GENERATION.incrementAndGet();
        scheduleFactionTp(server, source, members, tx, ty, tz, safe, 0, count, generation);
        return 1;
    }

    private static double parseCoord(String s, double origin) {
        if (s.startsWith("~")) {
            double offset = s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0;
            return origin + offset;
        }
        return Double.parseDouble(s);
    }

    private static final java.util.concurrent.ScheduledExecutorService TP_SCHEDULER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FactionTP");
            t.setDaemon(true);
            return t;
        });
    /** Delayed batches that have not reached the server thread yet. */
    private static final java.util.Set<java.util.concurrent.ScheduledFuture<?>> PENDING_TELEPORTS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Invalidates queued work when a new batch starts or the current server stops. */
    private static final java.util.concurrent.atomic.AtomicLong TELEPORT_GENERATION =
        new java.util.concurrent.atomic.AtomicLong();

    private static void scheduleFactionTp(MinecraftServer server, CommandSourceStack source,
                                          java.util.List<String> members, double tx, double ty, double tz,
                                          boolean safe, int index, int[] count, long generation) {
        if (generation != TELEPORT_GENERATION.get()) {
            return;
        }
        if (index >= members.size()) {
            source.sendSuccess(() -> Component.literal("Teleported " + count[0] + " bots in faction"), true);
            return;
        }
        int end = Math.min(index + 5, members.size());
        PENDING_TELEPORTS.removeIf(java.util.concurrent.Future::isDone);
        var future = TP_SCHEDULER.schedule(() -> {
            if (generation != TELEPORT_GENERATION.get()) {
                return;
            }
            server.execute(() -> {
                if (generation != TELEPORT_GENERATION.get()) {
                    return;
                }
                var rng = java.util.concurrent.ThreadLocalRandom.current();
                for (int i = index; i < end; i++) {
                    String name = members.get(i);
                    double x = tx, z = tz;
                    if (safe) {
                        x += (rng.nextDouble() * 0.4 + 0.1) * (rng.nextBoolean() ? 1 : -1);
                        z += (rng.nextDouble() * 0.4 + 0.1) * (rng.nextBoolean() ? 1 : -1);
                    }
                    ServerPlayer bot = BotManager.getBot(server, name);
                    if (bot != null) {
                        bot.teleportTo((net.minecraft.server.level.ServerLevel) bot.level(), x, ty, tz,
                            java.util.Set.of(), 0.0f, 0.0f, false);
                        count[0]++;
                    }
                }
                scheduleFactionTp(server, source, members, tx, ty, tz, safe, end, count, generation);
            });
        }, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        PENDING_TELEPORTS.add(future);
    }

    /** Cancels work that could otherwise target a server after it has begun stopping. */
    public static void cancelPendingTeleports() {
        TELEPORT_GENERATION.incrementAndGet();
        for (var future : PENDING_TELEPORTS) {
            future.cancel(false);
        }
        PENDING_TELEPORTS.clear();
    }

    // ========== GUI / INSPECTION ==========

    private static int openGui(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Only players can open the SBots panel."));
            return 0;
        }
        scriptify.bots.ui.MainMenu.open(player);
        return 1;
    }

    private static int botProfile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        List<String> bots = BotSelector.bots(ctx, "botname");
        for (String name : bots) {
            var target = BotCombat.getTarget(name);

            source.sendSuccess(() -> Component.literal("=== " + name + " ==="), false);
            source.sendSuccess(() -> Component.literal("Target: "
                + (target != null ? target.getName().getString() : "none")), false);
            source.sendSuccess(() -> Component.literal("Doing: "
                + scriptify.bots.ai.Maneuver.intentOf(name).name().toLowerCase(java.util.Locale.ROOT)), false);
            var held = BotCombat.getState(name);
            source.sendSuccess(() -> Component.literal("Shield: "
                + (held.isUsingShield ? "raised" : "down")), false);
            if (scriptify.bots.ai.Engagement.inBrawl(name)) {
                source.sendSuccess(() -> Component.literal("In a brawl as "
                    + scriptify.bots.ai.Engagement.roleOf(name).name().toLowerCase(java.util.Locale.ROOT)
                    + " (" + scriptify.bots.ai.Engagement.allyCount(name) + " allies, "
                    + scriptify.bots.ai.Engagement.enemyCount(name) + " enemies)"), false);
                var focus = scriptify.bots.ai.Engagement.focusFor(name);
                source.sendSuccess(() -> Component.literal("Group focus: "
                    + (focus != null ? focus.getName().getString() : "none")), false);
            }
        }
        return bots.size();
    }

}


