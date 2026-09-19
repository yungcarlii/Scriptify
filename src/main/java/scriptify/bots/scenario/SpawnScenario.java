package scriptify.bots.scenario;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.Rng;
import scriptify.bots.bot.BotActions;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotNameGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A server spawn that never empties.
 *
 * <p>People arrive, drift off in whatever direction, and after a few minutes they are gone -
 * fell somewhere, starved, drowned, got killed. It is the texture of a populated server, which
 * is almost entirely made of strangers you never interact with.</p>
 *
 * <p>Deaths use real damage sources rather than a silent despawn, because the death message in
 * chat is most of the effect. "Quartzwin fell from a high place" is what makes a world feel
 * busy; a bot vanishing does nothing.</p>
 */
public final class SpawnScenario {

    private SpawnScenario() {}

    /**
     * Ticks a newcomer stands about before setting off.
     *
     * <p>Short. Loading in, glancing around, and leaving is what joining a server looks like;
     * standing on the platform for ten seconds is not.</p>
     */
    private static final int SETTLE_MIN = 15;
    private static final int SETTLE_MAX = 60;
    /**
     * Ticks a bot is allowed to take to actually appear before it is written off.
     *
     * <p>Spawning is not synchronous - the command returns and the fake player joins a second
     * or three later. Without this the scenario looks for its new arrival on the very next
     * tick, does not find it, strikes it off, and spawns another. The population never fills,
     * and the world quietly accumulates orphaned bots nobody is driving.</p>
     */
    private static final int JOIN_GRACE_TICKS = 200;

    private static final String KEY_SPAWN = "spawnCooldown";

    public static void tick(MinecraftServer server, Level world,
                            ScenarioInstance instance, long serverTick) {
        Vec3 anchor = instance.anchor();
        if (anchor == null) return;

        var settings = instance.settings;

        // --- keep the place populated ----------------------------------------------------
        if (instance.tickTimer(KEY_SPAWN)) {
            instance.setTimer(KEY_SPAWN, Math.max(20, settings.spawnIntervalSeconds * 20));
            if (countAlive(server, instance) < settings.population) {
                arrive(server, world, instance, anchor);
            }
        }

        // --- run everyone who is here ----------------------------------------------------
        List<String> gone = new ArrayList<>();
        for (String name : instance.bots) {
            ServerPlayer bot = server.getPlayerList().getPlayer(name);
            if (bot == null || !bot.isAlive()) {
                // Still on its way in? Leave it alone.
                if (!instance.tickTimer(joinKey(name))) continue;
                gone.add(name);
                continue;
            }
            instance.setTimer(joinKey(name), 0);
            live(server, world, instance, bot, name);
        }
        for (String name : gone) {
            instance.bots.remove(name);
            instance.roamers.remove(scriptify.bots.utils.Names.key(name));
            instance.timers.remove(lifeKey(name));
            instance.timers.remove(settleKey(name));
            instance.timers.remove(joinKey(name));
            ScenarioManager.release(name);
        }
    }

    /** Bots present, plus those still arriving - otherwise the population overshoots. */
    private static int countAlive(MinecraftServer server, ScenarioInstance instance) {
        int alive = 0;
        for (String name : instance.bots) {
            ServerPlayer bot = server.getPlayerList().getPlayer(name);
            if (bot != null && bot.isAlive()) alive++;
            else if (instance.timer(joinKey(name)) > 0) alive++;
        }
        return alive;
    }

    private static void arrive(MinecraftServer server, Level world,
                               ScenarioInstance instance, Vec3 anchor) {
        String name = BotNameGenerator.generateUniqueName();
        Random rng = new Random();

        // Arrive somewhere around the point, not on it. A spawn is a place with an area, and
        // people appear across it - two players materialising inside each other and shoving
        // apart is the one thing a real spawn never looks like. The radius follows the
        // scenario's own roam radius, so a bigger staged spawn scatters people further.
        double area = Math.max(3.0, Math.min(instance.settings.radius * 0.35, 16.0));
        Vec3 at = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            // Square root keeps the distribution even across the disc instead of clumping
            // everyone toward the middle.
            double distance = Math.sqrt(rng.nextDouble()) * area;
            double x = anchor.x + Math.cos(angle) * distance;
            double z = anchor.z + Math.sin(angle) * distance;
            var ground = Roamer.groundAt(world, x, anchor.y, z);
            if (ground != null) {
                at = new Vec3(x, ground.getY() + 1, z);
                break;
            }
        }
        if (at == null) at = anchor;

        if (!BotManager.spawnBot(server, name, BotActions.source(server), at, false)) return;

