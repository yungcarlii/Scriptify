package scriptify.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScriptifyEvent {
	private final String id;
	private final boolean enabled;
	private final String trigger;
	private final List<Action> actions;

	public ScriptifyEvent(String id, boolean enabled, String trigger, List<Action> actions) {
		this.id = id;
		this.enabled = enabled;
		this.trigger = trigger;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
	}

	public static ScriptifyEvent from(JsonObject object) {
		String id = object.get("id").getAsString();
		boolean enabled = !object.has("enabled") || object.get("enabled").getAsBoolean();
		String trigger = object.has("trigger") ? object.get("trigger").getAsString() : "manual";
		List<Action> actions = new ArrayList<>();
		if (object.has("actions") && object.get("actions").isJsonArray()) {
			JsonArray array = object.getAsJsonArray("actions");
			for (JsonElement element : array) {
				if (element.isJsonObject()) {
					actions.add(Action.from(element.getAsJsonObject()));
				}
			}
		}
		return new ScriptifyEvent(id, enabled, trigger, actions);
	}

	public String id() {
		return this.id;
	}

	public boolean enabled() {
		return this.enabled;
	}

	public String trigger() {
		return this.trigger;
	}

	public List<Action> actions() {
		return this.actions;
	}

	/** The same event with a different step list - how a cue is edited without touching the loaded copy. */
	public ScriptifyEvent withActions(List<Action> actions) {
		return new ScriptifyEvent(this.id, this.enabled, this.trigger, actions);
	}

	/**
	 * A recorded command appended after a delay covering the real gap since the previous step, so a
	 * rehearsed scene plays back with the timing it was performed with. The first step of an empty cue
	 * gets no lead-in: the time spent deciding what to type first is not part of the scene.
	 */
	public ScriptifyEvent withRecorded(long gapTicks, String command) {
		List<Action> next = new ArrayList<>(this.actions);
		if (!next.isEmpty() && gapTicks > 0) {
			next.add(Action.delay((int) Math.min(Integer.MAX_VALUE, gapTicks)));
		}
		next.add(Action.command(command));
		return withActions(next);
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.addProperty("id", this.id);
		root.addProperty("enabled", this.enabled);
		root.addProperty("trigger", this.trigger);
		JsonArray array = new JsonArray();
		for (Action action : this.actions) {
			array.add(action.data());
		}
		root.add("actions", array);
		return root;
	}

	public record Action(String type, JsonObject data) {
		public static Action from(JsonObject object) {
			String type = object.has("type") ? object.get("type").getAsString() : "message";
			return new Action(type, object);
		}

		public static Action command(String command) {
			JsonObject data = new JsonObject();
			data.addProperty("type", "command");
			data.addProperty("command", command);
			return new Action("command", data);
		}

		public static Action delay(int ticks) {
			JsonObject data = new JsonObject();
			data.addProperty("type", "delay");
			data.addProperty("ticks", ticks);
			return new Action("delay", data);
		}

		/** How long this step holds the cue, in ticks. Only a delay waits; every other step is instant. */
		public int ticks() {
			return "delay".equals(this.type) && this.data.has("ticks")
				? Math.max(0, this.data.get("ticks").getAsInt()) : 0;
		}

		public static boolean success(Action ignored, net.minecraft.commands.CommandSourceStack source) {
			return true;
		}
	}
}
