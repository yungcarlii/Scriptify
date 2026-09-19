package scriptify.compat;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import scriptify.config.ScriptifyConfig;

/**
 * Finds commands something else registers under a name Scriptify also uses.
 *
 * <p>This is the detection that matters, because it does not depend on knowing the other mod.
 * Scriptify claims ordinary names — {@code /nick}, {@code /vanish}, {@code /heal}, {@code /top},
 * {@code /ec}, {@code /gmc} — and any server-utility mod written since is liable to claim the same
 * ones.
 *
 * <p>What happens without this is worth stating precisely, because it is not what people assume.
 * Brigadier does not let a second registration replace the first: {@code addChild} <em>merges</em>
 * them. Both mods' argument branches end up on one node, and the executor belongs to whichever
 * registered last. So {@code /nick Steve} can run one mod's code while {@code /nick} on its own runs
 * the other's. Nothing errors, nothing is logged, and the result changes if you reorder your mods
 * folder.
 *
 * <p>Catching it takes three observations, because a name can be claimed at three different moments:
 * <ul>
 *   <li>Vanilla, before any mod runs. Seen by snapshotting the tree in an early event phase.</li>
 *   <li>A mod registering before Scriptify. Seen as we register, because the name is already there.</li>
 *   <li>A mod registering after Scriptify. Invisible at registration time, so what we registered is
 *       remembered and checked again once the server is up, which is the first moment every mod has
 *       certainly had its turn.</li>
 * </ul>
 */
public final class CommandConflicts {
	/** Where the other claim on a command name came from. */
	public enum Origin {
		/** Minecraft itself. Scriptify is extending a vanilla command, not colliding with a mod. */
		VANILLA,
		/** Another mod, which registered before Scriptify. */
		MOD_BEFORE,
		/** Another mod, which registered after Scriptify and merged over the top. */
		MOD_AFTER
	}

	/** One command name claimed twice. */
	public record Collision(String name, Origin origin, ConflictPolicy policy) {
		/** How a report should describe who else wants this name. */
		public String describe() {
			return switch (this.origin) {
				case VANILLA -> "is also a vanilla command";
				case MOD_BEFORE -> "is registered by another mod too";
				case MOD_AFTER -> "was registered over by another mod";
			};
		}
	}

	/** Root command names present before any mod registered: vanilla's own. */
	private static final Set<String> vanilla = new HashSet<>();
	/** The names Scriptify registered, so they can be checked again later. */
	private static final Map<String, LiteralCommandNode<CommandSourceStack>> registered =
		new LinkedHashMap<>();
	/** What each of those executed at the time, to notice it being taken over. */
	private static final Map<String, Command<CommandSourceStack>> executors = new HashMap<>();
	private static final List<Collision> collisions = new ArrayList<>();

	private CommandConflicts() {
	}

	public static List<Collision> collisions() {
		return List.copyOf(collisions);
	}

	/**
	 * The root command names Scriptify itself registered.
	 *
	 * <p>Used by the rank gate to tell its own commands from everyone else's. Scriptify's commands are
	 * governed by feature nodes, so a rank's command filter must leave them alone - an "only
	 * /gamemode" rank that also took away /scriptify would be nobody's intent.
	 */
	public static Set<String> scriptifyRoots() {
		return Set.copyOf(registered.keySet());
	}

	/**
	 * Records the command tree before any mod has added to it.
	 *
	 * <p>Registered in an event phase ordered ahead of the default one, which is the only way to be
	 * sure of running before other mods. Without it, every vanilla command Scriptify extends — and
	 * {@code /item} is one — looks exactly like a mod conflict. A detector that cries wolf about
	 * vanilla is one people learn to ignore.
	 */
	public static void snapshotVanilla(CommandDispatcher<CommandSourceStack> dispatcher) {
		vanilla.clear();
		for (CommandNode<CommandSourceStack> child : dispatcher.getRoot().getChildren()) {
			vanilla.add(child.getName());
		}
	}

	/**
	 * Wraps the dispatcher so every Scriptify registration is seen.
	 *
	 * <p>A subclass sharing the real root node, rather than a copy: registrations land in the live
	 * command tree exactly as before, and the only added behaviour is the bookkeeping. That is what
	 * keeps the forty-odd registration sites untouched — they cannot tell the difference.
	 */
	public static CommandDispatcher<CommandSourceStack> watch(
			CommandDispatcher<CommandSourceStack> real, ScriptifyConfig.Compat settings) {
		registered.clear();
		executors.clear();
		collisions.clear();
		if (settings == null || !settings.enabled) {
			return real;
		}
		return new Watching(real, settings);
	}

