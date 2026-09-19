package scriptify.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * The {@code /scriptify help} landing screen.
 *
 * <p>A front page rather than a wall: six ways in, each one line, instead of the seventy-odd commands
 * that used to scroll past faster than anyone could read them. The full list is still one click away.
 *
 * <p>Every row is clickable and hovers with a hint, which is the part a chat menu can do that a
 * screenshot of one cannot — the list is a launcher, not documentation.
 */
public final class Help {
	private Help() {
	}

	public static void send(CommandSourceStack source) {
		blank(source);
		source.sendSuccess(Help::wordmark, false);
		source.sendSuccess(() -> indent()
			.append(Component.literal("v" + Version.CURRENT + "  ")
				.withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal("server-side · vanilla clients")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)), false);
		blank(source);

		row(source, "★", "Browse Categories", "/scriptify help all",
			"Every command, grouped by what it does.");
		row(source, "◆", "Open The Menu", "/scriptify",
			"Kits, identity, scenes and toggles in one screen.");
		row(source, "❖", "Kits", "/kitted",
			"Save and hand out full loadouts.");
		row(source, "✦", "Identity", "/identity set",
			"Take on another player's name and skin at once.");
		row(source, "⌘", "Scenes", "/ondeath",
			"Staged deaths, vanish, fake join and leave.");
		row(source, "❂", "Bots", "/bots",
			"Spawn and direct bots. Needs HeroBot installed.");
		row(source, "✎", "Edit A Bot", "/bots edit",
			"Turn it on, then hit a bot to open its panel.");

		blank(source);
		heading(source, "Admin");
		row(source, "⚠", "Reload Config", "/scriptify reload",
			"Re-reads the config and event files from disk.");
		row(source, "⚙", "Mod Conflicts", "/scriptify compat",
			"Which mods overlap with Scriptify, and who won.");

