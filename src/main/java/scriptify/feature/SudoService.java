package scriptify.feature;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;

/**
 * Runs a command, or says a line in chat, as another player.
 *
 * <p>For scripted content this is how a "character" says or does something on cue without the person
 * behind them having to type it at the right moment.
 */
public final class SudoService {
	private final ScriptifyContext context;

	public SudoService(ScriptifyContext context) {
		this.context = context;
	}

	/**
	 * Executes a command as the target.
	 *
	 * <p>It runs at the target's own permission level, not the operator's, so sudo cannot be used to
	 * escalate a normal player into running commands they could not run themselves.
	 */
	public boolean runCommand(ServerPlayer target, String command) {
		String trimmed = command.startsWith("/") ? command.substring(1) : command;
		if (trimmed.isBlank()) {
			return false;
		}
		try {
			CommandSourceStack source = target.createCommandSourceStack();
			this.context.server().getCommands().performPrefixedCommand(source, trimmed);
			return true;
		} catch (RuntimeException exception) {
			this.context.logger().warn("Sudo command '{}' failed for {}.", trimmed, target.getGameProfile().name(), exception);
			return false;
		}
	}

	/** Broadcasts a chat line attributed to the target, using their nickname if they have one. */
	public void say(ServerPlayer target, String message) {
		this.context.server().getPlayerList().broadcastSystemMessage(
			Component.literal("<").append(target.getDisplayName()).append("> " + message), false);
	}
}
