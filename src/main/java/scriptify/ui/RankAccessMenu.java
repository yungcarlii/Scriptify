package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;
import scriptify.permission.RankService;

/** Reusable multi-rank allow-list editor for saved GUI resources. */
public final class RankAccessMenu {
	private static final int[] RANK_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
		37, 38, 39, 40, 41, 42, 43,
	};

	/** Resource-specific persistence and navigation hooks. */
	public interface Controller {
		Set<String> allowedRanks();
		boolean setAllowed(String rank, boolean allowed);
		void clear();
		void back();
	}

	private RankAccessMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer, String title,
			String usePermissionLabel, Controller controller, int page) {
		List<RankService.Rank> ranks = context.permissions().ranks().all();
		int perPage = RANK_SLOTS.length;
		int totalPages = Math.max(1, (ranks.size() + perPage - 1) / perPage);
		int currentPage = Math.max(0, Math.min(page, totalPages - 1));
		Set<String> allowed = controller.allowedRanks();

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), viewer, false);
		gui.setTitle(Msg.header(title + " access", ChatFormatting.LIGHT_PURPLE));
		gui.setLockPlayerInventory(true);
		Menus.frame(gui);
		gui.setSlot(4, Menus.button(Items.TRIPWIRE_HOOK, "Rank Access", ChatFormatting.LIGHT_PURPLE,
			allowed.isEmpty() ? "Every eligible rank can use this." : allowed.size() + " selected rank(s) can use this.",
			"",
			"Selecting any rank makes this an allow-list.",
			"Selected ranks still need " + usePermissionLabel + ".").build());

		for (int cell = 0; cell < perPage; cell++) {
			int index = currentPage * perPage + cell;
			if (index >= ranks.size()) break;
			RankService.Rank rank = ranks.get(index);
			boolean enabled = allowed.contains(rank.name());
			gui.setSlot(RANK_SLOTS[cell], Menus.toggle(enabled ? scriptify.ui.DyedItems.dye(DyeColor.LIME) : scriptify.ui.DyedItems.dye(DyeColor.GRAY),
				rank.label(), enabled, enabled ? "Can use this." : "Cannot use this.", "",
				enabled ? "Click to remove this rank." : "Click to allow this rank.")
				.setCallback((slot, type, action, ignored) -> {
					controller.setAllowed(rank.name(), !enabled);
					Sfx.click(viewer);
					open(context, viewer, title, usePermissionLabel, controller, currentPage);
				}).build());
		}

		if (!allowed.isEmpty()) {
			gui.setSlot(46, Menus.button(Items.ENDER_CHEST, "Allow Every Eligible Rank", ChatFormatting.GREEN,
				"Clears this resource's rank allow-list.")
				.setCallback((slot, type, action, ignored) -> {
					controller.clear();
					open(context, viewer, title, usePermissionLabel, controller, currentPage);
				}).build());
		}
		if (currentPage > 0) {
			gui.setSlot(48, Menus.previousPageButton(currentPage, totalPages)
				.setCallback((slot, type, action, ignored) ->
					open(context, viewer, title, usePermissionLabel, controller, currentPage - 1)).build());
		}
		if (currentPage < totalPages - 1) {
			gui.setSlot(50, Menus.nextPageButton(currentPage + 2, totalPages)
				.setCallback((slot, type, action, ignored) ->
					open(context, viewer, title, usePermissionLabel, controller, currentPage + 1)).build());
		}
		gui.setSlot(45, Menus.backButton().setCallback((slot, type, action, ignored) -> controller.back()).build());
		gui.setSlot(53, Menus.closeButton().setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());
		gui.open();
		Sfx.menuOpen(viewer);
	}
}
