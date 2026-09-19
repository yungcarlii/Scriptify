package scriptify.bots;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;
import scriptify.core.ScriptifyBootstrap;
import scriptify.api.ScriptifyModule;
import scriptify.events.ActionRegistry;
import scriptify.events.TriggerRegistry;
import scriptify.permission.PermissionService;
import scriptify.bots.api.SBotsApi;
import scriptify.ui.Msg;

/**
 * Wires the bots into Scriptify's event engine, and fills the gaps in their command tree.
 *
 * <p>The event actions are the point of it. They let a scripted scene say "spawn three bots here,
 * make them fight, then remove them" from an event file, which is what Scriptify is for. Each action
 * maps to exactly one facade call and reports success as a boolean, so a step that fails stops the
 * event rather than being swallowed.</p>
 *
 * <p>The commands here are only the ones the bot tree has no text form for: settings and group
 * membership, both of which it exposes solely through menus and proximity. Neither can go in a
 * command block or an event file, which is where a scripted scene needs them.</p>
 */
public final class BotsModule implements ScriptifyModule {

	/** Bot names, offered live so a script author is not typing from memory. */
	private static final SuggestionProvider<CommandSourceStack> BOT_NAMES =
		(context, builder) -> SharedSuggestionProvider.suggest(SBotsApi.names(), builder);

	/** The {@link scriptify.bots.npc.NpcSettings} field names, as the facade accepts them. */
	private static final List<String> SETTING_KEYS = List.of(
		"hittable", "collidable", "crouching", "listed", "immortal",
		"dropsOnDeath", "action", "kit");

	private static final SuggestionProvider<CommandSourceStack> SETTING_KEY =
		(context, builder) -> SharedSuggestionProvider.suggest(SETTING_KEYS, builder);

	@Override
	public String id() {
		return "bots";
	}

	@Override
	public void initialize(ScriptifyContext context) {
		context.logger().info("Bots ready: /bots and the bot_* event actions are live.");
	}

	// ---------------------------------------------------------------- commands

