package scriptify.bots.bot;

import com.mojang.brigadier.ParseResults;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place that talks to the fake-player backend.
 *
 * <p>SBots does not spawn or drive players itself - HeroBot does, through carpet-style
 * {@code /player <name> ...} commands. Every input the AI wants to send (swing, use, stop,
 * jump) goes through here.</p>
 *
 * <p>Two things this fixes compared to the original, which called
 * {@code dispatcher.execute(...)} with {@code server.createCommandSourceStack()} from a dozen
 * places:</p>
 * <ul>
 *   <li><b>Console spam / op broadcast.</b> The server command source broadcasts feedback to
 *       operators. With a handful of bots attacking every few ticks that is thousands of
 *       messages a minute. A silent source is created once and reused.</li>
 *   <li><b>Allocation churn.</b> The command strings are interned per bot instead of being
 *       rebuilt with string concatenation on every single tick.</li>
 *   <li><b>Parsing.</b> Handing a dispatcher a string means parsing it: walking the command
 *       tree from the root, matching literals, allocating a context builder and a result set.
 *       That is a lot of work to arrive at the same answer as last tick, and it happened several
 *       times per bot per tick - an attack, a movement input, a jump. At two hundred and fifty
 *       bots that is thousands of parses a tick, and it was the largest single thing the bots
 *       cost the server: the reason a big arena ran in slow motion while the heap sat idle.
 *       Every command here is fixed text against a fixed source, so each is parsed once and the
 *       result reused.</li>
 * </ul>
 */
public final class BotActions {

    private BotActions() {}

    private static MinecraftServer boundServer;
    private static CommandSourceStack silentSource;

    /**
     * One bot's inputs, as text and as the parse of that text.
     *
     * <p>Parses are filled in lazily. A bot standing in a scenario never attacks, and parsing
     * all twelve inputs for every name on the roster at spawn time would be work done against
     * the chance it is wanted.</p>
     */
    private static final class Inputs {
        final String[] text;
        final List<ParseResults<CommandSourceStack>> parsed;

        Inputs(String[] text) {
            this.text = text;
            this.parsed = new ArrayList<>(COMMANDS);
            for (int index = 0; index < COMMANDS; index++) {
                this.parsed.add(null);
            }
        }
    }

    private static final Map<String, Inputs> COMMAND_CACHE = new HashMap<>();

    /**
     * The tree the stored parses were made against.
     *
     * <p>A datapack reload builds an entirely new dispatcher, and a parse held over from the old
     * one points at command nodes that are no longer registered. It would very probably still
     * behave, which is worse than failing - so the parses are thrown away when the tree changes
     * and quietly rebuilt. One reference comparison per input to be sure of it.</p>
     */
    private static com.mojang.brigadier.CommandDispatcher<CommandSourceStack> parsedAgainst;

    private static final int ATTACK = 0;
    private static final int USE_ONCE = 1;
    private static final int USE_CONTINUOUS = 2;
    private static final int STOP = 3;
    private static final int JUMP = 4;
    private static final int SWING = 5;
    private static final int KILL = 6;
    private static final int DROP = 7;
    private static final int ATTACK_CONTINUOUS = 8;
    private static final int SNEAK = 9;
    private static final int UNSNEAK = 10;
    private static final int COMMANDS = 11;

    public static void bind(MinecraftServer server) {
        boundServer = server;
        CommandSourceStack base = server.createCommandSourceStack();
        // withSilent() drops command feedback and the op broadcast; if a future mapping
        // removes it we fall back to the noisy source rather than failing to act at all.
        CommandSourceStack quiet;
        try {
            quiet = base.withSuppressedOutput();
        } catch (Throwable t) {
            quiet = base;
        }
        silentSource = quiet;
        parsedAgainst = null;
        COMMAND_CACHE.clear();
    }

    public static void unbind() {
        boundServer = null;
        silentSource = null;
        parsedAgainst = null;
        COMMAND_CACHE.clear();
    }

    public static CommandSourceStack source(MinecraftServer server) {
        if (silentSource == null || boundServer != server) {
            bind(server);
        }
        return silentSource;
    }