		blank(source);
		selectorFooter(source);
		blank(source);
	}

	/**
	 * The full index, moved behind a click.
	 *
	 * <p>It is genuinely long, and a landing page that makes you scroll to reach the top of itself is
	 * not a landing page.
	 */
	public static void sendAll(CommandSourceStack source) {
		blank(source);
		source.sendSuccess(Help::wordmark, false);

		section(source, "Menus");
		entry(source, "/scriptify", "Open the main menu");
		entry(source, "/itemmaker", "Item editor");
		entry(source, "/villagermaker", "Villager editor");
		entry(source, "/faketab", "Pad the tab list");
		entry(source, "/kitted", "Kits — add a player to give it to them");
		entry(source, "/potion", "Potion presets");
		entry(source, "/randomfill", "Fill an inventory");
		entry(source, "/backupinv", "Inventory rollbacks — auto-saved on death");
		entry(source, "/copyinv", "Give a player a copy of your inventory");
		entry(source, "/clearground", "Remove dropped items from the ground");

		section(source, "Identity");
		entry(source, "/nick", "Set a nickname");
		entry(source, "/randomnick", "Roll a random nickname");
		entry(source, "/unnick", "Remove a nickname");
		entry(source, "/skin set", "Copy another account's skin");
		entry(source, "/skin clear", "Restore their own skin");
		entry(source, "/randomskin", "Roll a random real player's skin");
		entry(source, "/identity set", "Take a name and skin together");
		entry(source, "/identity clear", "Give back their own name and skin");
		entry(source, "/randomidentity", "Become a random real player");

		section(source, "Player");
		entry(source, "/gms", "Survival — also /gmc /gma /gmsp");
		entry(source, "/freeze", "Lock a player in place - run again to release");
		entry(source, "/invis on", "Full invisibility");
		entry(source, "/vanish", "Disappear completely");
		entry(source, "/heal", "Full health");
		entry(source, "/feed", "Full hunger");
		entry(source, "/health", "Change the heart row");
		entry(source, "/totem", "Give totems");
		entry(source, "/top", "Teleport to the surface");
		entry(source, "/pvp on", "Toggle PvP server-wide");
		entry(source, "/immortal", "Cannot die — run again to switch off");

		section(source, "Targeting");
		entry(source, "@a", "Everyone - also @s @p @r");
		entry(source, "@o", "Operators only");
		entry(source, "@d", "Default players - real, no op");
		entry(source, "@b", "Bots and other fake players");
		entry(source, "Steve @d", "Several targets at once");
		entry(source, "/gamemode @d", "@o and @d work on vanilla commands too");

		section(source, "Inventory");
		entry(source, "/invsee", "View a player's inventory");
		entry(source, "/ec", "View a player's ender chest");
		entry(source, "/restock", "Refill every stack");
		entry(source, "/durability max", "Repair everything");

		section(source, "Authoring");
		entry(source, "/item name", "Rename the held item — also lore, enchant, glint");
		entry(source, "/villager create", "Spawn a trading villager");
		entry(source, "/villager addtrade", "Add a trade to the villager you face");
		entry(source, "/steams create", "Create a team — also add, colour, prefix");
		entry(source, "/scriptifyperms grant", "Grant a node: <node> <player>");
		entry(source, "/scriptifyranks", "Ranks: bundles of permissions");

		section(source, "Bots");
		entry(source, "/bots", "Control panel for the bot roster");
		entry(source, "/bots spawn", "Spawn a bot - also mass-spawn, remove, removeall");
		entry(source, "/bots who", "What a bot is doing: target, intent, role");
		entry(source, "/bots edit", "Hit a bot to open its panel - settings, skin, paths");
		entry(source, "/bots group", "Bot groups and per-group settings");
		entry(source, "/bots skin random", "Give a bot an appearance from the local pool");
		entry(source, "/bots kit", "Save and hand out bot kits");
		entry(source, "/bots faction", "Teams, hostility and group orders");

		section(source, "Scene");
		entry(source, "/ondeath", "Staged deaths");
		entry(source, "/ondeath restore", "Bring someone back");
		entry(source, "/sudo", "Act as another player");

		blank(source);
		selectorFooter(source);
		source.sendSuccess(() -> indent()
			.append(Component.literal("Click any command to put it in your chat box.")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)), false);
		blank(source);
	}

	private static MutableComponent wordmark() {
		return Panel.wordmark();
	}

	/** One landing-page row. The look belongs to {@link Panel}; the click rule belongs here. */
	private static void row(CommandSourceStack source, String symbol, String label, String command,
			String hint) {
		Panel.row(source, symbol, label, command, Panel.ACTION, command, hint, isComplete(command));
	}

	/** Commands that take no further arguments are safe to fire on a click. */
	private static boolean isComplete(String command) {
		return switch (command) {
			case "/scriptify", "/scriptify help all", "/scriptify reload", "/scriptify compat",
				"/kitted", "/ondeath", "/bots", "/bots edit" -> true;
			default -> false;
		};
	}

	/**
	 * The footer, and the one honest brag on the page: Scriptify answers three selectors vanilla does
	 * not, and this is where somebody finds that out.
	 */
	private static void selectorFooter(CommandSourceStack source) {
		MutableComponent line = indent()
			.append(Component.literal("Targets ").withStyle(ChatFormatting.DARK_GRAY));
		String[] vanilla = {"@a", "@p", "@r", "@s"};
		for (String selector : vanilla) {
			line.append(Component.literal(selector + " ").withStyle(ChatFormatting.GRAY));
		}
		line.append(Component.literal("plus ").withStyle(ChatFormatting.DARK_GRAY));
		for (String selector : new String[]{"@o", "@d", "@b"}) {
			line.append(Component.literal(selector + " ").withStyle(Panel.MARK_B));
		}
		MutableComponent hover = Component.literal("@o")
			.withStyle(Panel.MARK_B).append(Component.literal("  operators\n").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("@d").withStyle(Panel.MARK_B))
			.append(Component.literal("  default players, no op\n").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("@b").withStyle(Panel.MARK_B))
			.append(Component.literal("  bots and other fake players\n\n").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("These work on vanilla commands too.")
				.withStyle(ChatFormatting.DARK_GRAY));
		source.sendSuccess(() -> line.withStyle(style -> style
			.withHoverEvent(new HoverEvent.ShowText(hover))), false);
	}

	private static void heading(CommandSourceStack source, String name) {
		Panel.heading(source, name);
	}

	private static void section(CommandSourceStack source, String name) {
		Panel.section(source, name);
	}

	private static void blank(CommandSourceStack source) {
		Panel.blank(source);
	}

	private static MutableComponent indent() {
		return Panel.indent();
	}

	/** One clickable row in the full index: suggesting, since most of these take arguments. */
	private static void entry(CommandSourceStack source, String command, String description) {
		source.sendSuccess(() -> indent()
			.append(Component.literal(" "))
			.append(Component.literal(command)
				.withStyle(style -> style
					.withColor(Panel.ACTION)
					.withClickEvent(new ClickEvent.SuggestCommand(command + " "))))
			.append(Component.literal(" — ").withStyle(Panel.PUNCT))
			.append(Component.literal(description).withStyle(ChatFormatting.GRAY)), false);
	}

	/**
	 * The version string shown here and by {@code /scriptify version}.
	 *
	 * <p>Read from the jar's own metadata rather than typed in. It used to be a constant, and the
	 * constant said 2.3.0 while the jar it was compiled into said 2.10.5 - so the one place a person
	 * checks which build they are running was seven releases stale, and every bug report carried the
	 * wrong version with it. A number that has to be updated by hand in two places eventually is not.
	 */
	public static final class Version {
		public static final String CURRENT = read();

		private static String read() {
			return net.fabricmc.loader.api.FabricLoader.getInstance()
				.getModContainer(scriptify.function.Scriptify.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		}

		private Version() {
		}
	}
}
