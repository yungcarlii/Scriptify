package scriptify.ui;

@FunctionalInterface
public interface SlotClickHandler {
	void click(GuiClickContext context);
}
