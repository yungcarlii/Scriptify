package scriptify.bots.scenario;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The kinds of staged behaviour a scenario can run.
 *
 * <p>Scenarios are the opposite of the combat AI: nothing here is trying to win a fight. They
 * exist to make a world look inhabited - people milling about a spawn, a pair of players stood
 * talking, a crowd watching something happen. Each one is a small script over the same
 * movement and aiming primitives the fighting bots use.</p>
 *
 * <p>Each type carries the block it is represented by, so the menu and the placed marker agree
 * without a second table to keep in sync.</p>
 */
public enum Scenario {

    SPAWN("Spawn", Items.GRASS_BLOCK, true,
        "New players appear, wander off, and eventually die",
        "the way they do on a busy server."),

    CONVERSATION("Conversation", Items.NOTE_BLOCK, false,
        "Two or more bots stand talking: looking at",
        "each other, nodding, shifting their weight."),

    CROWD("Crowd", Items.TARGET, false,
        "Everyone in a radius watches whatever you",
        "point them at, and fidgets while they do."),

    PRISON_CELL("Prison Cell", Items.IRON_BARS, false,
        "Bots pace whatever enclosed space they are",
        "standing in, with nowhere to go.");

    public final String displayName;
    public final Item icon;
    /** Whether this type is built by placing points in the world. */
    public final boolean usesPoints;
    public final String[] description;

    Scenario(String displayName, Item icon, boolean usesPoints, String... description) {
        this.displayName = displayName;
        this.icon = icon;
        this.usesPoints = usesPoints;
        this.description = description;
    }

    /** Scenarios that accept exactly one point rather than a route. */
    public boolean isSinglePoint() {
        return this == SPAWN;
    }

    public static Scenario byName(String name) {
        for (Scenario s : values()) {
            if (s.name().equalsIgnoreCase(name) || s.displayName.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }
}
