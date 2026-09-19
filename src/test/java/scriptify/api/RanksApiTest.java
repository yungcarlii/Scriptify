package scriptify.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * RanksApi must never throw into a caller, even with no server running.
 *
 * <p>This is the contract that matters most. Eventify calls these reflectively at arbitrary
 * times, including before Scriptify has finished starting - mod initialisation order between two
 * unrelated mods is not something either one can rely on. Every method has to answer with a safe
 * empty value rather than propagate, because the alternative is one mod's startup timing throwing
 * an exception inside the other mod's event handler.
 *
 * <p>A test JVM has no {@code ScriptifyBootstrap.context()}, which makes it exactly the
 * "unavailable" case this class is built to survive.
 */
class RanksApiTest {

	@Test
	void answersSafelyWithNoRuntime() {
		UUID anybody = UUID.randomUUID();

		assertEquals("", RanksApi.rankOf(anybody));
		assertFalse(RanksApi.isAssigned(anybody));
		assertArrayEquals(new String[0], RanksApi.allRankNames());
		assertEquals(-1, RanksApi.commandLevelOf("vip"));
		assertEquals("", RanksApi.prefixOf("vip"));
		assertFalse(RanksApi.available());
	}

	@Test
	void toleratesNullArguments() {
		assertEquals("", RanksApi.rankOf(null));
		assertFalse(RanksApi.isAssigned(null));
		assertEquals(-1, RanksApi.commandLevelOf(null));
		assertEquals("", RanksApi.prefixOf(null));
	}

	@Test
	void toleratesBlankRankNames() {
		assertEquals(-1, RanksApi.commandLevelOf("   "));
		assertEquals("", RanksApi.prefixOf("   "));
	}

	@Test
	void acceptsAListenerWithNoRuntime() {
		assertDoesNotThrow(() -> RanksApi.onRanksChanged(() -> { }));
		assertDoesNotThrow(() -> RanksApi.onRanksChanged(null));
	}

	@Test
	void drainingListenersIntoNoServiceIsHarmless() {
		assertDoesNotThrow(() -> RanksApi.drainPendingListeners(null));
	}
}
