package scriptify.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import scriptify.api.ScriptifyContext;
import scriptify.nick.PlayerRefresh;

/**
 * Central state and behaviour for the per-player scene toggles and one-shot actions.
 *
 * <p>Everything that has to survive a restart (freeze, invisibility, immortality, vanish) is persisted;
 * the one-shot actions (heal, feed, top, restock) simply act and return.
 */
public final class FeatureManager {
	private static final String MODULE = "features";
	private static final String FILE = "state.json";
	private static final int EFFECT_FOREVER = -1;
	/** Duration sentinel meaning "never runs out". */
	public static final int FOREVER = -1;
	/** Timers tick down, and protected armour repairs, once a second. */
	private static final int SECOND_IN_TICKS = 20;
	private static final EquipmentSlot[] ARMOR_SLOTS = {
		EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
	};

	private final ScriptifyContext context;
	private final Random random = new Random();
	private final Map<UUID, FreezeAnchor> frozen = new HashMap<>();
	private final Set<UUID> invisible = new HashSet<>();
	/** Seconds of immortality left per player; {@link #FOREVER} for no limit. */
	private final Map<UUID, Integer> immortals = new HashMap<>();
	/** Opt-in fall-damage immunity, independent of immortality. */
	private final Set<UUID> fallImmune = new HashSet<>();
	/** Opt-in armour self-repair. */
	private final Set<UUID> armorProtected = new HashSet<>();
	private final Set<UUID> vanished = new HashSet<>();
	/** Global chat mute — the "blockchatmute" feature. */
	private boolean chatBlocked;
	/**
	 * Global immortality defaults to off. The reference mod ships it on, which would make every player on
	 * a fresh install unkillable — surprising for a toolkit where this is one feature among many.
	 */
	private boolean globalImmortal;
	private int globalSeconds = FOREVER;
	/** When false the void kills even immortal players, so nobody gets stranded under the world. */
	private boolean allowVoidImmortality;
	private int tickCounter;

	public FeatureManager(ScriptifyContext context) {
		this.context = context;
	}

	// ---------------------------------------------------------------- persistence

