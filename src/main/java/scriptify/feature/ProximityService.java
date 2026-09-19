package scriptify.feature;

import com.google.gson.JsonObject;
import scriptify.api.ScriptifyContext;

/**
 * Proximity chat: a player only hears people standing near them.
 *
 * <p>For scenes. A conversation in a base should not be readable by everyone on the server, and a
 * shout across a field should not carry to another biome — proximity is what makes a scripted scene
 * feel like it is happening somewhere rather than in a chat room.
 *
 * <p>Scoped to player chat on purpose. Private messages, system lines, join and leave notices, death
 * messages and command broadcasts such as {@code /say} all reach everyone as they always did. Those
 * either have no source point at all, or are the tools someone runs the server with — a director who
 * cannot announce anything to the whole cast has lost something they need.
 *
 * <p>Configured from the menu only, as specified. There is no proximity command.
 */
public final class ProximityService {
	private static final String MODULE = "proximity";
	private static final String FILE = "state.json";
	/** Roughly a large room. Close enough to be a real limit, far enough not to feel broken. */
	public static final double DEFAULT_RADIUS = 48.0D;
	public static final double MIN_RADIUS = 1.0D;
	/**
	 * Past this, proximity stops meaning anything: players outside a server's view distance are not
	 * loaded near each other anyway, so a larger number would only look like a setting that does
	 * nothing.
	 */
	public static final double MAX_RADIUS = 512.0D;

	private final ScriptifyContext context;
	private boolean enabled;
	private double radius = DEFAULT_RADIUS;

	public ProximityService(ScriptifyContext context) {
		this.context = context;
	}

	public void load() {
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, ProximityService::defaults);
		this.enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
		this.radius = root.has("radius") ? clamp(root.get("radius").getAsDouble()) : DEFAULT_RADIUS;
		// Push to the registry even when loading defaults: the mixins read from there, and skipping this
		// on a fresh install is the kind of gap that leaves a feature silently inert.
		apply();
	}

	public void save() {
		JsonObject root = new JsonObject();
		root.addProperty("enabled", this.enabled);
		root.addProperty("radius", this.radius);
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	private static JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("enabled", false);
		root.addProperty("radius", DEFAULT_RADIUS);
		return root;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public double radius() {
		return this.radius;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		apply();
		save();
	}

	public void setRadius(double radius) {
		this.radius = clamp(radius);
		apply();
		save();
	}

	private void apply() {
		ProximityRegistry.configure(this.enabled, this.radius);
	}

	/** Called when the server stops, so a lingering static does not filter the next world's chat. */
	public static void reset() {
		ProximityRegistry.configure(false, 0.0D);
		ProximityRegistry.endChat();
	}

	public static double clamp(double radius) {
		return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
	}
}
