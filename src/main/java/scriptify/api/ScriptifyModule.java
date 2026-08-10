package scriptify.api;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import scriptify.events.ActionRegistry;
import scriptify.events.TriggerRegistry;
import scriptify.ui.MenuRegistry;

public interface ScriptifyModule {
	String id();

	default void initialize(ScriptifyContext context) {
	}

	default void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
	}

	default void registerMenus(MenuRegistry registry) {
	}

	default void registerEventActions(ActionRegistry actions, TriggerRegistry triggers) {
	}

	default void shutdown() {
	}
}
