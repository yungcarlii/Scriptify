package scriptify.bots.tool;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import scriptify.bots.bot.BotCombat;
import scriptify.bots.bot.BotFaction;
import scriptify.bots.bot.BotKits;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotPath;
import scriptify.bots.ui.Msg;
import scriptify.bots.ui.Sfx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Issues the bot tools and runs them.
 *
 * <p>One place for all of them on purpose. There were three of these before - a stick for paths, a
 * shield for kits, and the scenario sticks - each with its own copy of the same four ideas: name the
 * item, remember what it is bound to, notice it in somebody's hand, and forget it when they leave.
 * Every new tool meant a fourth copy and a fourth chance to forget the disconnect hook. Adding one
 * here is a line in an enum.
 *
 * <p>See {@link BotTool} for why aimed tools are sticks, held ones are shields, and neither writes
 * its binding into the item.
 */
public final class BotTools {

	private BotTools() {
	}

	/** What each player's tools are pointed at: kit name, path name, faction, or a count. */
	private static final Map<UUID, Map<BotTool, String>> BOUND = new HashMap<>();

	/** How long each player has been holding a shield down, in ticks. */
	private static final Map<UUID, Integer> HELD = new HashMap<>();

	/**
	 * The bot picked up by an Attack Stick, waiting for a target.
	 *
	 * <p>The only tool that needs two clicks, because "attack" is the only one of these that takes a
	 * second noun. Kept here rather than in a fourth map somewhere else.
	 */
	private static final Map<UUID, String> ARMED = new HashMap<>();

	/** How often a filling shield redraws its countdown, in ticks. */
	private static final int PROGRESS_INTERVAL = 5;

	// ================================================================ issuing

	/**
	 * Hands a player the tool, bound to {@code argument} when the tool needs one.
	 *
	 * @return an error to show, or {@code null} when the tool was handed over
	 */
	public static String give(ServerPlayer player, BotTool tool, String argument) {
		if (tool.needsBinding()) {
			String problem = checkBinding(tool, argument);
			if (problem != null) {
				return problem;
			}
			BOUND.computeIfAbsent(player.getUUID(), key -> new HashMap<>()).put(tool, argument);
		}

		ItemStack stack = new ItemStack(tool.icon());
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(tool.displayName())
			.withStyle(ChatFormatting.LIGHT_PURPLE)
			.withStyle(style -> style.withItalic(false)));
		List<Component> lore = new ArrayList<>();
		for (String line : tool.lore(argument)) {
			lore.add(Component.literal(line).withStyle(ChatFormatting.GRAY)
				.withStyle(style -> style.withItalic(false)));
		}
		stack.set(DataComponents.LORE, new ItemLore(lore));

