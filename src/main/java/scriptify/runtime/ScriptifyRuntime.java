package scriptify.runtime;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;
import scriptify.compat.CompatService;
import scriptify.core.ScriptifyBootstrap;
import scriptify.config.ConfigManager;
import scriptify.function.Scriptify;
import scriptify.death.DeathControlManager;
import scriptify.feature.FakeMessageService;
import scriptify.feature.FakeTabService;
import scriptify.feature.FeatureManager;
import scriptify.feature.InventoryBackupService;
import scriptify.feature.PotionPresetService;
import scriptify.feature.ProximityService;
import scriptify.feature.fill.FillService;
import scriptify.feature.SudoService;
import scriptify.feature.TeamService;
import scriptify.inventory.InventoryManager;
import scriptify.kits.KitManager;
import scriptify.nick.NickManager;
import scriptify.skin.SkinManager;

public final class ScriptifyRuntime {
	private static ConfigManager config;
	private static NickManager nickManager;
	private static SkinManager skinManager;
	private static DeathControlManager deathControl;
	private static InventoryManager inventory;
	private static KitManager kits;
	private static FeatureManager features;
	private static InventoryBackupService backups;
	private static scriptify.rollback.RollbackService rollback;
	private static FillService fill;
	private static SudoService sudo;
	private static FakeMessageService fakeMessages;
	private static TeamService teams;
	private static PotionPresetService potions;
	private static ProximityService proximity;
	private static FakeTabService fakeTab;
	private static CompatService compat;
	private static boolean eventsRegistered;

	private ScriptifyRuntime() {
	}

	public static void start(ScriptifyContext context) {
		config();
		nickManager = new NickManager(context);
		skinManager = new SkinManager(context, nickManager);
		deathControl = new DeathControlManager(context, config);
		inventory = new InventoryManager();
		kits = new KitManager(context);
		features = new FeatureManager(context);
		backups = new InventoryBackupService(context);
		rollback = new scriptify.rollback.RollbackService(context);
		fill = new FillService(context);
		fill.load();
		sudo = new SudoService(context);
		fakeMessages = new FakeMessageService(context);
		teams = new TeamService(context.server());
		potions = new PotionPresetService(context);
		potions.load();
		proximity = new ProximityService(context);
		proximity.load();
		kits.load();
		nickManager.load();
		features.load();
		deathControl.load();

		fakeTab = new FakeTabService(context);
		var tabSettings = config.get().fakeTab;
		if (tabSettings.enabled) {
			fakeTab.setCount(tabSettings.count > 0
				? tabSettings.count
				: FakeTabService.Size.parse(tabSettings.mode).count());
		}
	}

	public static void stop() {
		// Static, so in singleplayer it would otherwise carry into the next world opened.
		scriptify.feature.Countdown.stop();
		scriptify.feature.Announcement.stop();
		// The rank store defers writes to the next tick, and shutdown is exactly when there is no next
		// tick. Flushing here is what stops a rank edit made seconds before /stop being lost.
		var permissions = context() == null ? null : context().permissions();
		if (permissions != null && permissions.ranks() != null) {
			permissions.ranks().flush();
		}
		if (kits != null) {
			kits.save();
		}
		if (nickManager != null) {
			nickManager.save();
		}
		if (features != null) {
			features.save();
		}
		if (deathControl != null) {
			deathControl.save();
		}
		// The proximity filter lives in a static for the mixins to read, so it has to be switched
		// off by hand — otherwise a second world in the same JVM would start with the last one's rule.
		scriptify.nick.RefreshWatchdog.clear();
		ProximityService.reset();
		config = null;
		nickManager = null;
		skinManager = null;
		deathControl = null;
		inventory = null;
		kits = null;
		features = null;
		backups = null;
		rollback = null;
		fill = null;
		sudo = null;
		fakeMessages = null;
		teams = null;
		potions = null;
		proximity = null;
		fakeTab = null;
	}

