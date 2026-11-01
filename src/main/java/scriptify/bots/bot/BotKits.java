package scriptify.bots.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DataResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The gear a bot can be handed, saved by name.
 *
 * <p>Stored as the item NBT rather than as an item id and a count, because a kit is almost never
 * plain items: it is a specific enchanted sword, a named totem, a potion with a particular effect
 * on it. Anything that summarised the stack would quietly lose all of that, and the loss would only
 * show up in a fight.</p>
 *
 * <p>The on-disk shape is a map of kit name to a map of slot number to that stack's NBT, and it is
 * kept exactly as it was: existing {@code kits.json} files predate everything else here and there is
 * no reason to make anyone rebuild their kits.</p>
 */
public final class BotKits {

    private BotKits() {}

    /**
     * Slots in a kit: the thirty-six of a player inventory, four of armour, and the off hand.
     *
     * <p>The whole player, in other words. A kit that stopped at the hotbar would be unable to
     * describe a bot in armour, which is most of them.</p>
     */
    public static final int SLOTS = 41;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path configPath;

    /** Kit name in lower case, to slot number as text, to that stack's NBT as text. */
    private static final Map<String, Map<String, Object>> kitsRaw = new HashMap<>();

    public static void init(MinecraftServer srv) {
        configPath = scriptify.bots.config.WorldConfigHelper.getGlobalConfigDir()
            .resolve("kits.json");
        load();
    }

    public static void reload(MinecraftServer srv) {
        init(srv);
    }

    // ===================== reading =====================

    public static Set<String> getKitNames() {
        return new HashSet<>(kitsRaw.keySet());
    }

    public static boolean kitExists(String kitName) {
        return kitsRaw.containsKey(kitName.toLowerCase());
    }

    /**
     * A kit as a slot-to-stack map, or null when there is no such kit.
     *
     * <p>Needs a player only to reach the registries the item codec decodes against; nothing about
     * the kit belongs to them.</p>
     */
    public static Map<Integer, ItemStack> getKitItems(String kitName, ServerPlayer player) {
        Map<String, Object> data = kitsRaw.get(kitName.toLowerCase());
        if (data == null) return null;
        return decode(data, player.registryAccess());
    }

    /**
     * Slot-to-NBT text back into stacks: the kit file's shape, which saved scenes reuse for a bot's
     * own inventory. A stack that no longer decodes - an item from a mod since removed - is skipped.
     */
    public static Map<Integer, ItemStack> decode(Map<String, ?> data, HolderLookup.Provider registries) {
        var registryOps = RegistryOps.create(NbtOps.INSTANCE, registries);
        Map<Integer, ItemStack> items = new HashMap<>();

        for (var entry : data.entrySet()) {
            try {
                int slot = Integer.parseInt(entry.getKey());
                CompoundTag nbt = net.minecraft.nbt.TagParser
                    .parseCompoundFully(entry.getValue().toString());
                DataResult<ItemStack> result = ItemStack.CODEC.parse(registryOps, nbt);
                if (result.result().isPresent()) {
                    items.put(slot, result.result().get());
                }
            } catch (Exception e) {
                scriptify.bots.BotRuntime.LOGGER.warn("Failed to parse kit item: {}", e.getMessage());
            }
        }
        return items;
    }