	/**
	 * Checks whether anything took a command over after we registered it, and puts ours back where the
	 * policy says to.
	 */
	public static void verify(MinecraftServer server, ScriptifyConfig.Compat settings, Logger logger) {
		if (settings == null || !settings.enabled || registered.isEmpty()) {
			return;
		}
		RootCommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot();
		for (Map.Entry<String, LiteralCommandNode<CommandSourceStack>> entry : registered.entrySet()) {
			String name = entry.getKey();
			CommandNode<CommandSourceStack> live = root.getChild(name);
			Command<CommandSourceStack> ours = executors.get(name);
			if (live == null || ours == null || live.getCommand() == ours) {
				continue;
			}
			// The executor changed after we set it, so something registered the same literal later and
			// Brigadier merged its command onto the node.
			ConflictPolicy policy = settings.policyForCommand(name);
			collisions.add(new Collision(name, Origin.MOD_AFTER, policy));
			if (policy != ConflictPolicy.PRIORITY) {
				continue;
			}
			// Merging a bare literal carrying only our executor puts it back, without disturbing the
			// argument branches either mod added.
			root.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal(name)
				.executes(ours)
				.build());
			logger.info("Scriptify reclaimed /{} after another mod registered over it.", name);
		}
		report(logger);
	}

	/**
	 * Names the command collisions once every mod has registered.
	 *
	 * <p>Only the ones involving another mod. Extending a vanilla command is intentional and happens on
	 * every start, so logging it would be noise that trains people to skim the warnings that matter.
	 */
	private static void report(Logger logger) {
		List<Collision> withMods = collisions.stream()
			.filter(collision -> collision.origin() != Origin.VANILLA)
			.toList();
		if (withMods.isEmpty()) {
			return;
		}
		logger.warn("Scriptify shares {} command name(s) with another mod:", withMods.size());
		for (Collision collision : withMods) {
			logger.warn("  /{} {} — {}", collision.name(), collision.describe(),
				switch (collision.policy()) {
					case PRIORITY -> "Scriptify is taking it";
					case YIELD -> "Scriptify has stood down";
					case IGNORE -> "both are registered, so load order decides";
				});
		}
		logger.warn("Hand any single one over with compat.commandOverrides in the Scriptify config, "
			+ "for example \"nick\": \"YIELD\".");
	}

	private static final class Watching extends CommandDispatcher<CommandSourceStack> {
		private final Set<String> taken;
		private final ScriptifyConfig.Compat settings;
		/**
		 * Somewhere to put a command we are yielding. The builder is still built and a real node still
		 * returned, so a caller doing anything with the result is unaffected — the node simply is not in
		 * the tree the server uses.
		 */
		private final RootCommandNode<CommandSourceStack> discarded = new RootCommandNode<>();

		private Watching(CommandDispatcher<CommandSourceStack> real, ScriptifyConfig.Compat settings) {
			super(real.getRoot());
			this.settings = settings;
			this.taken = new HashSet<>();
			for (CommandNode<CommandSourceStack> child : real.getRoot().getChildren()) {
				this.taken.add(child.getName());
			}
		}

		@Override
		public LiteralCommandNode<CommandSourceStack> register(
				LiteralArgumentBuilder<CommandSourceStack> builder) {
			String name = builder.getLiteral();
			// Scriptify registers several of its own names more than once - /bots is built by three
			// separate places that each add branches to it - and Brigadier merging those is the
			// intended behaviour, not a collision. Without this the mod reported itself as the
			// conflicting mod.
			if (this.taken.contains(name) && !registered.containsKey(name)) {
				// Extending a vanilla command is a deliberate part of the mod, not an accident, so it is
				// reported as what it is and never yielded away by the mod-conflict setting.
				Origin origin = vanilla.contains(name) ? Origin.VANILLA : Origin.MOD_BEFORE;
				ConflictPolicy policy = origin == Origin.VANILLA
					? ConflictPolicy.PRIORITY
					: this.settings.policyForCommand(name);
				collisions.add(new Collision(name, origin, policy));
				if (policy == ConflictPolicy.YIELD) {
					LiteralCommandNode<CommandSourceStack> node = builder.build();
					this.discarded.addChild(node);
					return node;
				}
			}
			LiteralCommandNode<CommandSourceStack> node = super.register(builder);
			registered.put(name, node);
			executors.put(name, node.getCommand());
			return node;
		}
	}
}