    private static Inputs commands(String botName) {
        return COMMAND_CACHE.computeIfAbsent(scriptify.bots.utils.Names.key(botName), key -> {
            String name = botName;
            String[] arr = new String[COMMANDS];
            arr[ATTACK] = "player " + name + " attack once";
            arr[USE_ONCE] = "player " + name + " use once";
            arr[USE_CONTINUOUS] = "player " + name + " use continuous";
            arr[STOP] = "player " + name + " stop";
            arr[JUMP] = "player " + name + " jump once";
            // "swing", not "swinghand". The backend has never accepted the longer spelling, so every
            // swing-only input was a parse failure swallowed by the catch in run() - which is most of
            // what makes a bot look like it is fighting rather than mining: the animation-only swing
            // that keeps a bot from chipping at a wall behind its target, the arm movement that goes
            // with placing a block, and every deliberate miss. All of them sent nothing at all.
            arr[SWING] = "player " + name + " swing";
            arr[KILL] = "player " + name + " kill";
            arr[DROP] = "player " + name + " drop";
            arr[ATTACK_CONTINUOUS] = "player " + name + " attack continuous";
            arr[SNEAK] = "player " + name + " sneak";
            arr[UNSNEAK] = "player " + name + " unsneak";
            return new Inputs(arr);
        });
    }

    /**
     * Sends one input to the backend.
     *
     * <p>Parses on first use and reuses the parse after that. Re-executing a stored parse is
     * what the server itself does with a datapack function, and it is sound for the same reason:
     * executing builds a fresh context from the parse rather than consuming it. A parse does
     * hold the source it was made against, which is why {@link #bind} empties the cache - one
     * made against a previous server must not outlive it.</p>
     */
    private static void run(MinecraftServer server, String botName, int command) {
        if (server == null) return;
        var dispatcher = server.getCommands().getDispatcher();
        if (dispatcher != parsedAgainst) {
            parsedAgainst = dispatcher;
            for (Inputs held : COMMAND_CACHE.values()) {
                java.util.Collections.fill(held.parsed, null);
            }
        }

        Inputs inputs = commands(botName);
        try {
            ParseResults<CommandSourceStack> parsed = inputs.parsed.get(command);
            if (parsed == null) {
                parsed = dispatcher.parse(inputs.text[command], source(server));
                // A parse that consumed nothing is the backend not being there at all, or not speaking
                // the syntax we are speaking. Both look identical from here and both mean every input
                // this class sends is going nowhere.
                if (parsed.getReader().canRead()) {
                    noteBackendRefused(inputs.text[command]);
                }
                inputs.parsed.set(command, parsed);
            }
            dispatcher.execute(parsed);
        } catch (Exception refused) {
            // A bot that has just despawned throws here routinely, so this is not an error - but a
            // backend that refuses *everything* is, and it is indistinguishable from a working one
            // without saying so. See noteBackendRefused.
            noteBackendRefused(inputs.text[command]);
        }
    }

    /**
     * Says once, in the log, that the fake-player backend would not take an input.
     *
     * <p>Worth a line because the failure is otherwise perfectly silent and perfectly confusing: the
     * AI carries on thinking, targeting, pathing and deciding, and the bots stand still. There is
     * nothing in a log to connect the two, and the obvious conclusion - that the AI is broken - is
     * the wrong one.
     *
     * <p>Once per distinct input rather than once overall, because a single wrong verb among eleven
     * right ones is the interesting case and a global flag would hide it behind the first despawn.
     * A handful of lines at most, and only ever when something is actually wrong.
     */
    private static void noteBackendRefused(String input) {
        // Strip the bot's name: the interesting part is the verb, and one line per bot on a
        // two-hundred-bot roster is a log nobody reads.
        int space = input.indexOf(' ');
        int verbStart = space < 0 ? 0 : input.indexOf(' ', space + 1) + 1;
        String verb = verbStart <= 0 || verbStart >= input.length()
            ? input : input.substring(verbStart);
        if (!REFUSED.add(verb)) {
            return;
        }
        scriptify.bots.BotRuntime.LOGGER.warn(
            "The fake-player backend refused \"{}\". Bots will not perform that action. Either HeroBot "
                + "is not installed, or the version installed does not accept that input.", verb);
    }

