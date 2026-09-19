package scriptify.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import scriptify.compat.CompatDomain;
import scriptify.compat.CompatRegistry;
import scriptify.permission.RankRegistry;

/**
 * Adds the vanilla command level a player's rank grants, on top of whatever they already had.
 *
 * <p>This is the only place a rank can affect which vanilla commands exist for a player. Minecraft
 * filters the command tree by four coarse buckets — moderator, gamemaster, admin, owner — before the
 * client is ever sent it, so raising the bucket is what makes {@code /gamemode} appear at all.
 * Narrowing it back down to specific commands happens at execution instead, in
 * {@link CommandGateMixin}.
 *
 * <p>A rank can only ever add. An operator keeps everything op gives them whatever their rank says,
 * which means a misconfigured rank can never lock an admin out.
 *
 * <p><b>Do not use {@code PermissionSet.union} here.</b> It does not do what its name says for these
 * sets. {@code LevelBasedPermissionSet} overrides it as
 * {@code this.level().isEqualOrHigherThan(other.level()) ? other : this} - which returns the
 * <em>lower</em> of the two levels. Both a player's own permissions and a rank's grant are level
 * based, so unioning them handed back whatever the player already had and discarded the grant
 * entirely. Every rank that granted a command level appeared to do nothing at all, for every vanilla
 * command, and it failed silently because the call looked exactly like the right one. The two levels
 * are compared here instead and the higher wins.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerPermissionsMixin {
	@Inject(method = "permissions", at = @At("RETURN"), cancellable = true)
	private void scriptify$addRankLevel(CallbackInfoReturnable<PermissionSet> callback) {
		// Yielded when a real permission system such as LuckPerms is installed and configured to win:
		// two sources deciding one answer is worse than either of them deciding it alone.
		if (!CompatRegistry.owns(CompatDomain.PERMISSIONS)) {
			return;
		}
		ServerPlayer player = (ServerPlayer) (Object) this;
		// Prebuilt per rank. This runs once per command node when a tree is sent, so building the set
		// here would allocate hundreds of times for a single join.
		PermissionSet granted = RankRegistry.of(player.getUUID()).permissions();
		if (granted == null) {
			return;
		}
		PermissionSet current = callback.getReturnValue();
		if (current == null) {
			callback.setReturnValue(granted);
			return;
		}
		if (current instanceof LevelBasedPermissionSet mine
			&& granted instanceof LevelBasedPermissionSet theirs) {
			callback.setReturnValue(
				mine.level().isEqualOrHigherThan(theirs.level()) ? mine : theirs);
			return;
		}
		// Anything not level based gets a straight "either one allows it", which is what a union of
		// permissions is supposed to mean.
		PermissionSet held = current;
		callback.setReturnValue(
			permission -> held.hasPermission(permission) || granted.hasPermission(permission));
	}
}
