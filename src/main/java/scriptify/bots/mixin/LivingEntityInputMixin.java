package scriptify.bots.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import scriptify.access.BotInputBridge;

/**
 * Re-applies a bot's held keys where the game actually reads them.
 *
 * <p>HeroBot writes {@code xxa}/{@code zza} from its own action pack at the head of
 * {@code ServerPlayer.tick}, which runs after the server tick event and before physics - so anything
 * the AI wrote was gone by the time {@code travel} looked. {@code aiStep} is where vanilla consumes
 * movement and the jump key, so setting them here is exactly equivalent to a player holding them:
 * swimming, treading water and climbing out onto a bank all come from the game's own rules rather
 * than from velocity pushes that only approximate them.
 *
 * <p>Costs one boolean test for every living entity that is not being driven.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityInputMixin implements BotInputBridge {

	@Shadow
	public float xxa;

	@Shadow
	public float zza;

	@Shadow
	protected boolean jumping;

	@Unique
	private boolean scriptify$held;

	@Unique
	private float scriptify$forward;

	@Unique
	private float scriptify$strafe;

	@Unique
	private boolean scriptify$jump;

	@Unique
	private boolean scriptify$sprint;

	@Unique
	private long scriptify$until;

	@Inject(method = "aiStep", at = @At("HEAD"))
	private void scriptify$applyHeldInput(CallbackInfo ci) {
		if (!this.scriptify$held) {
			return;
		}
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().getGameTime() > this.scriptify$until) {
			// The decision that set these keys has lapsed without being renewed; let go rather than
			// leave the bot walking on a stale order.
			this.scriptify$releaseInput();
			return;
		}
		// Vanilla slows a player who is eating or drinking to a fifth of their input; the client does
		// that for a real player, so it has to be done here for a bot.
		float scale = self.isUsingItem() ? 0.2F : 1.0F;
		this.zza = this.scriptify$forward * scale;
		this.xxa = this.scriptify$strafe * scale;
		this.jumping = this.scriptify$jump;
		if (self.isSprinting() != this.scriptify$sprint) {
			self.setSprinting(this.scriptify$sprint);
		}
	}

	@Override
	public void scriptify$holdInput(float forward, float strafe, boolean jump, boolean sprint,
			long untilGameTime) {
		this.scriptify$forward = forward;
		this.scriptify$strafe = strafe;
		this.scriptify$jump = jump;
		this.scriptify$sprint = sprint;
		this.scriptify$until = untilGameTime;
		this.scriptify$held = true;
	}

	@Override
	public void scriptify$releaseInput() {
		if (!this.scriptify$held) {
			return;
		}
		this.scriptify$held = false;
		this.jumping = false;
		this.zza = 0.0F;
		this.xxa = 0.0F;
	}

	@Override
	public boolean scriptify$isHoldingInput() {
		return this.scriptify$held;
	}
}
