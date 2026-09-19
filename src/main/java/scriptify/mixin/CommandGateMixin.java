package scriptify.mixin;

import com.mojang.brigadier.ParseResults;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import scriptify.compat.CompatDomain;
import scriptify.compat.CompatRegistry;
import scriptify.permission.RankRegistry;

/**
 * Blocks individual commands a rank is not allowed to run.
 *
 * <p>The permission level a rank grants is all-or-nothing per bucket: level 2 is {@code /gamemode}
 * and {@code /give} and everything else at that level. This is the half that makes it selective —
 * "gamemaster, but not {@code /op}" is expressed by allowing the level and blocking the command.
 *
 * <p>Enforced at execution rather than by hiding the node, because the tree the client receives is
 * built from the coarse buckets and cannot be filtered per command from a server-side mod. A blocked
 * command therefore still appears in the player's suggestions and simply refuses to run, which is
 * worth knowing when a rank is set up.
 *
 * <p>Operators are never blocked. A rank is a way to hand out access, not a way to take it away from
 * the people running the server.
 */
@Mixin(Commands.class)
public abstract class CommandGateMixin {
	@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
	private void scriptify$gateCommand(ParseResults<CommandSourceStack> parse, String command,
			CallbackInfo callback) {
		if (!CompatRegistry.owns(CompatDomain.PERMISSIONS)) {
			return;
		}
		CommandSourceStack source = parse.getContext().getSource();
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			return;
		}
		var server = player.level().getServer();
		if (server != null && server.getPlayerList().isOp(player.nameAndId())) {
			return;
		}
		String root = rootOf(command);
		// Only commands a rank actually has a say over: everything that needs a permission level,
		// minus Scriptify's own, which answer to feature nodes. Worked out from the live command tree
		// rather than from a list kept by hand - the list left out /execute, which by itself runs
		// anything, so "only /gamemode" was never the restriction it claimed to be.
		if (root.isEmpty() || !RankRegistry.isGateable(root)
			|| RankRegistry.of(player.getUUID()).mayRun(root)) {
			return;
		}
		source.sendFailure(Component.literal("Your rank cannot use /" + root + ".")
			.withStyle(ChatFormatting.RED));
		callback.cancel();
	}

	/** The first word of the command, without its slash and without any arguments. */
	private static String rootOf(String command) {
		String trimmed = command.startsWith("/") ? command.substring(1) : command;
		int space = trimmed.indexOf(' ');
		if (space >= 0) {
			trimmed = trimmed.substring(0, space);
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}
}
