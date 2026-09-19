package scriptify.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;
import scriptify.permission.PermissionService;
import scriptify.permission.RankService;

/**
 * One screen for who is allowed to do what.
 *
 * <p>There were two, and they overlapped badly. Permissions opened a player picker and granted nodes
 * one person at a time; ranks was a separate command with its own list, and its back button led to
 * the permissions picker - so the two screens each behaved as though they were the top of the tree
 * and you could arrive at the same rank by two routes that looked unrelated. Neither said how it
 * related to the other, which is the actual question anybody has here.
 *
 * <p>They are one thing, so this is one screen and it says so: <b>ranks are the answer</b>, and a
 * per-player grant is an exception on top of one. Ranks are the body of the screen because that is
 * what should be edited most; players are a single door at the bottom, and behind it the same screen
 * shows both the person's rank and their exceptions together rather than pretending the rank is
 * somewhere else.
 */
public final class AccessMenu {

	/** Two rows of ranks, which is more than any crew has needed. */
	private static final int[] RANK_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
	};

	private AccessMenu() {
	}

	/** The one entry point. */
	public static void open(ScriptifyContext context, ServerPlayer viewer) {
		if (!context.permissions().has(viewer, PermissionService.ADMIN)) {
			Menus.deny(viewer, "Only Scriptify admins can edit access.");
			return;
		}
		RankService ranks = context.permissions().ranks();
		List<RankService.Rank> all = ranks.all();

		SimpleGui gui = new SimpleGui(Menus.typeForRows(5), viewer, false);
		gui.setTitle(Component.literal("» Access «").withStyle(ChatFormatting.GOLD));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.button(Items.BOOKSHELF, "Access", ChatFormatting.GOLD,
				"A rank is what somebody is allowed to do -",
				"Scriptify features and vanilla commands both.",
				"Change the rank and everyone on it changes.",
				"",
				"Players with no rank count as " + RankService.DEFAULT_RANK + ".",
				context.permissions().isManaged()
					? ""
					: "LuckPerms is in charge here, so these are stored but not obeyed.").build());

		for (int cell = 0; cell < RANK_SLOTS.length && cell < all.size(); cell++) {
			RankService.Rank rank = all.get(cell);
			boolean isDefault = RankService.DEFAULT_RANK.equals(rank.name());
			gui.setSlot(RANK_SLOTS[cell], Menus.button(
					isDefault ? Items.PAPER : Items.WRITTEN_BOOK,
					rank.label(), rank.colour(),
					rank.nodes().size() + " Scriptify permission(s).",
					"Command level " + rank.commandLevel() + ", " + modeName(rank) + ".",
					rank.prefix().isEmpty() ? "No prefix." : "Prefix: " + rank.prefix(),
					"",
					isDefault ? "Everyone starts here." : "Click to edit.",
					isDefault ? "Click to edit." : "Shift click to delete.")
				.setCallback((slot, type, action, ignored) -> {
					if (type.shift && !isDefault) {
						ranks.delete(rank.name());
						Menus.notify(viewer, "Deleted the " + rank.label() + " rank.");
						open(context, viewer);
					} else {
						Sfx.click(viewer);
						RanksMenu.openRank(context, viewer, rank.name());
					}
				}).build());
		}

		gui.setSlot(38, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "New Rank", ChatFormatting.GREEN,
				"Creates an empty rank.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				RanksMenu.promptNewRank(context, viewer);
			}).build());

		gui.setSlot(42, Menus.button(Items.PLAYER_HEAD, "Players", ChatFormatting.AQUA,
				"Give somebody a rank, or grant them",
				"a one-off exception on top of it.",
				"",
				"Click to pick someone.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				players(context, viewer);
			}).build());

		gui.setSlot(36, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> MainMenu.open(context, viewer)).build());
		gui.setSlot(44, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	/** The player picker, which leads to the combined rank-and-exceptions screen. */
	public static void players(ScriptifyContext context, ServerPlayer viewer) {
		PlayerPicker.open(context, viewer, "Players", ChatFormatting.GOLD,
			target -> {
				int granted = context.permissions().granted(target.getUUID()).size();
				return List.of(
					"Rank: " + context.permissions().ranks().of(target.getUUID()).label(),
					granted == 0 ? "No exceptions." : granted + " exception(s) on top.",
					"Click to edit.");
			},
			(target, click) -> PermissionsMenu.openFor(context, viewer, target), 0, true);
	}

	/**
	 * Choosing which rank somebody holds.
	 *
	 * <p>Reached from that player's own screen, because "what rank is this person" is a question about
	 * the person. Sending them off to the rank list to find a member roster - which is how this
	 * worked - answers it in the wrong place.
	 */
	public static void chooseRank(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target) {
		RankService ranks = context.permissions().ranks();
		List<RankService.Rank> all = ranks.all();
		String current = ranks.of(target.getUUID()).name();

		SimpleGui gui = new SimpleGui(Menus.typeForRows(5), viewer, false);
		gui.setTitle(Component.literal("» " + target.getGameProfile().name() + "'s rank «")
			.withStyle(ChatFormatting.GOLD));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.playerHead(target, ChatFormatting.GOLD,
			"Currently " + ranks.of(target.getUUID()).label() + ".").build());

		for (int cell = 0; cell < RANK_SLOTS.length && cell < all.size(); cell++) {
			RankService.Rank rank = all.get(cell);
			boolean held = rank.name().equals(current);
			gui.setSlot(RANK_SLOTS[cell], Menus.toggle(
					held ? Items.WRITTEN_BOOK : Items.PAPER, rank.label(), held,
					rank.nodes().size() + " permission(s), command level "
						+ rank.commandLevel() + ".",
					"",
					held ? "This is their rank." : "Click to move them here.")
				.setCallback((slot, type, action, ignored) -> {
					if (held) {
						return;
					}
					if (!ranks.assign(target.getUUID(), rank.name())) {
						Menus.deny(viewer, "Ranks are for players, not bots.");
						return;
					}
					Menus.notify(viewer, target.getGameProfile().name()
						+ " is now " + rank.label() + ".");
					chooseRank(context, viewer, target);
				}).build());
		}

		gui.setSlot(36, Menus.backButton()
			.setCallback((slot, type, action, ignored) ->
				PermissionsMenu.openFor(context, viewer, target)).build());
		gui.setSlot(44, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	private static String modeName(RankService.Rank rank) {
		return switch (rank.commandMode()) {
			case ONLY -> "only listed commands";
			case EXCEPT -> "all but listed";
			default -> "all commands";
		};
	}
}
