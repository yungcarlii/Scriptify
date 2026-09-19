package scriptify.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Makes the player's {@code GameProfile} field replaceable.
 *
 * <p>Mutating {@code getGameProfile().properties()} in place is not reliable: {@code PropertyMap} is a
 * {@code ForwardingMultimap}, and the delegate a profile arrives with can be immutable — the write then
 * silently does nothing, so the skin never changes. Swapping the whole profile for a freshly built one
 * is what SkinRestorer does, and it is the only approach that holds up across server platforms.
 */
@Mixin(Player.class)
public interface PlayerAccessor {
	@Accessor("gameProfile")
	@Mutable
	void scriptify$setGameProfile(GameProfile profile);
}