    /**
     * A kit as a dense array, the shape an inventory screen wants.
     *
     * <p>Empty slots are {@link ItemStack#EMPTY} rather than absent, because the editor has to draw
     * every cell whether or not anything is in it.</p>
     */
    public static ItemStack[] contents(String kitName, ServerPlayer player) {
        Map<Integer, ItemStack> items = getKitItems(kitName, player);
        if (items == null) return null;

        ItemStack[] out = new ItemStack[SLOTS];
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = items.get(slot);
            out[slot] = stack == null ? ItemStack.EMPTY : stack;
        }
        return out;
    }

    // ===================== writing =====================

    /** Saves whatever the player is carrying, under a new name. */
    public static boolean createKit(String kitName, ServerPlayer player) {
        String key = kitName.toLowerCase();
        if (kitsRaw.containsKey(key)) return false;

        ItemStack[] contents = new ItemStack[SLOTS];
        var inventory = player.getInventory();
        for (int slot = 0; slot < SLOTS; slot++) {
            contents[slot] = inventory.getItem(slot);
        }
        if (isEmpty(contents)) return false;

        return write(key, contents, player);
    }

    /**
     * Creates a kit with nothing in it, to be filled in by hand.
     *
     * <p>The point of the editor: a kit no longer has to be assembled in your own inventory first,
     * which meant clearing whatever you were carrying to build one.</p>
     */
    public static boolean createEmpty(String kitName) {
        String key = kitName.toLowerCase();
        if (kitsRaw.containsKey(key)) return false;
        kitsRaw.put(key, new LinkedHashMap<>());
        save();
        return true;
    }

    /** Replaces a kit's contents wholesale. Used by the editor when its screen closes. */
    public static boolean setContents(String kitName, ItemStack[] contents, ServerPlayer player) {
        String key = kitName.toLowerCase();
        if (!kitsRaw.containsKey(key)) return false;
        return write(key, contents, player);
    }

    private static boolean write(String key, ItemStack[] contents, ServerPlayer player) {
        kitsRaw.put(key, new LinkedHashMap<String, Object>(encode(contents, player.registryAccess())));
        save();
        return true;
    }

    /** Stacks as slot number to NBT text, empty slots left out; the reverse of {@link #decode}. */
    public static Map<String, String> encode(ItemStack[] contents, HolderLookup.Provider registries) {
        var registryOps = RegistryOps.create(NbtOps.INSTANCE, registries);
        Map<String, String> encoded = new LinkedHashMap<>();

        for (int slot = 0; slot < contents.length && slot < SLOTS; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) continue;
            DataResult<Tag> result = ItemStack.CODEC.encodeStart(registryOps, stack);
            if (result.result().isPresent()) {
                encoded.put(String.valueOf(slot), result.result().get().toString());
            }
        }
        return encoded;
    }

    private static boolean isEmpty(ItemStack[] contents) {
        for (ItemStack stack : contents) {
            if (stack != null && !stack.isEmpty()) return false;
        }
        return true;
    }

    public static boolean deleteKit(String kitName) {
        boolean removed = kitsRaw.remove(kitName.toLowerCase()) != null;
        if (removed) save();
        return removed;
    }

    // ===================== handing out =====================

    /** Replaces a bot's entire inventory with the kit. */
    public static boolean giveKit(String kitName, ServerPlayer bot) {
        Map<Integer, ItemStack> kitItems = getKitItems(kitName, bot);
        if (kitItems == null) return false;

        var inventory = bot.getInventory();
        inventory.clearContent();
        for (var entry : kitItems.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue().copy());
        }
        return true;
    }

    /**
     * Hands a kit to every bot standing near a player.
     *
     * <p>Lives here rather than in the menu that first needed it, because the kit stick wants
     * exactly the same thing and two copies of "which bots count as nearby" is two answers.</p>
     *
     * @return how many bots were equipped
     */
    public static int giveToNearby(ServerPlayer player, String kitName, double radius) {
        var server = player.level().getServer();
        if (server == null || !kitExists(kitName)) return 0;

        double radiusSquared = radius * radius;
        int given = 0;
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (!BotManager.isBot(candidate.getName().getString())) continue;
            if (candidate.level() != player.level()) continue;
            if (candidate.position().distanceToSqr(player.position()) > radiusSquared) continue;
            if (giveKit(kitName, candidate)) given++;
        }
        return given;
    }

    // ===================== persistence =====================

    private static void load() {
        if (configPath == null || !Files.exists(configPath)) return;

        try (Reader reader = Files.newBufferedReader(configPath)) {
            Map<String, Map<String, Object>> loaded = GSON.fromJson(
                reader, new TypeToken<Map<String, Map<String, Object>>>() {}.getType());
            if (loaded != null) {
                kitsRaw.clear();
                kitsRaw.putAll(loaded);
            }
        } catch (Exception e) {
            scriptify.bots.BotRuntime.LOGGER.warn("Failed to load kits: {}", e.getMessage());
        }
    }

    private static void save() {
        if (configPath == null) return;
        scriptify.storage.JsonFile.write(GSON, configPath, kitsRaw,
            scriptify.bots.BotRuntime.LOGGER, "bot kits");
    }
}
