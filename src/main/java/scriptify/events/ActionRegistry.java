package scriptify.events;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ActionRegistry {
	private final Map<String, EventActionHandler> handlers = new LinkedHashMap<>();

	public void register(String id, EventActionHandler handler) {
		this.handlers.put(id, handler);
	}

	public EventActionHandler get(String id) {
		return this.handlers.get(id);
	}

	public Map<String, EventActionHandler> handlers() {
		return Collections.unmodifiableMap(this.handlers);
	}
}