	public void load() {
		JsonObject root = this.context.storage().loadObject(MODULE, FILE, this::defaults);
		readSet(root, "invisible", this.invisible);
		readSet(root, "vanished", this.vanished);
		readSet(root, "fallImmune", this.fallImmune);
		readSet(root, "armorProtected", this.armorProtected);
		// Remaining seconds persist, so a restart mid-countdown resumes rather than resetting.
		this.immortals.clear();
		if (root.has("immortals") && root.get("immortals").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("immortals").entrySet()) {
				try {
					this.immortals.put(UUID.fromString(entry.getKey()), entry.getValue().getAsInt());
				} catch (IllegalArgumentException | IllegalStateException ignored) {
					// Skip malformed entries rather than refusing to load the file.
				}
			}
		}
		// Frozen players are restored without an anchor; it is re-established the first time they tick,
		// which pins them wherever they log back in rather than at a stale coordinate.
		Set<UUID> frozenIds = new HashSet<>();
		readSet(root, "frozen", frozenIds);
		this.frozen.clear();
		frozenIds.forEach(uuid -> this.frozen.put(uuid, null));
		this.chatBlocked = root.has("chatBlocked") && root.get("chatBlocked").getAsBoolean();
		this.globalImmortal = root.has("globalImmortal") && root.get("globalImmortal").getAsBoolean();
		this.globalSeconds = root.has("globalSeconds") ? root.get("globalSeconds").getAsInt() : FOREVER;
		this.allowVoidImmortality = root.has("allowVoidImmortality")
			&& root.get("allowVoidImmortality").getAsBoolean();
	}

	public void save() {
		JsonObject root = defaults();
		writeSet(root, "invisible", this.invisible);
		writeSet(root, "vanished", this.vanished);
		writeSet(root, "frozen", this.frozen.keySet());
		writeSet(root, "fallImmune", this.fallImmune);
		writeSet(root, "armorProtected", this.armorProtected);
		JsonObject immortalObject = new JsonObject();
		this.immortals.forEach((uuid, seconds) -> immortalObject.addProperty(uuid.toString(), seconds));
		root.add("immortals", immortalObject);
		root.addProperty("chatBlocked", this.chatBlocked);
		root.addProperty("globalImmortal", this.globalImmortal);
		root.addProperty("globalSeconds", this.globalSeconds);
		root.addProperty("allowVoidImmortality", this.allowVoidImmortality);
		this.context.storage().saveObject(MODULE, FILE, root);
	}

	// ---------------------------------------------------------------- freeze

	public void setFrozen(ServerPlayer player, boolean enabled) {
		if (enabled) {
			this.frozen.put(player.getUUID(), new FreezeAnchor(player.getX(), player.getY(), player.getZ()));
			Sfx.freeze(player);
		} else {
			this.frozen.remove(player.getUUID());
			Sfx.unfreeze(player);
		}
		save();
	}

	public boolean isFrozen(ServerPlayer player) {
		return this.frozen.containsKey(player.getUUID());
	}

	// ---------------------------------------------------------------- invisibility

	/**
	 * "Bottle" invisibility: the potion effect alone still leaves worn armour rendering, which gives the
	 * player away instantly. Clearing the equipment slots for observers completes the effect.
	 */
	public void setInvisible(ServerPlayer player, boolean enabled) {
		if (enabled) {
			this.invisible.add(player.getUUID());
			player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_FOREVER, 0, false, false, false));
			Sfx.invisOn(player);
		} else {
			this.invisible.remove(player.getUUID());
			player.removeEffect(MobEffects.INVISIBILITY);
			Sfx.invisOff(player);
		}
		player.setInvisible(enabled);
		PlayerRefresh.resendEquipment(player, enabled);
		save();
	}

	public boolean isInvisible(ServerPlayer player) {
		return this.invisible.contains(player.getUUID());
	}

	// ---------------------------------------------------------------- immortality

	/**
	 * Grants immortality to one player.
	 *
	 * @param seconds how long it lasts, or {@link #FOREVER} for no limit.
	 */
	public void setImmortal(ServerPlayer player, boolean enabled, int seconds) {
		if (enabled) {
			this.immortals.put(player.getUUID(), seconds);
			Sfx.immortalOn(player);
		} else {
			this.immortals.remove(player.getUUID());
			Sfx.immortalOff(player);
		}
		save();
	}

	public void setImmortal(ServerPlayer player, boolean enabled) {
		setImmortal(player, enabled, FOREVER);
	}

	/** Global immortality, covering everyone regardless of their individual flag. */
	public void setGlobalImmortal(boolean enabled, int seconds) {
		this.globalImmortal = enabled;
		this.globalSeconds = enabled ? seconds : FOREVER;
		save();
	}

	public boolean isGlobalImmortal() {
		return this.globalImmortal;
	}

	public int globalSecondsRemaining() {
		return this.globalSeconds;
	}

	public boolean isImmortal(ServerPlayer player) {
		return this.globalImmortal || this.immortals.containsKey(player.getUUID());
	}

	/** Seconds left, {@link #FOREVER} when unlimited, or {@code null} when not individually immortal. */
	public Integer immortalSecondsRemaining(ServerPlayer player) {
		return this.immortals.get(player.getUUID());
	}

	/** Per-player fall-damage immunity. Independent of immortality — either can be used alone. */
	public void setFallImmune(ServerPlayer player, boolean enabled) {
		if (enabled) {
			this.fallImmune.add(player.getUUID());
		} else {
			this.fallImmune.remove(player.getUUID());
		}
		save();
	}

	public boolean isFallImmune(ServerPlayer player) {
		return this.fallImmune.contains(player.getUUID());
	}

	/** Per-player armour protection: worn gear slowly repairs itself while this is on. */
	public void setArmorProtected(ServerPlayer player, boolean enabled) {
		if (enabled) {
			this.armorProtected.add(player.getUUID());
		} else {
			this.armorProtected.remove(player.getUUID());
		}
		save();
	}

	public boolean isArmorProtected(ServerPlayer player) {
		return this.armorProtected.contains(player.getUUID());
	}

	public void setAllowVoidImmortality(boolean enabled) {
		this.allowVoidImmortality = enabled;
		save();
	}

	public boolean allowVoidImmortality() {
		return this.allowVoidImmortality;
	}

	/**
	 * Damage filter.
	 *
	 * <p>Ordinary damage is deliberately allowed through even for immortal players — the hit still
	 * registers, knocks back and plays its sound, which is what a scripted fight needs to look right.
	 * Death is absorbed separately in {@link #surviveLethalDamage}. Fall-damage immunity is its own
	 * opt-in and applies whether or not the player is immortal.
	 */
	public boolean allowDamage(ServerPlayer player, DamageSource source) {
		return !(source.is(DamageTypeTags.IS_FALL) && this.fallImmune.contains(player.getUUID()));
	}

	/**
	 * Called when damage would be fatal; immortal players are pinned at half a heart instead of dying.
	 *
	 * <p>Two things outrank immortality, both matching the reference mod. A held totem always wins, so it
	 * pops and is consumed exactly as a player expects rather than being silently swallowed. And the void
	 * kills regardless unless {@code allowVoidImmortality} is on — otherwise falling out of the world
	 * leaves the player stuck below bedrock taking damage forever with no way out.
	 *
	 * @return {@code true} when the death was absorbed.
	 */
	public boolean surviveLethalDamage(ServerPlayer player, DamageSource source) {
		if (!isImmortal(player)) {
			return false;
		}
		if (holdsDeathProtection(player)) {
			return false;
		}
		if (isInVoid(player) && !this.allowVoidImmortality) {
			return false;
		}
		player.setHealth(1.0F);
		player.clearFire();
		return true;
	}

	private static boolean holdsDeathProtection(ServerPlayer player) {
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.getItemInHand(hand).get(DataComponents.DEATH_PROTECTION) != null) {
				return true;
			}
		}
		return false;
	}

	private static boolean isInVoid(ServerPlayer player) {
		return player.getY() < player.level().getMinY() - 5;
	}

	// ---------------------------------------------------------------- vanish

	/**
	 * Hides the player completely: out of the tab list and out of every other client's entity tracker.
	 *
	 * <p>Untracking the entity rather than leaning on the invisibility effect is what makes this airtight
	 * — an invisible player still shows armour, particles and a nameplate through walls.
	 */
	public void setVanished(ServerPlayer player, boolean enabled) {
		if (enabled) {
			this.vanished.add(player.getUUID());
			Sfx.vanishOut(player);
			PlayerRefresh.hideFromPlayerList(player);
			PlayerRefresh.untrackFromAll(player);
		} else {
			this.vanished.remove(player.getUUID());
			// Full refresh brings back both the list entry and the entity for every observer.
			PlayerRefresh.refresh(player);
			Sfx.vanishIn(player);
		}
		save();
	}

	public boolean isVanished(ServerPlayer player) {
		return this.vanished.contains(player.getUUID());
	}

	// ---------------------------------------------------------------- chat mute

	public void setChatBlocked(boolean blocked) {
		this.chatBlocked = blocked;
		save();
	}

	public boolean isChatBlocked() {
		return this.chatBlocked;
	}

	// ---------------------------------------------------------------- pvp

	/** PvP is a world-level rule, so this flips it across every loaded dimension at once. */
	public void setPvp(boolean enabled) {
		MinecraftServer server = this.context.server();
		for (ServerLevel level : server.getAllLevels()) {
			level.getGameRules().set(GameRules.PVP, enabled, server);
		}
	}

	public boolean isPvpEnabled() {
		ServerLevel overworld = this.context.server().overworld();
		return overworld.getGameRules().get(GameRules.PVP);
	}

	// ---------------------------------------------------------------- one-shot actions

	public void heal(ServerPlayer player) {
		player.setHealth(player.getMaxHealth());
		player.clearFire();
		player.removeAllEffects();
		Sfx.heal(player);
	}

	public void feed(ServerPlayer player) {
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(20.0F);
		Sfx.feed(player);
	}

	/**
	 * Sets how many hearts the player has by moving the {@code max_health} attribute, the same thing
	 * {@code /attribute <player> minecraft:generic.max_health base set <n>} does.
	 *
	 * <p>Changing the attribute rather than just current health is what actually changes the heart row on
	 * the HUD — writing current health alone leaves ten hearts drawn and simply refills them.
	 *
	 * @param hearts number of hearts, clamped to the 0.5–1024 range the attribute accepts.
	 * @return the applied heart count.
	 */
	public double setMaxHealth(ServerPlayer player, double hearts) {
		double clamped = Math.max(0.5D, Math.min(1024.0D, hearts));
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
		if (attribute == null) {
			return player.getMaxHealth() / 2.0D;
		}
		attribute.setBaseValue(clamped * 2.0D);
		// Current health is not rescaled automatically, so top it up to the new maximum.
		player.setHealth(player.getMaxHealth());
		return clamped;
	}

	/** Puts the heart row back to the vanilla 10. */
	public void resetMaxHealth(ServerPlayer player) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
		if (attribute != null) {
			attribute.setBaseValue(20.0D);
		}
		player.setHealth(player.getMaxHealth());
	}

	/** Sets current health without touching the maximum. */
	public void setHealth(ServerPlayer player, float health) {
		player.setHealth(Math.max(0.5F, Math.min(player.getMaxHealth(), health)));
	}

	public void giveTotems(ServerPlayer player, int count) {
		int remaining = Math.max(1, Math.min(64 * 9, count));
		while (remaining > 0) {
			int stackSize = Math.min(64, remaining);
			ItemStack stack = new ItemStack(Items.TOTEM_OF_UNDYING, stackSize);
			if (!player.getInventory().add(stack)) {
				// Inventory full — drop the remainder at the player's feet rather than voiding it.
				player.drop(stack, false, false);
			}
			remaining -= stackSize;
		}
		Sfx.totem(player);
	}

	/** Teleports the player to the highest solid block at their current column. */
	public boolean teleportToTop(ServerPlayer player) {
		ServerLevel level = player.level();
		int x = player.getBlockX();
		int z = player.getBlockZ();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		if (y <= level.getMinY()) {
			return false;
		}
		player.teleportTo(level, x + 0.5D, y, z + 0.5D, Set.of(), player.getYRot(), player.getXRot(), false);
		Sfx.teleport(player);
		return true;
	}

	/**
	 * Tops every stackable item in the inventory back up to a full stack.
	 *
	 * <p>The practice-server "restock": between takes, blocks, food and projectiles go back to full
	 * without changing the loadout's composition. Damageable gear is untouched — that is what
	 * {@link #repairAll} is for.
	 */
	public int restock(ServerPlayer player) {
		int restocked = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty() || stack.isDamageableItem()) {
				continue;
			}
			int max = stack.getMaxStackSize();
			if (stack.getCount() < max) {
				stack.setCount(max);
				restocked++;
			}
		}
		player.getInventory().setChanged();
		Sfx.restock(player);
		return restocked;
	}

	// ---------------------------------------------------------------- durability

	/** Sets every damageable item the player carries to full durability. */
	public int repairAll(ServerPlayer player) {
		int changed = forEachDamageable(player, stack -> stack.setDamageValue(0));
		Sfx.repair(player);
		return changed;
	}

	/** Randomises durability on every damageable item — instant "been through a war" gear for a scene. */
	public int randomiseDurability(ServerPlayer player) {
		return forEachDamageable(player, stack ->
			stack.setDamageValue(this.random.nextInt(Math.max(1, stack.getMaxDamage()))));
	}

	private int forEachDamageable(ServerPlayer player, java.util.function.Consumer<ItemStack> action) {
		int changed = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.isDamageableItem()) {
				action.accept(stack);
				changed++;
			}
		}
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isEmpty() && stack.isDamageableItem()) {
				action.accept(stack);
				changed++;
			}
		}
		player.getInventory().setChanged();
		return changed;
	}

	// ---------------------------------------------------------------- tick

	public void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			tickFreeze(player);
		}
		// Timers and armour repair run on a one-second beat, matching the reference mod.
		if (++this.tickCounter < SECOND_IN_TICKS) {
			return;
		}
		this.tickCounter = 0;
		countDownImmortality();
		repairProtectedArmor(server);
	}

	/** Decrements every countdown by a second and drops the ones that hit zero. */
	private void countDownImmortality() {
		boolean changed = false;
		var iterator = this.immortals.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Integer> entry = iterator.next();
			if (entry.getValue() == FOREVER) {
				continue;
			}
			int remaining = entry.getValue() - 1;
			if (remaining <= 0) {
				iterator.remove();
			} else {
				entry.setValue(remaining);
			}
			changed = true;
		}
		if (this.globalImmortal && this.globalSeconds > 0) {
			this.globalSeconds--;
			if (this.globalSeconds == 0) {
				this.globalImmortal = false;
				this.globalSeconds = FOREVER;
			}
			changed = true;
		}
		if (changed) {
			save();
		}
	}

	/**
	 * Repairs one point of durability per second on protected players' worn armour.
	 *
	 * <p>Gradual rather than instant, as in the reference mod: gear visibly wears during a fight and
	 * recovers between takes, instead of snapping back to pristine mid-shot.
	 */
	private void repairProtectedArmor(MinecraftServer server) {
		if (this.armorProtected.isEmpty()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!this.armorProtected.contains(player.getUUID())) {
				continue;
			}
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				ItemStack stack = player.getItemBySlot(slot);
				if (!stack.isEmpty() && stack.isDamageableItem() && stack.getDamageValue() > 0) {
					stack.setDamageValue(Math.max(0, stack.getDamageValue() - 1));
				}
			}
		}
	}

	/**
	 * Holds a frozen player at their anchor. Vertical position is left free so they can still jump and
	 * fall, and sneaking is unaffected — only horizontal travel is cancelled.
	 */
	private void tickFreeze(ServerPlayer player) {
		if (!this.frozen.containsKey(player.getUUID())) {
			return;
		}
		FreezeAnchor anchor = this.frozen.get(player.getUUID());
		if (anchor == null) {
			// Restored from disk without an anchor; pin them where they are now.
			this.frozen.put(player.getUUID(), new FreezeAnchor(player.getX(), player.getY(), player.getZ()));
			return;
		}
		double driftX = Math.abs(player.getX() - anchor.x());
		double driftZ = Math.abs(player.getZ() - anchor.z());
		if (driftX > 0.05D || driftZ > 0.05D) {
			player.teleportTo(player.level(), anchor.x(), player.getY(), anchor.z(),
				Set.of(), player.getYRot(), player.getXRot(), false);
		}
	}

	// ---------------------------------------------------------------- feedback helpers

	// ---------------------------------------------------------------- json helpers

	private static void readSet(JsonObject root, String key, Set<UUID> output) {
		output.clear();
		if (!root.has(key) || !root.get(key).isJsonArray()) {
			return;
		}
		for (JsonElement element : root.getAsJsonArray(key)) {
			try {
				output.add(UUID.fromString(element.getAsString()));
			} catch (IllegalArgumentException ignored) {
				// Skip malformed ids rather than refusing to load the whole file.
			}
		}
	}

	private static void writeSet(JsonObject root, String key, Set<UUID> values) {
		JsonArray array = new JsonArray();
		values.forEach(uuid -> array.add(uuid.toString()));
		root.add(key, array);
	}

	private JsonObject defaults() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", 1);
		root.add("invisible", new JsonArray());
		root.add("vanished", new JsonArray());
		root.add("frozen", new JsonArray());
		root.add("fallImmune", new JsonArray());
		root.add("armorProtected", new JsonArray());
		root.add("immortals", new JsonObject());
		root.addProperty("chatBlocked", false);
		root.addProperty("globalImmortal", false);
		root.addProperty("globalSeconds", FOREVER);
		root.addProperty("allowVoidImmortality", false);
		return root;
	}

	private record FreezeAnchor(double x, double y, double z) {
	}
}
