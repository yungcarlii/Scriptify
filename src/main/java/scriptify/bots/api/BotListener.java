package scriptify.bots.api;

/**
 * Notification that a bot appeared or went away.
 *
 * <p>Bots are not created or destroyed by anything the consumer controls - the fake-player backend
 * owns their lifetime, and a bot can vanish because it died, because an operator removed it, or
 * because the backend dropped it. Polling {@link SBotsApi#names()} to notice is both wasteful and
 * always slightly out of date, so the roster reports its own changes instead.</p>
 *
 * <p>Callbacks run on the server thread, so an implementation may touch game state directly and
 * must not block. A listener that throws is dropped rather than allowed to break the roster - see
 * {@link SBotsApi#addListener}.</p>
 */
public interface BotListener {

    /** A bot joined the roster and is on the server. */
    default void botSpawned(String name) {}

    /**
     * A bot left the roster.
     *
     * <p>No distinction is drawn between dying and being removed, because the backend does not
     * reliably offer one: a killed fake player is disconnected, which is indistinguishable from
     * being taken out deliberately. Promising a difference the data cannot support would be worse
     * than not offering it.</p>
     */
    default void botRemoved(String name) {}
}
