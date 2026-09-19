package scriptify.bots.scenario;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;
import scriptify.bots.bot.BotManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The two hand tools that build scenarios by pointing at things instead of typing coordinates.
 *
 * <p>Both are ordinary sticks carrying a marker in their custom data. Nothing is registered:
 * this is a server-side mod talking to vanilla clients, so a genuinely new item is not an
 * option, and it is not needed - a named stick that the server recognises behaves the same from
 * the player's side.</p>
 */
public final class ScenarioTools {

    private ScenarioTools() {}

    private static final String MARKER = "SBotsTool";
    private static final String TOOL_CONVERSATION = "conversation";
    private static final String TOOL_PRISON = "prison";

    /** How far the Prison Stick reaches. */
    public static final double PRISON_RADIUS = 300.0;
    private static final int PRISON_CHARGE_TICKS = 100;

    /** Players part-way through charging the Prison Stick, and how many ticks remain. */
    private static final Map<UUID, Integer> CHARGING = new HashMap<>();

    // ===================== items =====================

    public static ItemStack conversationStick() {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.CUSTOM_NAME,
            Component.literal("Conversation Stick").withStyle(ChatFormatting.AQUA)
                .withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
            hint("Right-click bots to add them to the group."),
            hint("At least two, then /bots scenarios finish."))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        mark(stack, TOOL_CONVERSATION);
        return stack;
    }

    public static ItemStack prisonStick() {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.CUSTOM_NAME,
            Component.literal("Prison Stick").withStyle(ChatFormatting.GRAY)
                .withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
            hint("Right-click and wait 5s to assign every bot"),
            hint("within " + (int) PRISON_RADIUS + " blocks to a prison cell."))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        mark(stack, TOOL_PRISON);
        return stack;
    }

    private static Component hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY)
            .withStyle(s -> s.withItalic(false));
    }

    private static void mark(ItemStack stack, String tool) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString(MARKER, tool);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
    }

    private static String toolOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag nbt = data.copyTag();
        return nbt.getString(MARKER).orElse(null);
    }

    // ===================== wiring =====================

    public static void register() {
        UseEntityCallback.EVENT.register(ScenarioTools::onUseEntity);
        UseItemCallback.EVENT.register(ScenarioTools::onUseItem);
    }

    /** Conversation Stick: right-clicking a bot adds it to the draft. */
    private static InteractionResult onUseEntity(net.minecraft.world.entity.player.Player player,
                                            net.minecraft.world.level.Level world,
                                            net.minecraft.world.InteractionHand hand,
                                            Entity entity,
                                            net.minecraft.world.phys.EntityHitResult hit) {
        if (world.isClientSide() || !(player instanceof ServerPlayer server)) return InteractionResult.PASS;
        // One right-click on an entity delivers two interactions - the client sends both the
        // "at" and the plain form, and the callback runs for the off hand as well. The handler
        // below used to toggle, so a single click added the bot and then took it straight back
        // out again, reporting it as a removal. Main hand only, and never toggle.
        if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!TOOL_CONVERSATION.equals(toolOf(player.getItemInHand(hand)))) return InteractionResult.PASS;

        ScenarioDraft draft = ScenarioManager.draft(server);
        if (draft == null || draft.type != Scenario.CONVERSATION) {
            ScenarioPrompt.flash(server, "Open /bots scenarios and pick Conversation first", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        String name = entity.getName().getString();
        if (!BotManager.isBot(name)) {
            ScenarioPrompt.flash(server, name + " is not a bot", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        if (!draft.bots.add(name)) {
            ScenarioPrompt.flash(server, name + " is already in the group ("
                + draft.bots.size() + ")", ChatFormatting.YELLOW);
            return InteractionResult.SUCCESS;
        }
        ScenarioPrompt.flash(server, "Added " + name + " (" + draft.bots.size()
            + " in the group)", ChatFormatting.GREEN);
        return InteractionResult.SUCCESS;
    }

    /** Prison Stick: begins the charge, or cancels one already running. */
    private static InteractionResult onUseItem(net.minecraft.world.entity.player.Player player,
                                          net.minecraft.world.level.Level world,
                                          net.minecraft.world.InteractionHand hand) {
        if (world.isClientSide() || !(player instanceof ServerPlayer server)) return InteractionResult.PASS;
        if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!TOOL_PRISON.equals(toolOf(player.getItemInHand(hand)))) return InteractionResult.PASS;

        // A charge just started cannot be cancelled by the same click arriving twice.
        Integer active = CHARGING.get(server.getUUID());
        if (active != null && active > PRISON_CHARGE_TICKS - 5) return InteractionResult.SUCCESS;

        if (CHARGING.remove(server.getUUID()) != null) {
            ScenarioPrompt.flash(server, "Prison Stick cancelled", ChatFormatting.YELLOW);
            return InteractionResult.SUCCESS;
        }
        CHARGING.put(server.getUUID(), PRISON_CHARGE_TICKS);
        return InteractionResult.SUCCESS;
    }

    /**
     * Runs the Prison Stick charge.
     *
     * <p>Held right-click is not something a server can see for an item with no use action, so
     * the charge is a timer the player starts and can cancel by clicking again. It is the same
     * five seconds from their side, and it does not need the client to tell us anything.</p>
     */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (CHARGING.isEmpty()) return;

        CHARGING.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;
            if (!TOOL_PRISON.equals(toolOf(player.getMainHandItem()))) {
                ScenarioPrompt.flash(player, "Prison Stick cancelled", ChatFormatting.YELLOW);
                return true;
            }

            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                if (remaining % 5 == 0) {
                    ScenarioPrompt.flash(player, String.format(java.util.Locale.US,
                        "Assigning prison cells in %.1fs", remaining / 20.0),
                        ChatFormatting.GRAY);
                }
                return false;
            }

            assignPrison(server, player);
            return true;
        });
    }

    private static void assignPrison(net.minecraft.server.MinecraftServer server,
                                     ServerPlayer player) {
        ScenarioDraft draft = ScenarioManager.draft(player);
        if (draft == null || draft.type != Scenario.PRISON_CELL) {
            draft = ScenarioManager.beginDraft(player, Scenario.PRISON_CELL);
        }

        Vec3 origin = player.position();
        int added = 0;
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (!BotManager.isBot(candidate.getName().getString())) continue;
            if (candidate.level() != player.level()) continue;
            if (candidate.position().distanceTo(origin) > PRISON_RADIUS) continue;
            if (draft.bots.add(candidate.getName().getString())) added++;
        }

        if (added == 0) {
            ScenarioPrompt.flash(player, "No bots within " + (int) PRISON_RADIUS + " blocks",
                ChatFormatting.RED);
            return;
        }
        ScenarioPrompt.flash(player, added + " bots assigned", ChatFormatting.GREEN);
    }

    public static void clear() {
        CHARGING.clear();
    }
}
