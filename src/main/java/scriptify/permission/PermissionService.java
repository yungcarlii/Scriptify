package scriptify.permission;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public interface PermissionService {
	String ADMIN = "scriptify.admin";
	String GUI_OPEN = "scriptify.gui.open";
	String NICK = "scriptify.nick";
	String SKIN = "scriptify.skin";
	String DEATH = "scriptify.ondeath";
	String INVENTORY = "scriptify.inventory";
	String KITS = "scriptify.kits";
	String PLAYER = "scriptify.player";

	boolean has(CommandSourceStack source, String node);

	boolean has(ServerPlayer player, String node);

	void reload();
}
