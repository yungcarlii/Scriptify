package scriptify.feature;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wear levels behind {@code /durability}: 100 is pristine, 1 is nearly broken, nothing breaks. */
class WornDamageTest {
	private static final int DIAMOND_SWORD = 1561;

	@Test
	void hundredLeavesItemsUntouched() {
		Random random = new Random(1);
		for (int i = 0; i < 1000; i++) {
			assertEquals(0, FeatureManager.wornDamage(DIAMOND_SWORD, 100, random));
		}
	}

	@Test
	void oneLeavesItemsNearlyBrokenButNeverBroken() {
		Random random = new Random(1);
		for (int max : new int[] {1, 25, 250, DIAMOND_SWORD}) {
			for (int i = 0; i < 1000; i++) {
				int left = max - FeatureManager.wornDamage(max, 1, random);
				assertTrue(left >= 1 && left <= Math.max(1, Math.round(max / 100.0D)), "left " + left + " of " + max);
			}
		}
	}

	@Test
	void itemsScatterAroundTheTarget() {
		Random random = new Random(1);
		int lowest = 100;
		int highest = 0;
		for (int i = 0; i < 1000; i++) {
			int left = 100 - FeatureManager.wornDamage(100, 50, random);
			lowest = Math.min(lowest, left);
			highest = Math.max(highest, left);
		}
		assertEquals(50 - FeatureManager.WEAR_SPREAD, lowest);
		assertEquals(50 + FeatureManager.WEAR_SPREAD, highest);
	}
}
