package scriptify.bots;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import scriptify.bots.ai.AimController;
import scriptify.bots.ai.BotProfile;
import scriptify.bots.ai.SquadCoordinator;
import scriptify.bots.ai.TargetSelector;
import scriptify.bots.ai.Vision;
import scriptify.bots.bot.BotActions;
import scriptify.bots.bot.BotKits;
import scriptify.bots.bot.BotManager;
import scriptify.bots.bot.BotPath;
import scriptify.bots.bot.BotSettings;
import scriptify.bots.bot.BotTicker;
import scriptify.bots.command.BotCommand;
import scriptify.bots.config.WorldConfigHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot half of Scriptify: roster, combat AI, scenarios and paths.
 *
 * <p>Everything runs on the logical server, so vanilla clients can connect and use the
 * whole feature set (including the GUI) without installing anything. In singleplayer the
 * integrated server is the same code path, so the mod behaves identically there.</p>
 *
 * <p>Bot bodies themselves come from HeroBot; SBots supplies the brain.</p>
 */
public final class BotRuntime {

    public static final Logger LOGGER = LoggerFactory.getLogger("scriptify-bots");

    private BotRuntime() {}

    /**
     * Started by {@link scriptify.core.ScriptifyBootstrap}, not by the loader.
     *
     * <p>Commands are deliberately not registered here. They go through the dispatcher the bootstrap
     * hands out, which is watched for name collisions, and a callback registered here would run
     * before that watcher exists - so Scriptify's own {@code /bots} arrived in the tree unannounced
     * and the watcher then reported it as another mod's command, on every single start.</p>
     */
    public static void initialize() {
        registerServerEvents();
        registerNpcRules();
        BotTicker.register();
        scriptify.bots.scenario.ScenarioTools.register();
        LOGGER.info("Bot subsystem initialised.");
    }

    /** The bot commands. Called by the bootstrap with the watched dispatcher. */
    public static void registerCommands(
            com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        BotCommand.register(dispatcher);
    }

    /**
     * Folds the NPC commands into {@code /bots} rather than leaving them under {@code /bots npc}.
     *
     * <p>They were a separate tree because they came from a separate mod, which is not a reason a
     * player should have to know about.</p>
     *
     * <p>The two trees overlap - both have spawn, list, remove, clear, reload and show - and merging
     * a duplicate would leave which one runs decided by registration order. So the names already
     * present win and the NPC copies are dropped. Working it out from the live tree rather than from
     * a hand-written list means neither side can drift out of step with it.</p>
     *
     * <p>Runs last, after every other part of {@code /bots} has registered, because what it drops is
     * decided by what is already on the node.</p>
     */
    public static void foldNpcCommands(
            com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        var botsNode = dispatcher.getRoot().getChild("bots");
        var folded = net.minecraft.commands.Commands.literal("bots");
        int added = 0;
        for (var child : scriptify.bots.command.NpcCommand.node("npc").build().getChildren()) {
            if (botsNode == null || botsNode.getChild(child.getName()) == null) {
                folded.then(child);
                added++;
            }
        }
        if (added > 0) {
            dispatcher.register(folded);
        }
    }

