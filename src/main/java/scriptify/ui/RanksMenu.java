package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
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
 * Ranks: named permission bundles, and who holds them.
 *
 * <p>The list is the top level because a rank is the thing you usually want to change — adjusting
 * what "crew" can do fixes it for the whole crew, where editing people one at a time fixes it for
 * whoever you remembered.
 */
public final class RanksMenu {
	private static final int[] RANK_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
	};
	private static final int[] NODE_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
	};

	private RanksMenu() {
	}

	/** One rank: its nodes, its prefix and its colour. */
	public static void openRank(ScriptifyContext context, ServerPlayer viewer, String name) {
		RankService ranks = context.permissions().ranks();
		var found = ranks.byName(name);
		if (found.isEmpty()) {
			Menus.deny(viewer, "That rank is gone.");
			AccessMenu.open(context, viewer);
			return;
		}
		RankService.Rank rank = found.get();

		SimpleGui gui = new SimpleGui(Menus.typeForRows(5), viewer, false);
		gui.setTitle(Component.literal("» " + rank.label() + " «").withStyle(rank.colour()));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.button(Items.WRITTEN_BOOK, rank.label(), rank.colour(),
				rank.nodes().size() + " of " + PermissionService.ALL_NODES.size() + " permissions.",
				rank.prefix().isEmpty() ? "No prefix." : "Prefix: " + rank.prefix()).build());

		List<String> nodes = PermissionService.ALL_NODES;
		for (int cell = 0; cell < NODE_SLOTS.length && cell < nodes.size(); cell++) {
			String node = nodes.get(cell);
			boolean on = rank.nodes().contains(node);
			var info = PermissionsMenu.describe(node);
			gui.setSlot(NODE_SLOTS[cell], Menus.toggle(info.icon(), info.title(), on,
					info.description(),
					"",
					on ? "Click to remove from this rank." : "Click to add to this rank.")
				.setCallback((slot, type, action, ignored) -> {
					ranks.setNode(rank.name(), node, !on);
					Menus.notifyToggle(viewer, info.title() + (on ? " removed" : " added"), !on);
					openRank(context, viewer, rank.name());
				}).build());
		}

		gui.setSlot(31, Menus.button(Items.COMMAND_BLOCK, "Vanilla Commands", ChatFormatting.YELLOW,
				"What Minecraft's own commands this rank",
				"can use — /gamemode, /tp, /give and so on.",
				"",
				"Level: " + levelName(rank.commandLevel()),
				"Filter: " + modeName(rank))
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				openCommands(context, viewer, rank.name());
			}).build());

		gui.setSlot(38, Menus.button(Items.NAME_TAG, "Prefix", ChatFormatting.AQUA,
				"Shown before the name in menus.",
				"",
				rank.prefix().isEmpty() ? "None set." : "Now: " + rank.prefix())
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				promptPrefix(context, viewer, rank.name(), rank.prefix());
			}).build());

		gui.setSlot(40, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.RED), "Colour", ChatFormatting.LIGHT_PURPLE,
				"Click to cycle through the chat colours.",
				"",
				"Now: " + Colours.name(rank.colour()))
			.setCallback((slot, type, action, ignored) -> {
				ranks.setColour(rank.name(), nextColour(rank.colour()));
				Sfx.click(viewer);
				openRank(context, viewer, rank.name());
			}).build());

		gui.setSlot(29, Menus.button(Items.BOOK, "Members", ChatFormatting.AQUA,
				ranks.membersOf(rank.name()).size() + " player(s) in this rank.",
				"",
				"Click to see who they are.")
			.setCallback((slot, type, action, ignored) -> {
				Sfx.click(viewer);
				openMembers(context, viewer, rank.name());
			}).build());

		gui.setSlot(42, Menus.button(Items.PLAYER_HEAD, "Assign", ChatFormatting.GREEN,
				"Give this rank to a player.")
			.setCallback((slot, type, action, ignored) ->
				PlayerPicker.open(context, viewer, "Assign " + rank.label(), rank.colour(),
					target -> List.of(
						"Currently " + ranks.of(target.getUUID()).label() + ".",
						"Click to make them " + rank.label() + "."),
					(target, click) -> {
						ranks.assign(target.getUUID(), rank.name());
						Menus.notify(viewer,
							target.getGameProfile().name() + " is now " + rank.label() + ".");
						Sfx.success(viewer);
						openRank(context, viewer, rank.name());
					}, 0)).build());

		gui.setSlot(36, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> AccessMenu.open(context, viewer)).build());
		gui.setSlot(44, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	private static final int[] MEMBER_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};

	/**
	 * Who holds this rank.
	 *
	 * <p>Offline members are listed too — a rank is about who somebody is on this server, not about
	 * who happens to be logged in while you are looking. Names come from the server profile cache, so
	 * somebody who has never joined shows as their id.
	 */
	public static void openMembers(ScriptifyContext context, ServerPlayer viewer, String name) {
		RankService ranks = context.permissions().ranks();
		var found = ranks.byName(name);
		if (found.isEmpty()) {
			AccessMenu.open(context, viewer);
			return;
		}
		RankService.Rank rank = found.get();
		java.util.List<java.util.UUID> members = ranks.membersOf(rank.name());

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), viewer, false);
		gui.setTitle(Component.literal(rank.label() + " members").withStyle(rank.colour()));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.button(Items.BOOK, rank.label(), rank.colour(),
				members.size() + " player(s).",
				RankService.DEFAULT_RANK.equals(rank.name())
					? "Anyone with no rank counts here too." : "").build());

		var server = context.server();
		for (int cell = 0; cell < MEMBER_SLOTS.length && cell < members.size(); cell++) {
			java.util.UUID uuid = members.get(cell);
			ServerPlayer online = server.getPlayerList().getPlayer(uuid);
			String label = online != null ? online.getGameProfile().name() : ranks.nameOf(uuid);
			gui.setSlot(MEMBER_SLOTS[cell], Menus.button(Items.PLAYER_HEAD, label,
					online != null ? ChatFormatting.GREEN : ChatFormatting.GRAY,
					online != null ? "Online." : "Offline.",
					"",
					"Shift click to move them to default.")
				.setCallback((slot, type, action, ignored) -> {
					if (type.shift && !RankService.DEFAULT_RANK.equals(rank.name())) {
						ranks.assign(uuid, RankService.DEFAULT_RANK);
						Menus.notify(viewer, label + " moved to default.");
						openMembers(context, viewer, rank.name());
					}
				}).build());
		}

		if (members.isEmpty()) {
			gui.setSlot(22, Menus.button(scriptify.ui.DyedItems.glassPane(DyeColor.LIGHT_GRAY), "Nobody yet",
				ChatFormatting.GRAY, "Assign somebody from the rank screen.").build());
		}

		gui.setSlot(45, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> openRank(context, viewer, rank.name())).build());
		gui.setSlot(53, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	/** The vanilla commands most crews actually hand out or hold back. */
	private static final int[] COMMAND_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
	};

	/**
	 * The vanilla side of a rank: which permission level it grants, and which commands are filtered.
	 *
	 * <p>Two controls because Minecraft only offers two. The level decides what the client is even
	 * shown; the filter decides what is allowed to run. Neither alone gives "gamemaster except /op".
	 */
	public static void openCommands(ScriptifyContext context, ServerPlayer viewer, String name) {
		openCommands(context, viewer, name, 0);
	}

	/** The command allow/block editor, paged so the supported vanilla roots all remain reachable. */
	private static void openCommands(ScriptifyContext context, ServerPlayer viewer, String name, int page) {
		RankService ranks = context.permissions().ranks();
		var found = ranks.byName(name);
		if (found.isEmpty()) {
			AccessMenu.open(context, viewer);
			return;
		}
		RankService.Rank rank = found.get();
		java.util.List<String> gateable =
			RankService.gateableCommands(viewer.level().getServer());
		int perPage = COMMAND_SLOTS.length;
		int totalPages = Math.max(1, (gateable.size() + perPage - 1) / perPage);
		int currentPage = Math.max(0, Math.min(page, totalPages - 1));

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), viewer, false);
		gui.setTitle(Component.literal("» " + rank.label() + " commands «").withStyle(rank.colour()));
		gui.setLockPlayerInventory(true);
		Menus.fill(gui);

		gui.setSlot(4, Menus.button(Items.COMMAND_BLOCK, "Permission Level", ChatFormatting.YELLOW,
				"Click to raise, right click to lower.",
				"",
				"Now: " + levelName(rank.commandLevel()),
				"",
				"This is what decides which vanilla",
				"commands the player is offered at all.")
			.setCallback((slot, type, action, ignored) -> {
				int step = type.isRight ? -1 : 1;
				ranks.setCommandLevel(rank.name(), rank.commandLevel() + step);
				Sfx.click(viewer);
				openCommands(context, viewer, rank.name(), currentPage);
			}).build());

		gui.setSlot(49, Menus.button(Items.HOPPER, "Filter: " + modeName(rank), ChatFormatting.AQUA,
				"Everything — no filtering.",
				"Only these — nothing but the ticked ones.",
				"All except — the ticked ones are blocked.",
				"",
				"Click to change.")
			.setCallback((slot, type, action, ignored) -> {
				RankService.CommandMode[] modes = RankService.CommandMode.values();
				RankService.CommandMode next = modes[(rank.commandMode().ordinal() + 1) % modes.length];
				ranks.setCommandMode(rank.name(), next);
				Sfx.click(viewer);
				openCommands(context, viewer, rank.name(), currentPage);
			}).build());

		boolean filtering = rank.commandMode() != RankService.CommandMode.ALL;
		for (int cell = 0; cell < COMMAND_SLOTS.length; cell++) {
			int commandIndex = currentPage * perPage + cell;
			if (commandIndex >= gateable.size()) {
				break;
			}
			String command = gateable.get(commandIndex);
			boolean listed = rank.commands().contains(command);
			gui.setSlot(COMMAND_SLOTS[cell], Menus.toggle(
					listed ? Items.PAPER : scriptify.ui.DyedItems.dye(DyeColor.GRAY), "/" + command, listed,
					filtering ? describeEffect(rank, listed) : "Filter is off, so this has no effect.",
					"",
					listed ? "Click to unlist." : "Click to list.")
				.setCallback((slot, type, action, ignored) -> {
					ranks.setCommandListed(rank.name(), command, !listed);
					Menus.notifyToggle(viewer, "/" + command + (listed ? " unlisted" : " listed"), !listed);
					openCommands(context, viewer, rank.name(), currentPage);
				}).build());
		}

		if (currentPage > 0) {
			gui.setSlot(48, Menus.previousPageButton(currentPage, totalPages)
				.setCallback((slot, type, action, ignored) ->
					openCommands(context, viewer, rank.name(), currentPage - 1)).build());
		}
		if (currentPage < totalPages - 1) {
			gui.setSlot(50, Menus.nextPageButton(currentPage + 2, totalPages)
				.setCallback((slot, type, action, ignored) ->
					openCommands(context, viewer, rank.name(), currentPage + 1)).build());
		}

		gui.setSlot(45, Menus.backButton()
			.setCallback((slot, type, action, ignored) -> openRank(context, viewer, rank.name())).build());
		gui.setSlot(53, Menus.closeButton()
			.setCallback((slot, type, action, ignored) -> viewer.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(viewer);
	}

	private static String describeEffect(RankService.Rank rank, boolean listed) {
		if (rank.commandMode() == RankService.CommandMode.ONLY) {
			return listed ? "Allowed." : "Blocked — not on the list.";
		}
		return listed ? "Blocked." : "Allowed.";
	}

	private static String levelName(int level) {
		return switch (level) {
			case 1 -> "1 — moderator";
			case 2 -> "2 — gamemaster";
			case 3 -> "3 — admin";
			case 4 -> "4 — owner";
			default -> "0 — none";
		};
	}

	private static String modeName(RankService.Rank rank) {
		return switch (rank.commandMode()) {
			case ONLY -> "only these";
			case EXCEPT -> "all except these";
			default -> "everything";
		};
	}

	/** Cycles the sixteen chat colours, wrapping at the end. */
	private static ChatFormatting nextColour(ChatFormatting current) {
		ChatFormatting[] all = ChatFormatting.values();
		int start = current.ordinal();
		for (int step = 1; step <= all.length; step++) {
			ChatFormatting candidate = all[(start + step) % all.length];
			if (Colours.isColour(candidate)) {
				return candidate;
			}
		}
		return ChatFormatting.WHITE;
	}

	public static void promptNewRank(ScriptifyContext context, ServerPlayer viewer) {
		AnvilInputGui anvil = new AnvilInputGui(viewer, false);
		anvil.setTitle(Component.literal("Rank name"));
		anvil.setDefaultInputValue("");
		anvil.setSlot(0, new GuiElementBuilder(Items.WRITABLE_BOOK)
			.setName(Msg.hint("Type a name, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Create", ChatFormatting.GREEN,
				"Creates an empty rank.")
			.setCallback((slot, type, action, ignored) -> {
				if (context.permissions().ranks().create(anvil.getInput())) {
					Menus.notify(viewer, "Created the rank.");
				} else {
					Menus.deny(viewer, "That name is taken, or not a usable name.");
				}
				AccessMenu.open(context, viewer);
			}).build());
		anvil.open();
	}

	private static void promptPrefix(ScriptifyContext context, ServerPlayer viewer,
			String rank, String current) {
		AnvilInputGui anvil = new AnvilInputGui(viewer, false);
		anvil.setTitle(Component.literal("Prefix"));
		anvil.setDefaultInputValue(current);
		anvil.setSlot(0, new GuiElementBuilder(Items.NAME_TAG)
			.setName(Msg.hint("Type a prefix, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Apply", ChatFormatting.GREEN,
				"Sets this rank's prefix.")
			.setCallback((slot, type, action, ignored) -> {
				context.permissions().ranks().setPrefix(rank, anvil.getInput());
				Menus.notify(viewer, "Prefix updated.");
				openRank(context, viewer, rank);
			}).build());
		anvil.open();
	}
}
