package scriptify.bots.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import scriptify.bots.bot.BotManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Lets the {@code /bots} commands take target selectors ({@code @a}, {@code @r},
 * {@code @p[distance=..10]} ...) anywhere they take a bot's name.
 *
 * <p><b>Selectors pick from the bots.</b> Plain vanilla {@code @r} picks a random player and
 * {@code @p} the nearest one, which on a server with people on it is usually a person, so a
 * command meant for "a random bot" would do nothing most of the time. Here the selector is
 * widened to every entity it matches, cut down to bots, and only then sorted and trimmed the
 * way it asked for. {@code @r} is a random bot, {@code @p} the nearest bot,
 * {@code @r[limit=3]} three random bots, and {@code @a} every bot.</p>
 *
 * <p><b>Plain names still reach bots that are not in the world.</b> A bot that is dead and waiting
 * to respawn is still on the roster, and the old commands took its name. A selector can only find
 * what is loaded, so a plain name that matches nothing loaded falls back to the roster.</p>
 *
 * <p>The argument is vanilla's own entity argument, so the client already knows how to parse and
 * highlight it. A custom argument type would have to be registered on the client as well.</p>
 */
final class BotSelector {

    private static final SimpleCommandExceptionType NO_BOTS =
        new SimpleCommandExceptionType(Component.literal("No bots matched that."));

    private BotSelector() {}

    /** Suggests bot names first, and hands over to vanilla once a selector is being typed. */
    static final SuggestionProvider<CommandSourceStack> BOT_OR_SELECTOR = (ctx, builder) -> {
        String remaining = builder.getRemaining();
        if (remaining.startsWith("@")) {
            return EntityArgument.entities().listSuggestions(ctx, builder);
        }
        String lower = remaining.toLowerCase(Locale.ROOT);
        if (EntitySelectorParser.allowSelectors((Object) ctx.getSource())) {
            for (String selector : List.of("@a", "@r", "@p", "@n", "@s")) {
                if (selector.startsWith(lower)) builder.suggest(selector);
            }
        }
        for (String bot : BotManager.botNames()) {
            if (bot.toLowerCase(Locale.ROOT).contains(lower)) builder.suggest(bot);
        }
        return builder.buildFuture();
    };

    /** Players rather than bots: vanilla's own suggestions, which already list both. */
    static final SuggestionProvider<CommandSourceStack> PLAYER_OR_SELECTOR =
        (ctx, builder) -> EntityArgument.entities().listSuggestions(ctx, builder);

    /** An argument that takes one or more bots. */
    static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> bots(String name) {
        return Commands.argument(name, EntityArgument.entities()).suggests(BOT_OR_SELECTOR);
    }

    /** An argument that takes one or more players, bots or not. */
    static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> players(String name) {
        return Commands.argument(name, EntityArgument.entities()).suggests(PLAYER_OR_SELECTOR);
    }

    /**
     * The bots an argument names, as their in-game names, in selector order.
     *
     * @throws CommandSyntaxException when nothing matched, or selectors are not allowed here
     */
    static List<String> bots(CommandContext<CommandSourceStack> ctx, String argument)
        throws CommandSyntaxException {
        String raw = raw(ctx, argument);
        CommandSourceStack source = ctx.getSource();
        List<String> names = new ArrayList<>();
        for (Entity entity : select(source, raw, true)) {
            names.add(entity.getName().getString());
        }
        if (names.isEmpty() && !raw.startsWith("@") && BotManager.isBot(raw)) {
            names.add(BotManager.canonical(raw));
        }
        if (names.isEmpty()) throw NO_BOTS.create();
        return names;
    }

    /**
     * The players an argument names, as names, in selector order. A plain name is passed through as
     * written even when nobody by that name is online, since faction membership is kept by name and
     * outlives a session.
     */
    static List<String> playerNames(CommandContext<CommandSourceStack> ctx, String argument)
        throws CommandSyntaxException {
        String raw = raw(ctx, argument);
        if (!raw.startsWith("@")) {
            ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(raw);
            return List.of(online != null ? online.getName().getString() : raw);
        }
        List<String> names = new ArrayList<>();
        for (Entity entity : select(ctx.getSource(), raw, false)) {
            names.add(entity.getName().getString());
        }
        if (names.isEmpty()) throw EntityArgument.NO_PLAYERS_FOUND.create();
        return names;
    }

    /** The online players an argument names. */
    static List<ServerPlayer> onlinePlayers(CommandContext<CommandSourceStack> ctx, String argument)
        throws CommandSyntaxException {
        List<ServerPlayer> players = new ArrayList<>();
        for (Entity entity : select(ctx.getSource(), raw(ctx, argument), false)) {
            players.add((ServerPlayer) entity);
        }
        if (players.isEmpty()) throw EntityArgument.NO_PLAYERS_FOUND.create();
        return players;
    }

    /** "Bot 'Steve'" for one, "3 bots" for several: how the replies name what they acted on. */
    static String describe(List<String> bots) {
        return bots.size() == 1 ? "Bot '" + bots.get(0) + "'" : bots.size() + " bots";
    }

    /**
     * Runs a selector over the players, keeping only bots when asked, then sorts and trims.
     *
     * <p>The selector is parsed twice on purpose. The first parse is only to learn how many results
     * it wants and in what order; the second has both lifted, so the filtering to bots happens
     * before the limit rather than after it.</p>
     */
    private static List<Entity> select(CommandSourceStack source, String raw, boolean botsOnly)
        throws CommandSyntaxException {
        boolean allowed = EntitySelectorParser.allowSelectors((Object) source);
        EntitySelectorParser parser = new EntitySelectorParser(new StringReader(raw), allowed);
        EntitySelector asked = parser.parse();
        int limit = asked.getMaxResults();
        BiConsumer<net.minecraft.world.phys.Vec3, List<? extends Entity>> order = parser.getOrder();
        parser.setMaxResults(EntitySelector.INFINITE);
        parser.setOrder(EntitySelector.ORDER_ARBITRARY);
        EntitySelector widened = parser.getSelector();

        // A set, because an entity selector can list someone twice and a bot told twice to start a
        // path would just restart it.
        LinkedHashSet<Entity> matched = new LinkedHashSet<>();
        for (Entity entity : widened.findEntities(source)) {
            if (!(entity instanceof ServerPlayer player)) continue;
            if (botsOnly && !BotManager.isBot(player.getName().getString())) continue;
            matched.add(player);
        }
        List<Entity> result = new ArrayList<>(matched);
        if (result.size() > 1) order.accept(source.getPosition(), result);
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    /** The text the operator typed for an argument, which is what gets re-parsed above. */
    private static String raw(CommandContext<CommandSourceStack> ctx, String argument) {
        for (ParsedCommandNode<CommandSourceStack> node : ctx.getNodes()) {
            // Arguments only: "bots" is also the name of the root literal.
            if (node.getNode() instanceof ArgumentCommandNode<?, ?>
                && node.getNode().getName().equals(argument)) {
                return node.getRange().get(ctx.getInput());
            }
        }
        throw new IllegalArgumentException("No argument named " + argument);
    }
}
