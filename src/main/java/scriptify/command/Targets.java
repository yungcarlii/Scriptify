package scriptify.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import scriptify.access.FakePlayers;
import scriptify.nick.NickRegistry;

/**
 * Player targeting for Scriptify's commands: selectors, real names and nicknames.
 *
 * <p>Deliberately a plain string argument rather than a custom {@code ArgumentType}. A registered
 * argument type has to be known to the client, which would break the promise that Scriptify works with
 * an unmodified client — a string is understood by everyone. It also means suggestions are served by
 * the <em>server</em>, which is what lets this list real usernames while nicknames are active.
 *
 * <p>Greedy rather than a single word, because Brigadier's unquoted-word reader rejects {@code @}
 * outright: {@code /unnick @a} never reached this class at all, it failed while being parsed. Greedy
 * takes the rest of the line, which also means several targets can be listed at once.
 *
 * <p>The cost of greedy is that nothing can follow the target, so every command puts it last.
 *
 * <p>Supported tokens:
 * <ul>
 *   <li>{@code @a} everyone, {@code @s} yourself, {@code @r} a random player</li>
 *   <li>{@code @p} the closest player to whoever ran the command</li>
 *   <li>{@code @o} every operator, {@code @d} every real player without operator</li>
 *   <li>{@code @b} every bot or other fake player</li>
 *   <li>a username, or a nickname currently in use</li>
 * </ul>
 */
public final class Targets {
	private static final SimpleCommandExceptionType NO_MATCH =
		new SimpleCommandExceptionType(Component.literal("No player matched that target."));

	/** Selector tokens offered alongside real player names. */
	private static final List<String> SELECTORS = List.of("@a", "@s", "@p", "@r", "@o", "@d", "@b");

	private Targets() {
	}

	/** The argument type to register. Must be the last argument of its command. */
	public static ArgumentType<String> arg() {
		return StringArgumentType.greedyString();
	}

	/**
	 * Suggestions: selector tokens plus every player's <em>real</em> name.
	 *
	 * <p>Real names on purpose. While a nickname is active the client's own suggestion list shows the
	 * nickname, because that is the only name it knows — this is served from the server, so it can show
	 * who the player actually is.
	 */
	public static final SuggestionProvider<CommandSourceStack> SUGGEST = (context, builder) -> {
		List<String> options = new ArrayList<>(SELECTORS);
		for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
			options.add(player.getGameProfile().name());
		}
		// A greedy argument hands over everything typed so far, so suggestions would try to complete the
		// whole run of names at once. Offsetting to after the last space completes just the name being
		// typed, and keeps suggestions working for the second and third target in a list.
		String remaining = builder.getRemaining();
		int lastSpace = remaining.lastIndexOf(' ');
		SuggestionsBuilder at = lastSpace < 0 ? builder : builder.createOffset(builder.getStart() + lastSpace + 1);
		return SharedSuggestionProvider.suggest(options, at);
	};

	/**
	 * Resolves a target token to every player it matches.
	 *
	 * @throws CommandSyntaxException when nothing matched, so the caller does not have to check.
	 */
	public static List<ServerPlayer> many(CommandContext<CommandSourceStack> context, String name)
			throws CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		// Several targets can be listed: "/heal Steve @o" is one command, not two.
		List<ServerPlayer> matched = new ArrayList<>();
		for (String token : StringArgumentType.getString(context, name).trim().split("\\s+")) {
			if (token.isEmpty()) {
				continue;
			}
			for (ServerPlayer player : resolve(source, token)) {
				// Overlapping selectors are normal — "@o Steve" should not act on Steve twice.
				if (!matched.contains(player)) {
					matched.add(player);
				}
			}
		}
		if (matched.isEmpty()) {
			throw NO_MATCH.create();
		}
		return matched;
	}

	/** Resolves to exactly one player — the first match when a selector covers several. */
	public static ServerPlayer one(CommandContext<CommandSourceStack> context, String name)
			throws CommandSyntaxException {
		return many(context, name).get(0);
	}

	private static List<ServerPlayer> resolve(CommandSourceStack source, String token) {
		List<ServerPlayer> online = new ArrayList<>(source.getServer().getPlayerList().getPlayers());
		return switch (token.toLowerCase(Locale.ROOT)) {
			case "@a", "@e" -> online;
			case "@s" -> self(source);
			case "@p" -> nearest(source, online);
			case "@r" -> online.isEmpty()
				? List.of()
				: List.of(online.get(source.getServer().overworld().getRandom().nextInt(online.size())));
			case "@o" -> online.stream().filter(player -> isOperator(source, player)).toList();
			// @d, not @n: vanilla already uses @n for the nearest entity, and a client seeing its own
			// selector offers to open an options block with [ - which has nothing to do with this.
			//
			// Bots are excluded here rather than counted as ordinary players. They are always non-ops,
			// so without this every /heal @d and /kill @d would sweep up the scene bots too, which is
			// almost never what somebody targeting "the players" meant.
			case "@d" -> online.stream()
				.filter(player -> !isOperator(source, player) && !FakePlayers.isFake(player))
				.toList();
			case "@b" -> online.stream().filter(FakePlayers::isFake).toList();
			default -> byName(source, online, token);
		};
	}

	private static List<ServerPlayer> self(CommandSourceStack source) {
		return source.getEntity() instanceof ServerPlayer player ? List.of(player) : List.of();
	}

	private static List<ServerPlayer> nearest(CommandSourceStack source, List<ServerPlayer> online) {
		var origin = source.getPosition();
		return online.stream()
			.min(java.util.Comparator.comparingDouble(player -> player.position().distanceToSqr(origin)))
			.map(List::of)
			.orElse(List.of());
	}

	private static boolean isOperator(CommandSourceStack source, ServerPlayer player) {
		return source.getServer().getPlayerList().isOp(player.nameAndId());
	}

	/**
	 * Matches a real username first, then a nickname.
	 *
	 * <p>Nicknames are accepted because that is the name everyone on the server can actually see — being
	 * told "no such player" for the name above someone's head would be baffling. Real names win a tie, so
	 * a nickname can never hide the account it is imitating.
	 */
	private static List<ServerPlayer> byName(CommandSourceStack source, List<ServerPlayer> online, String token) {
		for (ServerPlayer player : online) {
			if (player.getGameProfile().name().equalsIgnoreCase(token)) {
				return List.of(player);
			}
		}
		for (ServerPlayer player : online) {
			String nickname = NickRegistry.get(player.getUUID());
			if (nickname != null && nickname.equalsIgnoreCase(token)) {
				return List.of(player);
			}
		}
		return List.of();
	}
}
