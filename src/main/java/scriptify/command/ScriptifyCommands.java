package scriptify.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import scriptify.core.ScriptifyBootstrap;
import scriptify.feature.FeatureManager;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;
import scriptify.ui.BackupMenu;
import scriptify.ui.KitsMenu;
import scriptify.ui.MainMenu;
import scriptify.ui.PotionMenu;
import scriptify.ui.RandomFillMenu;

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
		registerGamemodes(dispatcher);
		registerToggles(dispatcher);
		registerPlayerActions(dispatcher);
		registerHealth(dispatcher);
		registerInventoryTools(dispatcher);
		registerDeath(dispatcher);
		registerSudo(dispatcher);
	}

	// ------------------------------------------------------------------ core

	private static void registerCore(CommandDispatcher<CommandSourceStack> dispatcher) {
		// Bare /scriptify opens the dashboard; the sub-commands stay for console use.
		dispatcher.register(literal("scriptify")
			.executes(context -> openMain(context.getSource()))
			.then(literal("version").executes(context -> {
				context.getSource().sendSuccess(() -> Component.literal("Scriptify 1.0.0 server-side toolkit."), false);
				return 1;
			}))
			.then(literal("reload")
				.requires(source -> has(source, PermissionService.ADMIN))
				.executes(context -> {
					if (!ScriptifyBootstrap.reload()) {
						context.getSource().sendFailure(Component.literal("Scriptify is not started yet."));
						return 0;
					}
					ScriptifyRuntime.config().load();
					context.getSource().sendSuccess(() -> Component.literal("Scriptify reloaded."), true);
					return 1;
				}))
			.then(literal("gui")
				.requires(source -> has(source, PermissionService.GUI_OPEN))
				.executes(context -> openMain(context.getSource()))));
	}

	private static int openMain(CommandSourceStack source) {
		try {
			MainMenu.open(ScriptifyBootstrap.context(), source.getPlayerOrException());
			return 1;
		} catch (CommandSyntaxException exception) {
			source.sendFailure(Component.literal("Only players can open the Scriptify menu."));
			return 0;
		}
	}

	// ------------------------------------------------------------------ kits

	private static void registerKits(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("skits")
			.requires(source -> has(source, PermissionService.KITS))
			.executes(context -> {
				try {
					KitsMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException(), 0);
					return 1;
				} catch (CommandSyntaxException exception) {
					context.getSource().sendFailure(Component.literal("Only players can open the kit menu."));
					return 0;
				}
			})
			.then(literal("create")
				.then(argument("name", StringArgumentType.word())
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						String name = StringArgumentType.getString(context, "name");
						try {
							ScriptifyRuntime.kits().create(name, player);
						} catch (IllegalArgumentException exception) {
							context.getSource().sendFailure(Component.literal(exception.getMessage()));
							return 0;
						}
						context.getSource().sendSuccess(() -> Component.literal(
							"Created kit '" + name + "' from your inventory."), true);
						return 1;
					})))
			.then(literal("apply")
				.then(argument("name", StringArgumentType.word())
					.executes(context -> applyKit(context.getSource(),
						StringArgumentType.getString(context, "name"),
						context.getSource().getPlayerOrException()))
					.then(argument("target", EntityArgument.player())
						.executes(context -> applyKit(context.getSource(),
							StringArgumentType.getString(context, "name"),
							EntityArgument.getPlayer(context, "target"))))))
			.then(literal("delete")
				.then(argument("name", StringArgumentType.word())
					.executes(context -> {
						String name = StringArgumentType.getString(context, "name");
						boolean removed = ScriptifyRuntime.kits().delete(name);
						context.getSource().sendSuccess(() -> Component.literal(
							(removed ? "Deleted kit '" : "No kit named '") + name + "'."), true);
						return removed ? 1 : 0;
					})))
			.then(literal("list")
				.executes(context -> {
					context.getSource().sendSuccess(() -> Component.literal(
						"Kits: " + ScriptifyRuntime.kits().names()), false);
					return 1;
				})));
	}

	private static int applyKit(CommandSourceStack source, String name, ServerPlayer target) {
		if (!ScriptifyRuntime.kits().apply(name, target)) {
			source.sendFailure(Component.literal("Unknown kit '" + name + "'."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
			"Applied kit '" + name + "' to " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ nicknames

	private static void registerNick(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("nick")
			.requires(source -> has(source, PermissionService.NICK))
			.then(argument("nickname", StringArgumentType.word())
				.executes(context -> setNick(context.getSource(),
					context.getSource().getPlayerOrException(),
					StringArgumentType.getString(context, "nickname")))
				.then(argument("target", EntityArgument.player())
					.executes(context -> setNick(context.getSource(),
						EntityArgument.getPlayer(context, "target"),
						StringArgumentType.getString(context, "nickname"))))));

		dispatcher.register(literal("randomnick")
			.requires(source -> has(source, PermissionService.NICK))
			.executes(context -> randomNick(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", EntityArgument.player())
				.executes(context -> randomNick(context.getSource(), EntityArgument.getPlayer(context, "target")))));

		dispatcher.register(literal("unnick")
			.requires(source -> has(source, PermissionService.NICK))
			.executes(context -> clearNick(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", EntityArgument.player())
				.executes(context -> clearNick(context.getSource(), EntityArgument.getPlayer(context, "target")))));
	}

	private static int setNick(CommandSourceStack source, ServerPlayer target, String nickname) {
		try {
			String applied = ScriptifyRuntime.nick().set(target, nickname);
			source.sendSuccess(() -> Component.literal(
				"Nicknamed " + target.getGameProfile().name() + " as " + applied + "."), true);
			return 1;
		} catch (IllegalArgumentException exception) {
			source.sendFailure(Component.literal(exception.getMessage()));
			return 0;
		}
	}

	private static int randomNick(CommandSourceStack source, ServerPlayer target) {
		String nickname = ScriptifyRuntime.nick().random(target);
		source.sendSuccess(() -> Component.literal(
			"Random nickname for " + target.getGameProfile().name() + ": " + nickname), true);
		return 1;
	}

	private static int clearNick(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.nick().clear(target);
		source.sendSuccess(() -> Component.literal(
			"Removed nickname from " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ skins

	private static void registerSkin(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("skin")
			.requires(source -> has(source, PermissionService.SKIN))
			.then(literal("set")
				.then(argument("source", StringArgumentType.word())
					.executes(context -> setSkin(context.getSource(),
						StringArgumentType.getString(context, "source"),
						context.getSource().getPlayerOrException()))
					.then(argument("target", EntityArgument.player())
						.executes(context -> setSkin(context.getSource(),
							StringArgumentType.getString(context, "source"),
							EntityArgument.getPlayer(context, "target"))))))
			// clear strips the skin entirely; reset restores the account's own Mojang skin.
			.then(literal("clear")
				.executes(context -> clearSkin(context.getSource(), context.getSource().getPlayerOrException()))
				.then(argument("target", EntityArgument.player())
					.executes(context -> clearSkin(context.getSource(), EntityArgument.getPlayer(context, "target")))))
			.then(literal("reset")
				.executes(context -> resetSkin(context.getSource(), context.getSource().getPlayerOrException()))
				.then(argument("target", EntityArgument.player())
					.executes(context -> resetSkin(context.getSource(), EntityArgument.getPlayer(context, "target"))))));
	}

	private static int setSkin(CommandSourceStack source, String skinSource, ServerPlayer target) {
		try {
			ScriptifyRuntime.skin().copySkin(skinSource, target);
			source.sendSuccess(() -> Component.literal(
				"Applied " + skinSource + "'s skin to " + target.getGameProfile().name() + "."), true);
			return 1;
		} catch (Exception exception) {
			ScriptifyBootstrap.context().logger().warn("Skin lookup failed for {}.", skinSource, exception);
			source.sendFailure(Component.literal("Could not fetch a signed skin for " + skinSource + "."));
			return 0;
		}
	}

	private static int clearSkin(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.skin().clearSkin(target);
		source.sendSuccess(() -> Component.literal(
			"Cleared the skin override on " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	private static int resetSkin(CommandSourceStack source, ServerPlayer target) {
		try {
			ScriptifyRuntime.skin().resetSkin(target);
			source.sendSuccess(() -> Component.literal(
				"Restored " + target.getGameProfile().name() + "'s own skin."), true);
			return 1;
		} catch (Exception exception) {
			source.sendFailure(Component.literal("Could not fetch that account's original skin."));
			return 0;
		}
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
			.then(argument("target", EntityArgument.player())
				.executes(context -> setGameMode(context.getSource(), EntityArgument.getPlayer(context, "target"), mode))));
	}

	private static int setGameMode(CommandSourceStack source, ServerPlayer target, GameType mode) {
		target.setGameMode(mode);
		source.sendSuccess(() -> Component.literal(
			"Set " + target.getGameProfile().name() + " to " + mode.getName() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ toggles

	private static void registerToggles(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("pvp")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				context.getSource().sendSuccess(() -> Component.literal(
					"PvP is " + (ScriptifyRuntime.features().isPvpEnabled() ? "on" : "off") + "."), false);
				return 1;
			})
			.then(literal("on").executes(context -> setPvp(context.getSource(), true)))
			.then(literal("off").executes(context -> setPvp(context.getSource(), false))));

		dispatcher.register(literal("freeze")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> setFrozen(context.getSource(), context.getSource().getPlayerOrException(), true))
			.then(argument("target", EntityArgument.player())
				.executes(context -> setFrozen(context.getSource(), EntityArgument.getPlayer(context, "target"), true))));

		dispatcher.register(literal("unfreeze")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> setFrozen(context.getSource(), context.getSource().getPlayerOrException(), false))
			.then(argument("target", EntityArgument.player())
				.executes(context -> setFrozen(context.getSource(), EntityArgument.getPlayer(context, "target"), false))));

		dispatcher.register(literal("invis")
			.requires(source -> has(source, PermissionService.PLAYER))
			.then(literal("on").executes(context -> setInvis(context.getSource(),
				context.getSource().getPlayerOrException(), true))
				.then(argument("target", EntityArgument.player())
					.executes(context -> setInvis(context.getSource(), EntityArgument.getPlayer(context, "target"), true))))
			.then(literal("off").executes(context -> setInvis(context.getSource(),
				context.getSource().getPlayerOrException(), false))
				.then(argument("target", EntityArgument.player())
					.executes(context -> setInvis(context.getSource(), EntityArgument.getPlayer(context, "target"), false)))));

		// Mirrors the reference mod's command shape: global / set / fall / armor / get.
		dispatcher.register(literal("immortal")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				context.getSource().sendSuccess(() -> Component.literal(
					describeImmortality(ScriptifyRuntime.features(), player)), false);
				return 1;
			})
			.then(literal("on")
				.executes(context -> setImmortal(context.getSource(),
					java.util.List.of(context.getSource().getPlayerOrException()), true, FeatureManager.FOREVER))
				.then(argument("targets", EntityArgument.players())
					.executes(context -> setImmortal(context.getSource(),
						EntityArgument.getPlayers(context, "targets"), true, FeatureManager.FOREVER))))
			.then(literal("off")
				.executes(context -> setImmortal(context.getSource(),
					java.util.List.of(context.getSource().getPlayerOrException()), false, FeatureManager.FOREVER))
				.then(argument("targets", EntityArgument.players())
					.executes(context -> setImmortal(context.getSource(),
						EntityArgument.getPlayers(context, "targets"), false, FeatureManager.FOREVER))))
			.then(literal("set")
				.then(argument("targets", EntityArgument.players())
					.executes(context -> setImmortal(context.getSource(),
						EntityArgument.getPlayers(context, "targets"), true, FeatureManager.FOREVER))
					// -1 means no limit, anything higher is a number of seconds.
					.then(argument("seconds", IntegerArgumentType.integer(-1))
						.executes(context -> setImmortal(context.getSource(),
							EntityArgument.getPlayers(context, "targets"), true,
							IntegerArgumentType.getInteger(context, "seconds"))))))
			.then(literal("global")
				.executes(context -> {
					FeatureManager features = ScriptifyRuntime.features();
					context.getSource().sendSuccess(() -> Component.literal(
						"Global immortality: " + features.isGlobalImmortal()
							+ (features.isGlobalImmortal() && features.globalSecondsRemaining() > 0
								? " (" + features.globalSecondsRemaining() + "s left)" : "")), false);
					return 1;
				})
				.then(argument("enabled", BoolArgumentType.bool())
					.executes(context -> setGlobalImmortal(context.getSource(),
						BoolArgumentType.getBool(context, "enabled"), FeatureManager.FOREVER))
					.then(argument("seconds", IntegerArgumentType.integer(-1))
						.executes(context -> setGlobalImmortal(context.getSource(),
							BoolArgumentType.getBool(context, "enabled"),
							IntegerArgumentType.getInteger(context, "seconds"))))))
			.then(literal("fall")
				.then(argument("enabled", BoolArgumentType.bool())
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						boolean enabled = BoolArgumentType.getBool(context, "enabled");
						ScriptifyRuntime.features().setFallImmune(player, enabled);
						context.getSource().sendSuccess(() -> Component.literal(
							"Fall immunity " + (enabled ? "enabled" : "disabled") + " for "
								+ player.getGameProfile().name() + "."), true);
						return 1;
					})))
			.then(literal("armor")
				.then(argument("enabled", BoolArgumentType.bool())
					.executes(context -> {
						ServerPlayer player = context.getSource().getPlayerOrException();
						boolean enabled = BoolArgumentType.getBool(context, "enabled");
						ScriptifyRuntime.features().setArmorProtected(player, enabled);
						context.getSource().sendSuccess(() -> Component.literal(
							"Armour protection " + (enabled ? "enabled" : "disabled") + " for "
								+ player.getGameProfile().name() + "."), true);
						return 1;
					})))
			.then(literal("void")
				.then(argument("enabled", BoolArgumentType.bool())
					.executes(context -> {
						boolean enabled = BoolArgumentType.getBool(context, "enabled");
						ScriptifyRuntime.features().setAllowVoidImmortality(enabled);
						context.getSource().sendSuccess(() -> Component.literal(
							"Immortality in the void set to " + enabled + "."), true);
						return 1;
					})))
			.then(literal("get")
				.then(argument("target", EntityArgument.player())
					.executes(context -> {
						ServerPlayer target = EntityArgument.getPlayer(context, "target");
						context.getSource().sendSuccess(() -> Component.literal(
							target.getGameProfile().name() + " — "
								+ describeImmortality(ScriptifyRuntime.features(), target)), false);
						return 1;
					}))));

		dispatcher.register(literal("vanish")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				boolean value = !ScriptifyRuntime.features().isVanished(player);
				ScriptifyRuntime.features().setVanished(player, value);
				context.getSource().sendSuccess(() -> Component.literal("Vanish " + (value ? "enabled" : "disabled") + "."), false);
				return 1;
			}));
	}

	private static int setPvp(CommandSourceStack source, boolean enabled) {
		ScriptifyRuntime.features().setPvp(enabled);
		source.sendSuccess(() -> Component.literal("PvP turned " + (enabled ? "on" : "off") + "."), true);
		return 1;
	}

	private static int setFrozen(CommandSourceStack source, ServerPlayer target, boolean frozen) {
		ScriptifyRuntime.features().setFrozen(target, frozen);
		source.sendSuccess(() -> Component.literal(
			(frozen ? "Froze " : "Unfroze ") + target.getGameProfile().name() + "."), true);
		return 1;
	}

	private static int setInvis(CommandSourceStack source, ServerPlayer target, boolean enabled) {
		ScriptifyRuntime.features().setInvisible(target, enabled);
		source.sendSuccess(() -> Component.literal(
			"Invisibility for " + target.getGameProfile().name() + " " + (enabled ? "enabled" : "disabled") + "."), true);
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
		source.sendSuccess(() -> Component.literal(
			"Immortality " + (enabled ? "enabled" : "disabled") + " for " + names + suffix + "."), true);
		return targets.size();
	}

	private static int setGlobalImmortal(CommandSourceStack source, boolean enabled, int seconds) {
		ScriptifyRuntime.features().setGlobalImmortal(enabled, seconds);
		String suffix = enabled && seconds > 0 ? " for " + seconds + "s" : "";
		source.sendSuccess(() -> Component.literal(
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
				.then(argument("target", EntityArgument.player())
					.executes(context -> resetHealth(context.getSource(), EntityArgument.getPlayer(context, "target")))))
			.then(argument("hearts", DoubleArgumentType.doubleArg(0.5D, 1024.0D))
				.executes(context -> setHealth(context.getSource(),
					context.getSource().getPlayerOrException(),
					DoubleArgumentType.getDouble(context, "hearts")))
				.then(argument("target", EntityArgument.player())
					.executes(context -> setHealth(context.getSource(),
						EntityArgument.getPlayer(context, "target"),
						DoubleArgumentType.getDouble(context, "hearts"))))));
	}

	private static int setHealth(CommandSourceStack source, ServerPlayer target, double hearts) {
		double applied = ScriptifyRuntime.features().setMaxHealth(target, hearts);
		source.sendSuccess(() -> Component.literal(
			"Set " + target.getGameProfile().name() + " to " + applied + " heart(s)."), true);
		return 1;
	}

	private static int resetHealth(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.features().resetMaxHealth(target);
		source.sendSuccess(() -> Component.literal(
			"Reset " + target.getGameProfile().name() + " to 10 hearts."), true);
		return 1;
	}

	// ------------------------------------------------------------------ player actions

	private static void registerPlayerActions(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("heal")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> heal(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", EntityArgument.player())
				.executes(context -> heal(context.getSource(), EntityArgument.getPlayer(context, "target")))));

		dispatcher.register(literal("feed")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> feed(context.getSource(), context.getSource().getPlayerOrException()))
			.then(argument("target", EntityArgument.player())
				.executes(context -> feed(context.getSource(), EntityArgument.getPlayer(context, "target")))));

		dispatcher.register(literal("totem")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> giveTotems(context.getSource(), context.getSource().getPlayerOrException(), 1))
			.then(argument("count", IntegerArgumentType.integer(1, 576))
				.executes(context -> giveTotems(context.getSource(),
					context.getSource().getPlayerOrException(),
					IntegerArgumentType.getInteger(context, "count")))
				.then(argument("target", EntityArgument.player())
					.executes(context -> giveTotems(context.getSource(),
						EntityArgument.getPlayer(context, "target"),
						IntegerArgumentType.getInteger(context, "count"))))));

		dispatcher.register(literal("top")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				if (!ScriptifyRuntime.features().teleportToTop(player)) {
					context.getSource().sendFailure(Component.literal("Nothing solid above you."));
					return 0;
				}
				context.getSource().sendSuccess(() -> Component.literal("Teleported to the surface."), false);
				return 1;
			}));

		dispatcher.register(literal("potion")
			.requires(source -> has(source, PermissionService.PLAYER))
			.executes(context -> {
				try {
					PotionMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException());
					return 1;
				} catch (CommandSyntaxException exception) {
					context.getSource().sendFailure(Component.literal("Only players can open the potion menu."));
					return 0;
				}
			})
			.then(literal("clear")
				.executes(context -> {
					scriptify.feature.PotionPresets.clear(context.getSource().getPlayerOrException());
					context.getSource().sendSuccess(() -> Component.literal("Cleared all effects."), false);
					return 1;
				}))
			.then(argument("preset", StringArgumentType.word())
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					String preset = StringArgumentType.getString(context, "preset");
					if (!scriptify.feature.PotionPresets.apply(player, preset)) {
						context.getSource().sendFailure(Component.literal(
							"Unknown preset. Available: " + scriptify.feature.PotionPresets.all().keySet()));
						return 0;
					}
					context.getSource().sendSuccess(() -> Component.literal("Applied the " + preset + " preset."), false);
					return 1;
				})));
	}

	private static int heal(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.features().heal(target);
		source.sendSuccess(() -> Component.literal("Healed " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	private static int feed(CommandSourceStack source, ServerPlayer target) {
		ScriptifyRuntime.features().feed(target);
		source.sendSuccess(() -> Component.literal("Fed " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	private static int giveTotems(CommandSourceStack source, ServerPlayer target, int count) {
		ScriptifyRuntime.features().giveTotems(target, count);
		source.sendSuccess(() -> Component.literal(
			"Gave " + count + " totem(s) to " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ inventory tools

	private static void registerInventoryTools(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("durability")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.then(literal("max").executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				int changed = ScriptifyRuntime.features().repairAll(player);
				context.getSource().sendSuccess(() -> Component.literal("Repaired " + changed + " item(s)."), false);
				return 1;
			}))
			.then(literal("random").executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				int changed = ScriptifyRuntime.features().randomiseDurability(player);
				context.getSource().sendSuccess(() -> Component.literal("Randomised " + changed + " item(s)."), false);
				return 1;
			})));

		dispatcher.register(literal("restock")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				int restocked = ScriptifyRuntime.features().restock(player);
				context.getSource().sendSuccess(() -> Component.literal("Restocked " + restocked + " stack(s)."), false);
				return 1;
			}));

		dispatcher.register(literal("randomfill")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.executes(context -> {
				try {
					RandomFillMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException());
					return 1;
				} catch (CommandSyntaxException exception) {
					context.getSource().sendFailure(Component.literal("Only players can open the random-fill menu."));
					return 0;
				}
			}));

		dispatcher.register(literal("backupinv")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.executes(context -> {
				try {
					BackupMenu.open(ScriptifyBootstrap.context(), context.getSource().getPlayerOrException(), 0);
					return 1;
				} catch (CommandSyntaxException exception) {
					context.getSource().sendFailure(Component.literal("Only players can open the backup menu."));
					return 0;
				}
			}));

		dispatcher.register(literal("invsee")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.then(argument("target", EntityArgument.player())
				.executes(context -> {
					ScriptifyRuntime.inventory().openInventory(
						context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "target"));
					return 1;
				})));

		dispatcher.register(literal("ec")
			.requires(source -> has(source, PermissionService.INVENTORY))
			.then(argument("target", EntityArgument.player())
				.executes(context -> {
					ScriptifyRuntime.inventory().openEnderChest(
						context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "target"));
					return 1;
				})));
	}

	// ------------------------------------------------------------------ death control

	private static void registerDeath(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("ondeath")
			.requires(source -> has(source, PermissionService.DEATH))
			.executes(context -> {
				context.getSource().sendSuccess(() -> Component.literal(
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
				.then(argument("target", EntityArgument.player())
					.executes(context -> restoreStaged(context.getSource(),
						EntityArgument.getPlayer(context, "target"))))));
	}

	private static int restoreStaged(CommandSourceStack source, ServerPlayer target) {
		if (!ScriptifyRuntime.deathControl().restore(target, GameType.SURVIVAL)) {
			source.sendFailure(Component.literal(
				target.getGameProfile().name() + " is not staged as having left."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
			"Restored " + target.getGameProfile().name() + " into the scene."), true);
		return 1;
	}

	private static int setOnDeath(CommandSourceStack source, boolean enabled) {
		ScriptifyRuntime.deathControl().setEnabled(enabled);
		source.sendSuccess(() -> Component.literal("On-death control set to " + enabled + "."), true);
		return 1;
	}

	// ------------------------------------------------------------------ sudo

	private static void registerSudo(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("sudo")
			.requires(source -> has(source, PermissionService.ADMIN))
			.then(argument("target", EntityArgument.player())
				.then(literal("command")
					.then(argument("command", StringArgumentType.greedyString())
						.executes(context -> {
							ServerPlayer target = EntityArgument.getPlayer(context, "target");
							String command = StringArgumentType.getString(context, "command");
							if (!ScriptifyRuntime.sudo().runCommand(target, command)) {
								context.getSource().sendFailure(Component.literal("That command could not be run."));
								return 0;
							}
							context.getSource().sendSuccess(() -> Component.literal(
								"Ran '" + command + "' as " + target.getGameProfile().name() + "."), true);
							return 1;
						})))
				.then(literal("say")
					.then(argument("message", StringArgumentType.greedyString())
						.executes(context -> {
							ServerPlayer target = EntityArgument.getPlayer(context, "target");
							ScriptifyRuntime.sudo().say(target, StringArgumentType.getString(context, "message"));
							return 1;
						})))));
	}

	// ------------------------------------------------------------------ helpers

	private static boolean has(CommandSourceStack source, String node) {
		return ScriptifyBootstrap.context() != null && ScriptifyBootstrap.context().permissions().has(source, node);
	}
}
