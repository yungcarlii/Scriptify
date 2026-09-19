package scriptify.events;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import scriptify.storage.DataStore;

public final class EventEngine {
	private final DataStore storage;
	private final Logger logger;
	private final ActionRegistry actions = new ActionRegistry();
	private final TriggerRegistry triggers = new TriggerRegistry();
	private final Map<String, ScriptifyEvent> events = new LinkedHashMap<>();

	public EventEngine(DataStore storage, Logger logger) {
		this.storage = storage;
		this.logger = logger;
		registerBuiltInActions();
	}

	public ActionRegistry actions() {
		return this.actions;
	}

	public TriggerRegistry triggers() {
		return this.triggers;
	}

	public Map<String, ScriptifyEvent> events() {
		return Collections.unmodifiableMap(this.events);
	}

	public void reload() {
		this.events.clear();
		for (var stored : this.storage.loadObjects("events", "")) {
			try {
				ScriptifyEvent event = ScriptifyEvent.from(stored.object());
				if (!this.triggers.contains(event.trigger())) {
					this.logger.warn("Skipping Scriptify event '{}' with unknown trigger '{}'.", event.id(), event.trigger());
					continue;
				}
				this.events.put(event.id(), event);
			} catch (RuntimeException exception) {
				this.logger.warn("Skipping invalid Scriptify event file {}: {}", stored.path(), exception.getMessage());
			}
		}
		ensureExampleEvent();
	}

	public boolean run(String id, CommandSourceStack source) {
		ScriptifyEvent event = this.events.get(id);
		if (event == null || !event.enabled()) {
			return false;
		}
		for (ScriptifyEvent.Action action : event.actions()) {
			EventActionHandler handler = this.actions.get(action.type());
			if (handler == null) {
				this.logger.warn("Event '{}' references unknown action '{}'.", event.id(), action.type());
				continue;
			}
			handler.run(action, source);
		}
		return true;
	}

	public void runTrigger(String trigger, CommandSourceStack source) {
		for (ScriptifyEvent event : this.events.values()) {
			if (event.enabled() && event.trigger().equals(trigger)) {
				run(event.id(), source);
			}
		}
	}

	private void registerBuiltInActions() {
		this.triggers.register("manual");
		this.triggers.register("player_join");
		this.actions.register("delay", (action, source) -> true);
		this.actions.register("message", (action, source) -> {
			String text = text(action, "text", "Scriptify event message.");
			source.sendSuccess(() -> Component.literal(text), false);
			return true;
		});
		this.actions.register("actionbar", (action, source) -> {
			if (source.getEntity() instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.literal(text(action, "text", "")), true);
			}
			return true;
		});
		this.actions.register("title", (action, source) -> {
			if (source.getEntity() instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.literal(text(action, "text", "")), false);
			}
			return true;
		});
		this.actions.register("command", (action, source) -> {
			String command = text(action, "command", "");
			if (!command.isBlank()) {
				source.getServer().getCommands().performPrefixedCommand(source, command);
			}
			return true;
		});
	}

	private void ensureExampleEvent() {
		if (!this.events.isEmpty()) {
			return;
		}
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.addProperty("id", "example");
		root.addProperty("enabled", false);
		root.addProperty("trigger", "manual");
		root.add("actions", new com.google.gson.JsonArray());
		this.storage.saveObject("events", "example.json", root);
	}

	private static String text(ScriptifyEvent.Action action, String key, String fallback) {
		return action.data().has(key) ? action.data().get(key).getAsString() : fallback;
	}
}
