package scriptify.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The clock on the countdown bar reads the way a countdown is read out. */
class CountdownTest {
	@Test
	void clockReadsLikeACountdown() {
		assertEquals("0:05", Countdown.clock(5));
		assertEquals("1:30", Countdown.clock(90));
		assertEquals("59:59", Countdown.clock(3599));
		assertEquals("1:00:00", Countdown.clock(3600));
		assertEquals("2:03:04", Countdown.clock(7384));
	}
}