	public static void registerEvents() {
		if (eventsRegistered) {
			return;
		}
		eventsRegistered = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (features != null) {
				features.tick(server);
			}
			if (kits != null) {
				kits.tick(server);
			}
			if (fakeTab != null) {
				fakeTab.tick();
			}
			// Converges the entity tracker after an identity change, so nobody is left invisible.
			scriptify.nick.RefreshWatchdog.tick(server);
			var currentContext = context();
			if (currentContext != null) {
				currentContext.events().tick(server);
			}
			scriptify.feature.Countdown.tick(server);
			scriptify.feature.Announcement.tick(server);
			// Collapses a burst of rank edits into one file write.
			var permissions = currentContext == null ? null : currentContext.permissions();
			if (permissions != null && permissions.ranks() != null) {
				permissions.ranks().flush();
			}
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (features != null && entity instanceof ServerPlayer player && !features.allowDamage(player, source)) {
				return false;
			}
			return true;
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (!(entity instanceof ServerPlayer player)) {
				return true;
			}
			// Snapshot before anything else touches the inventory. This runs for staged deaths too — the
			// gear is only recoverable while it is still on the player, and a fraction of a second later
			// it may have been cleared, dropped or swapped for a scene.
			if (rollback != null) {
				try {
					// At this point the inventory still holds everything that is about to hit the
					// floor, so the inventory section is the death drops. Capturing after the drop
					// would find an empty player, which is what the old backup system did.
					rollback.capture(player, scriptify.rollback.Snapshot.REASON_DEATH, "");
				} catch (RuntimeException exception) {
					context().logger().warn("Could not snapshot {} on death.", player.getGameProfile().name(), exception);
				}
			}
			// Immortality wins over staged deaths: an immortal player is pinned at half a heart and stays
			// in the scene rather than being moved to spectator.
			if (features != null && features.surviveLethalDamage(player, source)) {
				return false;
			}
			if (deathControl != null) {
				return deathControl.allowDeath(player, source, amount);
			}
			return true;
		});
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			// The only chat rule left is the global mute toggle; operators still get through so they can
			// coordinate while everyone else is silenced for a take.
			if (features != null && features.isChatBlocked() && !isOperator(sender)) {
				sender.sendSystemMessage(Component.literal("Chat is currently muted."), true);
				return false;
			}
			return true;
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (kits != null) {
				// A half-dealt kit would otherwise sit queued against a player who is no longer here.
				kits.cancelAnimation(handler.player);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (nickManager != null) {
				nickManager.applyVisuals(handler.player);
			}
			if (deathControl != null) {
				// Reconnecting is what people try when they feel invisible, so make it the escape hatch.
				deathControl.onJoin(handler.player);
			}
			if (fakeTab != null) {
				fakeTab.sendTo(handler.player);
			}
			// Everyone gets a rank on arrival. Unassigned players behaved as default already; writing it
			// down is what makes them visible in the rank's member list.
			var currentContext = context();
			var permissions = currentContext == null ? null : currentContext.permissions();
			if (permissions != null && permissions.ranks() != null) {
				permissions.ranks().rememberName(handler.player);
				permissions.ranks().ensureAssigned(handler.player);
			}
		});
	}

	private static ScriptifyContext context() {
		return ScriptifyBootstrap.context();
	}

	private static boolean isOperator(ServerPlayer player) {
		var server = player.level().getServer();
		return server != null && server.getPlayerList().isOp(player.nameAndId());
	}

	/**
	 * The config, loading it on first use.
	 *
	 * <p>Lazy because commands register before the server is started, and the conflict settings have to
	 * be readable at that point to decide whether to register a contested command name at all.
	 */
	public static ConfigManager config() {
		if (config == null) {
			config = new ConfigManager(
				FabricLoader.getInstance().getConfigDir().resolve("scriptify.json"), Scriptify.LOGGER);
			config.load();
		}
		return config;
	}

	public static CompatService compat() {
		return compat;
	}

	public static void setCompat(CompatService service) {
		compat = service;
	}

	public static ProximityService proximity() {
		return proximity;
	}

	public static NickManager nick() {
		return nickManager;
	}

	public static SkinManager skin() {
		return skinManager;
	}

	public static DeathControlManager deathControl() {
		return deathControl;
	}

	public static InventoryManager inventory() {
		return inventory;
	}

	public static KitManager kits() {
		return kits;
	}

	public static FeatureManager features() {
		return features;
	}

	public static InventoryBackupService backups() {
		return backups;
	}

	/** Snapshots and rollback. The successor to {@link #backups()}; see {@code /inventoryrollback}. */
	public static scriptify.rollback.RollbackService rollback() {
		return rollback;
	}

	public static FillService fill() {
		return fill;
	}

	public static SudoService sudo() {
		return sudo;
	}

	public static FakeMessageService fakeMessages() {
		return fakeMessages;
	}

	public static TeamService teams() {
		return teams;
	}

	public static FakeTabService fakeTab() {
		return fakeTab;
	}

	public static PotionPresetService potions() {
		return potions;
	}
}
