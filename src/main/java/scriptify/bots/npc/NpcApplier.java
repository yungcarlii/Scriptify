package scriptify.bots.npc;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.Crouch;
import scriptify.bots.ai.Rng;
import scriptify.bots.bot.BotActions;
import scriptify.bots.bot.BotManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a description into a bot.
 *
 * <p>Most of these are entity flags that only need setting when they change, so the applier
 * remembers what it last wrote and does nothing on the ticks where nothing moved. At two
 * hundred and fifty bots the difference between "set six flags per bot per tick" and "set them
 * when they change" is the difference between free and not.</p>
 */
public final class NpcApplier {

    private NpcApplier() {}

    /** Scoreboard team used purely to switch collision off. */
    private static final String NO_COLLIDE_TEAM = "scriptify_nocollide";
    /** The name the team had before the merge; cleaned up at startup so it does not linger. */
    private static final String LEGACY_NO_COLLIDE_TEAM = "sbots_nocollide";

    /**
     * Ticks between swings for the SWING action, jittered within this band.
     *
     * <p>A range rather than a fixed number because a perfectly even click rate is the most
     * obviously automated thing a player model can do. Nine to fifteen ticks is roughly the spread
     * of a person clicking as fast as they comfortably can.</p>
     */
    private static final int SWING_MIN_TICKS = 9;
    private static final int SWING_MAX_TICKS = 15;
    /** A jump takes a couple of ticks to leave the ground; asking sooner does nothing. */
    private static final int JUMP_TICKS = 2;
    /** Use is a hold, so it only needs renewing about once a second. */
    private static final int USE_TICKS = 20;
    /** Crouch is a hold too, renewed well before it lapses so the pose never flickers. */
    private static final int CROUCH_HOLD_TICKS = 4;

    /** Last applied state per bot, so unchanged settings cost nothing. */
    private static final Map<String, NpcSettings> APPLIED = new HashMap<>();
    private static final Map<String, Integer> ACTION_COOLDOWN = new HashMap<>();
    /**
     * Bots currently hidden from the tab list.
     *
     * <p>Kept because hiding is a packet, not a state a joining client can discover. Vanilla sends
     * a new arrival the whole player list, bots included, and the per-bot cache above would say the
     * setting was already applied and never send the removal again - so a hidden bot was visible to
     * everyone who joined after it was hidden, permanently. See {@link #hideUnlistedFrom}.</p>
     */
    private static final java.util.Set<String> UNLISTED = new java.util.HashSet<>();

    /** Held for the team cleanup in {@link #forget}, which has no server of its own. */
    private static MinecraftServer boundServer;

    /**
     * Binds the server and drops any collision-team membership left over from last session.
     *
     * <p>The team is derived state - the applier puts bots back into it on their first tick - so
     * starting empty is both correct and the only thing that stops the team growing without limit
     * in the world save as bots come and go.</p>
     */
    public static void init(MinecraftServer server) {
        boundServer = server;
        UNLISTED.clear();
        clearTeam(server, NO_COLLIDE_TEAM);
        // Worlds that ran the mod before the merge still carry the old team.
        PlayerTeam legacy = server.getScoreboard().getPlayerTeam(LEGACY_NO_COLLIDE_TEAM);
        if (legacy != null) {
            server.getScoreboard().removePlayerTeam(legacy);
        }
    }

    private static void clearTeam(MinecraftServer server, String teamName) {
        PlayerTeam team = server.getScoreboard().getPlayerTeam(teamName);
        if (team == null) {
            return;
        }
        for (String member : new java.util.ArrayList<>(team.getPlayers())) {
            server.getScoreboard().removePlayerFromTeam(member, team);
        }
    }

    /**
     * Re-hides every unlisted bot from one client.
     *
     * <p>Called when somebody joins, because they are sent the full player list on the way in and
     * nothing else will ever tell them about bots that were hidden before they arrived.</p>
     */
    public static void hideUnlistedFrom(ServerPlayer viewer) {
        if (UNLISTED.isEmpty() || boundServer == null) {
            return;
        }
        List<java.util.UUID> hidden = new java.util.ArrayList<>();
        for (ServerPlayer player : boundServer.getPlayerList().getPlayers()) {
            if (player != viewer && UNLISTED.contains(scriptify.bots.utils.Names.key(
                    player.getName().getString()))) {
                hidden.add(player.getUUID());
            }
        }
        if (!hidden.isEmpty()) {
            viewer.connection.send(new ClientboundPlayerInfoRemovePacket(hidden));
        }
    }

    public static void tick(ServerPlayer bot, MinecraftServer server, long serverTick) {
        String name = bot.getName().getString();
        String key = scriptify.bots.utils.Names.key(name);
        NpcSettings want = NpcRegistry.resolve(name);
        NpcSettings had = APPLIED.get(key);

        applyFlags(bot, server, want, had);
        APPLIED.put(key, want);

        // Crouch is a hold rather than a flag: something has to keep asking.
        if (want.crouching()) {
            Crouch.hold(bot, server, CROUCH_HOLD_TICKS);
        }

        runAction(bot, server, want, key);
    }

