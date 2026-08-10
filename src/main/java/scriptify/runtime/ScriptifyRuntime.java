package scriptify.runtime;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import scriptify.api.ScriptifyContext;
import scriptify.config.ConfigManager;
import scriptify.death.DeathControlManager;
import scriptify.feature.FakeMessageService;
import scriptify.feature.FeatureManager;
import scriptify.feature.InventoryBackupService;
import scriptify.feature.RandomFillService;
import scriptify.feature.SudoService;
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
	private static RandomFillService randomFill;
	private static SudoService sudo;
	private static FakeMessageService fakeMessages;
	private static boolean eventsRegistered;

	private ScriptifyRuntime() {
	}

	public static void start(ScriptifyContext context) {
		config = new ConfigManager(FabricLoader.getInstance().getConfigDir().resolve("scriptify.json"), context.logger());
		config.load();
		nickManager = new NickManager(context);
		skinManager = new SkinManager(context, nickManager);
		deathControl = new DeathControlManager(context, config);
		inventory = new InventoryManager();
		kits = new KitManager(context);
		features = new FeatureManager(context);
		backups = new InventoryBackupService(context);
		randomFill = new RandomFillService();
		sudo = new SudoService(context);
		fakeMessages = new FakeMessageService(context);
		kits.load();
		nickManager.load();
		features.load();
		deathControl.load();
	}

	public static void stop() {
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
		config = null;
		nickManager = null;
		skinManager = null;
		deathControl = null;
		inventory = null;
		kits = null;
		features = null;
		backups = null;
		randomFill = null;
		sudo = null;
		fakeMessages = null;
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
				sender.displayClientMessage(Component.literal("Chat is currently muted."), true);
				return false;
			}
			return true;
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (nickManager != null) {
				nickManager.applyVisuals(handler.player);
			}
			if (deathControl != null) {
				// Reconnecting is what people try when they feel invisible, so make it the escape hatch.
				deathControl.onJoin(handler.player);
			}
		});
	}

	private static boolean isOperator(ServerPlayer player) {
		var server = player.level().getServer();
		return server != null && server.getPlayerList().isOp(player.nameAndId());
	}

	public static ConfigManager config() {
		return config;
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

	public static RandomFillService randomFill() {
		return randomFill;
	}

	public static SudoService sudo() {
		return sudo;
	}

	public static FakeMessageService fakeMessages() {
		return fakeMessages;
	}
}
