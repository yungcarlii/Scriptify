package scriptify.feature;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import scriptify.api.ScriptifyContext;
import scriptify.nick.PlayerRefresh;

/**
 * Broadcasts join, leave and death lines that are indistinguishable from the real thing.
 *
 * <p>Every message reuses vanilla's own translation key and styling rather than a hand-written string,
 * because on camera a subtly wrong colour or wording is exactly what gives a staged beat away.
 */
public final class FakeMessageService {
	private final ScriptifyContext context;

	public FakeMessageService(ScriptifyContext context) {
		this.context = context;
	}

	/** {@code <player> joined the game}, in vanilla yellow. */
	public void broadcastJoin(ServerPlayer player) {
		broadcast(Component.translatable("multiplayer.player.joined", player.getDisplayName())
			.withStyle(ChatFormatting.YELLOW));
	}

	/** {@code <player> left the game}, in vanilla yellow. */
	public void broadcastLeave(ServerPlayer player) {
		broadcast(Component.translatable("multiplayer.player.left", player.getDisplayName())
			.withStyle(ChatFormatting.YELLOW));
	}

	/**
	 * Broadcasts the exact death line the player would have produced had the damage actually killed
	 * them, so the message matches the cause on screen.
	 */
	public void broadcastDeath(ServerPlayer player, DamageSource source) {
		Component message = source == null
			? Component.translatable("death.attack.generic", player.getDisplayName())
			: source.getLocalizedDeathMessage(player);
		broadcast(message);
	}

	/** Generic death line for GUI-triggered fake deaths, where there is no real damage source. */
	public void broadcastDeath(ServerPlayer player) {
		broadcastDeath(player, null);
	}

	/**
	 * Stages a full disconnect: the leave message plus removal from everyone's tab list. The player
	 * stays connected and can keep filming.
	 */
	public void stageLeave(ServerPlayer player) {
		broadcastLeave(player);
		PlayerRefresh.hideFromPlayerList(player);
	}

	/** Undoes {@link #stageLeave}: puts them back in the list and announces a join. */
	public void stageJoin(ServerPlayer player) {
		PlayerRefresh.resendToOthers(player);
		broadcastJoin(player);
	}

	private void broadcast(Component message) {
		this.context.server().getPlayerList().broadcastSystemMessage(message, false);
	}
}
