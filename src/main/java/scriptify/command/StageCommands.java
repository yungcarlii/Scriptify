package scriptify.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import scriptify.core.ScriptifyBootstrap;
import scriptify.permission.PermissionService;
import scriptify.ui.Msg;
import scriptify.ui.Panel;

/**
 * {@code /stage}: put the set back between takes.
 *
 * <p>{@code /stage save <name> [radius]} copies every block in a box around you - air included, so
 * debris is cleared too, and chests and signs with what is in them - and {@code /stage reset <name>}
 * lays it back exactly: the crater from take one is gone for take two. Built on vanilla's structure
 * templates, the machinery behind the structure block. Entities are left alone, so the cast and the bots
 * stay put; item frames and armour stands are not restored either.
 */
public final class StageCommands {
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
	private static final int DEFAULT_RADIUS = 24;
	/** 97 blocks a side. Bigger and a reset starts to stall the server for long enough to notice. */
	private static final int MAX_RADIUS = 48;
	/**
	 * Nothing reacts to the restore: no drops, no spilled chests where a chest is replaced, no sand
	 * falling or TNT lighting because it was placed. The set comes back as it was, not as it would land.
	 */
	private static final int RESTORE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
		| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;

	private static final String DIMENSION = "scriptify_dimension";
	private static final String X = "scriptify_x";
	private static final String Y = "scriptify_y";
	private static final String Z = "scriptify_z";

	private StageCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("stage")
			.requires(source -> ScriptifyBootstrap.context() != null
				&& ScriptifyBootstrap.context().permissions().has(source, PermissionService.ADMIN))
			.executes(context -> list(context.getSource()))
			.then(literal("list").executes(context -> list(context.getSource())))
			.then(literal("save").then(argument("name", StringArgumentType.word())
				.executes(context -> save(context, DEFAULT_RADIUS))
				.then(argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
					.executes(context -> save(context, IntegerArgumentType.getInteger(context, "radius"))))))
			.then(literal("reset").then(stage().executes(context -> reset(context))))
			.then(literal("delete").then(stage().executes(context -> {
				String name = name(context);
				try {
					if (!Files.deleteIfExists(file(name))) {
						return fail(context, "No stage called '" + name + "'.");
					}
				} catch (IOException exception) {
					return fail(context, "Could not delete stage '" + name + "': " + exception.getMessage());
				}
				context.getSource().sendSuccess(() -> Msg.line("Deleted stage " + name + "."), false);
				return 1;
			}))));
	}

	private static int save(CommandContext<CommandSourceStack> context, int radius) {
		String name = name(context);
		if (!NAME.matcher(name).matches()) {
			return fail(context, "Stage names are letters, digits, - and _, up to 32 long.");
		}
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		BlockPos centre = BlockPos.containing(source.getPosition());
		int minY = Math.max(level.getMinY(), centre.getY() - radius);
		int maxY = Math.min(level.getMaxY(), centre.getY() + radius);
		BlockPos origin = new BlockPos(centre.getX() - radius, minY, centre.getZ() - radius);
		Vec3i size = new Vec3i(radius * 2 + 1, maxY - minY + 1, radius * 2 + 1);

		StructureTemplate template = new StructureTemplate();
		template.fillFromWorld(level, origin, size, false, List.of());
		CompoundTag tag = template.save(new CompoundTag());
		tag.putString(DIMENSION, level.dimension().identifier().toString());
		tag.putInt(X, origin.getX());
		tag.putInt(Y, origin.getY());
		tag.putInt(Z, origin.getZ());
		try {
			Files.createDirectories(directory());
			NbtIo.writeCompressed(tag, file(name));
		} catch (IOException exception) {
			return fail(context, "Could not save stage '" + name + "': " + exception.getMessage());
		}
		source.sendSuccess(() -> Msg.line("Saved stage " + name + ": " + size.getX() + "×" + size.getY() + "×"
			+ size.getZ() + " blocks around you. /stage reset " + name + " puts it back."), false);
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> context) {
		String name = name(context);
		Path file = file(name);
		if (!Files.exists(file)) {
			return fail(context, "No stage called '" + name + "'. /stage lists them.");
		}
		CompoundTag tag;
		try {
			tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (IOException exception) {
			return fail(context, "Could not read stage '" + name + "': " + exception.getMessage());
		}
		var server = context.getSource().getServer();
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
			Identifier.parse(tag.getStringOr(DIMENSION, "minecraft:overworld"))));
		if (level == null) {
			return fail(context, "Stage '" + name + "' was saved in a dimension this server no longer has.");
		}
		StructureTemplate template = new StructureTemplate();
		template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		BlockPos origin = new BlockPos(tag.getIntOr(X, 0), tag.getIntOr(Y, 0), tag.getIntOr(Z, 0));
		template.placeInWorld(level, origin, origin, new StructurePlaceSettings(), level.getRandom(), RESTORE_FLAGS);
		context.getSource().sendSuccess(() -> Msg.line("Reset stage " + name + "."), false);
		return 1;
	}

	private static int list(CommandSourceStack source) {
		Panel.open(source, "Stages");
		List<String> names = names();
		if (names.isEmpty()) {
			Panel.note(source, "None yet. Stand in the middle of the set: /stage save <name> [radius]");
		}
		for (String name : names) {
			Panel.row(source, "•", name, "/stage reset " + name, Panel.ACTION, "/stage reset " + name,
				"Click to type the reset. Every block in the box goes back to how it was saved.");
		}
		Panel.close(source);
		return names.size();
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> stage() {
		return argument("name", StringArgumentType.word())
			.suggests((context, builder) -> SharedSuggestionProvider.suggest(names(), builder));
	}

	private static List<String> names() {
		List<String> names = new ArrayList<>();
		try (var files = Files.list(directory())) {
			files.map(path -> path.getFileName().toString())
				.filter(file -> file.endsWith(".nbt"))
				.map(file -> file.substring(0, file.length() - ".nbt".length()))
				.sorted()
				.forEach(names::add);
		} catch (IOException ignored) {
			// No folder yet is no stages yet.
		}
		return names;
	}

	private static Path directory() {
		return ScriptifyBootstrap.context().storage().root().resolve("stages");
	}

	/** Only ever a validated name, so the path cannot leave the stages folder. */
	private static Path file(String name) {
		if (!NAME.matcher(name).matches()) {
			name = "_invalid";
		}
		return directory().resolve(name + ".nbt");
	}

	private static String name(CommandContext<CommandSourceStack> context) {
		return StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
	}

	private static int fail(CommandContext<CommandSourceStack> context, String message) {
		context.getSource().sendFailure(Msg.failure(message));
		return 0;
	}
}
