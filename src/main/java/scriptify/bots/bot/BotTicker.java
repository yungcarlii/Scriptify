package scriptify.bots.bot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.LoadGovernor;
import scriptify.bots.ai.SquadCoordinator;
import scriptify.bots.ai.Vision;

/**
 * The server tick driver.
 *
 * <p>Work is deliberately spread out rather than run for every bot every tick. Each bot
 * carries a fixed phase offset (see {@link BotProfile#phase}) and expensive subsystems only
 * run on the tick matching that offset, so with twenty bots the cost of style evaluation,
 * equipment scans and path recalculation is amortised across twenty ticks instead of
 * landing all at once. Cheap, latency-sensitive work (aiming, attacking, movement) still
 * runs every tick because anything less is visible as stutter.</p>
 */
public class BotTicker {

    private static long serverTick = 0;

    private static final int AUTO_SAVE_INTERVAL = 1200;   // 60s
    private static final int SETTINGS_FLUSH_INTERVAL = 40; // 2s
    private static final int CLEANUP_INTERVAL = 10;
    private static final int BACKEND_SYNC_INTERVAL = 100;

    private static int autoSaveCounter = 0;

    public static long tick() {
        return serverTick;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(BotTicker::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        serverTick++;
        autoSaveCounter++;

        Vision.onServerTick(serverTick);
        // One shared snapshot of where everyone is, so two hundred and fifty bots do not
        // each run their own world query over the same crowd.
        scriptify.bots.ai.BotIndex.rebuild(server, serverTick);
        // How much thinking this machine can afford this tick. Read before anything thinks.
        LoadGovernor.sample(server, serverTick);
        // And how many route searches it will carry. Same reason, different resource: the load
        // governor thins decisions, which are cheap individually; this bounds the one piece of
        // work a single bot can spend a whole tick's budget on.
        scriptify.bots.ai.Pathfinder.beginTick();
        SquadCoordinator.beginTick();
        // Battlefield picture: who is fighting whom, who the group is collapsing on, and
        // what each bot's job is. Rebuilt on a global timer, not per bot.
        scriptify.bots.ai.Engagement.tick(server, serverTick);

        BotManager.tickRestore(server);
        scriptify.bots.npc.NpcRegistry.tickSave(serverTick);

        // Staged behaviour runs from one sweep rather than the per-bot loop below, because a
        // scenario is a group of bots doing one thing, not a bot doing its own thing.
        scriptify.bots.scenario.ScenarioManager.tick(server, serverTick);
        scriptify.bots.scenario.ScenarioTools.tick(server);
        scriptify.bots.tool.BotTools.tick(server);
        scriptify.bots.scenario.ScenarioPrompt.tick(server);

        if (serverTick % CLEANUP_INTERVAL == 0) {
            BotManager.cleanupDeadBots(server);
        }
        if (serverTick % SETTINGS_FLUSH_INTERVAL == 0) {
            BotSettings.flush();
            BotManager.flushBots();
        }
        if (autoSaveCounter >= AUTO_SAVE_INTERVAL) {
            autoSaveCounter = 0;
            BotManager.updateBotData(server);
            BotManager.saveBots();
            BotManager.flushBots();
        }

        BotPathVisualizer.update(server);
        // Wandering bots and anybody recording a walk, both driven from here so they move on the
        // same clock as everything else rather than on a timer of their own.
        scriptify.bots.npc.EditMode.tick(server, serverTick);
        scriptify.bots.npc.EditSession.tick(server);
        scriptify.bots.npc.MovementReplay.tick(server);

        BotSettings settings = BotSettings.get();
        int equipInterval = Math.max(1, settings.getCheckInterval());

        // Walk the online players once and pick out the bots, rather than looking up each
        // roster name in the player list. The lookup is a case-insensitive scan of every
        // online player, so doing it per bot made the cost quadratic in the bot count -
        // sixty bots meant thousands of string comparisons every tick, for nothing.
        int botsSeen = 0;
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            if (!bot.isAlive()) continue;
            String botName = bot.getName().getString();
            if (!BotManager.isBot(botName)) continue;
            botsSeen++;
            BotActions.abortStrayMining(bot);

            BotProfile profile = BotProfile.of(botName);

            // What the bot *is* - hittable, listed, crouching, its repeating action. Applied
            // before anything decides what it should do, so the two never disagree, and applied
            // every tick however loaded the server is: this is appearance, not decision making,
            // and a bot that flickers between hittable and not is worse than a slow one.
            scriptify.bots.npc.NpcApplier.tick(bot, server, serverTick);

            // A bot in a scenario is being driven by that scenario. Running the combat brain
            // over the top would have someone stood in a conversation break off to go and
            // fight, which is the whole thing scenarios exist not to do.
            if (scriptify.bots.npc.MovementReplay.isPlaying(botName)) {
                continue;
            }
            if (scriptify.bots.scenario.ScenarioManager.owns(botName)) {
                scriptify.bots.ai.Crouch.tick(bot, server);
                continue;
            }

            // Mid-air with a water bucket and a long way to fall: nothing matters more this tick, so
            // it is not left to the load governor either. A clutch a few ticks late is a death.
            if (scriptify.bots.ai.WaterClutch.tick(bot, profile, server)) {
                continue;
            }

            boolean techniqueBusy = BotCombat.isTechniqueActive(botName);

            // Can this bot afford a decision this tick? Movement inputs and head angles hold
            // between ticks, so one that sits this out keeps walking and keeps looking where it
            // was aimed; what it loses is a few hundredths of a second of reaction. Under no
            // load this is always true and costs a comparison.
            //
            // Anything already under way is exempt. A bite of a golden apple, a combo, cutting
            // out of a cobweb: those are committed actions running on their own timers, and
            // stretching one is the difference between a bot that reacts slowly and a bot that
            // is visibly broken.
            boolean committed = techniqueBusy || !BotUtils.canAct(bot)
                || scriptify.bots.ai.CobwebTactics.isBusy(botName);
            if (!committed && !LoadGovernor.shouldThink(bot, profile, serverTick)) {
                continue;
            }

            // A fresh hand on the keys every decision: whatever drives the bot this tick presses them
            // again, and anything that wants it still - bridging, eating, a clutch - gets it still.
            scriptify.bots.ai.BotInput.release(bot);

            if (!techniqueBusy) {
                BotUtils.update(bot, server, serverTick);
                scriptify.bots.fixes.EatingMovementFix.apply(bot);
            }

            // Trapped in a hole? Out of it first. Every other piece of movement code assumes
            // the way to somewhere is along the ground, so a bot in a one-block pit walks into
            // the same wall until somebody lets it out.
            if (scriptify.bots.ai.PitEscape.tick(bot, profile, server, serverTick)) {
                continue;
            }

            boolean followingWithoutAttack = BotPath.isFollowing(botName) && !BotPath.shouldAttack(botName);
            if (!followingWithoutAttack) {
                BotCombat.update(bot, server, serverTick);
            }

            // Not while its hands are busy: getting out of a web, fetching the water back, eating.
            // Walking the route there turned the head away from the bucket it was aiming, every tick.
            if (BotPath.isFollowing(botName) && BotUtils.canAct(bot)
                && !scriptify.bots.ai.CobwebTactics.isBusy(botName)) {
                tickPathFollowing(bot, botName, profile);
            }

            // Following a player: catch up whenever there is nothing to fight (or fighting is off).
            if (BotFollow.isFollowing(botName) && BotUtils.canAct(bot)
                && !scriptify.bots.ai.CobwebTactics.isBusy(botName)) {
                var target = BotCombat.getTarget(botName);
                if (!settings.isCombatEnabled() || target == null || !target.isAlive()) {
                    BotFollow.tick(bot, server);
                }
            }

            // Releases the crouch once whatever asked for it stops asking. Cheap: a null map
            // lookup for every bot that is not crouching.
            scriptify.bots.ai.Crouch.tick(bot, server);

            if (profile.onPhase(serverTick, equipInterval)) {
                // Re-read rather than reusing the flag from the top of the loop: combat may
                // have *started* a technique since then, and re-equipping a weapon on the tick
                // a combo begins is exactly the swap that ruins it.
                // canAct rather than the eating flag alone. A bot cutting its way out of a
                // cobweb has deliberately picked up a sword, and auto-equip put a pickaxe back
                // in its hand a tick later - so it stood in the web mining at fist speed with a
                // sword in its bag, which is exactly what it looked like.
                if (BotUtils.canAct(bot) && !BotCombat.isTechniqueActive(botName)) {
                    BotEquipment.autoEquip(bot);
                }
            }
        }

        LoadGovernor.noteBotCount(botsSeen);

        if (serverTick % BACKEND_SYNC_INTERVAL == 0) {
            syncBackendSettings(server, settings);
        }
    }

