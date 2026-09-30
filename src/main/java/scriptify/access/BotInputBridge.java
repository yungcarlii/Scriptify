package scriptify.access;

/**
 * Held movement keys for a bot, applied inside the entity's own tick.
 *
 * <p>The fake-player backend rewrites a bot's movement input from its own action pack at the start
 * of every tick, before physics runs, so an input written from the server tick event never reaches
 * {@code travel}: it is overwritten a moment before it would be read. This is the way around that.
 * {@code scriptify.bots.mixin.LivingEntityInputMixin} implements it on {@code LivingEntity} and
 * re-applies the held keys at the top of {@code aiStep}, after the backend and before the game
 * reads them - so the bot presses W and space the way a player does, and vanilla does the rest.
 *
 * <p>Lives outside the mixin package for the same reason as {@link TrackedEntityBridge}: a plain
 * interface inside a registered mixin package cannot be loaded as an ordinary class.
 */
public interface BotInputBridge {
	/**
	 * Holds these keys until the given game time, inclusive.
	 *
	 * @param forward W/S, -1 to 1
	 * @param strafe  A/D, -1 to 1, positive being left as in vanilla
	 * @param jump    space
	 */
	void scriptify$holdInput(float forward, float strafe, boolean jump, long untilGameTime);

	/** Lets go of every key this bridge is holding. */
	void scriptify$releaseInput();

	boolean scriptify$isHoldingInput();
}
