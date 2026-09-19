package scriptify.bots.ui;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import scriptify.bots.bot.BotFaction;
import scriptify.bots.bot.BotKits;
import scriptify.bots.bot.BotPath;
import scriptify.bots.tool.BotTool;
import scriptify.bots.tool.BotTools;

import java.util.ArrayList;
import java.util.List;

/**
 * The tool rack.
 *
 * <p>Laid out by how a tool is used rather than by what it does, because that is the thing you have
 * to know before you pick one up: the top row is aimed at a single bot, the bottom row is held and
 * applies to everyone around you. Reading the screen teaches the two gestures without a word of
 * explanation.
 *
 * <p>A tool that needs pointing at something - a kit, a path, a side - opens a list of them rather
 * than asking anybody to type a name they would have to go and look up. That list is the same screen
 * for all three, because the question is the same one.
 */
public final class ToolsMenu {

	private ToolsMenu() {
	}

	/** Aimed tools, centred on the second row. */
	private static final int[] POKE_SLOTS = {10, 11, 12, 13, 14};
	/** Held tools, centred on the fourth row. */
	private static final int[] HOLD_SLOTS = {29, 30, 31};

	public static void open(ServerPlayer player) {
		SimpleGui gui = Menus.create(player, 5, "Bot Tools");
		paint(gui, player);
		gui.open();
	}

	private static void paint(SimpleGui gui, ServerPlayer player) {
		gui.setSlot(4, Menus.header(Items.CHEST, "Bot Tools",
			"Point at a bot instead of typing its name.",
			"",
			"Sticks are aimed - right click one bot.",
			"Shields are held - everyone within "
				+ (int) BotTool.RADIUS + " blocks.").build());

		int poke = 0;
		int hold = 0;
		for (BotTool tool : BotTool.values()) {
			int slot;
			if (tool.kind() == BotTool.Kind.POKE) {
				if (poke >= POKE_SLOTS.length) {
					continue;
				}
				slot = POKE_SLOTS[poke++];
			} else {
				if (hold >= HOLD_SLOTS.length) {
					continue;
				}
				slot = HOLD_SLOTS[hold++];
			}
			gui.setSlot(slot, entry(gui, player, tool).build());
		}

		gui.setSlot(36, Menus.backButton()
			.setCallback((i, t, a, ignored) -> {
				Sfx.click(player);
				MainMenu.open(player);
			}).build());
		gui.setSlot(44, Menus.closeButton()
			.setCallback((i, t, a, ignored) -> {
				Sfx.click(player);
				gui.close();
			}).build());
	}

	private static eu.pb4.sgui.api.elements.GuiElementBuilder entry(
		SimpleGui gui, ServerPlayer player, BotTool tool) {

		List<String> lore = new ArrayList<>(List.of(tool.lore(null)));
		lore.add("");
		lore.add(tool.needsBinding() ? "Click to choose one" : "Click to take one");

		return Menus.button(tool.icon(), tool.displayName(), ChatFormatting.AQUA,
				lore.toArray(new String[0]))
			.setCallback((i, t, a, ignored) -> {
				Sfx.click(player);
				if (!tool.needsBinding()) {
					hand(player, tool, null);
					gui.close();
					return;
				}
				chooser(player, tool);
			});
	}

	/**
	 * The list a bound tool asks for: which kit, which path, which side, how many.
	 *
	 * <p>One screen for all of them. The four bindings differ only in where the list comes from, and
	 * writing four nearly identical menus would be four places to keep in step.
	 */
	private static void chooser(ServerPlayer player, BotTool tool) {
		List<String> options = optionsFor(tool);
		if (options.isEmpty()) {
			Menus.deny(player, nothingThere(tool));
			return;
		}

		SimpleGui gui = Menus.create(player, 6, tool.displayName());
		gui.setSlot(4, Menus.header(tool.icon(), tool.displayName(),
			"Pick what this tool points at.").build());

		for (int index = 0; index < options.size() && index < Menus.PER_PAGE; index++) {
			String option = options.get(index);
			gui.setSlot(Menus.CONTENT_START + index,
				Menus.button(iconFor(tool), option, ChatFormatting.WHITE,
						"Click to take the tool", "pointed at " + option + ".")
					.setCallback((i, t, a, ignored) -> {
						Sfx.click(player);
						hand(player, tool, option);
						gui.close();
					}).build());
		}

		gui.setSlot(Menus.BACK_SLOT, Menus.backButton()
			.setCallback((i, t, a, ignored) -> {
				Sfx.click(player);
				open(player);
			}).build());
		gui.setSlot(Menus.CLOSE_SLOT, Menus.closeButton()
			.setCallback((i, t, a, ignored) -> {
				Sfx.click(player);
				gui.close();
			}).build());
		gui.open();
	}

	private static List<String> optionsFor(BotTool tool) {
		return switch (tool.binding()) {
			case KIT -> new ArrayList<>(BotKits.getKitNames());
			case PATH -> new ArrayList<>(BotPath.getAllPaths().keySet());
			case FACTION -> new ArrayList<>(BotFaction.getAllFactions());
			// Not a list of things that exist, so it is a list of sensible amounts instead.
			case COUNT -> List.of("1", "5", "10", "25", "50", "100", "250", "500");
			case NONE -> List.of();
		};
	}

	private static String nothingThere(BotTool tool) {
		return switch (tool.binding()) {
			case KIT -> "No kits yet - make one with /bots kit create-kit";
			case PATH -> "No paths yet - draw one with /bots bot-management path create";
			case FACTION -> "No factions yet - make one with /bots faction create";
			default -> "Nothing to choose.";
		};
	}

	private static net.minecraft.world.item.Item iconFor(BotTool tool) {
		return switch (tool.binding()) {
			case KIT -> Items.CHEST;
			case PATH -> Items.LEAD;
			case FACTION -> scriptify.ui.DyedItems.banner(DyeColor.WHITE);
			case COUNT -> Items.PLAYER_HEAD;
			case NONE -> tool.icon();
		};
	}

	private static void hand(ServerPlayer player, BotTool tool, String argument) {
		String problem = BotTools.give(player, tool, argument);
		if (problem != null) {
			Menus.deny(player, problem);
			return;
		}
		Menus.notify(player, tool.displayName() + " is in your inventory.");
	}
}