    /**
     * The two NPC settings that are decided at the moment a bot dies.
     *
     * <p>Both have to be hooks rather than per-tick flags: immortality is a veto on the death
     * itself, and suppressing drops means emptying the inventory in the window between the
     * death being final and vanilla scattering the contents.</p>
     */
    private static void registerNpcRules() {
        // Friendly fire off: a bot's stray blows - the sweep of an axe through a packed line, a
        // spear stab that carries on into the man beside the target - do not hurt its own side.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof net.minecraft.server.level.ServerPlayer victim)
                || !(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer attacker)
                || victim == attacker) {
                return true;
            }
            var settings = scriptify.bots.bot.BotSettings.get();
            if (settings.isFriendlyFireEnabled() || !settings.isFactionsEnabled()) return true;
            String attackerName = attacker.getName().getString();
            if (!scriptify.bots.bot.BotManager.isBot(attackerName)) return true;
            return !scriptify.bots.bot.BotFaction.areAllies(attackerName, victim.getName().getString());
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) {
                return true;
            }
            String name = player.getName().getString();
            if (!scriptify.bots.npc.NpcApplier.immortal(name)) return true;
            // Immortal: refuse the death and put it back on its feet.
            player.setHealth(player.getMaxHealth());
            player.clearFire();
            return false;
        });

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) {
                return true;
            }
            if (scriptify.bots.npc.NpcApplier.dropsOnDeath(player.getName().getString())) return true;
            player.getInventory().clearContent();
            return true;
        });
    }

    private static void registerServerEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BotActions.bind(server);
            WorldConfigHelper.init(server);
            BotSettings.load();
            BotKits.init(server);
            BotPath.init();
            BotManager.init(server);
            scriptify.bots.npc.NpcRegistry.init(server);
            scriptify.bots.npc.NpcApplier.init(server);
            scriptify.bots.npc.IdentityPool.init(server);
            scriptify.bots.scenario.ScenarioManager.init(server);
            scriptify.bots.bot.BotScenes.init();
            // Last, so a consumer that reacts to the roster on bind sees a fully built one.
            scriptify.bots.api.SBotsApi.bind(server);
        });

        // Edit mode: a punch on a bot opens its panel instead of hurting it. Cancelling the attack
        // matters - most bots worth editing are the hittable ones.
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register(
            (player, world, hand, entity, hitResult) -> {
                if (player instanceof net.minecraft.server.level.ServerPlayer editor
                    && scriptify.bots.npc.EditMode.interceptAttack(editor, entity)) {
                    return net.minecraft.world.InteractionResult.FAIL;
                }
                return net.minecraft.world.InteractionResult.PASS;
            });

        // The aimed tools: poke a bot and the thing happens to that bot.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
            (player, world, hand, entity, hitResult) -> {
                if (player instanceof net.minecraft.server.level.ServerPlayer holder
                    && scriptify.bots.tool.BotTools.handleUse(holder, entity,
                        player.getItemInHand(hand))) {
                    return net.minecraft.world.InteractionResult.SUCCESS;
                }
                return net.minecraft.world.InteractionResult.PASS;
            });

        // Drawing a path and recording a walk both borrow the hotbar, so their items answer to a
        // right click rather than to a menu.
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof net.minecraft.server.level.ServerPlayer editor
                && scriptify.bots.npc.EditSession.handleUse(editor, player.getItemInHand(hand))) {
                return net.minecraft.world.InteractionResult.FAIL;
            }
            return net.minecraft.world.InteractionResult.PASS;
        });

        // A block in hand places itself before its use ever fires, so looking at the ground while
        // clicking the recording control buried it in the floor instead of starting the recording.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(
            (player, world, hand, hitResult) -> {
                if (player instanceof net.minecraft.server.level.ServerPlayer editor
                    && scriptify.bots.npc.EditSession.holdingSessionItem(editor)) {
                    scriptify.bots.npc.EditSession.handleUse(editor, player.getItemInHand(hand));
                    return net.minecraft.world.InteractionResult.FAIL;
                }
                return net.minecraft.world.InteractionResult.PASS;
            });

        // A player who logs out mid-session would otherwise come back holding session items.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
            (handler, server) -> {
                scriptify.bots.npc.EditSession.end(handler.player, true);
                scriptify.bots.npc.EditMode.forgetPlayer(handler.player);
                scriptify.bots.tool.BotTools.forgetPlayer(handler.player);
                scriptify.bots.scenario.ScenarioPrompt.forget(handler.player);
            });

        // A joining client is sent the whole player list, bots included, so anything hidden from
        // the tab list has to be hidden again for them specifically.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
            (handler, sender, server) -> scriptify.bots.npc.NpcApplier.hideUnlistedFrom(handler.player));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            scriptify.bots.command.BotCommand.cancelPendingTeleports();
            // How far along its path each bot had got. Written here rather than on every point
            // reached, which would be a disk write per bot per few seconds for a number that only
            // matters once - when the server comes back up.
            BotPath.save();
            BotManager.reset(server);

            // Per-bot AI state is process-local; drop it so a world reload starts clean and
            // nothing survives into the next integrated-server session.
            BotProfile.clear();
            AimController.clear();
            TargetSelector.clear();
            SquadCoordinator.clear();
            scriptify.bots.ai.Engagement.clear();
            scriptify.bots.ai.Maneuver.clear();
            Vision.clearCache();
            // The shared position index, which everything else here already had a line for. It holds
            // live ServerPlayer references in static lists, so leaving it populated pins the whole of
            // the world that is being unloaded - the player, its level, and the chunks behind it -
            // for as long as the JVM lives. On a dedicated server that is one leak at shutdown; in
            // singleplayer it is one per world you open.
            scriptify.bots.ai.BotIndex.clear();
            scriptify.bots.utils.Names.clear();
            scriptify.bots.npc.NpcRegistry.shutdown();
            scriptify.bots.npc.NpcApplier.clear();
            scriptify.bots.scenario.ScenarioManager.shutdown();
            scriptify.bots.scenario.Gestures.clear();
            scriptify.bots.scenario.ScenarioTools.clear();
            scriptify.bots.ai.Crouch.clear();
            scriptify.bots.npc.EditMode.clear();
            scriptify.bots.npc.EditSession.clear();
            scriptify.bots.npc.MovementReplay.clear();
            scriptify.bots.tool.BotTools.clear();
            scriptify.bots.scenario.ScenarioPrompt.clearAll();
            scriptify.bots.npc.IdentityPool.shutdown();
            scriptify.bots.npc.BotIdentity.clear();
            scriptify.bots.api.SBotsApi.unbind();
            BotActions.unbind();
        });
    }
}
