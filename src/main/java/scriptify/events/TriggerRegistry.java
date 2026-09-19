package scriptify.events;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class TriggerRegistry {
	private final Set<String> triggers = new LinkedHashSet<>();

	public void register(String id) {
		this.triggers.add(id);
	}

	public boolean contains(String id) {
		return this.triggers.contains(id);
	}

	public Set<String> triggers() {
		return Collections.unmodifiableSet(this.triggers);
	}
}
