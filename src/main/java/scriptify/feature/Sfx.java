package scriptify.feature;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Named audio/visual cues for feature actions.
 *
 * <p>Two audiences, deliberately handled differently. Cues that only confirm an operator's click are
 * sent to that operator alone so they never leak into a take; cues that are part of the scene (a heal,
 * a vanish puff) play in the world for everyone nearby.
 */
public final class Sfx {
	private Sfx() {
	}

	// ---- private confirmations (operator only) ----------------------------

	public static void click(ServerPlayer player) {
		toPlayer(player, SoundEvents.UI_BUTTON_CLICK.value(), 0.5F, 1.0F);
	}

	public static void success(ServerPlayer player) {
		toPlayer(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.7F, 1.6F);
	}

	public static void error(ServerPlayer player) {
		toPlayer(player, SoundEvents.NOTE_BLOCK_BASS.value(), 0.7F, 0.7F);
	}

	public static void toggleOn(ServerPlayer player) {
		toPlayer(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.7F, 1.8F);
	}

	public static void toggleOff(ServerPlayer player) {
		toPlayer(player, SoundEvents.NOTE_BLOCK_PLING.value(), 0.7F, 0.8F);
	}

	public static void toggle(ServerPlayer player, boolean enabled) {
		if (enabled) {
			toggleOn(player);
		} else {
			toggleOff(player);
		}
	}

	public static void menuOpen(ServerPlayer player) {
		toPlayer(player, SoundEvents.CHEST_OPEN, 0.4F, 1.4F);
	}

	// ---- world cues (audible to everyone nearby) --------------------------

	public static void heal(ServerPlayer player) {
		inWorld(player, SoundEvents.PLAYER_LEVELUP, 0.6F, 1.6F);
		particles(player, ParticleTypes.HEART, 8, 0.4D);
	}

	public static void feed(ServerPlayer player) {
		inWorld(player, SoundEvents.PLAYER_BURP, 0.6F, 1.0F);
	}

	public static void totem(ServerPlayer player) {
		inWorld(player, SoundEvents.TOTEM_USE, 0.5F, 1.2F);
	}

	public static void immortalOn(ServerPlayer player) {
		inWorld(player, SoundEvents.BEACON_ACTIVATE, 0.6F, 1.4F);
		particles(player, ParticleTypes.END_ROD, 20, 0.6D);
	}

	public static void immortalOff(ServerPlayer player) {
		inWorld(player, SoundEvents.BEACON_DEACTIVATE, 0.6F, 1.0F);
	}

	public static void vanishOut(ServerPlayer player) {
		inWorld(player, SoundEvents.ENDERMAN_TELEPORT, 0.6F, 1.4F);
		particles(player, ParticleTypes.PORTAL, 30, 0.5D);
	}

	public static void vanishIn(ServerPlayer player) {
		inWorld(player, SoundEvents.ENDERMAN_TELEPORT, 0.6F, 0.9F);
		particles(player, ParticleTypes.PORTAL, 30, 0.5D);
	}

	public static void invisOn(ServerPlayer player) {
		inWorld(player, SoundEvents.BOTTLE_EMPTY, 0.6F, 1.5F);
		particles(player, ParticleTypes.WITCH, 12, 0.4D);
	}

	public static void invisOff(ServerPlayer player) {
		inWorld(player, SoundEvents.BOTTLE_FILL, 0.6F, 1.2F);
	}

	public static void freeze(ServerPlayer player) {
		inWorld(player, SoundEvents.GLASS_BREAK, 0.6F, 1.6F);
		particles(player, ParticleTypes.SNOWFLAKE, 20, 0.5D);
	}

	public static void unfreeze(ServerPlayer player) {
		inWorld(player, SoundEvents.AMETHYST_BLOCK_CHIME, 0.6F, 1.2F);
	}

	public static void teleport(ServerPlayer player) {
		inWorld(player, SoundEvents.ENDERMAN_TELEPORT, 0.5F, 1.0F);
	}

	/**
	 * The kit has finished arriving.
	 *
	 * <p>Two notes rather than one: the deal-out already clicks up a scale, so a single tone at the end
	 * sounds like another step instead of the finish.
	 */
	public static void kitComplete(ServerPlayer player) {
		toPlayer(player, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.7F, 1.6F);
		toPlayer(player, SoundEvents.PLAYER_LEVELUP, 0.4F, 1.8F);
	}

	public static void kitEquip(ServerPlayer player) {
		inWorld(player, SoundEvents.ARMOR_EQUIP_DIAMOND.value(), 0.7F, 1.0F);
	}

	/**
	 * One item landing during a kit deal.
	 *
	 * @param progress how far through the kit this item is, 0 to 1 — the pitch rises with it, which is
	 *                 what turns a stream of identical clicks into something that sounds like it is
	 *                 building to a finish.
	 */
	public static void kitItem(ServerPlayer player, float progress) {
		float pitch = 1.0F + Math.min(1.0F, Math.max(0.0F, progress)) * 0.8F;
		toPlayer(player, SoundEvents.NOTE_BLOCK_HAT.value(), 0.5F, pitch);
	}

	/** An armour piece snapping on — heavier than an item click, so the end of a kit lands. */
	public static void kitArmorPiece(ServerPlayer player) {
		inWorld(player, SoundEvents.ARMOR_EQUIP_IRON.value(), 0.6F, 1.1F);
	}

	public static void repair(ServerPlayer player) {
		inWorld(player, SoundEvents.ANVIL_USE, 0.5F, 1.4F);
	}

	public static void restock(ServerPlayer player) {
		inWorld(player, SoundEvents.ITEM_PICKUP, 0.6F, 1.2F);
	}

	public static void potion(ServerPlayer player) {
		inWorld(player, SoundEvents.BREWING_STAND_BREW, 0.6F, 1.2F);
		particles(player, ParticleTypes.WITCH, 16, 0.5D);
	}

	public static void death(ServerPlayer player) {
		inWorld(player, SoundEvents.PLAYER_HURT, 0.8F, 1.0F);
	}

	/**
	 * The real death sound.
	 *
	 * <p>Staged deaths cancel the death itself, which also cancels the sound vanilla would have played —
	 * so it has to be triggered by hand or the kill lands silently.
	 */
	public static void playerDeath(ServerPlayer player) {
		inWorld(player, SoundEvents.PLAYER_DEATH, 1.0F, 1.0F);
	}

	public static void backup(ServerPlayer player) {
		toPlayer(player, SoundEvents.PLAYER_LEVELUP, 0.4F, 2.0F);
	}

	// ---- plumbing ---------------------------------------------------------

	/** Sent only down this player's connection, so nobody else hears it. */
	private static void toPlayer(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		try {
			player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
				net.minecraft.core.Holder.direct(sound),
				SoundSource.MASTER,
				player.getX(), player.getY(), player.getZ(),
				volume, pitch,
				player.level().getRandom().nextLong()));
		} catch (RuntimeException ignored) {
			// A cue is never worth failing an action over.
		}
	}

	private static void inWorld(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		try {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				sound, SoundSource.PLAYERS, volume, pitch);
		} catch (RuntimeException ignored) {
		}
	}

	private static void particles(ServerPlayer player, ParticleOptions particle, int count, double spread) {
		try {
			if (player.level() instanceof ServerLevel level) {
				level.sendParticles(particle, player.getX(), player.getY() + 1.0D, player.getZ(),
					count, spread, 0.6D, spread, 0.0D);
			}
		} catch (RuntimeException ignored) {
		}
	}
}
