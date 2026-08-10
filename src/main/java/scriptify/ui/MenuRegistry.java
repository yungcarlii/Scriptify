package scriptify.ui;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MenuRegistry {
	private final Map<String, ScriptifyMenu> menus = new LinkedHashMap<>();

	public void register(ScriptifyMenu menu) {
		this.menus.put(menu.id(), menu);
	}

	public Optional<ScriptifyMenu> get(String id) {
		return Optional.ofNullable(this.menus.get(id));
	}

	public Collection<ScriptifyMenu> menus() {
		return this.menus.values();
	}
}
