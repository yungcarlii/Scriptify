package scriptify.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scriptify.compat.CompatDomain;
import scriptify.compat.CompatRegistry;
import scriptify.nick.NickRegistry;

/**
 * The above-head nametag on a vanilla client is rendered from the profile name carried by the
 * player-info {@code ADD_PLAYER} entry, not from any display-name field, so the only way to rename it
 * without a client mod is to rewrite the profile as it is written to the wire.
 *
 * <p>The {@code ADD_PLAYER} writer reads the profile through this accessor, so overriding it renames
 * the tag while leaving the server's own {@code GameProfile} — and therefore command targeting,
 * whitelist and ban lookups, and the Mojang skin signature — completely untouched.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.Entry.class)
public abstract class PlayerInfoEntryMixin {
	@Inject(method = "profile", at = @At("RETURN"), cancellable = true)
	private void scriptify$spoofProfileName(CallbackInfoReturnable<GameProfile> callback) {
		if (!CompatRegistry.owns(CompatDomain.NICKNAMES)) {
			return;
		}
		GameProfile original = callback.getReturnValue();
		if (original == null) {
			return;
		}
		String nickname = NickRegistry.get(original.id());
		if (nickname != null) {
			// Keep the id and every property (notably the signed "textures" skin blob), swap only the name.
			callback.setReturnValue(new GameProfile(original.id(), nickname, original.properties()));
		}
	}
}
