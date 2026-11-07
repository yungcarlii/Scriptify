package scriptify.events;

import java.util.function.Consumer;

/**
 * One cue being played: which step is next and the tick it may run at.
 *
 * <p>Kept apart from the engine and free of server types so the timing - the whole point of a cue - can
 * be checked without a server.
 */
final class Playback {
	private final ScriptifyEvent event;
	private int next;
	private long resumeAt;

	Playback(ScriptifyEvent event, long now) {
		this.event = event;
		this.resumeAt = now;
	}

	ScriptifyEvent event() {
		return this.event;
	}

	/**
	 * Runs every step that is due by {@code now}, stopping at the first delay that is not over yet.
	 *
	 * @return true once the last step has run and any delay after it has passed
	 */
	boolean advance(long now, Consumer<ScriptifyEvent.Action> run) {
		var actions = this.event.actions();
		while (this.next < actions.size() && now >= this.resumeAt) {
			ScriptifyEvent.Action action = actions.get(this.next++);
			int ticks = action.ticks();
			if (ticks > 0) {
				this.resumeAt = now + ticks;
			} else {
				run.accept(action);
			}
		}
		return this.next >= actions.size() && now >= this.resumeAt;
	}
}
