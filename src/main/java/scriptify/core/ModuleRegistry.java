package scriptify.core;

import com.mojang.brigadier.CommandDispatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import scriptify.api.ScriptifyContext;
import scriptify.api.ScriptifyModule;
import scriptify.events.ActionRegistry;
import scriptify.events.TriggerRegistry;
import scriptify.ui.MenuRegistry;

public final class ModuleRegistry {
	private final List<ScriptifyModule> modules = new ArrayList<>();

	public void register(ScriptifyModule module) {
		this.modules.add(module);
	}

	public List<ScriptifyModule> modules() {
		return Collections.unmodifiableList(this.modules);
	}

	public void initialize(ScriptifyContext context) {
		for (ScriptifyModule module : this.modules) {
			module.initialize(context);
		}
	}

	public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
		for (ScriptifyModule module : this.modules) {
			module.registerCommands(dispatcher, context);
		}
	}

	public void registerMenus(MenuRegistry registry) {
		for (ScriptifyModule module : this.modules) {
			module.registerMenus(registry);
		}
	}

	public void registerEventActions(ActionRegistry actions, TriggerRegistry triggers) {
		for (ScriptifyModule module : this.modules) {
			module.registerEventActions(actions, triggers);
		}
	}

	public void shutdown() {
		for (ScriptifyModule module : this.modules) {
			module.shutdown();
		}
	}
}
