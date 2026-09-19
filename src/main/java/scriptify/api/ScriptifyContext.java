package scriptify.api;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import scriptify.events.EventEngine;
import scriptify.permission.PermissionService;
import scriptify.storage.DataStore;
import scriptify.ui.GuiService;

public interface ScriptifyContext {
	MinecraftServer server();

	Logger logger();

	DataStore storage();

	PermissionService permissions();

	GuiService gui();

	EventEngine events();
}
