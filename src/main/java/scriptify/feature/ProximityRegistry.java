package scriptify.feature;

import net.minecraft.server.level.ServerPlayer;

/**
 * Whether one player is close enough to another to hear them, in a form a mixin can read.
 *
 * <p>Static and dependency-free for the same reason {@code NickRegistry} and {@code CompatRegistry}
 * are: mixins run inside vanilla classes and cannot reach the mod's object graph.
 *
 * <p>The sender is stashed rather than passed along, because vanilla splits the work across two
 * methods. {@code PlayerList} knows who is talking; {@code ServerPlayer.sendChatMessage} is where a
 * single recipient can actually be skipped, and by then the sender is gone — the chat type carries a
 * display name, not a position. Recording who is talking for the length of one broadcast bridges the
 * two.
 *
 * <p>Only ever touched from the server thread, which is the only thread that broadcasts chat.
 */
public final class ProximityRegistry {
	private static volatile boolean enabled;
	/** Squared, so the hot path never takes a square root. */
	private static volatile double radiusSquared;
	/**
	 * Who is talking, for the length of one broadcast.
	 *
	 * <p>Set only around player chat. Anything else that delivers a message — a private message, a
	 * system line, a command broadcast — never sets it, so it is never subject to proximity.
	 */
	private static ServerPlayer source;

	private ProximityRegistry() {
	}

	public static void configure(boolean on, double radius) {
		enabled = on;
		radiusSquared = radius * radius;
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void beginChat(ServerPlayer sender) {
		source = sender;
	}

	public static void endChat() {
		source = null;
	}

	/**
	 * Whether this recipient is too far from whoever is currently talking to receive the message.
	 *
	 * <p>Answers {@code false} — deliver it — for everything that is not a player chat message in
	 * progress, so the failure mode of anything unexpected is normal chat rather than silence.
	 */
	public static boolean blocks(ServerPlayer recipient) {
		if (!enabled) {
			return false;
		}
		ServerPlayer sender = source;
		// Talkers always hear themselves. Distance to yourself is zero so this is already true, but
		// stating it means a radius of zero still leaves your own messages readable rather than making
		// chat look broken to the one person who knows they typed something.
		if (sender == null || sender == recipient) {
			return false;
		}
		// Another dimension is out of earshot at any radius, and coordinates repeat across dimensions —
		// without this, someone in the Nether would hear the Overworld from eight times further away.
		if (recipient.level() != sender.level()) {
			return true;
		}
		return recipient.distanceToSqr(sender) > radiusSquared;
	}
}
