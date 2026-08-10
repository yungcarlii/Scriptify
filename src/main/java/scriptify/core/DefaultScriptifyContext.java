package scriptify.core;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import scriptify.api.ScriptifyContext;
import scriptify.events.EventEngine;
import scriptify.permission.PermissionService;
import scriptify.storage.DataStore;
import scriptify.ui.GuiService;

public final class DefaultScriptifyContext implements ScriptifyContext {
	private final MinecraftServer server;
	private final Logger logger;
	private final DataStore storage;
	private final PermissionService permissions;
	private final GuiService gui;
	private final EventEngine events;

	public DefaultScriptifyContext(MinecraftServer server, Logger logger, DataStore storage, PermissionService permissions, GuiService gui, EventEngine events) {
		this.server = server;
		this.logger = logger;
		this.storage = storage;
		this.permissions = permissions;
		this.gui = gui;
		this.events = events;
	}

	@Override
	public MinecraftServer server() {
		return this.server;
	}

	@Override
	public Logger logger() {
		return this.logger;
	}

	@Override
	public DataStore storage() {
		return this.storage;
	}

	@Override
	public PermissionService permissions() {
		return this.permissions;
	}

	@Override
	public GuiService gui() {
		return this.gui;
	}

	@Override
	public EventEngine events() {
		return this.events;
	}
}
