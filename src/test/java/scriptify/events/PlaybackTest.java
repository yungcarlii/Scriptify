package scriptify.events;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A cue's timing: steps fire in order, a delay holds what follows it, a recording keeps its gaps. */
class PlaybackTest {
	private static ScriptifyEvent cue(ScriptifyEvent.Action... steps) {
		return new ScriptifyEvent("scene", true, "manual", List.of(steps));
	}

	private static String commandOf(ScriptifyEvent.Action action) {
		return action.data().get("command").getAsString();
	}

	@Test
	void delaysHoldTheStepsAfterThem() {
		Playback playback = new Playback(cue(
			ScriptifyEvent.Action.command("a"),
			ScriptifyEvent.Action.command("b"),
			ScriptifyEvent.Action.delay(40),
			ScriptifyEvent.Action.command("c")), 100);
		List<String> ran = new ArrayList<>();

		assertFalse(playback.advance(100, action -> ran.add(commandOf(action))));
		assertEquals(List.of("a", "b"), ran);
		assertFalse(playback.advance(139, action -> ran.add(commandOf(action))));
		assertEquals(List.of("a", "b"), ran);
		assertTrue(playback.advance(140, action -> ran.add(commandOf(action))));
		assertEquals(List.of("a", "b", "c"), ran);
	}

	@Test
	void aTrailingDelayKeepsTheCuePlayingUntilItEnds() {
		Playback playback = new Playback(cue(ScriptifyEvent.Action.command("a"), ScriptifyEvent.Action.delay(20)), 0);
		assertFalse(playback.advance(0, action -> { }));
		assertFalse(playback.advance(19, action -> { }));
		assertTrue(playback.advance(20, action -> { }));
	}

	@Test
	void aLateTickTimesTheNextDelayFromNow() {
		Playback playback = new Playback(cue(
			ScriptifyEvent.Action.delay(5), ScriptifyEvent.Action.command("a"),
			ScriptifyEvent.Action.delay(5), ScriptifyEvent.Action.command("b")), 0);
		List<String> ran = new ArrayList<>();
		playback.advance(0, action -> ran.add(commandOf(action)));
		// Lag: the next tick lands well past the first delay. "a" runs, and the second delay is timed
		// from now rather than skipped, so the beat after a hitch keeps its spacing.
		assertFalse(playback.advance(30, action -> ran.add(commandOf(action))));
		assertEquals(List.of("a"), ran);
		assertTrue(playback.advance(35, action -> ran.add(commandOf(action))));
		assertEquals(List.of("a", "b"), ran);
	}

	@Test
	void recordingKeepsTheGapsButNotTheLeadIn() {
		ScriptifyEvent event = cue()
			.withRecorded(73, "title @a title \"Day 1\"")
			.withRecorded(40, "weather thunder")
			.withRecorded(0, "say now");
		var steps = event.actions();
		assertEquals(4, steps.size());
		assertEquals("title @a title \"Day 1\"", commandOf(steps.get(0)));
		assertEquals(40, steps.get(1).ticks());
		assertEquals("weather thunder", commandOf(steps.get(2)));
		assertEquals("say now", commandOf(steps.get(3)));
	}

	@Test
	void savedCuesReadBackTheSame() {
		ScriptifyEvent event = cue(ScriptifyEvent.Action.command("say hi"), ScriptifyEvent.Action.delay(12));
		ScriptifyEvent read = ScriptifyEvent.from(event.toJson());
		assertEquals("scene", read.id());
		assertEquals("say hi", commandOf(read.actions().get(0)));
		assertEquals(12, read.actions().get(1).ticks());
	}
}
