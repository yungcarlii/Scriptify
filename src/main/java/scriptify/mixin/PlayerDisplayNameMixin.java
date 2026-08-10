package scriptify.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scriptify.nick.NickRegistry;

/**
 * Routes the nickname into everything that renders a player's display name: death messages,
 * {@code /say}, {@code /msg}, advancement broadcasts and team formatting.
 *
 * <p>{@code getName()} is deliberately left untouched so command targeting and selectors such as
 * {@code @a[name=...]} keep resolving against the real account name, as specified.
 *
 * <p>The vanilla hover/insertion decoration is dropped for nicked players on purpose — it embeds the
 * real profile name and would leak the identity the nickname is meant to hide.
 */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
	@Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
	private void scriptify$nickDisplayName(CallbackInfoReturnable<Component> callback) {
		if (!((Object) this instanceof ServerPlayer player)) {
			return;
		}
		String nickname = NickRegistry.get(player.getUUID());
		if (nickname != null) {
			callback.setReturnValue(PlayerTeam.formatNameForTeam(player.getTeam(), Component.literal(nickname)));
		}
	}
}
