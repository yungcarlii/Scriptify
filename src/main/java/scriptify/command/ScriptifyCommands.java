package scriptify.command;

import net.minecraft.world.entity.EntityType;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import scriptify.compat.CommandConflicts;
import scriptify.compat.CompatDomain;
import scriptify.compat.CompatService;
import scriptify.compat.ConflictPolicy;
import scriptify.compat.KnownMods;
import scriptify.core.ScriptifyBootstrap;
import scriptify.feature.FakeTabService;
import scriptify.feature.IdentityService;
import scriptify.feature.InventoryCopyService;
import scriptify.feature.Sfx;
import scriptify.feature.FeatureManager;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;
import scriptify.ui.BackupMenu;
import scriptify.ui.DurabilityMenu;
import scriptify.ui.Help;
import scriptify.ui.KitsMenu;
import scriptify.ui.MainMenu;
import scriptify.ui.Msg;
import scriptify.ui.Panel;
import scriptify.ui.PotionMenu;
import scriptify.ui.FakeTabMenu;
import scriptify.ui.FillMenu;

/**
 * Every Scriptify command. Most are thin wrappers that either flip a {@code FeatureManager} toggle or
 * open the matching menu, so the command surface and the GUI stay in step.
 */
public final class ScriptifyCommands {
	private ScriptifyCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		registerCore(dispatcher);
		registerKits(dispatcher);
		registerNick(dispatcher);
		registerSkin(dispatcher);
		registerIdentity(dispatcher);
		registerGamemodes(dispatcher);
		registerToggles(dispatcher);
		registerPlayerActions(dispatcher);
		registerHealth(dispatcher);
		registerInventoryTools(dispatcher);
		registerDeath(dispatcher);
		registerSudo(dispatcher);
		MakerCommands.register(dispatcher);
		CueCommands.register(dispatcher);
		FakeCommands.register(dispatcher);
		StageCommands.register(dispatcher);
		registerCountdown(dispatcher);
	}

	/** {@code /countdown <seconds> [title]} for everyone online; {@code /countdown stop} takes it down. */
	private static void registerCountdown(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("countdown")
			.requires(source -> has(source, PermissionService.PLAYER))
			.then(literal("stop").executes(context -> {
				boolean stopped = scriptify.feature.Countdown.stop();
				context.getSource().sendSuccess(() -> Msg.line(stopped ? "Countdown stopped." : "Nothing is counting down."), false);
				return stopped ? 1 : 0;
			}))
			.then(argument("seconds", IntegerArgumentType.integer(1, 86400))
				.executes(context -> startCountdown(context.getSource(),
					IntegerArgumentType.getInteger(context, "seconds"), "Starting in"))
				.then(argument("title", StringArgumentType.greedyString())
					.executes(context -> startCountdown(context.getSource(),
						IntegerArgumentType.getInteger(context, "seconds"),
						StringArgumentType.getString(context, "title"))))));
	}

	private static int startCountdown(CommandSourceStack source, int seconds, String title) {
		scriptify.feature.Countdown.start(source.getServer(), seconds, title);
		source.sendSuccess(() -> Msg.line("Counting down " + seconds + "s: " + title), false);
		return 1;
	}

	/**
	 * Runs an action against every player a target token matched.
	 *
	 * <p>This is what makes {@code /unnick @a} mean "all of them" rather than "whichever one happens to
	 * be first" — the per-player helpers stay single-target and this fans them out.
	 *
	 * @return the number of players acted on, which Brigadier reports as the command's result.
	 */
	private static int each(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
			String argument, java.util.function.Function<ServerPlayer, Integer> action) throws CommandSyntaxException {
		int total = 0;
		for (ServerPlayer target : Targets.many(context, argument)) {
			total += action.apply(target);
		}
		return total;
	}

	// ------------------------------------------------------------------ core

	private static void registerCore(CommandDispatcher<CommandSourceStack> dispatcher) {
		// Bare /scriptify opens the dashboard; the sub-commands stay for console use.
		dispatcher.register(literal("scriptify")
			.executes(context -> openMain(context.getSource()))
			.then(literal("help")
				.executes(context -> {
					Help.send(context.getSource());
					return 1;
				})
				.then(literal("all").executes(context -> {
					Help.sendAll(context.getSource());
					return 1;
				})))
			.then(literal("version").executes(context -> {
				context.getSource().sendSuccess(() -> Msg.line("Scriptify " + Help.Version.CURRENT + " server-side toolkit."), false);
				return 1;
			}))
			.then(literal("reload")
				.requires(source -> has(source, PermissionService.ADMIN))
				.executes(context -> {
					if (!ScriptifyBootstrap.reload()) {
						context.getSource().sendFailure(Msg.failure("Scriptify is not started yet."));
						return 0;
					}
					ScriptifyRuntime.config().load();
					context.getSource().sendSuccess(() -> Msg.line("Scriptify reloaded."), true);
					return 1;
				}))
			.then(literal("gui")
				.requires(source -> has(source, PermissionService.GUI_OPEN))
				.executes(context -> openMain(context.getSource())))
			.then(literal("compat")
				.requires(source -> has(source, PermissionService.ADMIN))
				.executes(context -> reportCompat(context.getSource()))));
	}

	/**
	 * Prints what Scriptify found overlapping with it and who won each surface.
	 *
	 * <p>The same information goes to the log at startup, but a log line scrolls past and this is the
	 * question people ask hours later, when a nickname quietly stopped applying.
	 */
	private static int reportCompat(CommandSourceStack source) {
		CompatService compat = ScriptifyRuntime.compat();
		if (compat == null || !compat.enabled()) {
			source.sendSuccess(() -> Msg.line("Conflict detection is off."), false);
			return 0;
		}

		List<CompatService.Detected> mods = compat.detected();
		List<CommandConflicts.Collision> commands = CommandConflicts.collisions();
		if (mods.isEmpty() && commands.isEmpty()) {
			source.sendSuccess(() -> Msg.success("No conflicting mods found."), false);
			return 1;
		}

		// Worked out before the panel opens, because the heading has to tell the truth about what is
		// below it - "overlaps with other mods" above a clean report is how a reassuring answer gets
		// read as a bad one.
		boolean contestedFound = commands.stream()
			.anyMatch(collision -> collision.origin() != CommandConflicts.Origin.VANILLA)
			|| mods.stream().anyMatch(mod -> !mod.domains().isEmpty());
		Panel.open(source, contestedFound ? "overlaps with other mods" : "getting on with everything");
		boolean anyContested = false;
		for (CompatService.Detected mod : mods) {
			for (CompatDomain domain : mod.domains()) {
				ConflictPolicy policy = compat.policy(domain);
				Panel.row(source, "◆", mod.name() + " wants " + domain.id(),
					verdict(policy), colourOf(policy));
				anyContested = true;
			}
		}
		for (CommandConflicts.Collision collision : commands) {
			// Extending a vanilla command is a feature of the mod, not an overlap with anything, and
			// it happens on every start. Under a warning triangle it read as a fault report, which is
			// how a panel that is meant to reassure ends up worrying people instead.
			if (collision.origin() == CommandConflicts.Origin.VANILLA) {
				continue;
			}
			Panel.row(source, "⚠", "/" + collision.name() + " " + collision.describe(),
				verdict(collision.policy()), colourOf(collision.policy()));
			anyContested = true;
		}
		if (!anyContested) {
			Panel.row(source, "◆", "Nothing is contested", "all clear", ChatFormatting.GREEN);
		}
		// Mods that are getting on fine still earn a line. "Is Lithium the reason?" is a question
		// somebody asks at midnight, and answering it here costs one row.
		for (CompatService.Detected mod : mods) {
			if (mod.kind() != KnownMods.Kind.NOTED) {
				continue;
			}
			Panel.row(source, "·", mod.name(), "no conflict", ChatFormatting.GRAY, null, mod.note());
		}
		List<CommandConflicts.Collision> extended = commands.stream()
			.filter(collision -> collision.origin() == CommandConflicts.Origin.VANILLA).toList();
		if (!extended.isEmpty()) {
			StringBuilder names = new StringBuilder();
			for (CommandConflicts.Collision collision : extended) {
				names.append(names.isEmpty() ? "" : ", ").append('/').append(collision.name());
			}
			Panel.row(source, "·", "Extending vanilla", names.toString(), ChatFormatting.GRAY, null,
				"Scriptify adds to these rather than replacing them. Nothing to resolve.");
		}
		if (anyContested) {
			Panel.note(source, "Change these under compat in the Scriptify config.");
		}
		Panel.close(source);
		return 1;
	}

	private static String verdict(ConflictPolicy policy) {
		return switch (policy) {
			case PRIORITY -> "Scriptify wins";
			case YIELD -> "left to the other mod";
			case IGNORE -> "load order decides";
		};
	}

	private static ChatFormatting colourOf(ConflictPolicy policy) {
		return switch (policy) {
			case PRIORITY -> ChatFormatting.GREEN;
			case YIELD -> ChatFormatting.YELLOW;
			case IGNORE -> ChatFormatting.RED;
		};
	}

	private static int openMain(CommandSourceStack source) {
		try {
			MainMenu.open(ScriptifyBootstrap.context(), source.getPlayerOrException());
			return 1;
		} catch (CommandSyntaxException exception) {
			source.sendFailure(Msg.failure("Only players can open the Scriptify menu."));
			return 0;
		}
	}

	// ------------------------------------------------------------------ kits

	private static void registerKits(CommandDispatcher<CommandSourceStack> dispatcher) {
		// The factory exists because registering the same tree twice is the only way to give a command
		// two names — Brigadier refuses to forward a node that already has children, which is what broke
		// 1.3.2. Adding a second name is one more line here.
		dispatcher.register(kitCommand("kitted"));
	}

	/** The kit command tree, built fresh so it can be registered under more than one name. */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> kitCommand(
			String alias) {
		return (literal(alias)
			.requires(source -> has(source, PermissionService.KITS))
			.executes(context -> openKits(context.getSource(), null))
			.then(argument("for", Targets.arg()).suggests(Targets.SUGGEST)
				.requires(source -> has(source, PermissionService.KITS_MANAGE))
				.executes(context -> openKits(context.getSource(), Targets.one(context, "for"))))
			.then(literal("create").requires(source -> has(source, PermissionService.KITS_MANAGE))
				.then(argument("name", StringArgumentType.string()).suggests(KIT_NAMES)
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						String name = StringArgumentType.getString(context, "name");
						try {
							ScriptifyRuntime.kits().create(name, player);
						} catch (IllegalArgumentException exception) {
							context.getSource().sendFailure(Msg.failure(exception.getMessage()));
							return 0;
						}
						context.getSource().sendSuccess(() -> Msg.line(
							"Created kit '" + name + "' from your inventory."), true);
						return 1;
					})))
			.then(literal("apply")
				.then(argument("name", StringArgumentType.string()).suggests(KIT_NAMES)
					.executes(context -> applyKit(context.getSource(),
						StringArgumentType.getString(context, "name"),
						context.getSource().getPlayerOrException()))
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.requires(source -> has(source, PermissionService.KITS_MANAGE))
						.executes(context -> applyKit(context.getSource(),
							StringArgumentType.getString(context, "name"),
							Targets.one(context, "target"))))))
			.then(literal("delete").requires(source -> has(source, PermissionService.KITS_MANAGE))
				.then(argument("name", StringArgumentType.string()).suggests(KIT_NAMES)
					.executes(context -> {
						String name = StringArgumentType.getString(context, "name");
						boolean removed = ScriptifyRuntime.kits().delete(name);
						context.getSource().sendSuccess(() -> Msg.line(
							(removed ? "Deleted kit '" : "No kit named '") + name + "'."), true);
						return removed ? 1 : 0;
					})))
			.then(literal("list")
				.executes(context -> {
					ServerPlayer viewer = context.getSource().getEntity() instanceof ServerPlayer player ? player : null;
					java.util.List<String> visible = ScriptifyRuntime.kits().entries().stream()
						.filter(entry -> viewer == null || ScriptifyRuntime.kits().canManage(viewer)
							|| ScriptifyRuntime.kits().canUse(entry.name(), viewer))
						.map(entry -> entry.displayName())
						.toList();
					context.getSource().sendSuccess(() -> Msg.line(
						visible.isEmpty() ? "No kits available to your rank." : "Kits: " + visible), false);
					return visible.size();
				})));
	}

	private static int applyKit(CommandSourceStack source, String name, ServerPlayer target) {
		ServerPlayer actor = source.getEntity() instanceof ServerPlayer player ? player : null;
		boolean applyingToSelf = actor == target;
		if (!applyingToSelf && actor != null && !ScriptifyRuntime.kits().canManage(actor)) {
			source.sendFailure(Msg.failure("You cannot apply kits to other players."));
			return 0;
		}
		boolean applied = applyingToSelf
			? ScriptifyRuntime.kits().apply(name, target)
			: ScriptifyRuntime.kits().applyManaged(name, target);
		if (!applied) {
			source.sendFailure(Msg.failure(applyingToSelf
				? "Your rank cannot use kit '" + name + "'."
				: "Unknown kit '" + name + "'."));
			return 0;
		}
		source.sendSuccess(() -> Msg.line(
			"Applied kit '" + name + "' to " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ nicknames

	/** Opens the kit menu, optionally aimed at somebody else. */
	private static int openKits(CommandSourceStack source, ServerPlayer target) {
		try {
			ServerPlayer viewer = source.getPlayerOrException();
			if (target != null && target != viewer && !ScriptifyRuntime.kits().canManage(viewer)) {
				source.sendFailure(Msg.failure("You cannot hand kits to other players."));
				return 0;
			}
			KitsMenu.open(ScriptifyBootstrap.context(), viewer, target, 0);
			return 1;
		} catch (CommandSyntaxException exception) {
			source.sendFailure(Msg.failure("Only players can open the kit menu."));
			return 0;
		}
	}

	/** Kit names as they were typed, so a name with spaces can be completed rather than remembered. */
	private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> KIT_NAMES =
		(context, builder) -> {
			java.util.List<String> names = new java.util.ArrayList<>();
			ServerPlayer viewer = context.getSource().getEntity() instanceof ServerPlayer player ? player : null;
			for (var entry : ScriptifyRuntime.kits().entries()) {
				if (viewer == null || ScriptifyRuntime.kits().canManage(viewer)
					|| ScriptifyRuntime.kits().canUse(entry.name(), viewer)) {
					names.add(entry.displayName());
				}
			}
			return net.minecraft.commands.SharedSuggestionProvider.suggest(
				names.stream().map(name -> name.contains(" ") ? "\"" + name + "\"" : name).toList(),
				builder);
		};

	private static void registerNick(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("nick")
			.requires(source -> has(source, PermissionService.NICK))
			.then(argument("nickname", StringArgumentType.word())
				.executes(context -> setNick(context.getSource(),
					context.getSource().getPlayerOrException(),
					StringArgumentType.getString(context, "nickname")))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> each(context, "target", scriptifyTarget -> setNick(context.getSource(),
						scriptifyTarget,
						StringArgumentType.getString(context, "nickname")))))));

		dispatcher.register(literal("randomnick")
			.requires(source -> has(source, PermissionService.NICK))
			.executes(context -> randomNick(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> randomNick(context.getSource(), scriptifyTarget)))));

		dispatcher.register(literal("unnick")
			.requires(source -> has(source, PermissionService.NICK))
			.executes(context -> clearNick(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> clearNick(context.getSource(), scriptifyTarget)))));
	}

	private static int setNick(CommandSourceStack source, ServerPlayer target, String nickname) {
		try {
			String applied = ScriptifyRuntime.nick().set(target, nickname);
			source.sendSuccess(() -> Msg.line(
				"Nicknamed " + target.getGameProfile().name() + " as " + applied + "."), true);
			return 1;
		} catch (IllegalArgumentException exception) {
			source.sendFailure(Msg.failure(exception.getMessage()));
			return 0;
		}
	}

	private static int randomNick(CommandSourceStack source, ServerPlayer target) {
		// Generated rather than verified against Mojang. Only a small, repetitive set of invented names
		// turn out to be registered accounts, so checking them was the thing making every roll look the
		// same. A nickname has to read as real, not be real — that is /randomidentity's job, and it
		// needs a genuine account only because it also has to fetch that account's skin.
		String nickname = ScriptifyRuntime.nick().random(target);
		source.sendSuccess(() -> Msg.line(
			"Random nickname for " + target.getGameProfile().name() + ": " + nickname), true);
		return 1;
	}

	private static int clearNick(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.nick().clear(target);
		source.sendSuccess(() -> Msg.line(
			"Removed nickname from " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ skins

	private static void registerIdentity(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("identity")
			.requires(source -> has(source, PermissionService.SKIN))
			.then(literal("set")
				.then(argument("source", StringArgumentType.word())
					.executes(context -> setIdentity(context.getSource(),
						StringArgumentType.getString(context, "source"),
						context.getSource().getPlayerOrException()))
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> each(context, "target", scriptifyTarget -> setIdentity(context.getSource(),
							StringArgumentType.getString(context, "source"),
							scriptifyTarget))))))
			.then(literal("clear")
				.executes(context -> clearIdentity(context.getSource(), context.getSource().getPlayerOrException()))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> each(context, "target", scriptifyTarget -> clearIdentity(context.getSource(), scriptifyTarget))))));

		dispatcher.register(literal("randomidentity")
			.requires(source -> has(source, PermissionService.SKIN))
			.executes(context -> randomIdentity(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> randomIdentity(context.getSource(), scriptifyTarget)))));
	}

	private static int setIdentity(CommandSourceStack source, String sourceName, ServerPlayer target) {
		String original = target.getGameProfile().name();
		source.sendSuccess(() -> Msg.line("Becoming " + sourceName + "..."), false);
		IdentityService.assume(target, sourceName, applied -> {
			if (applied) {
				source.sendSuccess(() -> Msg.line(original + " is now " + sourceName + ", skin and all."), true);
			} else {
				// The name still changed - say so, rather than reporting a clean failure for a half-applied
				// identity and leaving the operator to find the mismatch on camera.
				source.sendFailure(Msg.failure(
					"Renamed to " + sourceName + ", but their skin could not be fetched."));
			}
		});
		return 1;
	}

	private static int clearIdentity(CommandSourceStack source, ServerPlayer target) {
		String shown = target.getGameProfile().name();
		IdentityService.clear(target, restored -> {
			if (restored) {
				source.sendSuccess(() -> Msg.line(shown + " is themselves again."), true);
			} else {
				source.sendFailure(Msg.failure(
					"Cleared the nickname, but their own skin could not be fetched back."));
			}
		});
		return 1;
	}

	private static int randomIdentity(CommandSourceStack source, ServerPlayer target) {
		String original = target.getGameProfile().name();
		source.sendSuccess(() -> Msg.line("Finding somebody to be..."), false);
		IdentityService.randomise(target, name -> {
			if (name.isEmpty()) {
				source.sendFailure(Msg.failure("Could not reach Mojang to find an account."));
			} else {
				source.sendSuccess(() -> Msg.line(original + " is now " + name.get() + ", skin and all."), true);
			}
		});
		return 1;
	}

	private static void registerSkin(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("randomskin")
			.requires(source -> has(source, PermissionService.SKIN))
			.executes(context -> randomSkin(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> randomSkin(context.getSource(), scriptifyTarget)))));

		dispatcher.register(literal("skin")
			.requires(source -> has(source, PermissionService.SKIN))
			.then(literal("set")
				.then(argument("source", StringArgumentType.word())
					.executes(context -> setSkin(context.getSource(),
						StringArgumentType.getString(context, "source"),
						context.getSource().getPlayerOrException()))
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> each(context, "target", scriptifyTarget -> setSkin(context.getSource(),
							StringArgumentType.getString(context, "source"),
							scriptifyTarget))))))
			// clear puts the account's own skin back - undoing the override, not blanking it to Steve.
			.then(literal("clear")
				.executes(context -> clearSkin(context.getSource(), context.getSource().getPlayerOrException()))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> each(context, "target", scriptifyTarget -> clearSkin(context.getSource(), scriptifyTarget))))));
	}

	private static int setSkin(CommandSourceStack source, String skinSource, ServerPlayer target) {
		source.sendSuccess(() -> Msg.line("Looking up " + skinSource + "…"), false);
		ScriptifyRuntime.skin().copySkinAsync(skinSource, target, applied -> {
			if (applied) {
				source.sendSuccess(() -> Msg.line(
					"Applied " + skinSource + "'s skin to " + target.getGameProfile().name() + "."), true);
			} else {
				source.sendFailure(Msg.failure("No account called '" + skinSource + "', or Mojang is unreachable."));
			}
		});
		return 1;
	}

	private static int randomSkin(CommandSourceStack source, ServerPlayer target) {
		// The lookup is asynchronous, so this reports success optimistically and the real outcome
		// arrives a moment later.
		source.sendSuccess(() -> Msg.line("Fetching a random skin…"), false);
		ScriptifyRuntime.skin().randomSkinAsync(target, applied -> {
			if (applied.isEmpty()) {
				source.sendFailure(Msg.failure(
					"Could not reach NameMC, and nobody else is online to borrow from."));
			} else {
				source.sendSuccess(() -> Msg.line(
					target.getGameProfile().name() + " now wears " + applied.get() + "."), true);
			}
		});
		return 1;
	}

	private static int clearSkin(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.skin().clearSkinAsync(target, applied -> {
			if (applied) {
				source.sendSuccess(() -> Msg.line(
					"Restored " + target.getGameProfile().name() + "'s own skin."), true);
			} else {
				source.sendFailure(Msg.failure("Could not fetch that account's own original skin."));
			}
		});
		return 1;
	}

	// ------------------------------------------------------------------ gamemodes

	private static void registerGamemodes(CommandDispatcher<CommandSourceStack> dispatcher) {
		registerGamemode(dispatcher, "gms", GameType.SURVIVAL);
		registerGamemode(dispatcher, "gmc", GameType.CREATIVE);
		registerGamemode(dispatcher, "gma", GameType.ADVENTURE);
		registerGamemode(dispatcher, "gmsp", GameType.SPECTATOR);
	}

	private static void registerGamemode(CommandDispatcher<CommandSourceStack> dispatcher, String alias, GameType mode) {
		dispatcher.register(literal(alias)
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> setGameMode(context.getSource(), context.getSource().getPlayerOrException(), mode))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> setGameMode(context.getSource(), scriptifyTarget, mode)))));
	}

	private static int setGameMode(CommandSourceStack source, ServerPlayer target, GameType mode) {
		target.setGameMode(mode);
		source.sendSuccess(() -> Msg.line(
			"Set " + target.getGameProfile().name() + " to " + mode.getName() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ toggles

	private static void registerToggles(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("pvp")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				context.getSource().sendSuccess(() -> Msg.line(
					"PvP is " + (ScriptifyRuntime.features().isPvpEnabled() ? "on" : "off") + "."), false);
				return 1;
			})
			.then(literal("on").executes(context -> setPvp(context.getSource(), true)))
			.then(literal("off").executes(context -> setPvp(context.getSource(), false))));

		// Toggling rather than always freezing: /freeze is the one people reach for mid-take, and
		// remembering which of the two commands undoes it is friction nobody needs.
		dispatcher.register(literal("freeze")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> toggleFrozen(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> toggleFrozen(context.getSource(), scriptifyTarget)))));

		dispatcher.register(literal("unfreeze")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> setFrozen(context.getSource(), context.getSource().getPlayerOrException(), false))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> setFrozen(context.getSource(), scriptifyTarget, false)))));

		dispatcher.register(literal("invis")
			.requires(source -> has(source, PermissionService.PLAYER))
			.then(literal("on").executes(context -> setInvis(context.getSource(),
				context.getSource().getPlayerOrException(), true))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> each(context, "target", scriptifyTarget -> setInvis(context.getSource(), scriptifyTarget, true)))))
			.then(literal("off").executes(context -> setInvis(context.getSource(),
				context.getSource().getPlayerOrException(), false))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> each(context, "target", scriptifyTarget -> setInvis(context.getSource(), scriptifyTarget, false))))));

		// Mirrors the reference mod's command shape: global / set / fall / armor / get.
		// One command that toggles, like /freeze. The on/off/set trio was three ways to say the same
		// thing, and remembering which one took a duration was friction in the middle of a take.
		dispatcher.register(literal("immortal")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> toggleImmortal(context.getSource(),
				java.util.List.of(context.getSource().getPlayerOrException()), FeatureManager.FOREVER))
			.then(literal("status")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					context.getSource().sendSuccess(() -> Msg.line(
						describeImmortality(ScriptifyRuntime.features(), player)), false);
					return 1;
				})
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> {
						ServerPlayer target = Targets.one(context, "target");
						context.getSource().sendSuccess(() -> Msg.line(
							target.getGameProfile().name() + " — "
								+ describeImmortality(ScriptifyRuntime.features(), target)), false);
						return 1;
					})))
			.then(literal("global")
				.executes(context -> {
					FeatureManager features = ScriptifyRuntime.features();
					return setGlobalImmortal(context.getSource(), !features.isGlobalImmortal(),
						FeatureManager.FOREVER);
				})
				.then(argument("seconds", IntegerArgumentType.integer(-1))
					.executes(context -> setGlobalImmortal(context.getSource(), true,
						IntegerArgumentType.getInteger(context, "seconds")))))
			.then(literal("fall")
				.executes(context -> toggleImmortalOption(context.getSource(), "fall")))
			.then(literal("armor")
				.executes(context -> toggleImmortalOption(context.getSource(), "armor")))
			.then(literal("void")
				.executes(context -> toggleImmortalOption(context.getSource(), "void")))
			.then(argument("targets", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> toggleImmortal(context.getSource(),
					Targets.many(context, "targets"), FeatureManager.FOREVER))
				// A duration still means "turn it on for this long" rather than toggling, because
				// toggling something off for ten seconds is not a thing anybody wants.
				.then(argument("seconds", IntegerArgumentType.integer(-1))
					.executes(context -> setImmortal(context.getSource(),
						Targets.many(context, "targets"), true,
						IntegerArgumentType.getInteger(context, "seconds"))))));

		dispatcher.register(literal("vanish")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				boolean value = !ScriptifyRuntime.features().isVanished(player);
				ScriptifyRuntime.features().setVanished(player, value);
				context.getSource().sendSuccess(() -> Msg.line("Vanish " + (value ? "enabled" : "disabled") + "."), false);
				return 1;
			}));
	}

	private static int setPvp(CommandSourceStack source, boolean enabled) {
		ScriptifyRuntime.features().setPvp(enabled);
		source.sendSuccess(() -> Msg.line("PvP turned " + (enabled ? "on" : "off") + "."), true);
		return 1;
	}

	/** Freezes a player, or thaws them if they were already frozen. */
	private static int toggleFrozen(CommandSourceStack source, ServerPlayer target) {
		return setFrozen(source, target, !ScriptifyRuntime.features().isFrozen(target));
	}

	private static int setFrozen(CommandSourceStack source, ServerPlayer target, boolean frozen) {
		ScriptifyRuntime.features().setFrozen(target, frozen);
		source.sendSuccess(() -> Msg.line(
			(frozen ? "Froze " : "Unfroze ") + target.getGameProfile().name() + "."), true);
		return 1;
	}

	private static int setInvis(CommandSourceStack source, ServerPlayer target, boolean enabled) {
		ScriptifyRuntime.features().setInvisible(target, enabled);
		source.sendSuccess(() -> Msg.line(
			"Invisibility for " + target.getGameProfile().name() + " " + (enabled ? "enabled" : "disabled") + "."), true);
		return 1;
	}

	/**
	 * Flips immortality for each target.
	 *
	 * <p>Decided per player rather than from one shared state: half the point of running this on a
	 * group is turning off whoever is still immortal, and a single flag would flip them back on.
	 */
	private static int toggleImmortal(CommandSourceStack source, java.util.List<ServerPlayer> targets,
			int seconds) {
		FeatureManager features = ScriptifyRuntime.features();
		int changed = 0;
		for (ServerPlayer target : targets) {
			boolean next = !features.isImmortal(target);
			changed += setImmortal(source, java.util.List.of(target), next, seconds);
		}
		return changed;
	}

	/** Toggles one of the immortality sub-options for the caller. */
	private static int toggleImmortalOption(CommandSourceStack source, String option)
			throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FeatureManager features = ScriptifyRuntime.features();
		boolean next;
		String label;
		switch (option) {
			case "fall" -> {
				next = !features.isFallImmune(player);
				features.setFallImmune(player, next);
				label = "Fall immunity";
			}
			case "armor" -> {
				next = !features.isArmorProtected(player);
				features.setArmorProtected(player, next);
				label = "Armour protection";
			}
			default -> {
				next = !features.allowVoidImmortality();
				features.setAllowVoidImmortality(next);
				label = "Immortality in the void";
			}
		}
		boolean enabled = next;
		source.sendSuccess(() -> Msg.line(label + " " + (enabled ? "enabled" : "disabled") + "."), true);
		return 1;
	}

	private static int setImmortal(CommandSourceStack source, java.util.Collection<ServerPlayer> targets,
			boolean enabled, int seconds) {
		FeatureManager features = ScriptifyRuntime.features();
		for (ServerPlayer target : targets) {
			features.setImmortal(target, enabled, seconds);
		}
		String names = targets.stream()
			.map(target -> target.getGameProfile().name())
			.reduce((a, b) -> a + ", " + b)
			.orElse("nobody");
		String suffix = enabled && seconds > 0 ? " for " + seconds + "s" : "";
		source.sendSuccess(() -> Msg.line(
			"Immortality " + (enabled ? "enabled" : "disabled") + " for " + names + suffix + "."), true);
		return targets.size();
	}

	private static int setGlobalImmortal(CommandSourceStack source, boolean enabled, int seconds) {
		ScriptifyRuntime.features().setGlobalImmortal(enabled, seconds);
		String suffix = enabled && seconds > 0 ? " for " + seconds + "s" : "";
		source.sendSuccess(() -> Msg.line(
			"Global immortality " + (enabled ? "enabled" : "disabled") + suffix + "."), true);
		return 1;
	}

	private static String describeImmortality(FeatureManager features, ServerPlayer player) {
		if (!features.isImmortal(player)) {
			return "Not immortal.";
		}
		if (features.isGlobalImmortal() && features.immortalSecondsRemaining(player) == null) {
			return "Immortal via the global toggle.";
		}
		Integer seconds = features.immortalSecondsRemaining(player);
		return seconds == null || seconds == FeatureManager.FOREVER
			? "Immortal (no time limit)."
			: "Immortal for another " + seconds + "s.";
	}

	private static void registerHealth(CommandDispatcher<CommandSourceStack> dispatcher) {
		// Backed by the max_health attribute, so the heart row itself changes.
		dispatcher.register(literal("health")
			.requires(source -> has(source, PermissionService.PLAYER))
			.then(literal("reset")
				.executes(context -> resetHealth(context.getSource(), context.getSource().getPlayerOrException()))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> each(context, "target", scriptifyTarget -> resetHealth(context.getSource(), scriptifyTarget)))))
			.then(argument("hearts", DoubleArgumentType.doubleArg(0.5D, 1024.0D))
				.executes(context -> setHealth(context.getSource(),
					context.getSource().getPlayerOrException(),
					DoubleArgumentType.getDouble(context, "hearts")))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> each(context, "target", scriptifyTarget -> setHealth(context.getSource(),
						scriptifyTarget,
						DoubleArgumentType.getDouble(context, "hearts")))))));
	}

	private static int setHealth(CommandSourceStack source, ServerPlayer target, double hearts) {
		double applied = ScriptifyRuntime.features().setMaxHealth(target, hearts);
		source.sendSuccess(() -> Msg.line(
			"Set " + target.getGameProfile().name() + " to " + applied + " heart(s)."), true);
		return 1;
	}

	private static int resetHealth(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.features().resetMaxHealth(target);
		source.sendSuccess(() -> Msg.line(
			"Reset " + target.getGameProfile().name() + " to 10 hearts."), true);
		return 1;
	}

	// ------------------------------------------------------------------ player actions

	private static void registerPlayerActions(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("heal")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> heal(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> heal(context.getSource(), scriptifyTarget)))));

		dispatcher.register(literal("feed")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> feed(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget -> feed(context.getSource(), scriptifyTarget)))));

		dispatcher.register(literal("totem")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> giveTotems(context.getSource(), context.getSource().getPlayerOrException(), 1))
			.then(argument("count", IntegerArgumentType.integer(1, 576))
				.executes(context -> giveTotems(context.getSource(),
					context.getSource().getPlayerOrException(),
					IntegerArgumentType.getInteger(context, "count")))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> each(context, "target", scriptifyTarget -> giveTotems(context.getSource(),
						scriptifyTarget,
						IntegerArgumentType.getInteger(context, "count")))))));

		dispatcher.register(literal("top")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				if (!ScriptifyRuntime.features().teleportToTop(player)) {
					context.getSource().sendFailure(Msg.failure("Nothing solid above you."));
					return 0;
				}
				context.getSource().sendSuccess(() -> Msg.line("Teleported to the surface."), false);
				return 1;
			}));

		dispatcher.register(literal("potion")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				try {
					PotionMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException());
					return 1;
				} catch (CommandSyntaxException exception) {
					context.getSource().sendFailure(Msg.failure("Only players can open the potion menu."));
					return 0;
				}
			})
			.then(literal("clear")
				.executes(context -> {
					scriptify.feature.PotionPresetService.clear(context.getSource().getPlayerOrException());
					context.getSource().sendSuccess(() -> Msg.line("Cleared all effects."), false);
					return 1;
				}))
			.then(argument("preset", StringArgumentType.word())
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					String preset = StringArgumentType.getString(context, "preset");
					if (!ScriptifyRuntime.potions().apply(context.getSource().getServer(), player, preset)) {
						context.getSource().sendFailure(Msg.failure(
							"That potion preset is unavailable to your rank."));
						return 0;
					}
					context.getSource().sendSuccess(() -> Msg.line("Applied the " + preset + " preset."), false);
					return 1;
				})));
	}

	private static int heal(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.features().heal(target);
		source.sendSuccess(() -> Msg.line("Healed " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	private static int feed(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.features().feed(target);
		source.sendSuccess(() -> Msg.line("Fed " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	private static int giveTotems(CommandSourceStack source, ServerPlayer target, int count) {
		ScriptifyRuntime.features().giveTotems(target, count);
		source.sendSuccess(() -> Msg.line(
			"Gave " + count + " totem(s) to " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ inventory tools

	private static void registerInventoryTools(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("durability")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.executes(context -> {
				try {
					DurabilityMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException());
					return 1;
				} catch (CommandSyntaxException exception) {
					context.getSource().sendFailure(Msg.failure("Only players can open the durability menu."));
					return 0;
				}
			}));

		dispatcher.register(literal("restock")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				int restocked = ScriptifyRuntime.features().restock(player);
				context.getSource().sendSuccess(() -> Msg.line("Restocked " + restocked + " stack(s)."), false);
				return 1;
			}));

		dispatcher.register(literal("randomfill")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.executes(context -> {
				try {
					FillMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException());
					return 1;
				} catch (CommandSyntaxException exception) {
					context.getSource().sendFailure(Msg.failure("Only players can open the fill menu."));
					return 0;
				}
			})
			// Fills straight away using whatever settings are already loaded, for repeat passes over a
			// row of chests without reopening the menu each time.
			.then(literal("now")
				.executes(context -> {
					try {
						FillMenu.doFill(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException());
						return 1;
					} catch (CommandSyntaxException exception) {
						context.getSource().sendFailure(Msg.failure("Only players can fill containers."));
						return 0;
					}
				}))
			.then(literal("preset")
				.then(literal("save").requires(source -> has(source, PermissionService.FILL_MANAGE))
					.then(argument("name", StringArgumentType.word())
						.executes(context -> {
							try {
								ServerPlayer player = context.getSource().getPlayerOrException();
								String name = StringArgumentType.getString(context, "name");
								ScriptifyRuntime.fill().savePreset(name, ScriptifyRuntime.fill().settings(player));
								context.getSource().sendSuccess(() -> Msg.line("Saved preset '" + name + "'."), false);
								return 1;
							} catch (IllegalArgumentException exception) {
								context.getSource().sendFailure(Msg.failure(exception.getMessage()));
								return 0;
							}
						})))
				.then(literal("load")
					.then(argument("name", StringArgumentType.word())
						.executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							String name = StringArgumentType.getString(context, "name");
							if (!ScriptifyRuntime.fill().canUsePreset(name, player)) {
								context.getSource().sendFailure(Msg.failure("That fill preset is unavailable to your rank."));
								return 0;
							}
							var preset = ScriptifyRuntime.fill().preset(name);
							if (preset.isEmpty()) {
								context.getSource().sendFailure(Msg.failure("No preset called '" + name + "'."));
								return 0;
							}
							ScriptifyRuntime.fill().setSettings(player, preset.get());
							context.getSource().sendSuccess(() -> Msg.line("Loaded preset '" + name + "'."), false);
							return 1;
						})))
				.then(literal("delete").requires(source -> has(source, PermissionService.FILL_MANAGE))
					.then(argument("name", StringArgumentType.word())
						.executes(context -> {
							String name = StringArgumentType.getString(context, "name");
							if (!ScriptifyRuntime.fill().deletePreset(name)) {
								context.getSource().sendFailure(Msg.failure("No preset called '" + name + "'."));
								return 0;
							}
							context.getSource().sendSuccess(() -> Msg.line("Deleted preset '" + name + "'."), false);
							return 1;
						})))
				.then(literal("list")
					.executes(context -> {
						ServerPlayer viewer = context.getSource().getEntity() instanceof ServerPlayer player ? player : null;
						java.util.List<String> visible = ScriptifyRuntime.fill().presetNames().stream()
							.filter(name -> viewer == null || ScriptifyRuntime.fill().canManagePresets(viewer)
								|| ScriptifyRuntime.fill().canUsePreset(name, viewer))
							.toList();
						context.getSource().sendSuccess(() -> Msg.line(
							visible.isEmpty() ? "No fill presets available to your rank." : "Presets: " + visible), false);
						return visible.size();
					}))));

		dispatcher.register(literal("backupinv")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.executes(context -> {
				try {
					BackupMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException(), 0);
					return 1;
				} catch (CommandSyntaxException exception) {
					context.getSource().sendFailure(Msg.failure("Only players can open the backup menu."));
					return 0;
				}
			})
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> {
					try {
						BackupMenu.open(ScriptifyBootstrap.context(),
							context.getSource().getPlayerOrException(),
							Targets.one(context, "target"), 0);
						return 1;
					} catch (CommandSyntaxException exception) {
						context.getSource().sendFailure(Msg.failure("Only players can open the backup menu."));
						return 0;
					}
				}))
			.then(literal("save")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					ScriptifyRuntime.backups().create(player, scriptify.feature.InventoryBackupService.REASON_MANUAL);
					context.getSource().sendSuccess(() -> Msg.line("Inventory snapshot saved."), false);
					return 1;
				})
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> {
						ServerPlayer target = Targets.one(context, "target");
						ScriptifyRuntime.backups().create(target, scriptify.feature.InventoryBackupService.REASON_MANUAL);
						context.getSource().sendSuccess(() -> Msg.line(
							"Saved a snapshot of " + target.getGameProfile().name() + "'s inventory."), true);
						return 1;
					}))));

		registerInventoryRollback(dispatcher);

		dispatcher.register(literal("faketab")
			.requires(source -> has(source, PermissionService.ADMIN))
			.executes(context -> {
				try {
					FakeTabMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException());
					return 1;
				} catch (CommandSyntaxException exception) {
					return fakeTabStatus(context.getSource());
				}
			})
			.then(literal("status").executes(context -> fakeTabStatus(context.getSource())))
			.then(literal("off").executes(context -> setFakeTab(context.getSource(), 0)))
			.then(literal("small").executes(context ->
				setFakeTab(context.getSource(), FakeTabService.Size.SMALL.count())))
			.then(literal("medium").executes(context ->
				setFakeTab(context.getSource(), FakeTabService.Size.MEDIUM.count())))
			.then(literal("big").executes(context ->
				setFakeTab(context.getSource(), FakeTabService.Size.BIG.count())))
			.then(literal("verybig").executes(context ->
				setFakeTab(context.getSource(), FakeTabService.Size.VERY_BIG.count())))
			.then(literal("massive").executes(context ->
				setFakeTab(context.getSource(), FakeTabService.Size.MASSIVE.count())))
			.then(literal("count")
				.then(argument("players", IntegerArgumentType.integer(0, 500))
					.executes(context -> setFakeTab(context.getSource(),
						IntegerArgumentType.getInteger(context, "players"))))));

		dispatcher.register(literal("clearground")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.executes(context -> clearGround(context.getSource(), -1))
			.then(argument("radius", IntegerArgumentType.integer(1, 512))
				.executes(context -> clearGround(context.getSource(),
					IntegerArgumentType.getInteger(context, "radius")))));

		dispatcher.register(literal("copyinv")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "target", scriptifyTarget ->
					copyInventory(context.getSource(), scriptifyTarget)))));

		dispatcher.register(literal("invsee")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> {
					ScriptifyRuntime.inventory().openInventory(
						context.getSource().getPlayerOrException(), Targets.one(context, "target"));
					return 1;
				})));

		dispatcher.register(literal("ec")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> {
					ScriptifyRuntime.inventory().openEnderChest(
						context.getSource().getPlayerOrException(), Targets.one(context, "target"));
					return 1;
				})));
	}

	// ------------------------------------------------------------------ death control

	private static int fakeTabStatus(CommandSourceStack source) {
		FakeTabService tab = ScriptifyRuntime.fakeTab();
		int shown = tab == null ? 0 : tab.size();
		int faces = tab == null ? 0 : tab.faceCount();
		int worn = tab == null ? 0 : tab.facesWorn();
		source.sendSuccess(() -> Msg.line(shown == 0
			? "Fake tab is off."
			: "Fake tab is showing " + shown + " extra player(s); " + worn
				+ " wearing a real skin, from a pool of " + faces + "."), false);
		return 1;
	}

	/**
	 * Sets how many fake players the tab list shows, and remembers it.
	 *
	 * <p>Written to the config as an exact count rather than a preset name, so a size typed with
	 * {@code /faketab count} survives a restart the same way a preset does.
	 */
	private static int setFakeTab(CommandSourceStack source, int count) {
		FakeTabService tab = ScriptifyRuntime.fakeTab();
		if (tab == null) {
			source.sendFailure(Msg.failure("Scriptify is not started yet."));
			return 0;
		}
		tab.setCount(count);

		var settings = ScriptifyRuntime.config().get().fakeTab;
		settings.enabled = count > 0;
		settings.count = count;
		ScriptifyRuntime.config().save();

		source.sendSuccess(() -> Msg.line(count == 0
			? "Fake tab turned off."
			: "Tab list padded to look like " + count + " extra player(s)."), true);
		return 1;
	}

	/**
	 * Removes dropped items from the ground.
	 *
	 * <p>The same thing as {@code /kill @e[type=item]}, which is what everybody actually types it for,
	 * without the selector. Discards rather than kills, so nothing fires a death or drops a second time.
	 *
	 * <p>Scoped to the caller's dimension, matching what that selector would have done. An optional
	 * radius keeps a clean-up from emptying the whole world when only the set needs clearing.
	 *
	 * @param radius blocks around the caller, or a negative number for the entire dimension.
	 */
	private static int clearGround(CommandSourceStack source, int radius) {
		ServerLevel level = source.getLevel();
		Vec3 origin = source.getPosition();
		double limit = radius < 0 ? -1 : (double) radius * radius;
		List<? extends ItemEntity> items = level.getEntities(EntityType.ITEM,
			item -> limit < 0 || item.position().distanceToSqr(origin) <= limit);
		for (ItemEntity item : items) {
			item.discard();
		}
		int cleared = items.size();
		source.sendSuccess(() -> Msg.line("Cleared " + cleared + " dropped item"
			+ (cleared == 1 ? "" : "s")
			+ (radius < 0 ? "" : " within " + radius + " blocks") + "."), true);
		return cleared;
	}

	/**
	 * Copies the caller's inventory onto a target.
	 *
	 * <p>Reports how much was copied rather than just succeeding, because "copied 0 items" is the
	 * useful answer when you ran it with an empty inventory by mistake.
	 */
	private static int copyInventory(CommandSourceStack source, ServerPlayer target) {
		ServerPlayer sender;
		try {
			sender = source.getPlayerOrException();
		} catch (CommandSyntaxException exception) {
			source.sendFailure(Msg.failure("Only a player has an inventory to copy."));
			return 0;
		}
		if (sender == target) {
			source.sendFailure(Msg.failure("That is already your inventory."));
			return 0;
		}
		int copied = InventoryCopyService.copy(sender, target, ScriptifyRuntime.backups());
		Sfx.kitEquip(target);
		source.sendSuccess(() -> Msg.line("Copied " + copied + " stack(s) to "
			+ target.getGameProfile().name() + ". Their old inventory was snapshotted."), true);
		return 1;
	}

	private static void registerDeath(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("ondeath")
			.requires(source -> has(source, PermissionService.DEATH))
			.executes(context -> {
				context.getSource().sendSuccess(() -> Msg.line(
					"On-death control is " + (ScriptifyRuntime.deathControl().isEnabled() ? "enabled" : "disabled")
						+ "; staged players: " + ScriptifyRuntime.deathControl().staged().size()), false);
				return 1;
			})
			.then(literal("on").executes(context -> setOnDeath(context.getSource(), true)))
			.then(literal("off").executes(context -> setOnDeath(context.getSource(), false)))
			.then(literal("restore")
				// No argument restores the caller — the staged player is usually the one who needs it, and
				// they cannot be expected to type their own name while stuck as an invisible spectator.
				.executes(context -> restoreStaged(context.getSource(), context.getSource().getPlayerOrException()))
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> restoreStaged(context.getSource(),
						Targets.one(context, "target"))))));
	}

	private static int restoreStaged(CommandSourceStack source, ServerPlayer target) {
		if (!ScriptifyRuntime.deathControl().restore(target, GameType.SURVIVAL)) {
			source.sendFailure(Msg.failure(
				target.getGameProfile().name() + " is not staged as having left."));
			return 0;
		}
		source.sendSuccess(() -> Msg.line(
			"Restored " + target.getGameProfile().name() + " into the scene."), true);
		return 1;
	}

	private static int setOnDeath(CommandSourceStack source, boolean enabled) {
		ScriptifyRuntime.deathControl().setEnabled(enabled);
		source.sendSuccess(() -> Msg.line("On-death control set to " + enabled + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ sudo

	private static void registerSudo(CommandDispatcher<CommandSourceStack> dispatcher) {
		// One line split by hand: the target argument is greedy, so "command" and "say" as children of it
		// could never be reached - everything after the target was read as more target names.
		dispatcher.register(literal("sudo")
			.requires(source -> has(source, PermissionService.ADMIN))
			.then(argument("line", StringArgumentType.greedyString())
				.suggests((context, builder) -> {
					String typed = builder.getRemaining();
					int space = typed.indexOf(' ');
					if (space < 0) {
						return Targets.SUGGEST.getSuggestions(context, builder);
					}
					return typed.indexOf(' ', space + 1) < 0
						? net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("command", "say"),
							builder.createOffset(builder.getStart() + space + 1))
						: builder.buildFuture();
				})
				.executes(context -> sudo(context))));
	}

	/** {@code <player> command <...>} or {@code <player> say <...>}. */
	private static int sudo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String[] parts = StringArgumentType.getString(context, "line").trim().split("\\s+", 3);
		if (parts.length < 3 || !(parts[1].equalsIgnoreCase("command") || parts[1].equalsIgnoreCase("say"))) {
			context.getSource().sendFailure(Msg.failure("Usage: /sudo <player> command <...> or /sudo <player> say <...>"));
			return 0;
		}
		ServerPlayer target = Targets.token(context.getSource(), parts[0]).get(0);
		if (parts[1].equalsIgnoreCase("say")) {
			ScriptifyRuntime.sudo().say(target, parts[2]);
			return 1;
		}
		String command = parts[2];
		if (!ScriptifyRuntime.sudo().runCommand(target, command)) {
			context.getSource().sendFailure(Msg.failure("That command could not be run."));
			return 0;
		}
		context.getSource().sendSuccess(() -> Msg.line(
			"Ran '" + command + "' as " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ rollback

	/**
	 * {@code /inventoryrollback} — the successor to {@code /backupinv}.
	 *
	 * <p>{@code /backupinv} stays registered and working; it opens the same history. Renaming a
	 * command somebody has in muscle memory, or in a macro, is not worth the tidiness.
	 *
	 * <p>Player arguments are greedy strings with server-side suggestions, like every other Scriptify
	 * command — a custom argument type would not survive the trip to a vanilla client.
	 */
	private static void registerInventoryRollback(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("inventoryrollback")
			.requires(source -> has(source, PermissionService.INVENTORY))

			.executes(context -> openHistory(context, null))

			.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> openHistory(context, Targets.one(context, "target"))))

			.then(literal("save")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					var snapshot = ScriptifyRuntime.rollback()
						.capture(player, scriptify.rollback.Snapshot.REASON_MANUAL, "");
					context.getSource().sendSuccess(
						() -> Msg.line("Snapshot saved: " + snapshot.label()), false);
					return 1;
				})
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> {
						ServerPlayer target = Targets.one(context, "target");
						var snapshot = ScriptifyRuntime.rollback()
							.capture(target, scriptify.rollback.Snapshot.REASON_MANUAL, "");
						context.getSource().sendSuccess(() -> Msg.line("Saved a snapshot of "
							+ target.getGameProfile().name() + "'s state: " + snapshot.label()), true);
						return 1;
					})))

			// Restoring without the menu. The timeline is the better interface for a person choosing
			// between twenty snapshots, and no interface at all for a console, a script or a scheduled
			// job - which is where "put everyone back how they were" actually gets run from.
			// The snapshot comes before the player, which reads backwards until you know why: player
			// arguments here are greedy strings, so they have to be last or they swallow whatever
			// follows. Putting the id first is the only ordering that can parse.
			.then(literal("restore")
				.then(literal("latest")
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> restore(context, Targets.one(context, "target"), null))))
				.then(argument("id", StringArgumentType.word())
					.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
						.executes(context -> restore(context, Targets.one(context, "target"),
							StringArgumentType.getString(context, "id"))))))

			.then(literal("undo")
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					if (ScriptifyRuntime.rollback().undo(player)) {
						context.getSource().sendSuccess(
							() -> Msg.line("Put you back to before your last restore."), false);
						return 1;
					}
					context.getSource().sendFailure(
						Msg.failure("You have not restored anything this session."));
					return 0;
				}))

			.then(literal("search")
				.then(argument("item", StringArgumentType.greedyString())
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						String needle = StringArgumentType.getString(context, "item");
						var hits = ScriptifyRuntime.rollback().search(player.getUUID(), needle);
						if (hits.isEmpty()) {
							context.getSource().sendSuccess(
								() -> Msg.line("Nothing in your history held '" + needle + "'."), false);
							return 0;
						}
						context.getSource().sendSuccess(() -> Msg.line(
							hits.size() + " snapshot(s) held '" + needle + "', newest "
								+ hits.get(0).label() + "."), false);
						return hits.size();
					})))

			.then(literal("purge")
				.then(argument("target", Targets.arg()).suggests(Targets.SUGGEST)
					.executes(context -> {
						ServerPlayer target = Targets.one(context, "target");
						ScriptifyRuntime.rollback().store().purge(target.getUUID());
						context.getSource().sendSuccess(() -> Msg.line("Cleared "
							+ target.getGameProfile().name() + "'s rollback history."), true);
						return 1;
					}))));
	}

	/**
	 * Restores a snapshot onto a player from the command line.
	 *
	 * @param id the snapshot id, or null for the most recent one. "Latest" is what somebody actually
	 *           wants nine times out of ten, and it saves copying a sixteen-digit id out of a menu.
	 */
	private static int restore(CommandContext<CommandSourceStack> context, ServerPlayer target,
			String id) {
		var rollback = ScriptifyRuntime.rollback();
		if (rollback == null) {
			context.getSource().sendFailure(Msg.failure("Scriptify is still starting up."));
			return 0;
		}
		String chosen = id;
		if (chosen == null) {
			var all = rollback.list(target.getUUID());
			if (all.isEmpty()) {
				context.getSource().sendFailure(Msg.failure(
					"There is nothing recorded for " + target.getGameProfile().name() + "."));
				return 0;
			}
			// list() is newest first.
			chosen = all.get(0).id();
		}
		if (!rollback.restore(target, chosen)) {
			context.getSource().sendFailure(Msg.failure("No snapshot with that id."));
			return 0;
		}
		String restored = chosen;
		context.getSource().sendSuccess(() -> Msg.line("Restored " + restored + " onto "
			+ target.getGameProfile().name() + ". Undo with /inventoryrollback undo."), true);
		return 1;
	}

	/** Opens the timeline for somebody, defaulting to the caller. */
	private static int openHistory(CommandContext<CommandSourceStack> context, ServerPlayer target)
			throws CommandSyntaxException {
		ServerPlayer viewer = context.getSource().getPlayerOrException();
		if (ScriptifyRuntime.rollback() == null) {
			context.getSource().sendFailure(Msg.failure("Scriptify is still starting up."));
			return 0;
		}
		ServerPlayer subject = target == null ? viewer : target;
		scriptify.ui.RollbackMenu.open(viewer, subject.getUUID(),
			subject.getGameProfile().name(), 0);
		return 1;
	}

	// ------------------------------------------------------------------ helpers

	private static boolean has(CommandSourceStack source, String node) {
		return ScriptifyBootstrap.context() != null && ScriptifyBootstrap.context().permissions().has(source, node);
	}
}
