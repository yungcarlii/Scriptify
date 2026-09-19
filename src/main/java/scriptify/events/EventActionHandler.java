package scriptify.events;

import net.minecraft.commands.CommandSourceStack;

@FunctionalInterface
public interface EventActionHandler {
	boolean run(ScriptifyEvent.Action action, CommandSourceStack source);
}
