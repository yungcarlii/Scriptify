package scriptify.bots.scenario;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A scenario being built.
 *
 * <p>The settings are copied out of the template the moment the draft starts, not when it is
 * finished. Opening the settings menu halfway through building a route therefore has no effect
 * on the thing you are currently building either - what you clicked is what you get.</p>
 */
public class ScenarioDraft {

    public final Scenario type;
    public final ScenarioSettings settings;
    public final List<ScenarioInstance.Point> points = new ArrayList<>();
    public final Set<String> bots = new LinkedHashSet<>();

    public ScenarioDraft(Scenario type, ScenarioSettings settings) {
        this.type = type;
        this.settings = settings;
    }

    public boolean addPoint(Vec3 point) {
        if (type.isSinglePoint() && !points.isEmpty()) return false;
        points.add(new ScenarioInstance.Point(point));
        return true;
    }

    public boolean removeLastPoint() {
        if (points.isEmpty()) return false;
        points.remove(points.size() - 1);
        return true;
    }

    /** Whether there is enough here to make a working scenario. */
    public String validate() {
        return switch (type) {
            case SPAWN -> points.isEmpty() ? "Place a spawn point first." : null;
            case CONVERSATION -> bots.size() < 2
                ? "Click at least two bots with the Conversation Stick." : null;
            case CROWD -> bots.isEmpty() ? "No bots in range - run /bots scenarios crowd first." : null;
            case PRISON_CELL -> bots.isEmpty()
                ? "No bots assigned - use the Prison Stick first." : null;
        };
    }
}