    /** Inputs already reported, so the warning is a line rather than a stream. */
    private static final java.util.Set<String> REFUSED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void attack(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), ATTACK);
    }

    /**
     * How far a swing is checked for something to hit. A shade past the longest melee reach, so
     * a swing that only just fails to connect still counts as aimed at the player.
     */
    private static final double SWING_RAY = 3.4;

    /** Entity boxes are widened this much when picking, matching what vanilla does. */
    private static final double PICK_MARGIN = 0.3;

    /**
     * Swings at a target, mining nothing.
     *
     * <p>The backend's attack action is a real left click: it casts a ray from the eyes and hits
     * whatever it finds, and if that is a block it starts <em>breaking</em> it. So a bot fighting
     * with a wall, a fence or the floor behind its opponent left cracks crawling over the scenery
     * for the whole duel - the block-breaking overlay appearing at random during a fight, which
     * is not something a player ever does.</p>
     *
     * <p>So ask the question the click is about to ask, first. If a block is nearer than the
     * target, send the swing as an animation only: the arm still moves, the hit still misses, and
     * nothing is mined. A swing into open air is left alone - it costs nothing, and it is what a
     * missed hit is supposed to look like.</p>
     */
    public static void attack(ServerPlayer bot, MinecraftServer server, Entity target) {
        // A spear needs nothing special here: the backend's attack sees one in hand and stabs.
        if (blockInTheWay(bot, target)) {
            run(server, bot.getName().getString(), SWING);
            return;
        }
        run(server, bot.getName().getString(), ATTACK);
    }

    /**
     * Whether a left click right now would land on a block rather than on the target.
     *
     * <p>One raycast, and only on the ticks a bot actually swings - a few times a second at
     * most, not every tick.</p>
     */
    private static boolean blockInTheWay(ServerPlayer bot, Entity target) {
        Vec3 eye = bot.getEyePosition();
        Vec3 end = eye.add(bot.getViewVector(1.0f).scale(SWING_RAY));

        BlockHitResult blockHit = (BlockHitResult) bot.level().clip(new ClipContext(
            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
        if (blockHit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (target == null) {
            return true;
        }

        AABB box = target.getBoundingBox().inflate(PICK_MARGIN);
        Optional<Vec3> entityHit = box.clip(eye, end);
        // Not on the target at all, and a block is in front: that click is a mine, not a miss.
        if (entityHit.isEmpty()) {
            return true;
        }
        return eye.distanceToSqr(blockHit.getLocation()) < eye.distanceToSqr(entityHit.get());
    }

    /**
     * Holds the attack button. Breaking a block is a continuous action - a single click
     * barely dents it - so mining out of a cobweb needs this rather than {@link #attack}.
     */
    public static void attackContinuous(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), ATTACK_CONTINUOUS);
    }

    public static void useOnce(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), USE_ONCE);
    }

    /**
     * Places a block and shows the arm swing that goes with it.
     *
     * <p>A real player's arm swings every time they place a block. The backend's use action on
     * its own does not always produce that animation for onlookers, so the swing is sent
     * explicitly - without it, blocks appear out of thin air next to a motionless bot, which is
     * one of the more obvious giveaways.</p>
     */
    public static void place(ServerPlayer bot, MinecraftServer server) {
        String name = bot.getName().getString();
        run(server, name, USE_ONCE);
        run(server, name, SWING);
    }

    public static void useContinuous(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), USE_CONTINUOUS);
    }

    /**
     * Releases every held input: the use button, and the attack button with it.
     *
     * <p>The attack half matters as much as the use half. Breaking a block needs the button
     * held, so anything that starts mining has to finish with this - a bot that walks away still
     * holding it carries on chipping at whatever it looks at next.</p>
     */
    public static void stopUsing(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), STOP);
    }

    public static void jump(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), JUMP);
    }

    public static void swing(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), SWING);
    }

    /**
     * Crouch. Beyond the visible pose this is what stops a player walking off a ledge, so it
     * is what makes bridging out over a drop survivable rather than a fall.
     */
    public static void sneak(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), SNEAK);
    }

    public static void unsneak(ServerPlayer bot, MinecraftServer server) {
        run(server, bot.getName().getString(), UNSNEAK);
    }

    public static void kill(String botName, MinecraftServer server) {
        run(server, botName, KILL);
    }

    /** Runs an arbitrary command silently (spawn, gamemode, clear, ...). */
    public static boolean execute(MinecraftServer server, String command) {
        if (server == null) return false;
        try {
            server.getCommands().getDispatcher().execute(command, source(server));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void forget(String botName) {
        COMMAND_CACHE.remove(scriptify.bots.utils.Names.key(botName));
    }
}
