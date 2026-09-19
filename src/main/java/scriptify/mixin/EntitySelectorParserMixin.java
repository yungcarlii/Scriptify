package scriptify.mixin;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import scriptify.access.FakePlayers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Teaches vanilla's selector parser three new targets: {@code @o} for operators, {@code @d} for real
 * players without operator, and {@code @b} for bots and other fake players.
 *
 * <p>Without this they only work on Scriptify's own commands, which is a strange line to draw — an
 * operator who has learned {@code @d} will reasonably expect {@code /gamemode adventure @d} to work
 * too. Hooking the parser puts them everywhere selectors are accepted: vanilla commands, command
 * blocks and functions alike.
 *
 * <p>Purely additive. Both letters previously threw "unknown selector type", so nothing that parsed
 * before parses differently now.
 *
 * <p>The {@code allowSelectors} permission check lives in {@code parse()} and runs before this method
 * is ever called, so cancelling here cannot smuggle a selector past it.
 *
 * <p>One thing this cannot fix: a vanilla client parses commands locally to colour them, does not know
 * these two letters, and will underline them red. It sends the command anyway and the server is what
 * decides, so they work — they just do not look like they will.
 */
@Mixin(EntitySelectorParser.class)
public abstract class EntitySelectorParserMixin {
	@Shadow
	@Final
	private StringReader reader;

	@Shadow
	private boolean usesSelectors;

	@Shadow
	protected abstract void parseOptions() throws CommandSyntaxException;

	@Shadow
	public abstract void setMaxResults(int maxResults);

	@Shadow
	public abstract void setIncludesEntities(boolean includesEntities);

	@Shadow
	public abstract void setOrder(BiConsumer<Vec3, List<? extends Entity>> order);

	@Shadow
	public abstract void limitToType(EntityType<?> type);

	@Shadow
	public abstract void addPredicate(Predicate<Entity> predicate);

	@Inject(method = "parseSelector", at = @At("HEAD"), cancellable = true)
	private void scriptify$operatorSelectors(CallbackInfo callback) throws CommandSyntaxException {
		if (!this.reader.canRead()) {
			return;
		}
		char selector = this.reader.peek();
		if (selector != 'o' && selector != 'd' && selector != 'b') {
			return;
		}
		this.reader.skip();

		// Mirrors vanilla's @a: every player, no entities, no particular order.
		this.usesSelectors = true;
		setMaxResults(Integer.MAX_VALUE);
		setIncludesEntities(false);
		setOrder(EntitySelector.ORDER_ARBITRARY);
		limitToType(EntityType.PLAYER);

		char kind = selector;
		addPredicate(entity -> {
			if (!(entity instanceof ServerPlayer player)) {
				return false;
			}
			boolean fake = FakePlayers.isFake(player);
			if (kind == 'b') {
				return fake;
			}
			// Bots are always non-ops, so @d would otherwise sweep them up alongside real players.
			if (fake) {
				return false;
			}
			MinecraftServer server = player.level().getServer();
			// Null on a client parsing a command for its own highlighting, where there is no op list to
			// consult — match nobody rather than guess.
			return server != null
				&& server.getPlayerList().isOp(player.nameAndId()) == (kind == 'o');
		});

		// Options still apply: @d[distance=..10] is a reasonable thing to write.
		if (this.reader.canRead() && this.reader.peek() == '[') {
			this.reader.skip();
			parseOptions();
		}
		callback.cancel();
	}
}