    /** Walks a bot along its assigned patrol path when it is not busy fighting. */
    /**
     * How close counts as having reached a path point, measured flat.
     *
     * <p>Flat on purpose. This was a plain three-dimensional distance, which a bhopping bot fails
     * for most of its stride: the apex of a jump is over a block above the ground on its own, so a
     * bot sailing directly over a point was still more than 1.5 blocks from it and never registered
     * arriving. It would then turn back for a point it was standing on top of, and circle.</p>
     *
     * <p>A path point is a place on the ground. Whether the bot is mid-hop above it is not the
     * question being asked.</p>
     */
    private static final double POINT_REACH = 1.5;
    private static final double POINT_REACH_SQR = POINT_REACH * POINT_REACH;

    /**
     * Vertical allowance, which still has to exist so a bot does not tick off a point on the floor
     * below or the roof above. Three blocks covers a jump and a step; a storey is more than that.
     */
    private static final double POINT_VERTICAL_REACH = 3.0;

    private static boolean reachedPoint(Vec3 botPos, Vec3 point) {
        double dx = botPos.x - point.x;
        double dz = botPos.z - point.z;
        if (dx * dx + dz * dz > POINT_REACH_SQR) {
            return false;
        }
        return Math.abs(botPos.y - point.y) <= POINT_VERTICAL_REACH;
    }