	/**
	 * Only the parts the bot command tree does not already cover.
	 *
	 * <p>{@code BotCommand} owns {@code /bots} - spawning, the roster, kits, factions, scenarios and
	 * the control panel. Registering a second {@code list} or {@code spawn} here would merge into that
	 * tree and leave which one runs decided by registration order, so this adds only names nothing
	 * else claims.</p>
	 */
	@Override
	public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
			CommandBuildContext buildContext) {
		dispatcher.register(literal("bots")
			// Naming a bot and a setting outright. The bot menu can do this too, but a menu cannot be
			// put in a command block or an event file, and /bots npc group add only takes whoever
			// happens to be standing nearby.
			// Editing by hitting the bot. A mode rather than a permanent behaviour, because a server
			// where every punch opened a menu would be unusable.
			.then(literal("edit")
				.requires(BotsModule::allowed)
				.executes(context -> {
					ServerPlayer player = context.getSource().getPlayer();
					if (player == null) {
						context.getSource().sendFailure(Msg.failure("Only a player can edit bots."));
						return 0;
					}
					return scriptify.bots.npc.EditMode.toggle(player) ? 1 : 0;
				})
				.then(literal("on").executes(context -> setEdit(context.getSource(), true)))
				.then(literal("off").executes(context -> setEdit(context.getSource(), false))))
			.then(literal("set")
				.requires(BotsModule::allowed)
				.then(argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
					.then(argument("key", StringArgumentType.word()).suggests(SETTING_KEY)
						.then(argument("value", StringArgumentType.word())
							.executes(context -> {
								String name = StringArgumentType.getString(context, "name");
								String key = StringArgumentType.getString(context, "key");
								String value = StringArgumentType.getString(context, "value");
								return report(context.getSource(),
									SBotsApi.setSetting("bot", name, key, value),
									key + " set to " + value + " for " + name + ".",
									"Could not set " + key + " to " + value + ".");
							})))))
			.then(literal("group")
				.requires(BotsModule::allowed)
				.then(argument("group", StringArgumentType.word())
					.then(argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
						.executes(context -> {
							String group = StringArgumentType.getString(context, "group");
							String name = StringArgumentType.getString(context, "name");
							return report(context.getSource(), SBotsApi.addToGroup(group, name),
								name + " joined " + group + ".",
								"Could not add " + name + " to " + group + ".");
						}))))
			.then(literal("ungroup")
				.requires(BotsModule::allowed)
				.then(argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
					.executes(context -> {
						String name = StringArgumentType.getString(context, "name");
						return report(context.getSource(), SBotsApi.removeFromGroup(name),
							name + " left its group.", name + " was not in a group.");
					})))
			.then(literal("skin")
				.requires(BotsModule::allowed)
				.then(literal("random")
					.then(argument("name", StringArgumentType.word()).suggests(BOT_NAMES)
						.executes(context -> {
							String name = StringArgumentType.getString(context, "name");
							return report(context.getSource(), SBotsApi.randomiseSkin(name),
								"Gave " + name + " a new look.",
								SBotsApi.skinPoolSize() == 0
									? "The skin pool is empty; nothing to draw from."
									: "Could not skin " + name + ".");
						})))
				.then(literal("add")
					.then(argument("label", StringArgumentType.word())
						.then(argument("value", StringArgumentType.string())
							.then(argument("signature", StringArgumentType.string())
								.executes(context -> {
									String label = StringArgumentType.getString(context, "label");
									boolean done = SBotsApi.addSkinToPool(label,
										StringArgumentType.getString(context, "value"),
										StringArgumentType.getString(context, "signature"));
									return report(context.getSource(), done,
										"Added " + label + " to the skin pool.",
										"That blob is unusable; both value and signature are required.");
								})))))
				.then(literal("pool").executes(context -> {
					int size = SBotsApi.skinPoolSize();
					context.getSource().sendSuccess(() -> Msg.line(
						"Skin pool holds " + size + " appearance(s)."), false);
					return size;
				}))));
	}

	private static int setEdit(CommandSourceStack source, boolean on) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Msg.failure("Only a player can edit bots."));
			return 0;
		}
		scriptify.bots.npc.EditMode.set(player, on);
		return 1;
	}

	private static boolean act(String name, String what) {
		return switch (what.toLowerCase(java.util.Locale.ROOT)) {
			case "attack" -> SBotsApi.attack(name);
			case "use" -> SBotsApi.use(name);
			case "jump" -> SBotsApi.jump(name);
			case "swing" -> SBotsApi.swing(name);
			case "sneak" -> SBotsApi.sneak(name, true);
			case "unsneak" -> SBotsApi.sneak(name, false);
			case "drop" -> SBotsApi.drop(name);
			default -> false;
		};
	}

	private static boolean allowed(CommandSourceStack source) {
		return ScriptifyBootstrap.context() != null
			&& ScriptifyBootstrap.context().permissions().has(source, PermissionService.BOTS);
	}

	/** Bot commands are useless until the server has finished starting and the roster exists. */
	private static boolean requireSBots(CommandSourceStack source) {
		if (SBotsApi.isAvailable()) {
			return true;
		}
		source.sendFailure(Msg.failure("Bots are not ready yet; the server is still starting."));
		return false;
	}

	private static int report(CommandSourceStack source, boolean done, String ok, String failed) {
		if (!requireSBots(source)) {
			return 0;
		}
		if (done) {
			source.sendSuccess(() -> Msg.success(ok), true);
			return 1;
		}
		source.sendFailure(Msg.failure(failed));
		return 0;
	}

	// ---------------------------------------------------------------- event actions

	/**
	 * Bot steps for event files.
	 *
	 * <p>Named {@code bot_*} so they group together in the action list, and each maps to exactly one
	 * facade call. Returning false stops the event, which is what a scene author wants when the bot a
	 * later step depends on never appeared.</p>
	 */
	@Override
	public void registerEventActions(ActionRegistry actions, TriggerRegistry triggers) {
		actions.register("bot_spawn", (action, source) -> {
			JsonObject data = action.data();
			String name = string(data, "name", null);
			if (name == null) {
				return false;
			}
			return SBotsApi.spawn(name,
				optionalDouble(data, "x"), optionalDouble(data, "y"), optionalDouble(data, "z"));
		});

		actions.register("bot_remove", (action, source) ->
			SBotsApi.remove(string(action.data(), "name", "")));

		actions.register("bot_kill", (action, source) ->
			SBotsApi.kill(string(action.data(), "name", "")));

		actions.register("bot_action", (action, source) -> {
			String name = string(action.data(), "name", "");
			String what = string(action.data(), "action", "attack");
			return act(name, what);
		});

		actions.register("bot_setting", (action, source) -> {
			JsonObject data = action.data();
			return SBotsApi.setSetting(
				string(data, "scope", "bot"),
				string(data, "name", null),
				string(data, "key", null),
				string(data, "value", null));
		});

		actions.register("bot_group", (action, source) -> {
			JsonObject data = action.data();
			String group = string(data, "group", null);
			String name = string(data, "name", null);
			return group == null ? SBotsApi.removeFromGroup(name) : SBotsApi.addToGroup(group, name);
		});

		actions.register("bot_skin", (action, source) -> {
			JsonObject data = action.data();
			String name = string(data, "name", "");
			String value = string(data, "value", null);
			String signature = string(data, "signature", null);
			// No value means "surprise me", which is the local pool and costs nothing.
			return value == null ? SBotsApi.randomiseSkin(name) : SBotsApi.setSkin(name, value, signature);
		});

		actions.register("bot_path", (action, source) -> {
			JsonObject data = action.data();
			String name = string(data, "name", "");
			String path = string(data, "path", null);
			return path == null ? SBotsApi.stopPath(name) : SBotsApi.startPath(name, path);
		});

		actions.register("bot_scenario_stop", (action, source) -> {
			JsonObject data = action.data();
			if (!data.has("id")) {
				return SBotsApi.stopAllScenarios() > 0;
			}
			return SBotsApi.stopScenario(data.get("id").getAsInt());
		});

		// Triggers fire from the roster, so a scene can react to a bot arriving or dying.
		triggers.register("bot_spawned");
		triggers.register("bot_removed");
	}

	private static String string(JsonObject data, String key, String fallback) {
		if (data == null || !data.has(key) || data.get(key).isJsonNull()) {
			return fallback;
		}
		return data.get(key).getAsString();
	}

	private static Double optionalDouble(JsonObject data, String key) {
		if (data == null || !data.has(key) || data.get(key).isJsonNull()) {
			return null;
		}
		try {
			return data.get(key).getAsDouble();
		} catch (NumberFormatException | UnsupportedOperationException exception) {
			return null;
		}
	}
}
