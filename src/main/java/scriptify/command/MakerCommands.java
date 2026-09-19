package scriptify.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import scriptify.core.ScriptifyBootstrap;
import scriptify.feature.ItemMakerService;
import scriptify.feature.Sfx;
import scriptify.feature.TeamService;
import scriptify.feature.VillagerMakerService;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;
import scriptify.ui.ItemMenu;
import scriptify.ui.AccessMenu;
import scriptify.ui.RanksMenu;
import scriptify.ui.VillagerMenu;
import scriptify.ui.Msg;

/**
 * The item maker, villager maker, team and permission commands.
 *
 * <p>Kept apart from {@link ScriptifyCommands} purely for size — these four are the "authoring" tools
 * and share no state with the scene toggles.
 */
public final class MakerCommands {
	/** How far to look for the villager a command should act on. */
	private static final double VILLAGER_REACH = 6.0D;

	private MakerCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		registerItem(dispatcher);
		registerVillager(dispatcher);
		registerTeams(dispatcher);
		registerPermissions(dispatcher);
		registerRanks(dispatcher);
	}

	// ------------------------------------------------------------------ item maker

	private static final SuggestionProvider<CommandSourceStack> ENCHANTMENTS = (context, builder) ->
		SharedSuggestionProvider.suggest(ItemMakerService.enchantmentIds(context.getSource().getServer()), builder);

	private static void registerItem(CommandDispatcher<CommandSourceStack> dispatcher) {
		// /itemmaker is the name people reach for, and it goes straight to the editor. /item keeps the
		// subcommands for scripting, and opens the same editor when given nothing to do.
		dispatcher.register(literal("itemmaker")
			.requires(source -> has(source, PermissionService.ITEMS))
			.executes(context -> openEditor(context.getSource())));

		dispatcher.register(literal("item")
			.requires(source -> has(source, PermissionService.ITEMS))
			.executes(context -> openEditor(context.getSource()))
			.then(literal("name")
				.then(argument("text", StringArgumentType.greedyString())
					.executes(context -> editHeld(context.getSource(), stack -> {
						ItemMakerService.setName(stack, StringArgumentType.getString(context, "text"));
						return "Renamed the held item.";
					}))))
			.then(literal("clearname")
				.executes(context -> editHeld(context.getSource(), stack -> {
					ItemMakerService.clearName(stack);
					return "Cleared the item's name.";
				})))
			.then(literal("lore")
				.then(literal("add")
					.then(argument("text", StringArgumentType.greedyString())
						.executes(context -> editHeld(context.getSource(), stack -> {
							ItemMakerService.addLoreLine(stack, StringArgumentType.getString(context, "text"));
							return "Added a lore line.";
						}))))
				.then(literal("undo")
					.executes(context -> editHeld(context.getSource(), stack ->
						ItemMakerService.removeLastLoreLine(stack)
							? "Removed the last lore line."
							: "There is no lore to remove.")))
				.then(literal("clear")
					.executes(context -> editHeld(context.getSource(), stack -> {
						ItemMakerService.clearLore(stack);
						return "Cleared the item's lore.";
					}))))
			.then(literal("enchant")
				.then(argument("enchantment", StringArgumentType.string()).suggests(ENCHANTMENTS)
					.then(argument("level", IntegerArgumentType.integer(0, 255))
						.executes(context -> editHeld(context.getSource(), stack -> {
							String id = StringArgumentType.getString(context, "enchantment");
							int level = IntegerArgumentType.getInteger(context, "level");
							ItemMakerService.enchant(context.getSource().getServer(), stack, id, level);
							return level == 0 ? "Removed " + id + "." : "Applied " + id + " " + level + ".";
						})))))
			.then(literal("clearenchants")
				.executes(context -> editHeld(context.getSource(), stack -> {
					ItemMakerService.clearEnchantments(stack);
					return "Cleared all enchantments.";
				})))
			.then(literal("unbreakable")
				.then(argument("enabled", BoolArgumentType.bool())
					.executes(context -> editHeld(context.getSource(), stack -> {
						boolean enabled = BoolArgumentType.getBool(context, "enabled");
						ItemMakerService.setUnbreakable(stack, enabled);
						return "Unbreakable set to " + enabled + ".";
					}))))
			.then(literal("glint")
				.then(argument("enabled", BoolArgumentType.bool())
					.executes(context -> editHeld(context.getSource(), stack -> {
						boolean enabled = BoolArgumentType.getBool(context, "enabled");
						ItemMakerService.setGlint(stack, enabled);
						return "Glint set to " + enabled + ".";
					}))))
			.then(literal("count")
				.then(argument("count", IntegerArgumentType.integer(1, 99))
					.executes(context -> editHeld(context.getSource(), stack -> {
						ItemMakerService.setCount(stack, IntegerArgumentType.getInteger(context, "count"));
						return "Stack size set to " + stack.getCount() + ".";
					})))));
	}

	/** Opens the villager editor on whatever the caller is looking at. */
	private static int openVillagerMenu(CommandSourceStack source) {
		try {
			VillagerMenu.open(ScriptifyBootstrap.context(), source.getPlayerOrException());
			return 1;
		} catch (CommandSyntaxException exception) {
			source.sendFailure(Msg.failure("Only a player can open the villager maker."));
			return 0;
		}
	}

	/** Opens the item editor on whatever the caller is holding. */
	private static int openEditor(CommandSourceStack source) {
		try {
			ItemMenu.open(ScriptifyBootstrap.context(), source.getPlayerOrException());
			return 1;
		} catch (CommandSyntaxException exception) {
			source.sendFailure(Msg.failure("Only a player can open the item maker."));
			return 0;
		}
	}

	/** Runs an edit against whatever the caller is holding, or explains why it cannot. */
	private static int editHeld(CommandSourceStack source, java.util.function.Function<ItemStack, String> edit) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			ItemStack held = player.getInventory().getSelectedItem();
			if (held.isEmpty()) {
				source.sendFailure(Msg.failure("Hold the item you want to edit."));
				return 0;
			}
			String message = edit.apply(held);
			player.getInventory().setChanged();
			Sfx.repair(player);
			source.sendSuccess(() -> Msg.line(message), false);
			return 1;
		} catch (IllegalArgumentException exception) {
			source.sendFailure(Msg.failure(exception.getMessage()));
			return 0;
		} catch (Exception exception) {
			source.sendFailure(Msg.failure("Only players can edit a held item."));
			return 0;
		}
	}

	// ------------------------------------------------------------------ villager maker

	private static final SuggestionProvider<CommandSourceStack> PROFESSIONS = (context, builder) ->
		SharedSuggestionProvider.suggest(VillagerMakerService.professionIds(context.getSource().getServer()), builder);

	private static void registerVillager(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("villagermaker")
			.requires(source -> has(source, PermissionService.VILLAGERS))
			.executes(context -> openVillagerMenu(context.getSource())));

		dispatcher.register(literal("villager")
			.requires(source -> has(source, PermissionService.VILLAGERS))
			.executes(context -> openVillagerMenu(context.getSource()))
			.then(literal("create")
				.then(argument("name", StringArgumentType.string())
					.executes(context -> createVillager(context.getSource(),
						StringArgumentType.getString(context, "name"), "farmer", 1))
					.then(argument("profession", StringArgumentType.word()).suggests(PROFESSIONS)
						.executes(context -> createVillager(context.getSource(),
							StringArgumentType.getString(context, "name"),
							StringArgumentType.getString(context, "profession"), 1))
						.then(argument("level", IntegerArgumentType.integer(1, 5))
							.executes(context -> createVillager(context.getSource(),
								StringArgumentType.getString(context, "name"),
								StringArgumentType.getString(context, "profession"),
								IntegerArgumentType.getInteger(context, "level")))))))
			// Trades are built from what you are carrying: hotbar slot 1 is the cost, slot 2 the optional
			// second cost, and the held item is what the player receives.
			.then(literal("addtrade")
				.executes(context -> editVillager(context.getSource(), (player, villager) -> {
					ItemStack first = player.getInventory().getItem(0);
					ItemStack second = player.getInventory().getItem(1);
					ItemStack result = player.getInventory().getSelectedItem();
					VillagerMakerService.addTrade(villager, first, second, result);
					return "Added a trade: " + describe(first)
						+ (second.isEmpty() ? "" : " + " + describe(second))
						+ " → " + describe(result);
				})))
			.then(literal("removetrade")
				.then(argument("index", IntegerArgumentType.integer(1))
					.executes(context -> editVillager(context.getSource(), (player, villager) ->
						VillagerMakerService.removeTrade(villager, IntegerArgumentType.getInteger(context, "index"))
							? "Removed that trade."
							: "There is no trade with that number."))))
			.then(literal("cleartrades")
				.executes(context -> editVillager(context.getSource(), (player, villager) -> {
					VillagerMakerService.clearTrades(villager);
					return "Cleared every trade.";
				})))
			.then(literal("list")
				.executes(context -> editVillager(context.getSource(), (player, villager) -> {
					var trades = VillagerMakerService.trades(villager);
					if (trades.isEmpty()) {
						return "This villager has no trades.";
					}
					StringBuilder builder = new StringBuilder("Trades:");
					for (int index = 0; index < trades.size(); index++) {
						builder.append("\n  ").append(index + 1).append(". ")
							.append(describe(trades.get(index).getCostA()))
							.append(" → ").append(describe(trades.get(index).getResult()));
					}
					return builder.toString();
				})))
			.then(literal("profession")
				.then(argument("profession", StringArgumentType.word()).suggests(PROFESSIONS)
					.then(argument("level", IntegerArgumentType.integer(1, 5))
						.executes(context -> editVillager(context.getSource(), (player, villager) -> {
							String profession = StringArgumentType.getString(context, "profession");
							VillagerMakerService.applyProfession(context.getSource().getServer(), villager,
								profession, IntegerArgumentType.getInteger(context, "level"));
							return "Set profession to " + profession + ".";
						})))))
			.then(literal("remove")
				.executes(context -> editVillager(context.getSource(), (player, villager) -> {
					villager.discard();
					return "Removed that villager.";
				}))));
	}

	private static int createVillager(CommandSourceStack source, String name, String profession, int level) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			VillagerMakerService.spawn(player, name, profession, level);
			Sfx.success(player);
			source.sendSuccess(() -> Msg.line("Created villager '" + name + "' (" + profession + ")."), true);
			return 1;
		} catch (IllegalArgumentException | IllegalStateException exception) {
			source.sendFailure(Msg.failure(exception.getMessage()));
			return 0;
		} catch (Exception exception) {
			source.sendFailure(Msg.failure("Only players can create villagers."));
			return 0;
		}
	}

	@FunctionalInterface
	private interface VillagerEdit {
		String apply(ServerPlayer player, Villager villager);
	}

	/** Every villager sub-command acts on the one you are looking at. */
	private static int editVillager(CommandSourceStack source, VillagerEdit edit) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			var villager = VillagerMakerService.lookingAt(player, VILLAGER_REACH);
			if (villager.isEmpty()) {
				source.sendFailure(Msg.failure("Look at a villager first."));
				return 0;
			}
			String message = edit.apply(player, villager.get());
			Sfx.success(player);
			source.sendSuccess(() -> Msg.line(message), false);
			return 1;
		} catch (IllegalArgumentException exception) {
			source.sendFailure(Msg.failure(exception.getMessage()));
			return 0;
		} catch (Exception exception) {
			source.sendFailure(Msg.failure("Only players can edit villagers."));
			return 0;
		}
	}

	private static String describe(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getCount() + "x " + stack.getHoverName().getString();
	}

	private static String describe(net.minecraft.world.item.trading.ItemCost cost) {
		return cost.count() + "x " + cost.itemStack().getHoverName().getString();
	}

	// ------------------------------------------------------------------ teams

	private static final SuggestionProvider<CommandSourceStack> TEAMS = (context, builder) ->
		SharedSuggestionProvider.suggest(ScriptifyRuntime.teams().names(), builder);

	private static void registerTeams(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("steams")
			.requires(source -> has(source, PermissionService.TEAMS))
			.executes(context -> {
				context.getSource().sendSuccess(() -> Msg.line(
					"Teams: " + ScriptifyRuntime.teams().names()), false);
				return 1;
			})
			.then(literal("create")
				.then(argument("name", StringArgumentType.word())
					.executes(context -> teamAction(context.getSource(), team ->
						ScriptifyRuntime.teams().create(team)
							? "Created team '" + team + "'."
							: null, StringArgumentType.getString(context, "name"),
						"A team called that already exists."))))
			.then(literal("delete")
				.then(argument("name", StringArgumentType.word()).suggests(TEAMS)
					.executes(context -> teamAction(context.getSource(), team ->
						ScriptifyRuntime.teams().delete(team)
							? "Deleted team '" + team + "'."
							: null, StringArgumentType.getString(context, "name"),
						"No team by that name."))))
			.then(literal("add")
				.then(argument("name", StringArgumentType.word()).suggests(TEAMS)
					.then(argument("targets", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> {
							String team = StringArgumentType.getString(context, "name");
							var targets = Targets.many(context, "targets");
							int added = 0;
							for (ServerPlayer target : targets) {
								if (ScriptifyRuntime.teams().add(team, target)) {
									added++;
								}
							}
							if (added == 0) {
								context.getSource().sendFailure(Msg.failure("No team called '" + team + "'."));
								return 0;
							}
							int total = added;
							context.getSource().sendSuccess(() -> Msg.line(
								"Added " + total + " player(s) to '" + team + "'."), true);
							return total;
						}))))
			.then(literal("remove")
				.then(argument("targets", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> {
						int removed = 0;
						for (ServerPlayer target : Targets.many(context, "targets")) {
							if (ScriptifyRuntime.teams().remove(target)) {
								removed++;
							}
						}
						int total = removed;
						context.getSource().sendSuccess(() -> Msg.line(
							"Removed " + total + " player(s) from their team."), true);
						return total;
					})))
			.then(literal("colour")
				.then(argument("name", StringArgumentType.word()).suggests(TEAMS)
					.then(argument("colour", StringArgumentType.word())
						.executes(context -> {
							String team = StringArgumentType.getString(context, "name");
							String rawColour = StringArgumentType.getString(context, "colour");
							ChatFormatting colour = scriptify.ui.Colours.byName(rawColour);
							if (colour == null || !scriptify.ui.Colours.isColour(colour)) {
								context.getSource().sendFailure(Msg.failure(
									"'" + rawColour + "' is not a colour. Try red, aqua, gold…"));
								return 0;
							}
							if (!ScriptifyRuntime.teams().setColour(team, colour)) {
								context.getSource().sendFailure(Msg.failure("No team called '" + team + "'."));
								return 0;
							}
							context.getSource().sendSuccess(() -> Msg.line(
								"Team '" + team + "' is now " + rawColour + "."), true);
							return 1;
						}))))
			.then(literal("prefix")
				.then(argument("name", StringArgumentType.word()).suggests(TEAMS)
					.then(argument("prefix", StringArgumentType.greedyString())
						.executes(context -> teamAction(context.getSource(), team ->
							ScriptifyRuntime.teams().setPrefix(team,
								StringArgumentType.getString(context, "prefix"))
								? "Set the prefix for '" + team + "'."
								: null, StringArgumentType.getString(context, "name"),
							"No team by that name.")))))
			.then(literal("friendlyfire")
				.then(argument("name", StringArgumentType.word()).suggests(TEAMS)
					.then(argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean enabled = BoolArgumentType.getBool(context, "enabled");
							return teamAction(context.getSource(), team ->
								ScriptifyRuntime.teams().setFriendlyFire(team, enabled)
									? "Friendly fire for '" + team + "' set to " + enabled + "."
									: null, StringArgumentType.getString(context, "name"),
								"No team by that name.");
						}))))
			.then(literal("collision")
				.then(argument("name", StringArgumentType.word()).suggests(TEAMS)
					.then(argument("enabled", BoolArgumentType.bool())
						.executes(context -> {
							boolean enabled = BoolArgumentType.getBool(context, "enabled");
							return teamAction(context.getSource(), team ->
								ScriptifyRuntime.teams().setCollision(team, enabled)
									? "Collision for '" + team + "' set to " + enabled + "."
									: null, StringArgumentType.getString(context, "name"),
								"No team by that name.");
						}))))
			.then(literal("nametags")
				.then(argument("name", StringArgumentType.word()).suggests(TEAMS)
					.then(argument("visible", BoolArgumentType.bool())
						.executes(context -> {
							boolean visible = BoolArgumentType.getBool(context, "visible");
							return teamAction(context.getSource(), team ->
								ScriptifyRuntime.teams().setNameTags(team, visible)
									? "Nametags for '" + team + "' set to " + visible + "."
									: null, StringArgumentType.getString(context, "name"),
								"No team by that name.");
						}))))
			.then(literal("members")
				.then(argument("name", StringArgumentType.word()).suggests(TEAMS)
					.executes(context -> {
						String team = StringArgumentType.getString(context, "name");
						if (!ScriptifyRuntime.teams().exists(team)) {
							context.getSource().sendFailure(Msg.failure("No team called '" + team + "'."));
							return 0;
						}
						context.getSource().sendSuccess(() -> Msg.line(
							team + ": " + ScriptifyRuntime.teams().members(team)), false);
						return 1;
					}))));
	}

	/** Shared shape for team edits: run it, and turn a {@code null} result into the failure message. */
	private static int teamAction(CommandSourceStack source, java.util.function.Function<String, String> action,
			String team, String failure) {
		try {
			String message = action.apply(team);
			if (message == null) {
				source.sendFailure(Msg.failure(failure));
				return 0;
			}
			source.sendSuccess(() -> Msg.line(message), true);
			return 1;
		} catch (IllegalArgumentException exception) {
			source.sendFailure(Msg.failure(exception.getMessage()));
			return 0;
		}
	}

	// ------------------------------------------------------------------ permissions

	private static final SuggestionProvider<CommandSourceStack> NODES = (context, builder) ->
		SharedSuggestionProvider.suggest(PermissionService.ALL_NODES, builder);

	private static final SuggestionProvider<CommandSourceStack> RANKS = (context, builder) -> {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (var rank : ScriptifyBootstrap.context().permissions().ranks().all()) {
			names.add(rank.name());
		}
		return SharedSuggestionProvider.suggest(names, builder);
	};

	private static void registerRanks(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("scriptifyranks")
			.requires(source -> has(source, PermissionService.ADMIN))
			.executes(context -> {
				try {
					AccessMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException());
					return 1;
				} catch (CommandSyntaxException exception) {
					return listRanks(context.getSource());
				}
			})
			.then(literal("list").executes(context -> listRanks(context.getSource())))
			// Answering "why can this person not use /tp" without anybody having to reason about the
			// interaction between a level, a filter and op status in their head.
			.then(literal("check")
				.then(argument("command", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						scriptify.permission.RankService.gateableCommands(
							context.getSource().getServer()), builder))
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> {
							String command = StringArgumentType.getString(context, "command");
							int reported = 0;
							for (ServerPlayer target : Targets.many(context, "target")) {
								checkAccess(context.getSource(), target, command);
								reported++;
							}
							return reported;
						}))))
			.then(literal("prune")
				.executes(context -> {
					var permissions = ScriptifyBootstrap.context().permissions();
					int removed = permissions.ranks().prune(
						id -> !permissions.granted(id).isEmpty());
					context.getSource().sendSuccess(() -> Msg.line(removed == 0
						? "Nothing to prune."
						: "Dropped " + removed + " empty row(s) - bots from older builds, mostly."), true);
					return removed;
				}))
			.then(literal("create")
				.then(argument("name", StringArgumentType.word())
					.executes(context -> {
						String name = StringArgumentType.getString(context, "name");
						boolean made = ScriptifyBootstrap.context().permissions().ranks().create(name);
						if (made) {
							context.getSource().sendSuccess(() -> Msg.line("Created rank '" + name + "'."), true);
						} else {
							context.getSource().sendFailure(Msg.failure("That name is taken or unusable."));
						}
						return made ? 1 : 0;
					})))
			.then(literal("delete")
				.then(argument("name", StringArgumentType.word()).suggests(RANKS)
					.executes(context -> {
						String name = StringArgumentType.getString(context, "name");
						boolean gone = ScriptifyBootstrap.context().permissions().ranks().delete(name);
						if (gone) {
							context.getSource().sendSuccess(() -> Msg.line(
								"Deleted rank '" + name + "'. Anyone holding it is back to default."), true);
						} else {
							context.getSource().sendFailure(Msg.failure(
								"No such rank, or you tried to delete the default."));
						}
						return gone ? 1 : 0;
					})))
			.then(literal("set")
				.then(argument("name", StringArgumentType.word()).suggests(RANKS)
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> {
							String name = StringArgumentType.getString(context, "name");
							int changed = 0;
							for (ServerPlayer target : Targets.many(context, "target")) {
								if (ScriptifyBootstrap.context().permissions().ranks()
										.assign(target.getUUID(), name)) {
									changed++;
								}
							}
							int total = changed;
							if (total == 0) {
								// Distinguishing the two, because "no such rank" for a correctly spelled
								// rank sends people looking for a problem that is not there.
								boolean rankExists = ScriptifyBootstrap.context().permissions().ranks()
									.byName(name).isPresent();
								context.getSource().sendFailure(Msg.failure(rankExists
									? "Ranks are for players, not bots."
									: "No such rank."));
								return 0;
							}
							context.getSource().sendSuccess(() -> Msg.line(
								"Set " + total + " player(s) to '" + name + "'."), true);
							return total;
						}))))
			// The vanilla-command half of a rank was previously only reachable through the menu, which
			// made it impossible to script and impossible to check without opening a screen.
			.then(literal("level")
				.then(argument("name", StringArgumentType.word()).suggests(RANKS)
					.then(argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 4))
						.executes(context -> {
							String name = StringArgumentType.getString(context, "name");
							int level = com.mojang.brigadier.arguments.IntegerArgumentType
								.getInteger(context, "level");
							var ranks = ScriptifyBootstrap.context().permissions().ranks();
							if (ranks.byName(name).isEmpty()) {
								context.getSource().sendFailure(Msg.failure("No such rank."));
								return 0;
							}
							ranks.setCommandLevel(name, level);
							context.getSource().sendSuccess(() -> Msg.line(
								"'" + name + "' now grants command level " + level + "."), true);
							return 1;
						}))))
			.then(literal("filter")
				.then(argument("name", StringArgumentType.word()).suggests(RANKS)
					.then(argument("mode", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
							new String[] {"all", "only", "except"}, builder))
						.executes(context -> {
							String name = StringArgumentType.getString(context, "name");
							String mode = StringArgumentType.getString(context, "mode");
							var ranks = ScriptifyBootstrap.context().permissions().ranks();
							if (ranks.byName(name).isEmpty()) {
								context.getSource().sendFailure(Msg.failure("No such rank."));
								return 0;
							}
							ranks.setCommandMode(name,
								scriptify.permission.RankService.CommandMode.parse(mode));
							context.getSource().sendSuccess(() -> Msg.line(
								"'" + name + "' command filter set to " + mode + "."), true);
							return 1;
						}))))
			.then(literal("command")
				.then(argument("name", StringArgumentType.word()).suggests(RANKS)
					.then(argument("command", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
							scriptify.permission.RankService.gateableCommands(
								context.getSource().getServer()), builder))
						.then(argument("listed", BoolArgumentType.bool())
							.executes(context -> {
								String name = StringArgumentType.getString(context, "name");
								String command = StringArgumentType.getString(context, "command");
								boolean listed = BoolArgumentType.getBool(context, "listed");
								var ranks = ScriptifyBootstrap.context().permissions().ranks();
								if (ranks.byName(name).isEmpty()) {
									context.getSource().sendFailure(Msg.failure("No such rank."));
									return 0;
								}
								ranks.setCommandListed(name, command, listed);
								context.getSource().sendSuccess(() -> Msg.line("/" + command
									+ (listed ? " listed for '" : " unlisted for '") + name + "'."), true);
								return 1;
							})))))
			.then(literal("node")
				.then(argument("name", StringArgumentType.word()).suggests(RANKS)
					.then(argument("node", StringArgumentType.string()).suggests(NODES)
						.then(argument("granted", BoolArgumentType.bool())
							.executes(context -> {
								String name = StringArgumentType.getString(context, "name");
								String node = StringArgumentType.getString(context, "node");
								boolean granted = BoolArgumentType.getBool(context, "granted");
								ScriptifyBootstrap.context().permissions().ranks()
									.setNode(name, node, granted);
								context.getSource().sendSuccess(() -> Msg.line(
									node + (granted ? " added to '" : " removed from '") + name + "'."), true);
								return 1;
							}))))));
	}

	/** Lists every rank with its size, for anyone not in a position to open a menu. */
	/** Explains, in order, everything that decides whether this player may run this command. */
	private static void checkAccess(CommandSourceStack source, ServerPlayer target, String command) {
		var permissions = ScriptifyBootstrap.context().permissions();
		var rank = permissions.ranks().of(target.getUUID());
		var grant = scriptify.permission.RankRegistry.of(target.getUUID());
		boolean operator = source.getServer().getPlayerList().isOp(target.nameAndId());
		Integer needs = scriptify.permission.RankRegistry.levelOf(command);
		int effective = scriptify.permission.RankRegistry.effectiveLevelOf(grant);

		source.sendSuccess(() -> Msg.header(target.getGameProfile().name() + " and /" + command, Msg.BRAND), false);
		source.sendSuccess(() -> Msg.hint("  rank: " + rank.label()
			+ (operator ? " (operator - nothing below applies)" : "")), false);
		if (needs == null) {
			source.sendSuccess(() -> Msg.hint(
				"  /" + command + " is not a command a rank has any say over."), false);
			source.sendSuccess(() -> Msg.hint(
				"  Either everyone can already run it, or it is one of Scriptify's own."), false);
			return;
		}
		source.sendSuccess(() -> Msg.hint("  /" + command + " needs level " + needs), false);
		source.sendSuccess(() -> Msg.hint("  rank grants level " + effective
			+ (effective == grant.commandLevel() ? "" : " (" + grant.commandLevel()
				+ " set, raised by the listed commands)")), false);
		source.sendSuccess(() -> Msg.hint("  filter: "
			+ grant.mode().name().toLowerCase(java.util.Locale.ROOT)
			+ (grant.mode() == scriptify.permission.RankService.CommandMode.ALL
				? "" : " " + grant.commands())), false);

		// Ground truth: ask vanilla the same question it asks itself, through the player's own
		// command source. Everything above is Scriptify reporting its intent; this is the answer that
		// actually governs whether the command runs, and the two disagreeing is worth seeing.
		var node = source.getServer().getCommands().getDispatcher().getRoot().getChild(command);
		boolean vanillaAllows = node != null && node.canUse(target.createCommandSourceStack());
		source.sendSuccess(() -> Msg.hint("  vanilla agrees: " + (vanillaAllows ? "yes" : "no")), false);

		boolean levelOk = operator || effective >= needs;
		boolean filterOk = operator || grant.mayRun(command);
		if (levelOk && filterOk) {
			source.sendSuccess(() -> Msg.success("  allowed"), false);
			return;
		}
		if (!levelOk) {
			source.sendSuccess(() -> Msg.failure("  blocked: the rank grants level " + effective
				+ ", and /" + command + " needs " + needs + "."), false);
			source.sendSuccess(() -> Msg.hint(
				"  Fix: /scriptifyranks level " + rank.name() + " " + needs), false);
			return;
		}
		source.sendSuccess(() -> Msg.failure("  blocked by the command filter."), false);
		source.sendSuccess(() -> Msg.hint("  Fix: /scriptifyranks command " + rank.name() + " "
			+ command + (grant.mode() == scriptify.permission.RankService.CommandMode.ONLY
				? " true" : " false")), false);
	}

	private static int listRanks(CommandSourceStack source) {
		var ranks = ScriptifyBootstrap.context().permissions().ranks().all();
		source.sendSuccess(() -> Msg.line("Ranks (" + ranks.size() + "):"), false);
		for (var rank : ranks) {
			// Command level and filter shown alongside the node count: a rank that grants nothing but
			// raises the level is a normal thing to set up, and it used to read here as empty.
			source.sendSuccess(() -> Msg.line("  " + rank.label() + " — "
				+ rank.nodes().size() + " permission(s), command level " + rank.commandLevel()
				+ ", " + rank.commandMode().name().toLowerCase(java.util.Locale.ROOT)
				+ (rank.commandMode() == scriptify.permission.RankService.CommandMode.ALL
					? "" : " (" + rank.commands().size() + " listed)")), false);
		}
		int gateable = scriptify.permission.RankService.gateableCommands(source.getServer()).size();
		source.sendSuccess(() -> Msg.line(gateable
			+ " command(s) can be allowed or blocked by a rank."), false);
		return ranks.size();
	}

	private static void registerPermissions(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("scriptifyperms")
			.requires(source -> has(source, PermissionService.ADMIN))
			.then(literal("grant")
				// Node first so the target stays last: it is a greedy argument and takes several names.
				.then(argument("node", StringArgumentType.string()).suggests(NODES)
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> {
							String node = StringArgumentType.getString(context, "node");
							int changed = 0;
							for (ServerPlayer target : Targets.many(context, "target")) {
								changed += setNode(context.getSource(), target, node, true);
							}
							return changed;
						}))))
			.then(literal("revoke")
				// Node first so the target stays last: it is a greedy argument and takes several names.
				.then(argument("node", StringArgumentType.string()).suggests(NODES)
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> {
							String node = StringArgumentType.getString(context, "node");
							int changed = 0;
							for (ServerPlayer target : Targets.many(context, "target")) {
								changed += setNode(context.getSource(), target, node, false);
							}
							return changed;
						}))))
			.then(literal("list")
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> {
						ServerPlayer target = Targets.one(context, "target");
						PermissionService permissions = ScriptifyBootstrap.context().permissions();
						var granted = permissions.granted(target.getUUID());
						context.getSource().sendSuccess(() -> Msg.line(
							target.getGameProfile().name() + " has: "
								+ (granted.isEmpty() ? "nothing granted locally" : granted)), false);
						if (!permissions.isManaged()) {
							context.getSource().sendSuccess(() -> Msg.line(
								"LuckPerms is installed and takes priority over this list.",
								ChatFormatting.YELLOW), false);
						}
						return 1;
					})))
			.then(literal("nodes")
				.executes(context -> {
					context.getSource().sendSuccess(() -> Msg.line(
						"Nodes: " + PermissionService.ALL_NODES), false);
					return 1;
				})));
	}

	private static int setNode(CommandSourceStack source, ServerPlayer target, String node, boolean grant) {
		PermissionService permissions = ScriptifyBootstrap.context().permissions();
		if (!PermissionService.ALL_NODES.contains(node)) {
			source.sendFailure(Msg.failure("Unknown node. See /scriptifyperms nodes."));
			return 0;
		}
		UUID uuid = target.getUUID();
		if (grant) {
			permissions.grant(uuid, node);
		} else {
			permissions.revoke(uuid, node);
		}
		source.sendSuccess(() -> Msg.line(
			(grant ? "Granted " : "Revoked ") + node + " for " + target.getGameProfile().name() + "."), true);
		if (!permissions.isManaged()) {
			// Saying nothing here would leave the operator thinking the change took effect.
			source.sendSuccess(() -> Msg.line(
				"Note: LuckPerms is installed, so it decides access — this grant is stored but unused.",
				ChatFormatting.YELLOW), false);
		}
		return 1;
	}

	private static boolean has(CommandSourceStack source, String node) {
		return ScriptifyBootstrap.context() != null && ScriptifyBootstrap.context().permissions().has(source, node);
	}
}