    private static void tickPathFollowing(ServerPlayer bot, String botName, BotProfile profile) {
        boolean shouldAttack = BotPath.shouldAttack(botName);
        var target = BotCombat.getTarget(botName);
        boolean hasTarget = target != null && target.isAlive();

        if (shouldAttack && hasTarget) {
            if (!BotPath.isInCombat(botName)) {
                BotPath.startCombat(botName, BotPath.getNextPoint(botName));
            }
            return;
        }

        if (BotPath.isInCombat(botName)) {
            BotPath.endCombat(botName);
        }

        Vec3 botPos = new Vec3(bot.getX(), bot.getY(), bot.getZ());
        Vec3 resume = BotPath.getPausedPoint(botName);
        if (resume != null) {
            if (reachedPoint(botPos, resume)) {
                BotPath.clearPausedPoint(botName);
            } else {
                faceAlongPath(bot, profile, botPos, resume);
                BotNavigation.moveTowardPosition(bot, resume, 1.0);
            }
            return;
        }

        Vec3 next = BotPath.getNextPoint(botName);
        if (next == null) return;
        if (reachedPoint(botPos, next)) {
            BotPath.advanceToNextPoint(botName);
        } else {
            faceAlongPath(bot, profile, botPos, next);
            BotNavigation.moveTowardPosition(bot, next, 1.0);
        }
    }

    /**
     * Look where you are going, not at the thing you are going to.
     *
     * <p>Path following used to aim straight at the waypoint. A waypoint is a point on the
     * ground, so the closer the bot got the further down it looked - it arrived staring at its
     * own feet, then snapped its head round to the next point and set off again. Both halves of
     * that read as robotic, and neither is what walking looks like.</p>
     *
     * <p>Aiming at a spot along the direction of travel, at eye height and a fixed distance
     * out, keeps the gaze level and turns the corner gradually: the aim point swings smoothly
     * as the bearing changes, and the aim controller is rate limited on top of that.</p>
     */
    static void faceAlongPath(ServerPlayer bot, BotProfile profile,
                                      Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double flat = Math.hypot(dx, dz);
        if (flat < 0.05) return;

        Vec3 eye = bot.getEyePosition();
        Vec3 ahead = new Vec3(
            eye.x + (dx / flat) * 10.0,
            // Follow the climb or descent of the route, but gently - a slope is not a reason
            // to stare at the floor.
            eye.y + Math.max(-2.0, Math.min(2.0, (to.y - from.y) * 0.35)),
            eye.z + (dz / flat) * 10.0);
        AimController.lookAtPos(bot, ahead, profile, false);
    }

    /**
     * Keeps the fake-player backend's own settings in step with ours. The original ran this
     * every second and rebuilt a whole custom command source each time; once every five
     * seconds through the cached silent source is plenty.
     */
    private static void syncBackendSettings(MinecraftServer server, BotSettings settings) {
        BotActions.execute(server, "herobot botLeaveOnDeath " + settings.isBotLeaveOnDeath());
    }
}
