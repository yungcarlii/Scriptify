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

	public record Action(String type, JsonObject data) {
		public static Action from(JsonObject object) {
			String type = object.has("type") ? object.get("type").getAsString() : "message";
			return new Action(type, object);
		}

		public static boolean success(Action ignored, net.minecraft.commands.CommandSourceStack source) {
			return true;
		}
	}
}
