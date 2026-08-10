package scriptify.core;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import scriptify.command.ScriptifyCommands;
import scriptify.events.EventEngine;
import scriptify.permission.JsonPermissionService;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;
import scriptify.storage.JsonDataStore;
import scriptify.ui.GuiService;
import scriptify.ui.SguiGuiService;

public final class ScriptifyBootstrap {
	private static final ModuleRegistry MODULES = new ModuleRegistry();
	private static DefaultScriptifyContext context;
	private static Logger logger;

	private ScriptifyBootstrap() {
	}

	public static void initialize(Logger logger) {
		ScriptifyBootstrap.logger = logger;
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			ScriptifyCommands.register(dispatcher);
			MODULES.registerCommands(dispatcher, registryAccess);
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
