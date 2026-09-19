package scriptify.ui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;

/**
 * A reusable "pick an online player" screen.
 *
 * <p>Anything that acts on another player routes through this instead of asking operators to type
 * names — the grid of faces is faster, and it removes a whole class of typo.
 */
public final class PlayerPicker {
	private static final int[] CONTENT_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
		37, 38, 39, 40, 41, 42, 43,
	};
	private static final int PREVIOUS_SLOT = 45;
	private static final int NEXT_SLOT = 53;
	private static final int BACK_SLOT = 48;

	private PlayerPicker() {
	}

	/** What happens when a face is clicked. */
	@FunctionalInterface
	public interface Handler {
		void pick(ServerPlayer target, ClickType click);
	}

	/** Per-entry lore, so each screen can describe its own click actions under every head. */
	@FunctionalInterface
	public interface Describer {
		List<String> describe(ServerPlayer target);
	}

	public static void open(ScriptifyContext context, ServerPlayer viewer, String title,
			ChatFormatting colour, Describer describer, Handler handler, int page) {
		open(context, viewer, title, colour, describer, handler, page, false);
	}

	/**
	 * @param realPlayersOnly leaves the scene bots out. Wanted by screens about people - who holds a
	 *                        rank, say - and not by the ones where a bot is a perfectly good target,
	 *                        such as skins or nicknames.
	 */
	public static void open(ScriptifyContext context, ServerPlayer viewer, String title,
			ChatFormatting colour, Describer describer, Handler handler, int page,
			boolean realPlayersOnly) {
		List<ServerPlayer> players = new ArrayList<>(context.server().getPlayerList().getPlayers());
		if (realPlayersOnly) {
			players.removeIf(scriptify.access.FakePlayers::isFake);
		}
		players.sort(Comparator.comparing(player -> player.getGameProfile().name().toLowerCase()));

		int perPage = CONTENT_SLOTS.length;
		int totalPages = Math.max(1, (players.size() + perPage - 1) / perPage);
		int currentPage = Math.max(0, Math.min(page, totalPages - 1));

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), viewer, false);
		gui.setTitle(Component.literal("» " + title + " «").withStyle(colour));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		for (int slot = 0; slot < perPage; slot++) {
			int index = currentPage * perPage + slot;
			if (index >= players.size()) {
				break;
			}
			ServerPlayer target = players.get(index);
			String[] lore = describer.describe(target).toArray(new String[0]);
			gui.setSlot(CONTENT_SLOTS[slot], Menus.playerHead(target, colour, lore)
				.setCallback((clicked, type, action, ignored) -> handler.pick(target, type))
				.build());
		}

		if (players.isEmpty()) {
			gui.setSlot(22, Menus.button(scriptify.ui.DyedItems.glassPane(DyeColor.LIGHT_GRAY), "Nobody online",
				ChatFormatting.GRAY, "There is no one to pick right now.").build());
		}

		if (currentPage > 0) {
			gui.setSlot(PREVIOUS_SLOT, Menus.previousPageButton(currentPage, totalPages)
				.setCallback((slot, type, action, ignored) ->
					open(context, viewer, title, colour, describer, handler, currentPage - 1)).build());
		}
		if (currentPage < totalPages - 1) {
			gui.setSlot(NEXT_SLOT, Menus.nextPageButton(currentPage + 2, totalPages)
				.setCallback((slot, type, action, ignored) ->
					open(context, viewer, title, colour, describer, handler, currentPage + 1)).build());
		}

		gui.setSlot(BACK_SLOT, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, viewer)).build());
		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}
}
