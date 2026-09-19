package scriptify.bots.scenario;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One scenario that has been set up and is running.
 *
 * <p>It owns its own {@link ScenarioSettings} rather than pointing at the template it was
 * created from, so a scenario keeps behaving the way it did on the day it was built. Editing
 * the menu afterwards sets up the <em>next</em> one.</p>
 */
public class ScenarioInstance {

    /** Gson-friendly point; {@link Vec3} is awkward to serialise and easy to get wrong. */
    public static class Point {
        public double x;
        public double y;
        public double z;

        public Point() {}

        public Point(Vec3 v) {
            this.x = v.x;
            this.y = v.y;
            this.z = v.z;
        }

        public Vec3 toVec() {
            return new Vec3(x, y, z);
        }
    }

    public int id;
    public Scenario type;
    public ScenarioSettings settings = new ScenarioSettings();
    public List<Point> points = new ArrayList<>();
    /** Bot names this scenario drives, in the order they were added. */
    public Set<String> bots = new LinkedHashSet<>();
    /** Names or nicknames a crowd is watching. */
    public List<String> watchTargets = new ArrayList<>();
    public String dimension = "minecraft:overworld";
    public boolean enabled = true;
    /** Free-text label shown in listings. */
    public String label = "";

    // ---- runtime, never persisted -------------------------------------------------------
    public transient Map<String, Roamer.State> roamers = new HashMap<>();
    public transient Map<String, Integer> timers = new HashMap<>();
    public transient Object runtime;
    /** True on the ticks where the anchor chunk is loaded and the scenario should run. */
    public transient boolean live;

    public Vec3 anchor() {
        if (points.isEmpty()) return null;
        return points.get(0).toVec();
    }

    public Roamer.State roamer(String botName) {
        if (roamers == null) roamers = new HashMap<>();
        return roamers.computeIfAbsent(scriptify.bots.utils.Names.key(botName), k -> new Roamer.State());
    }

    public int timer(String key) {
        if (timers == null) timers = new HashMap<>();
        return timers.getOrDefault(key, 0);
    }

    public void setTimer(String key, int value) {
        if (timers == null) timers = new HashMap<>();
        timers.put(key, value);
    }

    /** Decrements a timer and reports whether it just reached zero. */
    public boolean tickTimer(String key) {
        int value = timer(key);
        if (value <= 0) return true;
        timers.put(key, value - 1);
        return value - 1 <= 0;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(type.displayName);
        if (!label.isEmpty()) sb.append(" \"").append(label).append('"');
        if (!points.isEmpty()) {
            Point p = points.get(0);
            sb.append(String.format(java.util.Locale.US, " at %.0f %.0f %.0f", p.x, p.y, p.z));
        }
        if (!bots.isEmpty()) sb.append(" (").append(bots.size()).append(" bots)");
        return sb.toString();
    }
}
