package scriptify.bots.api;

/**
 * What a bot looked like at one instant, in types that cross a mod boundary safely.
 *
 * <p>Every field is a primitive or a {@link String}. That is not squeamishness about Minecraft
 * types, it is the only shape that works: a consumer built against different mappings sees
 * {@code ServerPlayerEntity} under another name entirely, and any signature mentioning one has to
 * be remapped in step with this mod. Strings and doubles are the same in every mapping set, so
 * this record compiles anywhere and keeps the two builds independent.</p>
 *
 * <p>It happens to be the right shape for scripting as well. A script wants
 * {@code "minecraft:diamond_sword"}, not an {@code ItemStack} it has no way to inspect.</p>
 *
 * <p>A snapshot is dead the moment it is handed over - the bot may be gone a tick later. Treat it
 * as a reading, never as a handle.</p>
 *
 * @param name      roster spelling, which is the key everything else is stored under
 * @param uuid      the bot's UUID as a string, for callers that drive it through their own systems
 * @param dimension identifier of the world it is in, e.g. {@code "minecraft:overworld"}
 * @param health    current health in half-hearts, matching the vanilla scale
 * @param heldItem  item id in the main hand, e.g. {@code "minecraft:netherite_axe"}
 * @param target    name of whatever it is currently fighting, or {@code null} for nobody
 * @param group     NPC group it belongs to, or {@code null} when it is in none
 * @param scenario  id of the scenario running it, or {@code -1} when it is under combat AI
 */
public record BotSnapshot(
    String name,
    String uuid,
    String dimension,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    float health,
    float maxHealth,
    String heldItem,
    String target,
    String group,
    int scenario
) {
}