		HELD.remove(player.getUUID());
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		Sfx.success(player);
		return null;
	}

	/** Whether the thing a tool is being pointed at still exists. */
	private static String checkBinding(BotTool tool, String argument) {
		if (argument == null || argument.isBlank()) {
			return "That tool needs something to point at.";
		}
		return switch (tool.binding()) {
			case KIT -> BotKits.kitExists(argument) ? null : "No kit called " + argument + ".";
			case PATH -> BotPath.getPath(argument) != null ? null
				: "No path called " + argument + ".";
			case FACTION -> BotFaction.getAllFactions().contains(argument) ? null
				: "No faction called " + argument + ".";
			case COUNT -> isCount(argument) ? null : "That is not a number of bots.";
			case NONE -> null;
		};
	}

	private static boolean isCount(String argument) {
		try {
			int count = Integer.parseInt(argument);
			return count > 0 && count <= 10000;
		} catch (NumberFormatException notANumber) {
			return false;
		}
	}

	private static String bound(ServerPlayer player, BotTool tool) {
		Map<BotTool, String> mine = BOUND.get(player.getUUID());
		return mine == null ? null : mine.get(tool);
	}

	// ================================================================ recognising

	/** The tool this stack is, or null when it is an ordinary item. */
	public static BotTool toolOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		if (name == null) {
			return null;
		}
		BotTool tool = BotTool.byDisplayName(name.getString());
		return tool != null && stack.is(tool.icon()) ? tool : null;
	}

	// ================================================================ aimed tools

	/**
	 * Handles a right click on an entity.
	 *
	 * @return true when the click was consumed
	 */
	public static boolean handleUse(ServerPlayer player, Entity target, ItemStack held) {
		BotTool tool = toolOf(held);
		if (tool == null || tool.kind() != BotTool.Kind.POKE) {
			return false;
		}

		// The Attack Stick is the one tool whose second click is not at a bot, so it is asked first.
		if (tool == BotTool.ATTACK) {
			return handleAttack(player, target);
		}

		String botName = botNameOf(target);
		if (botName == null) {
			Msg.actionBar(player, "That is not a bot", ChatFormatting.RED);
			return true;
		}

		switch (tool) {
			case PATH -> {
				String path = bound(player, tool);
				if (path == null) {
					Msg.actionBar(player, "This stick has lost its path - hand it out again",
						ChatFormatting.RED);
				} else if (BotPath.startFollowing(botName, path)) {
					Msg.actionBar(player, botName + " is walking " + path, ChatFormatting.GREEN);
					Sfx.success(player);
				} else {
					Msg.actionBar(player, path + " is empty or gone", ChatFormatting.RED);
				}
			}
			case STOP -> {
				BotCombat.clearTarget(botName);
				BotPath.stopFollowing(botName);
				Msg.actionBar(player, botName + " called off", ChatFormatting.GREEN);
				Sfx.success(player);
			}
			case DISMISS -> {
				MinecraftServer server = player.level().getServer();
				if (server != null
					&& BotManager.removeBot(server, botName, server.createCommandSourceStack())) {
					Msg.actionBar(player, botName + " sent away", ChatFormatting.GREEN);
					Sfx.success(player);
				} else {
					Msg.actionBar(player, "Could not remove " + botName, ChatFormatting.RED);
				}
			}
			case PANEL -> {
				player.closeContainer();
				scriptify.bots.ui.BotMenu.open(player, botName, 0);
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	/**
	 * The Attack Stick's two clicks: a bot, then whoever it should fight.
	 *
	 * <p>Deliberately allows any living target for the second click rather than only bots, since
	 * setting a bot on a real player is most of the point.
	 */
	private static boolean handleAttack(ServerPlayer player, Entity target) {
		String armed = ARMED.get(player.getUUID());
		if (armed == null) {
			String botName = botNameOf(target);
			if (botName == null) {
				Msg.actionBar(player, "Pick a bot first", ChatFormatting.RED);
				return true;
			}
			ARMED.put(player.getUUID(), botName);
			Msg.actionBar(player, botName + " picked up - now click who it fights",
				ChatFormatting.AQUA);
			Sfx.click(player);
			return true;
		}

		if (!(target instanceof net.minecraft.world.entity.LivingEntity)) {
			Msg.actionBar(player, "That cannot be fought", ChatFormatting.RED);
			return true;
		}
		String targetName = target.getName().getString();
		if (targetName.equalsIgnoreCase(armed)) {
			ARMED.remove(player.getUUID());
			Msg.actionBar(player, "Cancelled", ChatFormatting.YELLOW);
			return true;
		}
		BotCombat.setTarget(armed, targetName);
		ARMED.remove(player.getUUID());
		Msg.actionBar(player, armed + " is going for " + targetName, ChatFormatting.GREEN);
		Sfx.success(player);
		return true;
	}

	private static String botNameOf(Entity entity) {
		if (!(entity instanceof ServerPlayer candidate)) {
			return null;
		}
		String name = candidate.getName().getString();
		return BotManager.isBot(name) ? name : null;
	}

	// ================================================================ held tools

	/**
	 * Watches everybody holding a shield.
	 *
	 * <p>Driven from the item actually in use rather than from a click, which is what makes letting go
	 * cancel: the moment the button comes up the item stops being in use and the count is dropped.
	 */
	public static void tick(MinecraftServer server) {
		if (HELD.isEmpty() && BOUND.isEmpty()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			BotTool tool = player.isUsingItem() ? toolOf(player.getUseItem()) : null;
			if (tool == null || tool.kind() != BotTool.Kind.HOLD) {
				if (HELD.remove(id) != null) {
					Msg.actionBar(player, "Let go - nothing done", ChatFormatting.YELLOW);
				}
				continue;
			}

			int held = HELD.getOrDefault(id, 0) + 1;
			if (held < tool.holdTicks()) {
				HELD.put(id, held);
				if (held % PROGRESS_INTERVAL == 0) {
					Msg.actionBar(player, String.format(Locale.US, "%s in %.1fs",
						tool.displayName(), (tool.holdTicks() - held) / 20.0), ChatFormatting.GRAY);
				}
				continue;
			}

			HELD.remove(id);
			apply(player, tool, server);
		}
	}

	private static void apply(ServerPlayer player, BotTool tool, MinecraftServer server) {
		String argument = bound(player, tool);
		String problem = checkBinding(tool, argument);
		if (problem != null) {
			Msg.actionBar(player, problem, ChatFormatting.RED);
			return;
		}

		switch (tool) {
			case KIT -> {
				int given = BotKits.giveToNearby(player, argument, BotTool.RADIUS);
				report(player, given, "Gave " + argument + " to ");
			}
			case FACTION -> {
				int joined = 0;
				for (String botName : nearbyBots(player)) {
					if (BotFaction.addMember(argument, botName)) {
						joined++;
					}
				}
				report(player, joined, "Put " + joined + " on " + argument + " - ");
			}
			case SPAWN -> {
				int count = Integer.parseInt(argument);
				BotManager.queueMassSpawn(server, count, player.position(),
					server.createCommandSourceStack());
				Msg.actionBar(player, "Spawning " + count + " bots", ChatFormatting.GREEN);
				Sfx.spawn(player);
			}
			default -> {
			}
		}
	}

	private static void report(ServerPlayer player, int affected, String prefix) {
		if (affected == 0) {
			Msg.actionBar(player, "No bots within " + (int) BotTool.RADIUS + " blocks",
				ChatFormatting.RED);
			return;
		}
		Msg.actionBar(player, prefix + affected + (affected == 1 ? " bot" : " bots"),
			ChatFormatting.GREEN);
		Sfx.spawn(player);
	}

	/** Every bot within a held tool's reach. One pass over the player list, not one per bot. */
	private static List<String> nearbyBots(ServerPlayer player) {
		List<String> names = new ArrayList<>();
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return names;
		}
		double radiusSquared = BotTool.RADIUS * BotTool.RADIUS;
		for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
			if (candidate == player || candidate.level() != player.level()) {
				continue;
			}
			if (candidate.distanceToSqr(player) > radiusSquared) {
				continue;
			}
			String name = candidate.getName().getString();
			if (BotManager.isBot(name)) {
				names.add(name);
			}
		}
		return names;
	}

	// ================================================================ lifecycle

	public static void forgetPlayer(ServerPlayer player) {
		BOUND.remove(player.getUUID());
		HELD.remove(player.getUUID());
		ARMED.remove(player.getUUID());
	}

	public static void clear() {
		BOUND.clear();
		HELD.clear();
		ARMED.clear();
	}
}
