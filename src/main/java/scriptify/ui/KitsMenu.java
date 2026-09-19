package scriptify.ui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import scriptify.api.ScriptifyContext;
import scriptify.feature.Sfx;
import scriptify.kits.KitManager;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;

/**
 * The {@code /kitted} kit browser.
 *
 * <p>Left click equips. Right click opens the kit's own screen instead of hiding half a dozen actions
 * behind modifier keys nobody discovers — renaming, editing and deleting all live there with labels.
 *
 * <p>Opened with a target the whole menu hands kits to that player instead, and says so in the title
 * so there is no doubt about who is being dressed.
 */
public final class KitsMenu {
	/** Interior slots: everything inside the one-slot border. */
	private static final int[] CONTENT_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
		37, 38, 39, 40, 41, 42, 43,
	};
	/** The star sits in the first cell, so kits read as a list that starts under it. */
	private static final int NEW_KIT_SLOT = CONTENT_SLOTS[0];
	private static final int PREVIOUS_SLOT = 48;
	private static final int NEXT_SLOT = 50;
	private static final int BACK_SLOT = 45;
	private static final int CLOSE_SLOT = 53;

	private KitsMenu() {
	}

	public static void open(ScriptifyContext context, ServerPlayer player, int page) {
		open(context, player, null, page);
	}

	/**
	 * @param target who the kits are given to, or {@code null} for the viewer themselves.
	 */
	public static void open(ScriptifyContext context, ServerPlayer player, ServerPlayer target, int page) {
		if (!context.permissions().has(player, PermissionService.KITS)) {
			Menus.deny(player, "You do not have permission to use kits.");
			return;
		}
		KitManager kits = ScriptifyRuntime.kits();
		boolean canManage = kits.canManage(player);
		List<KitManager.KitEntry> entries = kits.entries().stream()
			// Managers must be able to configure and hand out a restricted kit even when their own
			// rank is not in its allow-list. Left-clicking it on themselves still respects that list.
			.filter(entry -> canManage || kits.canUse(entry.name(), player))
			.toList();
		// One cell on every page belongs to the star, so the button is in the same place wherever you are.
		int perPage = CONTENT_SLOTS.length - 1;
		int totalPages = Math.max(1, (entries.size() + perPage - 1) / perPage);
		int currentPage = Math.max(0, Math.min(page, totalPages - 1));
		boolean forOther = target != null && target != player;

		SimpleGui gui = new SimpleGui(Menus.typeForRows(6), player, false);
		gui.setTitle(Msg.header(forOther
			? "Kits → " + target.getGameProfile().name()
			: "Kits", ChatFormatting.GOLD));
		gui.setLockPlayerInventory(true);
		Menus.frame(gui);

		if (canManage) {
			gui.setSlot(NEW_KIT_SLOT, Menus.button(Items.NETHER_STAR, "New Kit", ChatFormatting.LIGHT_PURPLE,
				"Saves your current inventory as a kit.",
				"What you are holding becomes its icon.",
				"",
				"Names can have spaces.")
			.glow()
			.setCallback((index, type, action, ignored) -> {
				Sfx.click(player);
				promptForName(context, player, target);
			})
				.build());
		} else {
			gui.setSlot(NEW_KIT_SLOT, Menus.button(Items.PAPER, "Rank-controlled kits", ChatFormatting.GRAY,
				"Only kits your rank can use are shown.",
				"A kit manager can change access.").build());
		}

		if (forOther) {
			gui.setSlot(4, Menus.playerHead(target, ChatFormatting.AQUA,
				"Kits picked here go to this player.",
				"",
				"Click a kit to hand it over.").build());
		}

		for (int slot = 0; slot < perPage; slot++) {
			int entryIndex = currentPage * perPage + slot;
			if (entryIndex >= entries.size()) {
				break;
			}
			KitManager.KitEntry entry = entries.get(entryIndex);
			gui.setSlot(CONTENT_SLOTS[slot + 1], GuiElementBuilder.from(entry.icon().copy())
				.hideDefaultTooltip()
				.setName(Msg.title(entry.displayName(), ChatFormatting.GOLD))
				.addLoreLine(Component.empty())
				.addLoreLine(Msg.hint(entry.itemCount() + " item(s)"))
				.addLoreLine(Msg.hint(entry.allowedRanks().isEmpty()
					? "Access: all kit users"
					: "Access: " + entry.allowedRanks().size() + " rank(s)"))
				.addLoreLine(Component.empty())
				.addLoreLine(Msg.hint(forOther
					? "Left click: give to " + target.getGameProfile().name()
					: "Left click: equip"))
				.addLoreLine(Msg.hint(canManage ? "Right click: manage" : ""))
				.setCallback((index, type, action, ignored) -> {
					if (type.isRight && canManage) {
						Sfx.click(player);
						KitOptionsMenu.open(context, player, target, entry.name(), currentPage);
						return;
					}
					give(context, player, target, entry.name());
				}).build());
		}

		if (entries.isEmpty()) {
			gui.setSlot(CONTENT_SLOTS[1], Menus.button(scriptify.ui.DyedItems.glassPane(DyeColor.LIGHT_GRAY), "No kits yet",
				ChatFormatting.GRAY, "Fill your inventory, then click the star.").build());
		}

		if (currentPage > 0) {
			gui.setSlot(PREVIOUS_SLOT, Menus.previousPageButton(currentPage, totalPages)
				.setCallback((index, type, action, ignored) ->
					open(context, player, target, currentPage - 1)).build());
		}
		if (currentPage < totalPages - 1) {
			gui.setSlot(NEXT_SLOT, Menus.nextPageButton(currentPage + 2, totalPages)
				.setCallback((index, type, action, ignored) ->
					open(context, player, target, currentPage + 1)).build());
		}

		gui.setSlot(BACK_SLOT, Menus.backButton()
			.setCallback((index, type, action, ignored) -> MainMenu.open(context, player)).build());
		gui.setSlot(CLOSE_SLOT, Menus.closeButton()
			.setCallback((index, type, action, ignored) -> player.closeContainer()).build());

		gui.open();
		Sfx.menuOpen(player);
	}

	/** Hands a kit to whoever the menu is aimed at, and closes so the deal-out can be watched. */
	static void give(ScriptifyContext context, ServerPlayer viewer, ServerPlayer target, String kit) {
		KitManager kits = ScriptifyRuntime.kits();
		ServerPlayer receiver = target == null ? viewer : target;
		if (receiver != viewer && !kits.canManage(viewer)) {
			Menus.deny(viewer, "You cannot hand kits to other players.");
			return;
		}
		boolean applied = receiver == viewer ? kits.apply(kit, receiver) : kits.applyManaged(kit, receiver);
		if (!applied) {
			Menus.deny(viewer, receiver == viewer ? "Your rank cannot use that kit." : "That kit is gone.");
			return;
		}
		String name = kits.displayName(kit);
		if (receiver == viewer) {
			Menus.notify(viewer, "Equipped " + name + ".");
		} else {
			Menus.notify(viewer, "Gave " + name + " to " + receiver.getGameProfile().name() + ".");
			Msg.chat(receiver, Msg.line("You were given the " + name + " kit."));
		}
		// Closed on purpose: the items land one at a time and none of it is visible behind a menu.
		viewer.closeContainer();
	}

	/** An anvil is the only way to collect free text from a vanilla client. */
	private static void promptForName(ScriptifyContext context, ServerPlayer player, ServerPlayer target) {
		AnvilInputGui anvil = new AnvilInputGui(player, false);
		anvil.setTitle(Component.literal("Name this kit"));
		anvil.setDefaultInputValue("New Kit");
		ItemStack held = player.getInventory().getSelectedItem();
		anvil.setSlot(0, GuiElementBuilder.from(held.isEmpty() ? new ItemStack(Items.CHEST) : held.copy())
			.setName(Msg.hint("Type a name, then click the output")).build());
		anvil.setSlot(2, Menus.button(scriptify.ui.DyedItems.dye(DyeColor.LIME), "Create", ChatFormatting.GREEN,
				"Saves your inventory under this name.")
			.setCallback((slot, type, action, ignored) -> {
				try {
					ScriptifyRuntime.kits().create(anvil.getInput(), player);
					Menus.notify(player, "Saved kit '" + anvil.getInput().trim() + "'.");
					Sfx.success(player);
				} catch (IllegalArgumentException exception) {
					Menus.deny(player, exception.getMessage());
				}
				open(context, player, target, 0);
			}).build());
		anvil.open();
	}
}
