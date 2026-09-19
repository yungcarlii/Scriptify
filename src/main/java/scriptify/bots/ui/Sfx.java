package scriptify.bots.ui;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Click feedback.
 *
 * <p>Every button press makes a sound and every sound means something: a soft click for
 * navigation, a rising note for something enabled, a falling one for disabled, a low thud for
 * a refusal. Played to the operator alone, never into the world.</p>
 */
public final class Sfx {

    private Sfx() {}

    public static void click(ServerPlayer player) {
        play(player, 0.45F, 1.0F);
    }

    public static void success(ServerPlayer player) {
        note(player, 0.6F, 1.6F);
    }

    public static void error(ServerPlayer player) {
        bass(player, 0.6F, 0.7F);
    }

    public static void toggle(ServerPlayer player, boolean enabled) {
        note(player, 0.6F, enabled ? 1.8F : 0.9F);
    }

    public static void spawn(ServerPlayer player) {
        note(player, 0.7F, 1.2F);
    }

    private static void play(ServerPlayer player, float volume, float pitch) {
        send(player, SoundEvents.UI_BUTTON_CLICK, volume, pitch);
    }

    private static void note(ServerPlayer player, float volume, float pitch) {
        send(player, SoundEvents.NOTE_BLOCK_PLING, volume, pitch);
    }

    private static void bass(ServerPlayer player, float volume, float pitch) {
        send(player, SoundEvents.NOTE_BLOCK_BASS, volume, pitch);
    }

    /**
     * Sent as a packet positioned on the player rather than played into the world, so the
     * cue belongs to the operator's client and nobody nearby hears their menu clicks.
     */
    private static void send(ServerPlayer player, Holder<SoundEvent> sound,
                             float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
            sound, SoundSource.MASTER,
            player.getX(), player.getY(), player.getZ(),
            volume, pitch, player.level().getRandom().nextLong()));
    }
}
