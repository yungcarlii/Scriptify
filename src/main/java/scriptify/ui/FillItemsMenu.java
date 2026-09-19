package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;
import scriptify.feature.fill.FillCategory;
import scriptify.feature.fill.FillSettings;
import scriptify.runtime.ScriptifyRuntime;

/**
 * Every item in one category, pickable one at a time.
 *
 * <p>Turning a whole category on is a blunt instrument: "building blocks" is several hundred items,
 * and a chest filled from all of them looks like a creative inventory exploded rather than like
 * somebody's storage. This is the other half — open the category, tick the eight blocks the scene
 * actually wants.
 *
 * <p>Ticking anything here adds it to the hand-picked list, which is independent of whether the
 * category itself is enabled. The header says which of the two is currently in play, because a
 * chest filled from an enabled category will not obviously reflect the ticks underneath it.
 */
public final class FillItemsMenu {
	private static final int[] CELLS = {
		9, 10, 11, 12, 13, 14, 15, 16, 17,
		18, 19, 20, 21, 22, 23, 24, 25, 26,
		27, 28, 29, 30, 31, 32, 33, 34, 35,
		36, 37, 38, 39, 40, 41, 42, 43, 44,
	};
	private static final int PREVIOUS_SLOT = 47;
	private static final int NEXT_SLOT = 51;

	private FillItemsMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player, String categoryId, int page) {
		FillCategory category = FillCategory.byId(categoryId);
		if (category == null) {
			FillMenu.open(context, player);
			return;
		}
		FillSettings settings = ScriptifyRuntime.fill().settings(player);
		List<Item> pool = category.pool();
		int totalPages = Math.max(1, (pool.size() + CELLS.length - 1) / CELLS.length);
		int current = Math.max(0, Math.min(page, totalPages - 1));
		boolean wholeCategory = settings.isEnabled(categoryId);

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Component.literal("» " + category.displayName() + " «")
			.withStyle(ChatFormatting.GOLD));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		int picked = countPicked(settings, pool);
		gui.setSlot(4, Menus.button(category.icon(), category.displayName(), ChatFormatting.GOLD,
				pool.size() + " survival-obtainable item(s).",
				picked == 0 ? "None picked." : picked + " picked from this category.",
				"",
				wholeCategory
					? "The whole category is switched on,"
					: "Click items below to pick them.",
				wholeCategory
					? "so every item here is already in the pool."
					: "")
			.build());

		for (int cell = 0; cell < CELLS.length; cell++) {
			int index = current * CELLS.length + cell;
			if (index >= pool.size()) {
				break;
			}
			Item item = pool.get(index);
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			boolean chosen = settings.items().contains(id);

			ItemStack icon = new ItemStack(item);
			GuiElementBuilder button = GuiElementBuilder.from(icon)
				.hideDefaultTooltip()
				.setName(icon.getHoverName().copy()
					.withStyle(chosen ? ChatFormatting.GREEN : ChatFormatting.GRAY))
				.addLoreLine(Component.empty())
				.addLoreLine(chosen ? Msg.line("Picked", ChatFormatting.GREEN) : Msg.hint("Not picked"))
				.addLoreLine(Msg.hint(id))
				.addLoreLine(Component.empty())
				.addLoreLine(Msg.hint(chosen ? "Click to remove" : "Click to add"));
			if (chosen) {
				button.glow();
			}

			gui.setSlot(CELLS[cell], button.setCallback((slot, type, action, ignored) -> {
				if (chosen) {
					settings.removeItem(id);
				} else {
					settings.addItem(id);
				}
				Sfx.click(player);
				open(context, player, categoryId, current);
			}).build());
		}

		if (current > 0) {
			gui.setSlot(PREVIOUS_SLOT, Menus.previousPageButton(current, totalPages)
				.setCallback((slot, type, action, ignored) -> open(context, player, categoryId, current - 1))
				.build());
		}
		if (current < totalPages - 1) {
			gui.setSlot(NEXT_SLOT, Menus.nextPageButton(current + 2, totalPages)
				.setCallback((slot, type, action, ignored) -> open(context, player, categoryId, current + 1))
				.build());
		}

		gui.setSlot(49, Menus.toggle(Items.HOPPER, "Whole Category", wholeCategory,
				"Adds every item in this category at once,",
				"ignoring the picks above.")
			.setCallback((slot, type, action, ignored) -> {
				settings.toggle(categoryId);
				Menus.notifyToggle(player, category.displayName() + " "
					+ (settings.isEnabled(categoryId) ? "included" : "excluded"),
					settings.isEnabled(categoryId));
				open(context, player, categoryId, current);
			}).build());

		gui.setSlot(48, Menus.button(Items.BARRIER, "Clear Picks", ChatFormatting.RED,
				"Unpicks every item from this category.",
				"",
				"Picks from other categories are kept.")
			.setCallback((slot, type, action, ignored) -> {
				for (Item item : pool) {
					settings.removeItem(BuiltInRegistries.ITEM.getKey(item).toString());
				}
				Menus.notify(player, "Cleared picks from " + category.displayName() + ".");
				open(context, player, categoryId, current);
			}).build());

		gui.setSlot(45, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> FillMenu.open(context, player)).build());
		gui.setSlot(53, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	/** How many of this category's items are on the hand-picked list. */
	private static int countPicked(FillSettings settings, List<Item> pool) {
		int count = 0;
		for (Item item : pool) {
			if (settings.items().contains(BuiltInRegistries.ITEM.getKey(item).toString())) {
				count++;
			}
		}
		return count;
	}
}
