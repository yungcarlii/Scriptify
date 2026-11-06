package scriptify.bots.bot;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A crowd on a mixed route walks every which way, and each bot keeps its own way of walking. */
class BotPathHabitsTest {

    private static BotPath.PathFollower habitsOf(String name) {
        BotPath.PathFollower follower = new BotPath.PathFollower("route");
        BotPath.pickHabits(name, follower);
        return follower;
    }

    @Test
    void aCrowdMixesGaitsAndSomeNeverStop() {
        Map<String, Integer> gaits = new HashMap<>();
        int neverStop = 0;
        for (int i = 0; i < 1000; i++) {
            BotPath.PathFollower follower = habitsOf("Walker" + i);
            gaits.merge(follower.gait, 1, Integer::sum);
            if (follower.pauseChance == 0) neverStop++;
            else assertTrue(follower.pauseChance <= 1.0 / 400 && follower.pauseChance > 1.0 / 1200);
        }
        assertEquals(3, gaits.size(), "walk, sprint and bhop: " + gaits);
        for (int count : gaits.values()) {
            assertTrue(count > 200 && count < 450, "lopsided gaits: " + gaits);
        }
        assertTrue(neverStop > 250 && neverStop < 420, "never stop: " + neverStop);
    }

    @Test
    void aBotKeepsItsHabits() {
        BotPath.PathFollower first = habitsOf("Steve_42");
        BotPath.PathFollower again = habitsOf("steve_42");
        assertEquals(first.gait, again.gait);
        assertEquals(first.pauseChance, again.pauseChance);
    }

    @Test
    void oldDefaultBhopTurnsMixedOnceOnly() {
        BotPath.PathData old = new BotPath.PathData("old");
        old.walkType = "bhop";
        old.version = 0;
        BotPath.upgrade(old);
        assertEquals(BotPath.MIXED, old.walkType);

        BotPath.PathData chosen = new BotPath.PathData("chosen");
        chosen.walkType = "bhop";
        BotPath.upgrade(chosen);
        assertEquals("bhop", chosen.walkType, "a route set to bhop by hand stays bhop");

        BotPath.PathData oldest = new BotPath.PathData("oldest");
        oldest.walkType = null;
        oldest.points = null;
        oldest.version = 0;
        BotPath.upgrade(oldest);
        assertEquals(BotPath.MIXED, oldest.walkType, "a route saved before walk types existed");
        assertEquals(0, oldest.points.size());

        BotPath.PathData walk = new BotPath.PathData("walk");
        walk.walkType = "walk";
        walk.version = 0;
        BotPath.upgrade(walk);
        assertEquals("walk", walk.walkType);
    }
}
