package scriptify.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import scriptify.core.ScriptifyBootstrap;
import scriptify.feature.FakeMessageService;
import scriptify.permission.PermissionService;
import scriptify.runtime.ScriptifyRuntime;
import scriptify.ui.Msg;

/**
 * {@code /fake}: the illusions as commands, so a cue or a command block can stage them too.
 *
 * <p>Each one is what the server itself would have said - vanilla's own translation keys and screens -
 * with nothing real behind it: nobody dies, nobody is banned, nothing is saved.
 */
public final class FakeCommands {
	private FakeCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("fake")
			.requires(source -> ScriptifyBootstrap.context() != null
				&& ScriptifyBootstrap.context().permissions().has(source, PermissionService.DEATH))
			.then(literal("join").then(argument("targets", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "Staged a join for", player -> messages().stageJoin(player)))))
			.then(literal("leave").then(argument("targets", Targets.arg()).suggests(Targets.SUGGEST)
				.executes(context -> each(context, "Staged a leave for", player -> messages().stageLeave(player)))))
			.then(literal("death").then(line(false).executes(context -> death(context))))
			.then(literal("advancement").then(line(true).executes(context -> advancement(context))))
			.then(literal("kick").then(line(false).executes(context -> disconnect(context, false))))
			.then(literal("ban").then(line(false).executes(context -> disconnect(context, true))))
			.then(literal("chat").then(line(false).executes(context -> chat(context))))
			.then(literal("whisper").then(line(false).executes(context -> whisper(context))))
			.then(literal("lightning")
				.executes(context -> lightning(context.getSource(), aimed(context.getSource())))
				.then(argument("pos", Vec3Argument.vec3())
					.executes(context -> lightning(context.getSource(), Vec3Argument.getVec3(context, "pos")))))
			.then(literal("explosion")
				.executes(context -> explosion(context.getSource(), aimed(context.getSource())))
				.then(argument("pos", Vec3Argument.vec3())
					.executes(context -> explosion(context.getSource(), Vec3Argument.getVec3(context, "pos"))))));
	}

	/**
	 * {@code <speaker> <message>}: a chat line from anybody. An online player speaks under their display
	 * name, nickname and team colour included; any other word is a name nobody has to be logged in as.
	 */
	private static int chat(CommandContext<CommandSourceStack> context) {
		String[] parts = StringArgumentType.getString(context, "line").trim().split("\\s+", 2);
		if (parts.length < 2) {
			context.getSource().sendFailure(Msg.failure("Usage: /fake chat <speaker> <message>"));
			return 0;
		}
		context.getSource().getServer().getPlayerList().broadcastSystemMessage(
			Component.translatable("chat.type.text", speaker(context.getSource(), parts[0]), Component.literal(parts[1])),
			false);
		return 1;
	}

	/** {@code <to> <from> <message>}: "<from> whispers to you", shown to that one player only. */
	private static int whisper(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Line line = split(context);
		String[] parts = line.rest().split("\\s+", 2);
		if (parts.length < 2) {
			context.getSource().sendFailure(Msg.failure("Usage: /fake whisper <to> <from> <message>"));
			return 0;
		}
		Component message = Component.translatable("commands.message.display.incoming",
				speaker(context.getSource(), parts[0]), Component.literal(parts[1]))
			.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
		line.targets().forEach(player -> player.sendSystemMessage(message));
		return line.targets().size();
	}

	private static Component speaker(CommandSourceStack source, String word) {
		try {
			return Targets.token(source, word).get(0).getDisplayName();
		} catch (CommandSyntaxException exception) {
			return Component.literal(word);
		}
	}

	/** Where the person running it is looking, so a strike lands on the spot in shot; else where it runs. */
	private static Vec3 aimed(CommandSourceStack source) {
		return source.getEntity() instanceof ServerPlayer player
			? player.pick(160.0D, 1.0F, false).getLocation() : source.getPosition();
	}

	/** A real bolt with real thunder, marked visual-only: no fire, no damage, no charged creepers. */
	private static int lightning(CommandSourceStack source, Vec3 at) {
		ServerLevel level = source.getLevel();
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND);
		if (bolt == null) {
			return 0;
		}
		bolt.snapTo(at.x, at.y, at.z);
		bolt.setVisualOnly(true);
		level.addFreshEntity(bolt);
		return 1;
	}

	/**
	 * The look and sound of an explosion and nothing else: no blocks broken, nobody hurt or knocked back.
	 * Sent long-distance, so a blast across the valley still shows in a wide shot.
	 */
	private static int explosion(CommandSourceStack source, Vec3 at) {
		ServerLevel level = source.getLevel();
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, true, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		// Vanilla's own pitch spread, so a run of blasts does not sound like one sample on repeat.
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0F,
			(1.0F + (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.2F) * 0.7F);
		return 1;
	}

	/**
	 * {@code <who> <text...>} as one argument. The target is a name, nickname or selector, and Brigadier's
	 * word reader refuses {@code @}, while the greedy target argument would swallow the text after it - so
	 * the line is split here instead. Suggests targets for the first word and, for advancements, the
	 * three frames for the second.
	 */
	private static RequiredArgumentBuilder<CommandSourceStack, String> line(boolean frames) {
		return argument("line", StringArgumentType.greedyString()).suggests((context, builder) -> {
			String typed = builder.getRemaining();
			int space = typed.indexOf(' ');
			if (space < 0) {
				return Targets.SUGGEST.getSuggestions(context, builder);
			}
			if (frames && typed.indexOf(' ', space + 1) < 0) {
				return SharedSuggestionProvider.suggest(java.util.Arrays.stream(AdvancementType.values())
					.map(AdvancementType::getSerializedName), builder.createOffset(builder.getStart() + space + 1));
			}
			return builder.buildFuture();
		});
	}

	/** The players the line's first word names, and everything after it. */
	private record Line(List<ServerPlayer> targets, String rest) {
	}

	private static Line split(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		String[] parts = StringArgumentType.getString(context, "line").trim().split("\\s+", 2);
		return new Line(Targets.token(context.getSource(), parts[0]), parts.length > 1 ? parts[1].trim() : "");
	}

	private static int each(CommandContext<CommandSourceStack> context, String verb,
			java.util.function.Consumer<ServerPlayer> action) throws CommandSyntaxException {
		var targets = Targets.many(context, "targets");
		targets.forEach(action);
		context.getSource().sendSuccess(() -> Msg.line(verb + " " + targets.size() + " player(s)."), false);
		return targets.size();
	}

	/** {@code <players> [killer]}: a plain death line, or "slain by" whoever is named. */
	private static int death(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Line line = split(context);
		for (ServerPlayer player : line.targets()) {
			if (line.rest().isEmpty()) {
				messages().broadcastDeath(player);
			} else {
				messages().broadcastSlain(player, line.rest());
			}
		}
		context.getSource().sendSuccess(() -> Msg.line("Broadcast a death for " + line.targets().size()
			+ " player(s)" + (line.rest().isEmpty() ? "." : ", slain by " + line.rest() + ".")), false);
		return line.targets().size();
	}

	/**
	 * {@code <player> <task|goal|challenge> <title> [| description]}. The icon is whatever the person
	 * running it holds, so "Diamonds!" gets a diamond by holding one; from the console, a grass block, the
	 * icon of the advancement tree vanilla opens with.
	 */
	private static int advancement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Line line = split(context);
		String[] words = line.rest().split("\\s+", 2);
		AdvancementType type = java.util.Arrays.stream(AdvancementType.values())
			.filter(candidate -> candidate.getSerializedName().equalsIgnoreCase(words[0]))
			.findFirst().orElse(null);
		if (type == null || words.length < 2 || words[1].isBlank()) {
			context.getSource().sendFailure(Msg.failure(
				"Usage: /fake advancement <player> task|goal|challenge <title> [| description]"));
			return 0;
		}
		String[] parts = words[1].split("\\|", 2);
		Component title = Component.literal(parts[0].trim());
		Component description = Component.literal(parts.length > 1 ? parts[1].trim() : "");
		ItemStack icon = context.getSource().getEntity() instanceof ServerPlayer holder
			&& !holder.getMainHandItem().isEmpty()
			? holder.getMainHandItem().copyWithCount(1) : new ItemStack(Items.GRASS_BLOCK);
		for (ServerPlayer target : line.targets()) {
			messages().broadcastAdvancement(target, type, title, description, icon);
		}
		context.getSource().sendSuccess(() -> Msg.line("Awarded " + line.targets().size() + " player(s) a fake "
			+ type.getSerializedName() + ": " + parts[0].trim()), false);
		return line.targets().size();
	}

	/** {@code <player> [reason]}. One player: a selector naming several still takes only the first. */
	private static int disconnect(CommandContext<CommandSourceStack> context, boolean ban)
			throws CommandSyntaxException {
		Line line = split(context);
		ServerPlayer target = line.targets().get(0);
		String reason = line.rest().isEmpty() ? null : line.rest();
		String name = target.getGameProfile().name();
		if (ban) {
			messages().stageBan(target, reason);
		} else {
			messages().stageKick(target, reason);
		}
		context.getSource().sendSuccess(() -> Msg.line("Showed " + name + " the " + (ban ? "ban" : "kick")
			+ " screen. Nothing was recorded; they can rejoin."), false);
		return 1;
	}

	private static FakeMessageService messages() {
		return ScriptifyRuntime.fakeMessages();
	}
}