    private static void applyFlags(ServerPlayer bot, MinecraftServer server,
                                   NpcSettings want, NpcSettings had) {
        // Hittable and immortal both come down to invulnerability; immortal wins.
        boolean invulnerable = !want.hittable() || want.immortal();
        if (had == null || invulnerable != (!had.hittable() || had.immortal())) {
            bot.setInvulnerable(invulnerable);
        }

        if (had == null || want.collidable() != had.collidable()) {
            setCollidable(bot, server, want.collidable());
        }

        if (had == null || want.listed() != had.listed()) {
            String key = scriptify.bots.utils.Names.key(bot.getName().getString());
            if (want.listed()) {
                UNLISTED.remove(key);
            } else {
                UNLISTED.add(key);
            }
            setListed(bot, server, want.listed());
        }
    }

    /**
     * Collision is a team property in vanilla, not an entity one.
     *
     * <p>So a single team exists for the purpose, and bots that should not push people are put
     * in it. Nothing else about the team is used - no colour, no name tag rule - because those
     * would be visible side effects of a setting that is meant to be invisible.</p>
     */
    private static void setCollidable(ServerPlayer bot, MinecraftServer server,
                                      boolean collidable) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(NO_COLLIDE_TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(NO_COLLIDE_TEAM);
            team.setCollisionRule(Team.CollisionRule.NEVER);
            team.setNameTagVisibility(Team.Visibility.ALWAYS);
        }

        String name = bot.getName().getString();
        if (collidable) {
            if (team.getPlayers().contains(name)) {
                scoreboard.removePlayerFromTeam(name, team);
            }
        } else {
            scoreboard.addPlayerToTeam(name, team);
        }
    }

    /**
     * Tab list membership, by telling every client to forget the entry.
     *
     * <p>The player stays fully real on the server - it is only the list entry that goes. Which
     * is what "hide from tab" has to mean for a fake player: it is still there, still fighting,
     * just not cluttering a list of two hundred and fifty names.</p>
     */
    private static void setListed(ServerPlayer bot, MinecraftServer server, boolean listed) {
        if (listed) {
            var packet = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot));
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                viewer.connection.send(packet);
            }
            return;
        }
        var packet = new ClientboundPlayerInfoRemovePacket(List.of(bot.getUUID()));
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer == bot) continue;
            viewer.connection.send(packet);
        }
    }

    /** The repeating action from the clock slot. */
    private static void runAction(ServerPlayer bot, MinecraftServer server,
                                  NpcSettings want, String key) {
        NpcSettings.Action action = want.action();
        if (action == NpcSettings.Action.NONE) {
            ACTION_COOLDOWN.remove(key);
            return;
        }

        int remaining = ACTION_COOLDOWN.getOrDefault(key, 0);
        if (remaining > 0) {
            ACTION_COOLDOWN.put(key, remaining - 1);
            return;
        }

        BotProfile profile = BotProfile.of(bot.getName().getString());
        switch (action) {
            case SWING -> {
                // The animation, and nothing else. This was a real left click, so an NPC set to
                // swing quietly mined whatever it was facing - a decoration wearing a hole in
                // the wall behind it.
                BotActions.swing(bot, server);
                ACTION_COOLDOWN.put(key, Rng.range(profile.rng(), SWING_MIN_TICKS, SWING_MAX_TICKS));
            }
            case JUMP -> {
                if (bot.onGround()) bot.jumpFromGround();
                ACTION_COOLDOWN.put(key, JUMP_TICKS);
            }
            case USE -> {
                BotActions.useContinuous(bot, server);
                ACTION_COOLDOWN.put(key, USE_TICKS);
            }
            default -> { }
        }
    }

    /**
     * Copies the effects the editor is under onto the bot.
     *
     * <p>An action, not a setting. It was briefly a persisted flag that nothing ever read, which is
     * worse than not having it: the menu offered a switch that did nothing. What it is useful for is
     * a one-off - give this bot what I have, now - and that is all it does.</p>
     *
     * @return how many effects were copied
     */
    public static int copyEffects(ServerPlayer from, ServerPlayer to) {
        int copied = 0;
        for (net.minecraft.world.effect.MobEffectInstance effect : from.getActiveEffects()) {
            to.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect));
            copied++;
        }
        return copied;
    }

    /**
     * Whether this bot should leave its gear behind.
     *
     * <p>Consulted from the death hook. Returning false there means clearing the inventory
     * before vanilla gets to drop it, which is the only reliable way to suppress drops without
     * a mixin into the drop logic itself.</p>
     */
    public static boolean dropsOnDeath(String botName) {
        return !BotManager.isBot(botName) || NpcRegistry.resolve(botName).dropsOnDeath();
    }

    public static boolean immortal(String botName) {
        return BotManager.isBot(botName) && NpcRegistry.resolve(botName).immortal();
    }

    public static void forget(String botName) {
        String key = scriptify.bots.utils.Names.key(botName);
        APPLIED.remove(key);
        ACTION_COOLDOWN.remove(key);
        UNLISTED.remove(key);
        MovementReplay.forget(botName);
        EditMode.stopWander(botName);
        // Team membership is stored in the world save by name, so a bot that leaves without being
        // taken out of the team stays in it for good - across restarts, accumulating one dead entry
        // per bot ever spawned.
        if (boundServer != null) {
            PlayerTeam team = boundServer.getScoreboard().getPlayerTeam(NO_COLLIDE_TEAM);
            if (team != null && team.getPlayers().contains(botName)) {
                boundServer.getScoreboard().removePlayerFromTeam(botName, team);
            }
        }
    }

    public static void clear() {
        APPLIED.clear();
        ACTION_COOLDOWN.clear();
        UNLISTED.clear();
        boundServer = null;
    }
}
