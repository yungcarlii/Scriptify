package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;

/**
 * Bridges the module API's generic menu abstraction onto sgui.
 *
 * <p>The dashboard itself lives in {@link MainMenu}; this only handles the {@link ScriptifyMenu}
 * descriptors that add-on modules register.
 */
public final class SguiGuiService implements GuiService {
	private final MenuRegistry registry = new MenuRegistry();

	@Override
	public MenuRegistry registry() {
		return this.registry;
	}

	@Override
	public void openDashboard(ScriptifyContext context, ServerPlayer player) {
		MainMenu.open(context, player);
	}

	@Override
	public void openMenu(ScriptifyContext context, ServerPlayer player, ScriptifyMenu menu) {
		SimpleGui gui = new SimpleGui(Menus.typeForRows(menu.rows()), player, false);
		gui.setTitle(menu.title());
		gui.setLockPlayerInventory(true);
		for (MenuSlot slot : menu.slots()) {
			GuiElementBuilder builder = new GuiElementBuilder(slot.item()).setName(slot.name());
			if (slot.lore() != null) {
				builder.addLoreLine(slot.lore());
			}
			builder.setCallback((index, type, action, ignored) ->
				slot.handler().click(new GuiClickContext(context, player, index, type)));
			gui.setSlot(slot.index(), builder.build());
		}
		gui.open();
	}
}
