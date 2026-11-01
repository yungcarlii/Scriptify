package scriptify.bots.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import scriptify.bots.bot.BotScenes;

import java.util.List;
import java.util.Locale;

/**
 * Saved scenes: {@code /bots savescene}, {@code /bots scene} and {@code /bots scenes}.
 *
 * <p>Three words rather than one subtree, because bringing a scene back is the one typed most and
 * deserves to be short: {@code /bots scene city}. Everything else done to a scene hangs off its name
 * ({@code /bots scene city remove}), so no literal can shadow a scene that happens to be called
 * "list".</p>
 */
final class SceneCommand {

    private SceneCommand() {}

    /** About a street of a build, the usual unit a scene is laid out in. */
    private static final double DEFAULT_RADIUS = 48.0;

    private static final SuggestionProvider<CommandSourceStack> SCENE_SUGGESTIONS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (BotScenes.Scene scene : BotScenes.all()) {
                if (scene.name.toLowerCase(Locale.ROOT).contains(remaining)) {
                    builder.suggest(scene.name);
                }
            }
            return builder.buildFuture();
        };

    static LiteralArgumentBuilder<CommandSourceStack> saveNode() {
        return BotCommand.cmd("savescene", "Save the bots around you: /bots savescene <name> [radius]")
            .then(Commands.argument("name", StringArgumentType.word())
                .suggests(SCENE_SUGGESTIONS)
                .executes(ctx -> save(ctx, DEFAULT_RADIUS))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 512))
                    .executes(ctx -> save(ctx, DoubleArgumentType.getDouble(ctx, "radius")))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> sceneNode() {
        return BotCommand.cmd("scene", "Bring a saved scene back: /bots scene <name> [remove|delete]")
            .then(Commands.argument("name", StringArgumentType.word())
                .suggests(SCENE_SUGGESTIONS)
                .executes(SceneCommand::restore)
                .then(Commands.literal("remove").executes(SceneCommand::remove))
                .then(Commands.literal("delete").executes(SceneCommand::delete)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> listNode() {
        return Commands.literal("scenes").executes(SceneCommand::list);
    }

    // ===================== handlers =====================

    private static int save(CommandContext<CommandSourceStack> ctx, double radius) {
        var source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        boolean replacing = BotScenes.get(name) != null;
        int saved = BotScenes.save(source.getServer(), name, source.getLevel(), source.getPosition(), radius);
        if (saved == 0) {
            // Nothing is overwritten in this case, so a scene saved from the wrong spot is not lost.
            source.sendFailure(Component.literal("No bots within " + (int) radius + " blocks to save."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal((replacing ? "Updated" : "Saved") + " scene '" + name
            + "' with " + bots(saved) + ". Bring it back with /bots scene " + name), true);
        return saved;
    }

    private static int restore(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        BotScenes.Scene scene = find(ctx);
        if (scene == null) return 0;
        int queued = BotScenes.restore(source.getServer(), scene);
        int here = scene.bots.size() - queued;
        if (queued == 0) {
            source.sendSuccess(() -> Component.literal("Scene '" + scene.name + "' is already here."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Bringing back " + bots(queued) + " of scene '"
            + scene.name + "'" + (here > 0 ? " (" + here + " already here)" : "") + "."), true);
        return queued;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        BotScenes.Scene scene = find(ctx);
        if (scene == null) return 0;
        int removed = BotScenes.remove(source.getServer(), scene, source);
        source.sendSuccess(() -> Component.literal("Removed " + bots(removed) + " of scene '" + scene.name
            + "'. /bots scene " + scene.name + " brings them back."), true);
        return removed;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        BotScenes.Scene scene = find(ctx);
        if (scene == null) return 0;
        BotScenes.delete(scene.name);
        source.sendSuccess(() -> Component.literal("Deleted scene '" + scene.name
            + "'. Bots already in the world stay."), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        List<BotScenes.Scene> all = BotScenes.all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No saved scenes. Save one with /bots savescene <name> [radius]"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Saved scenes (" + all.size() + "):"), false);
        for (BotScenes.Scene scene : all) {
            int here = BotScenes.present(source.getServer(), scene);
            String line = String.format(Locale.US, " - %s: %s, %d here, around %.0f %.0f %.0f in %s",
                scene.name, bots(scene.bots.size()), here, scene.x, scene.y, scene.z, scene.dimension);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static BotScenes.Scene find(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        BotScenes.Scene scene = BotScenes.get(name);
        if (scene == null) {
            ctx.getSource().sendFailure(Component.literal("No scene called '" + name + "'. See /bots scenes"));
        }
        return scene;
    }

    private static String bots(int count) {
        return count + (count == 1 ? " bot" : " bots");
    }
}