        ScenarioManager.assign(instance, name);
        var settings = instance.settings;
        int min = Math.max(20, settings.lifetimeMinSeconds);
        int max = Math.max(min + 1, settings.lifetimeMaxSeconds);
        instance.setTimer(lifeKey(name), Rng.range(rng, min * 20, max * 20));
        instance.setTimer(settleKey(name), Rng.range(rng, SETTLE_MIN, SETTLE_MAX));
        instance.setTimer(joinKey(name), JOIN_GRACE_TICKS);
    }

    private static void live(MinecraftServer server, Level world, ScenarioInstance instance,
                             ServerPlayer bot, String name) {
        BotProfile profile = BotProfile.of(name);
        var settings = instance.settings;

        // --- their time comes ------------------------------------------------------------
        if (instance.tickTimer(lifeKey(name))) {
            depart(server, world, instance, bot, profile);
            return;
        }

        // --- freshly arrived: stand about for a moment first ------------------------------
        if (!instance.tickTimer(settleKey(name))) {
            Roamer.halt(bot);
            Roamer.idleLook(bot, profile, instance.roamer(name));
            return;
        }

        // --- off they go ------------------------------------------------------------------
        // One bearing, held for the whole trip. The previous version picked somewhere a few
        // blocks away, walked there, stopped, looked around and picked again - which reads as
        // someone lost, not someone leaving. A player who has just joined has somewhere to be.
        Roamer.State roam = instance.roamer(name);
        if (roam.destination == null) {
            Vec3 away = pickDestination(bot, profile, instance.anchor(), settings.travelDistance);
            if (away == null) {
                Roamer.halt(bot);
                Roamer.idleLook(bot, profile, roam);
                return;
            }
            roam.setGoal(null);
            roam.destination = away;
        }

        // Full pace, and bunny hopping - travel speed is part of looking like a player.
        Roamer.Step step = Roamer.travel(bot, profile, roam, Math.max(settings.speed, 1.0));
        if (step == Roamer.Step.ARRIVED || step == Roamer.Step.GIVE_UP) {
            roam.destination = null;
        }
    }

    /**
     * Somewhere far away, in a direction the bot can actually set off in.
     *
     * <p>The exact spot does not matter and is almost never reached - the bot dies on the way,
     * which is the point. What matters is that it is far enough that the walk is a walk rather
     * than a lap of the spawn platform, and that the first steps are onto real ground.</p>
     */
    private static Vec3 pickDestination(ServerPlayer bot, BotProfile profile,
                                         Vec3 anchor, double distance) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = profile.rng().nextDouble() * Math.PI * 2;
            // Some head straight out, some not quite as far - a spawn scatters people.
            double range = distance * (0.6 + profile.rng().nextDouble() * 0.6);
            Vec3 far = new Vec3(
                anchor.x + Math.cos(angle) * range,
                anchor.y,
                anchor.z + Math.sin(angle) * range);

            // Only the first stretch needs to be walkable; the rest loads as they get there.
            Vec3 probe = new Vec3(
                bot.getX() + Math.cos(angle) * 12,
                bot.getY(),
                bot.getZ() + Math.sin(angle) * 12);
            if (Roamer.groundAt(bot.level(), probe.x, probe.y, probe.z) != null) {
                return far;
            }
        }
        return null;
    }

    /**
     * Ends a bot the way the settings say it should end.
     *
     * <p>Damage is applied rather than the bot being removed, so the death behaves like a real
     * one: the message, the drops, the roster cleanup that already handles a dead bot.</p>
     */
    private static void depart(MinecraftServer server, Level world, ScenarioInstance instance,
                               ServerPlayer bot, BotProfile profile) {
        if (!(world instanceof ServerLevel serverWorld)) return;

        DamageSource source = pickCause(server, serverWorld, instance, bot, profile);
        bot.setHealth(1.0f);
        bot.hurtServer(serverWorld, source, 1000.0f);
    }

    private static DamageSource pickCause(MinecraftServer server, ServerLevel world,
                                          ScenarioInstance instance, ServerPlayer bot,
                                          BotProfile profile) {
        var settings = instance.settings;
        var sources = world.damageSources();

        int total = settings.deathWeightTotal();
        if (total <= 0) return sources.generic();

        int roll = profile.rng().nextInt(total);
        if ((roll -= settings.deathFall) < 0) return sources.fall();
        if ((roll -= settings.deathStarve) < 0) return sources.starve();
        if ((roll -= settings.deathWither) < 0) return sources.wither();
        if ((roll -= settings.deathPlayer) < 0) {
            ServerPlayer killer = randomOther(server, instance, bot, profile);
            return killer != null ? sources.playerAttack(killer) : sources.generic();
        }
        if ((roll -= settings.deathDrown) < 0) return sources.drown();
        return sources.onFire();
    }

    /** Another bot from the same scenario, to be blamed for the kill. */
    private static ServerPlayer randomOther(MinecraftServer server, ScenarioInstance instance,
                                                  ServerPlayer bot, BotProfile profile) {
        List<ServerPlayer> others = new ArrayList<>();
        for (String name : instance.bots) {
            ServerPlayer other = server.getPlayerList().getPlayer(name);
            if (other != null && other != bot && other.isAlive()) others.add(other);
        }
        if (others.isEmpty()) return null;
        return others.get(profile.rng().nextInt(others.size()));
    }

    private static String lifeKey(String name) {
        return "life:" + scriptify.bots.utils.Names.key(name);
    }

    private static String joinKey(String name) {
        return "join:" + scriptify.bots.utils.Names.key(name);
    }

    private static String settleKey(String name) {
        return "settle:" + scriptify.bots.utils.Names.key(name);
    }
}
