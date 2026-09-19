package scriptify.mixin;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets Scriptify put entries into a player-list packet that do not belong to a real player.
 *
 * <p>The public constructors only take {@code ServerPlayer}s, which is the right design for vanilla
 * and no use at all for a tab list full of people who are not connected. The entry record itself is
 * public; only the field holding them is not.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoPacketAccessor {
	@Mutable
	@Accessor("entries")
	void scriptify$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
