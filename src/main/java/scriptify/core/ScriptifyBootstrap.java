package scriptify.core;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import scriptify.command.ScriptifyCommands;
import scriptify.compat.CommandConflicts;
import scriptify.compat.CompatService;
import scriptify.events.EventEngine;
import scriptify.permission.JsonPermissionService;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;
import scriptify.storage.JsonDataStore;
import scriptify.ui.GuiService;
import scriptify.ui.SguiGuiService;

public final class ScriptifyBootstrap {
	/**
	 * An event phase ordered ahead of the default one, so the command tree can be read while it still
	 * holds nothing but vanilla. Mods register in the default phase, so this is the only vantage point
	 * from which "was this name already taken by a mod" has a reliable answer.
	 */
	private static final Identifier SNAPSHOT_PHASE =
		Identifier.fromNamespaceAndPath("scriptify", "vanilla_snapshot");
	private static final ModuleRegistry MODULES = new ModuleRegistry();
	private static DefaultScriptifyContext context;
	private static Logger logger;

	private ScriptifyBootstrap() {
	}

	public static void initialize(Logger logger) {
		ScriptifyBootstrap.logger = logger;
		// The one module so far. Registered here rather than discovered, because a toolkit with three
		// modules does not need a discovery mechanism.
		MODULES.register(new scriptify.bots.BotsModule());
		// The bot subsystem registers its own commands and server hooks; it used to be a separate
		// mod's initialiser and is now simply a part of this one that starts here.
		scriptify.bots.BotRuntime.initialize();
		// Detection is pure inspection of the loaded mod list, which is already final here — well before
		// any command registers or any player joins, so nothing ever runs under the wrong owner.
		CompatService compat = new CompatService();
		compat.detect(ScriptifyRuntime.config().get().compat, logger);
		ScriptifyRuntime.setCompat(compat);
		CommandRegistrationCallback.EVENT.addPhaseOrdering(SNAPSHOT_PHASE, Event.DEFAULT_PHASE);
		CommandRegistrationCallback.EVENT.register(SNAPSHOT_PHASE,
			(dispatcher, registryAccess, environment) -> CommandConflicts.snapshotVanilla(dispatcher));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// Registering through a watcher, so a command name another mod already claimed is noticed as
			// it happens rather than silently merged into their tree.
			var watched = CommandConflicts.watch(dispatcher, ScriptifyRuntime.config().get().compat);
			scriptify.bots.BotRuntime.registerCommands(watched);
			ScriptifyCommands.register(watched);
			MODULES.registerCommands(watched, registryAccess);
			// Last: the fold drops whichever NPC sub-commands /bots already has, so it has to see the
			// finished node.
			scriptify.bots.BotRuntime.foldNpcCommands(watched);
		});
		ScriptifyRuntime.registerEvents();
		ServerLifecycleEvents.SERVER_STARTED.register(ScriptifyBootstrap::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (context != null) {
				context.events().runTrigger("player_join", handler.player.createCommandSourceStack());
			}
		});
		logger.info("Scriptify server-side foundation registered.");
	}

	public static DefaultScriptifyContext context() {
		return context;
	}

	public static ModuleRegistry modules() {
		return MODULES;
	}

	public static boolean reload() {
		if (context == null) {
			return false;
		}
		context.permissions().reload();
		context.events().reload();
		return true;
	}

	private static void start(MinecraftServer server) {
		JsonDataStore storage = new JsonDataStore(server, "scriptify");
		PermissionService permissions = new JsonPermissionService(storage);
		GuiService gui = new SguiGuiService();
		EventEngine events = new EventEngine(storage, logger);
		context = new DefaultScriptifyContext(server, logger, storage, permissions, gui, events);
		ScriptifyRuntime.start(context);
		MODULES.registerMenus(gui.registry());
		MODULES.registerEventActions(events.actions(), events.triggers());
		MODULES.initialize(context);
		permissions.reload();
		events.reload();
		// The second half of command-conflict detection: mods that registered after Scriptify leave no
		// trace at registration time, and this is the first moment every one of them has had its turn.
		CommandConflicts.verify(server, ScriptifyRuntime.config().get().compat, logger);
		// Every command that will ever exist is registered by now, which is the earliest the set of
		// commands a rank can filter can be known - and the latest it can be worked out once instead
		// of on every command anybody runs.
		var ranks = permissions.ranks();
		scriptify.permission.RankRegistry.setGateable(
			scriptify.permission.RankService.commandLevels(server));
		// Handed the server so that editing a rank can resend the command tree to the people holding
		// it; without that a level change did not reach anybody until they reconnected.
		ranks.bind(server);
		logger.info("Scriptify started with {} module(s) and {} event definition(s).", MODULES.modules().size(), events.events().size());
	}

	private static void stop() {
		if (context != null) {
			MODULES.shutdown();
			ScriptifyRuntime.stop();
			context.logger().info("Scriptify stopped.");
			context = null;
		}
	}
}
