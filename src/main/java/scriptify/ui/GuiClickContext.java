package scriptify.ui;

import eu.pb4.sgui.api.ClickType;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;

public record GuiClickContext(ScriptifyContext scriptify, ServerPlayer player, int slot, ClickType clickType) {
}
