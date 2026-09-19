package scriptify.bots.npc;

/**
 * What a bot <em>is</em>, as opposed to how it fights.
 *
 * <p>Deliberately separate from {@code BotSettings}: that file is server-wide combat tuning,
 * while this is a per-bot description that can also exist as a default and as a group override.
 * The same class serves all three roles, which is what makes "defaults apply to new bots,
 * groups override defaults, a single bot overrides its group" work without three parallel
 * models drifting apart.</p>
 *
 * <p>Every field is boxed. Null means "not set at this level, ask the level below" - that is
 * the entire mechanism behind the override chain, and it is why a group can change one toggle
 * without silently pinning every other value to whatever the defaults happened to be when the
 * group was made.</p>
 */
public class NpcSettings {

    /** Can be attacked. Off makes the bot a prop. */
    public Boolean hittable;
    /** Pushes and is pushed by other entities. */
    public Boolean collidable;
    /** Holds a crouch. */
    public Boolean crouching;
    /** Appears in the tab list. */
    public Boolean listed;
    /** Survives anything - the totem slot in the menu. */
    public Boolean immortal;
    /** Leaves its gear on the floor when it dies. */
    public Boolean dropsOnDeath;
    /** Mirrors the potion effects of whoever spawned or edited it. */

    /** Repeating action: none, swing, jump, or hold right click. */
    public Action action;
    /** Kit handed to the bot on spawn, by name. */
    public String kit;

    public enum Action {
        NONE("None", "Stands there"),
        SWING("Swing", "Spams attack, timings jittered"),
        JUMP("Jump", "Hops continuously"),
        USE("Hold use", "Holds right click");

        public final String label;
        public final String description;

        Action(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public Action next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** The values a bot gets when nothing overrides them. */
    public static NpcSettings defaults() {
        NpcSettings s = new NpcSettings();
        s.hittable = true;
        s.collidable = true;
        s.crouching = false;
        s.listed = true;
        s.immortal = false;
        s.dropsOnDeath = true;
        s.action = Action.NONE;
        s.kit = null;
        return s;
    }

    /**
     * Lays {@code over} on top of this <em>in place</em>, keeping anything it does not specify.
     *
     * <p>Order matters and reads the way the chain does: defaults, then group, then bot.</p>
     *
     * <p>This used to return a new object, so resolving one bot allocated three of them - and
     * resolving happens for every bot on every tick. At the two hundred and fifty bots this mod is
     * built for that was fifteen thousand throwaway objects a second, to answer a question whose
     * answer almost never changes. Layering into one object makes it one.</p>
     */
    public void overlay(NpcSettings over) {
        if (over == null) return;
        if (over.hittable != null) hittable = over.hittable;
        if (over.collidable != null) collidable = over.collidable;
        if (over.crouching != null) crouching = over.crouching;
        if (over.listed != null) listed = over.listed;
        if (over.immortal != null) immortal = over.immortal;
        if (over.dropsOnDeath != null) dropsOnDeath = over.dropsOnDeath;
        if (over.action != null) action = over.action;
        if (over.kit != null) kit = over.kit;
    }

    public NpcSettings copy() {
        NpcSettings c = new NpcSettings();
        c.hittable = hittable;
        c.collidable = collidable;
        c.crouching = crouching;
        c.listed = listed;
        c.immortal = immortal;
        c.dropsOnDeath = dropsOnDeath;
        c.action = action;
        c.kit = kit;
        return c;
    }

    // ---- null-safe reads, for code that just wants an answer -------------------------

    public boolean hittable() { return hittable == null || hittable; }
    public boolean collidable() { return collidable == null || collidable; }
    public boolean crouching() { return crouching != null && crouching; }
    public boolean listed() { return listed == null || listed; }
    public boolean immortal() { return immortal != null && immortal; }
    public boolean dropsOnDeath() { return dropsOnDeath == null || dropsOnDeath; }
    public Action action() { return action == null ? Action.NONE : action; }
}
