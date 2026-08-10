package scriptify.ui;

import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;

public interface GuiService {
	MenuRegistry registry();

	void openDashboard(ScriptifyContext context, ServerPlayer player);

	void openMenu(ScriptifyContext context, ServerPlayer player, ScriptifyMenu menu);
}
