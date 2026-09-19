package scriptify.bots.npc;

import com.google.common.collect.LinkedHashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import scriptify.bots.utils.Names;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The skin a bot is wearing.
 *
 * <p>Bots join through the fake-player backend with an offline UUID, which by definition has no
 * Mojang profile behind it and therefore no {@code textures} property at all. Left alone every
 * bot in an arena is the same default skin, which is the single clearest tell that a crowd is
 * not people.</p>
 *
 * <p>A texture is a signed blob Mojang issues per account. Applying one is purely local once the
 * blob is in hand - it is copied onto the bot's {@link GameProfile} and the clients are told to
 * rebuild their view of that player. <b>Nothing here touches the network.</b> Obtaining blobs is
 * somebody else's job: either the caller already holds one, or it comes from {@link IdentityPool},
 * which is seeded from disk. That division is deliberate, because the alternative - resolving a
 * skin while spawning - is exactly the mistake {@link scriptify.bots.fixes.ProfileSeeder} exists to
 * document, where a synchronous Mojang call inside the spawn path put the server sixteen seconds
 * behind.</p>
 *
 * <p>With no internet, nothing here changes: the pool is whatever is on disk, and an empty pool
 * means bots keep their default skins. Nothing blocks and nothing throws.</p>
 */
public final class BotIdentity {

    /** Mojang's property key for the skin blob. Not a name we choose. */
    private static final String TEXTURES = "textures";

    /** What each bot is wearing, so a relogging client and a respawned bot both get it back. */
    private static final Map<String, Skin> WORN = new HashMap<>();

    private BotIdentity() {}

    /**
     * A signed skin blob.
     *
     * <p>The signature is not optional decoration. A texture applied without one renders as the
     * default skin on a vanilla client, so an unsigned blob is worse than no blob - it looks like
     * the feature silently failed.</p>
     */
    public record Skin(String value, String signature) {}

    /** What this bot is wearing, or {@code null} if nothing has been applied. */
    public static Skin worn(String botName) {
        return WORN.get(Names.key(botName));
    }

    /**
     * Puts a signed skin on a bot and makes every client rebuild it.
     *
     * @return false when the blob is unusable or the bot is not on the server right now
     */
    public static boolean apply(ServerPlayer bot, MinecraftServer server, Skin skin) {
        if (bot == null || server == null || skin == null) return false;
        if (skin.value() == null || skin.value().isBlank()) return false;
        if (skin.signature() == null || skin.signature().isBlank()) return false;

        try {
            // A copy, not an edit. The profile arrives carrying an ImmutableListMultimap, so writing to
            // it throws from inside Guava - see PlayerProfileAccessor for the full account.
            GameProfile current = bot.getGameProfile();
            LinkedHashMultimap<String, Property> properties =
                LinkedHashMultimap.create(current.properties());
            properties.removeAll(TEXTURES);
            properties.put(TEXTURES, new Property(TEXTURES, skin.value(), skin.signature()));
            GameProfile updated =
                new GameProfile(current.id(), current.name(), new PropertyMap(properties));
            ((scriptify.mixin.PlayerAccessor) bot).scriptify$setGameProfile(updated);

            WORN.put(Names.key(bot.getName().getString()), skin);
            refresh(bot, server);
            return true;
        } catch (RuntimeException | LinkageError e) {
            // Logged, not swallowed. A silent catch here is the exact shape of the bug documented in
            // ProfileSeeder: the failure was invisible, so the feature looked like it worked.
            scriptify.bots.BotRuntime.LOGGER.warn("Could not apply skin to {}",
                bot.getName().getString(), e);
            return false;
        }
    }

    /** Reapplies whatever this bot was last given. Used when a bot rejoins under the same name. */
    public static boolean reapply(ServerPlayer bot, MinecraftServer server) {
        if (bot == null) return false;
        return apply(bot, server, worn(bot.getName().getString()));
    }

    /**
     * Rebuilds the bot for every client, so a changed skin is actually seen.
     *
     * <p>A client snapshots the profile - name and skin together - when it first spawns a player
     * entity, so changing the profile server-side does nothing on its own. Two things have to
     * happen: the player-list entry is replaced, because that is where the texture blob travels;
     * and the entity is re-tracked, because that is what makes clients build a fresh one.</p>
     *
     * <p>The re-track goes through {@code ServerChunkManager}, not through hand-written add and
     * remove packets. The tracker keeps a record of who has been sent what, and packets that go
     * around it leave that record claiming clients hold a state they do not - which strands the
     * bot invisible until something unrelated happens to re-track it.</p>
     */
    public static void refresh(ServerPlayer bot, MinecraftServer server) {
        if (bot == null || server == null) return;

        List<ServerPlayer> viewers = server.getPlayerList().getPlayers();
        ClientboundPlayerInfoRemovePacket remove = new ClientboundPlayerInfoRemovePacket(List.of(bot.getUUID()));
        ClientboundPlayerInfoUpdatePacket add = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot));
        for (ServerPlayer viewer : viewers) {
            viewer.connection.send(remove);
            viewer.connection.send(add);
        }

        if (bot.level() instanceof ServerLevel world) {
            world.getChunkSource().removeEntity(bot);
            world.getChunkSource().addEntity(bot);
        }
    }

    /** Drops a bot's remembered skin. Called when the bot leaves the roster for good. */
    public static void forget(String botName) {
        WORN.remove(Names.key(botName));
    }

    public static void clear() {
        WORN.clear();
    }
}
