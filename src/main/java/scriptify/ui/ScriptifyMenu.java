package scriptify.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.chat.Component;

public final class ScriptifyMenu {
	private final String id;
	private final Component title;
	private final int rows;
	private final List<MenuSlot> slots;

	public ScriptifyMenu(String id, Component title, int rows, List<MenuSlot> slots) {
		this.id = id;
		this.title = title;
		this.rows = rows;
		this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
	}

	public String id() {
		return this.id;
	}

	public Component title() {
		return this.title;
	}

	public int rows() {
		return this.rows;
	}

	public List<MenuSlot> slots() {
		return this.slots;
	}
}
