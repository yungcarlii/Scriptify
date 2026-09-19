package scriptify.mixin;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import scriptify.compat.CompatRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the client ask the server who is online, instead of reading its own tab list.
 *
 * <p><b>Why this exists.</b> The fake tab roster was showing up in command autocomplete: type
 * {@code /tp } and there are the invented names, offered as if you could teleport to them. They are
 * not on the server at all - they exist only in packets - so picking one fails, and a list of two
 * hundred imaginary players buries the handful of real ones.
 *
 * <p>The reason is not that anything leaked. For a player-shaped argument the vanilla client does not
 * ask the server who is online; it answers from the player list it has been sent, which is precisely
 * where the fake entries live. No amount of care on the server side can affect that, because the
 * server is never consulted.
 *
 * <p><b>What changes.</b> Brigadier lets a node say "ask the server for suggestions on this one", and
 * the client honours it. So as the command tree is built for sending, every entity and profile
 * argument is marked that way. The client then asks, and the answer comes from
 * {@code getOnlinePlayerNames()} - the real player list, which the fakes were never in.
 *
 * <p>This edits the copy being prepared for the client, not the server's own dispatcher, so nothing
 * about how commands actually run is touched.
 *
 * <p><b>Only while the fake roster is actually showing.</b> This was applied unconditionally at
 * first, on the reasoning that asking the server is the more correct answer anyway. That was a bad
 * trade: it changes how every player-shaped argument on the server behaves - including how and when
 * the client draws its suggestion popup - in exchange for nothing at all on a server with no invented
 * players to hide. A change with no upside should not be paid for, so when the roster is empty the
 * tree is left exactly as vanilla built it and suggestions behave exactly as they always did.
 *
 * <p>Being conditional means the answer can change while somebody is connected, and the tree is built
 * once, at login. That hole was real and was reported twice: switch the roster on mid-scene and
 * everybody already online kept a tree that still answered locally, so the invented names went on
 * being offered until they reconnected; switch it off and they kept asking the server about every
 * player argument for the rest of the session, which on a remote server is a visible pause before the
 * suggestion box appears. {@link scriptify.feature.FakeTabService} now resends the tree when the
 * answer changes, so this tracks the setting immediately.
 *
 * <p>The cost, when it is on, is one small packet while somebody is mid-word in a command that takes
 * a player. That is what vanilla already does for most other argument types.
 */
@Mixin(Commands.class)
public abstract class CommandSuggestionMixin {

	/**
	 * Argument nodes that must be left answering locally, however much we would rather they did not.
	 *
	 * <p>Brigadier asks <em>every</em> child of a node for suggestions and waits for all of them, and
	 * the client can only have one question outstanding at a time:
	 *
	 * <pre>
	 * public CompletableFuture&lt;Suggestions&gt; customSuggestion(CommandContext&lt;?&gt; context) {
	 *     if (this.pendingSuggestionsFuture != null) {
	 *         this.pendingSuggestionsFuture.cancel(false);
	 *     }
	 *     ...
	 * </pre>
	 *
	 * <p>So marking two children of the same node server-answered is not twice as good, it is broken:
	 * the second question cancels the first, the combined wait never finishes, and the suggestion box
	 * is never drawn at all. {@code /teleport} is the one vanilla command this happens to - it takes
	 * either a destination or a list of targets in the same place, both of them entity arguments - and
	 * {@code /tp} redirects into it, which is why {@code /tp} was the only command in the game with no
	 * suggestions while every other one was fine.
	 *
	 * <p>Where that happens the node is left exactly as vanilla built it. The cost is that the invented
	 * names are still offered for {@code /tp} specifically, which is not good, but a wrong suggestion
	 * is worth more than no suggestion: the list can be ignored, a box that never appears cannot be
	 * worked around.
	 */
	private static Set<CommandNode<?>> ambiguous = Set.of();

	/** The dispatcher root the set above was worked out from; a rebuilt tree needs a fresh answer. */
	private static CommandNode<?> ambiguousRoot;

	/**
	 * Works out which nodes have an entity-argument sibling, once per command tree.
	 *
	 * <p>Done here rather than in the redirect below because a node does not know its parent - Brigadier
	 * only links downwards - so the question "does this have a sibling of the same shape" can only be
	 * answered by walking from the root.
	 */
	@Inject(method = "sendCommands", at = @At("HEAD"))
	private void scriptify$findAmbiguousArguments(ServerPlayer player, CallbackInfo callback) {
		CommandNode<CommandSourceStack> root = ((Commands) (Object) this).getDispatcher().getRoot();
		if (root == ambiguousRoot) {
			return;
		}
		Set<CommandNode<?>> found = Collections.newSetFromMap(new IdentityHashMap<>());
		scriptify$walk(root, found, Collections.newSetFromMap(new IdentityHashMap<>()));
		ambiguous = found;
		ambiguousRoot = root;
	}

	/**
	 * @param seen guards against the cycles redirects make - {@code /execute run} points back at the
	 *             root, so a walk without this never returns.
	 */
	private static void scriptify$walk(CommandNode<?> node, Set<CommandNode<?>> found,
			Set<CommandNode<?>> seen) {
		if (!seen.add(node)) {
			return;
		}
		List<CommandNode<?>> playerShaped = new ArrayList<>(2);
		for (CommandNode<?> child : node.getChildren()) {
			if (scriptify$isPlayerShaped(child)) {
				playerShaped.add(child);
			}
			scriptify$walk(child, found, seen);
		}
		if (playerShaped.size() > 1) {
			found.addAll(playerShaped);
		}
	}

	private static boolean scriptify$isPlayerShaped(CommandNode<?> node) {
		if (!(node instanceof ArgumentCommandNode<?, ?> argument)) {
			return false;
		}
		return argument.getType() instanceof EntityArgument
			|| argument.getType() instanceof GameProfileArgument;
	}

	/**
	 * Marks player-shaped arguments as server-answered as each node is copied for the client.
	 *
	 * <p>Redirecting the builder call rather than injecting at a line keeps this tied to something
	 * named - {@code createBuilder} is Brigadier's, so it is not remapped and cannot drift out from
	 * under the mixin the way an offset would.
	 */
	@Redirect(
		method = "fillUsableCommands",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/brigadier/tree/CommandNode;createBuilder()"
				+ "Lcom/mojang/brigadier/builder/ArgumentBuilder;"))
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static ArgumentBuilder scriptify$askServerAboutPlayers(CommandNode node) {
		ArgumentBuilder builder = node.createBuilder();
		// Nothing to hide, nothing to change.
		if (!CompatRegistry.fakeTabActive()) {
			return builder;
		}
		if (!(builder instanceof RequiredArgumentBuilder required)) {
			return builder;
		}
		// A node that already names its own provider is left alone: it has been given an answer on
		// purpose, and Scriptify's own commands suggest from the real player list already.
		if (required.getSuggestionsProvider() != null) {
			return builder;
		}
		// Two of these under one parent is worse than none: see the note on `ambiguous` above.
		if (ambiguous.contains(node)) {
			return builder;
		}
		if (required.getType() instanceof EntityArgument
			|| required.getType() instanceof GameProfileArgument) {
			required.suggests(SuggestionProviders.ASK_SERVER);
		}
		return builder;
	}
}
