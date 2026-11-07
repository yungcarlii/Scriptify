package scriptify.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import scriptify.core.ScriptifyBootstrap;
import scriptify.events.EventEngine;
import scriptify.events.ScriptifyEvent;
import scriptify.permission.PermissionService;
import scriptify.ui.Msg;
import scriptify.ui.Panel;

/**
 * {@code /cue}: scene scripts.
 *
 * <p>Write one step at a time, or rehearse the scene once with {@code /cue record} and let the timing be
 * captured as you go, then fire the whole beat with {@code /cue play} - from chat, a button wired to a
 * command block, or another cue. Admin-only: a cue runs commands, though always with the permissions of
 * whoever plays it, never more.
 */
public final class CueCommands {
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

	private CueCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("cue")
			.requires(source -> ScriptifyBootstrap.context() != null
				&& ScriptifyBootstrap.context().permissions().has(source, PermissionService.ADMIN))
			.executes(context -> list(context.getSource()))
			.then(literal("list").executes(context -> list(context.getSource())))
			.then(literal("play").then(cue().executes(context -> play(context))))
			.then(literal("stop")
				.executes(context -> {
					int stopped = engine().stopAll();
					context.getSource().sendSuccess(() -> Msg.line("Stopped " + stopped + " cue(s)."), false);
					return stopped;
				})
				.then(cue().executes(context -> {
					String id = name(context);
					if (!engine().stop(id)) {
						return fail(context, "Cue '" + id + "' is not playing.");
					}
					context.getSource().sendSuccess(() -> Msg.line("Stopped " + id + "."), false);
					return 1;
				})))
			.then(literal("new").then(argument("name", StringArgumentType.word())
				.executes(context -> create(context))))
			.then(literal("add").then(cue().then(argument("command", StringArgumentType.greedyString())
				.executes(context -> add(context)))))
			.then(literal("wait").then(cue().then(argument("seconds", DoubleArgumentType.doubleArg(0.05, 3600))
				.executes(context -> addWait(context)))))
			.then(literal("show").then(cue().executes(context -> show(context.getSource(), existing(context)))))
			.then(literal("remove").then(cue().then(argument("step", IntegerArgumentType.integer(1))
				.executes(context -> remove(context)))))
			.then(literal("delete").then(cue().executes(context -> {
				String id = existing(context).id();
				engine().delete(id);
				context.getSource().sendSuccess(() -> Msg.line("Deleted cue " + id + "."), false);
				return 1;
			})))
			.then(literal("record").then(argument("name", StringArgumentType.word())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(engine().events().keySet(), builder))
				.executes(context -> record(context))))
			.then(literal("done").executes(context -> done(context.getSource()))));
	}

	// ------------------------------------------------------------------ playback

	private static int play(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ScriptifyEvent event = existing(context);
		if (!engine().play(event.id(), context.getSource())) {
			return fail(context, "Cue '" + event.id() + "' is switched off in its file.");
		}
		context.getSource().sendSuccess(() -> Msg.line("Playing " + event.id() + " ("
			+ event.actions().size() + " steps, " + seconds(length(event)) + ")."), false);
		return 1;
	}

	// ------------------------------------------------------------------ writing

	private static int create(CommandContext<CommandSourceStack> context) {
		String id = validName(context);
		if (id == null) {
			return 0;
		}
		if (engine().events().containsKey(id)) {
			return fail(context, "Cue '" + id + "' already exists. /cue show " + id);
		}
		engine().save(new ScriptifyEvent(id, true, "manual", List.of()));
		context.getSource().sendSuccess(() -> Msg.line("Created cue " + id
			+ ". Add steps with /cue add " + id + " <command> and /cue wait " + id + " <seconds>."), false);
		return 1;
	}

	private static int add(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ScriptifyEvent event = existing(context);
		String command = StringArgumentType.getString(context, "command").trim();
		if (command.startsWith("/")) {
			command = command.substring(1);
		}
		// Caught now rather than halfway through a take, when the step would silently do nothing.
		var parse = context.getSource().getServer().getCommands().getDispatcher().parse(command, context.getSource());
		if (parse.getReader().canRead() || !parse.getExceptions().isEmpty()) {
			return fail(context, "That command does not parse: /" + command);
		}
		return append(context, event, ScriptifyEvent.Action.command(command));
	}

	private static int addWait(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		int ticks = (int) Math.round(DoubleArgumentType.getDouble(context, "seconds") * 20.0D);
		return append(context, existing(context), ScriptifyEvent.Action.delay(Math.max(1, ticks)));
	}

	private static int append(CommandContext<CommandSourceStack> context, ScriptifyEvent event,
			ScriptifyEvent.Action action) {
		List<ScriptifyEvent.Action> steps = new ArrayList<>(event.actions());
		steps.add(action);
		ScriptifyEvent edited = event.withActions(steps);
		engine().save(edited);
		context.getSource().sendSuccess(() -> Msg.line("Step " + steps.size() + " of " + event.id() + ": "
			+ describe(action) + "  (cue is now " + seconds(length(edited)) + ")"), false);
		return steps.size();
	}

	private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ScriptifyEvent event = existing(context);
		int step = IntegerArgumentType.getInteger(context, "step");
		if (step > event.actions().size()) {
			return fail(context, event.id() + " only has " + event.actions().size() + " step(s).");
		}
		List<ScriptifyEvent.Action> steps = new ArrayList<>(event.actions());
		ScriptifyEvent.Action removed = steps.remove(step - 1);
		engine().save(event.withActions(steps));
		context.getSource().sendSuccess(() -> Msg.line("Removed step " + step + ": " + describe(removed)), false);
		return 1;
	}

	// ------------------------------------------------------------------ recording

	private static int record(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		String id = validName(context);
		if (id == null) {
			return 0;
		}
		boolean existed = engine().events().containsKey(id);
		engine().startRecording(player, id);
		context.getSource().sendSuccess(() -> Msg.line((existed ? "Recording onto the end of " : "Recording new cue ")
			+ id + ". Run the scene's commands at the pace you want them played back, then /cue done."), false);
		return 1;
	}

	private static int done(CommandSourceStack source) throws CommandSyntaxException {
		String id = engine().stopRecording(source.getPlayerOrException());
		if (id == null) {
			source.sendFailure(Msg.failure("You are not recording a cue."));
			return 0;
		}
		ScriptifyEvent event = engine().events().get(id);
		int steps = event == null ? 0 : event.actions().size();
		source.sendSuccess(() -> Msg.line("Saved " + id + ": " + steps + " steps, "
			+ seconds(event == null ? 0 : length(event)) + ". /cue play " + id), false);
		return 1;
	}

	// ------------------------------------------------------------------ reading

	private static int list(CommandSourceStack source) {
		Panel.open(source, "Cues");
		if (engine().events().isEmpty()) {
			Panel.note(source, "No cues yet. /cue record <name> and act the scene out,");
			Panel.note(source, "or /cue new <name> and add steps one at a time.");
		}
		for (ScriptifyEvent event : engine().events().values()) {
			boolean playing = engine().isPlaying(event.id());
			Panel.row(source, playing ? "▶" : "•", event.id(),
				event.actions().size() + " steps · " + seconds(length(event))
					+ (event.enabled() ? "" : " · off") + (playing ? " · playing" : ""),
				Panel.DETAIL, "/cue play " + event.id(),
				"Click to play. /cue show " + event.id() + " lists its steps.", true);
		}
		Panel.close(source);
		return engine().events().size();
	}

	private static int show(CommandSourceStack source, ScriptifyEvent event) {
		Panel.open(source, "Cue · " + event.id() + (engine().isPlaying(event.id()) ? " · playing" : ""));
		long at = 0;
		int step = 0;
		for (ScriptifyEvent.Action action : event.actions()) {
			step++;
			Panel.row(source, null, step + ".  " + seconds(at), describe(action),
				action.ticks() > 0 ? Panel.DETAIL : Panel.ACTION,
				"/cue remove " + event.id() + " " + step, "Starts " + seconds(at) + " into the cue.");
			at += action.ticks();
		}
		if (step == 0) {
			Panel.note(source, "Empty. /cue add " + event.id() + " <command> or /cue record " + event.id());
		}
		Panel.blank(source);
		Panel.row(source, "▶", "Play", seconds(at), Panel.ACTION, "/cue play " + event.id(), "Run the cue now.", true);
		Panel.close(source);
		return step;
	}

	/** Total length: the sum of its delays, since every other step is instant. */
	static long length(ScriptifyEvent event) {
		long ticks = 0;
		for (ScriptifyEvent.Action action : event.actions()) {
			ticks += action.ticks();
		}
		return ticks;
	}

	static String seconds(long ticks) {
		return String.format(Locale.ROOT, "%.1fs", ticks / 20.0D);
	}

	private static String describe(ScriptifyEvent.Action action) {
		if (action.ticks() > 0) {
			return "wait " + seconds(action.ticks());
		}
		var data = action.data();
		if (action.type().equals("command") && data.has("command")) {
			return "/" + data.get("command").getAsString();
		}
		return action.type() + (data.has("text") ? " " + data.get("text").getAsString() : "");
	}

	// ------------------------------------------------------------------ helpers

	private static RequiredArgumentBuilder<CommandSourceStack, String> cue() {
		return argument("name", StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(engine().events().keySet(), builder));
	}

	private static String name(CommandContext<CommandSourceStack> context) {
		return StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
	}

	private static ScriptifyEvent existing(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String id = name(context);
		ScriptifyEvent event = engine().events().get(id);
		if (event == null) {
			throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
				Msg.failure("No cue called '" + id + "'. /cue lists them.")).create();
		}
		return event;
	}

	private static String validName(CommandContext<CommandSourceStack> context) {
		String id = name(context);
		if (!NAME.matcher(id).matches()) {
			fail(context, "Cue names are letters, digits, - and _, up to 32 long.");
			return null;
		}
		return id;
	}

	private static int fail(CommandContext<CommandSourceStack> context, String message) {
		context.getSource().sendFailure(Msg.failure(message));
		return 0;
	}

	private static EventEngine engine() {
		return ScriptifyBootstrap.context().events();
	}
}
