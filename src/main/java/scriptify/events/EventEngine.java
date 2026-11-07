package scriptify.events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import scriptify.storage.DataStore;
import scriptify.ui.Msg;

/**
 * Cues: named scene scripts played step by step on the server tick.
 *
 * <p>A cue is a list of steps - commands, delays, messages - stored one file per cue under
 * {@code events/}. Steps run in order and a delay holds everything after it, so a whole beat (title,
 * pause, lightning, a line of dialogue, a death) fires from one command with the same timing every take.
 */
public final class EventEngine {
	private static final String MODULE = "events";

	private final DataStore storage;
	private final Logger logger;
	private final ActionRegistry actions = new ActionRegistry();
	private final TriggerRegistry triggers = new TriggerRegistry();
	private final Map<String, ScriptifyEvent> events = new LinkedHashMap<>();
	/** Where each cue was loaded from, so deleting one removes the right file. */
	private final Map<String, Path> files = new HashMap<>();
	/** Keyed by cue id, or by id and player for a cue a trigger started for one player. */
	private final Map<String, Running> playing = new LinkedHashMap<>();
	private final Map<UUID, Recording> recordings = new HashMap<>();
	private long tick;
	/** Set while a cue's own steps run, so recording while a cue plays does not record the playback. */
	private boolean replaying;

	private record Running(Playback playback, CommandSourceStack source) {
	}

	private record Recording(String cue, long lastTick) {
	}

	public EventEngine(DataStore storage, Logger logger) {
		this.storage = storage;
		this.logger = logger;
		registerBuiltInActions();
	}

	public ActionRegistry actions() {
		return this.actions;
	}

	public TriggerRegistry triggers() {
		return this.triggers;
	}

	public Map<String, ScriptifyEvent> events() {
		return Collections.unmodifiableMap(this.events);
	}

	public void reload() {
		this.events.clear();
		this.files.clear();
		for (var stored : this.storage.loadObjects(MODULE, "")) {
			try {
				ScriptifyEvent event = ScriptifyEvent.from(stored.object());
				if (!this.triggers.contains(event.trigger())) {
					this.logger.warn("Skipping Scriptify event '{}' with unknown trigger '{}'.", event.id(), event.trigger());
					continue;
				}
				this.events.put(event.id(), event);
				this.files.put(event.id(), stored.path());
			} catch (RuntimeException exception) {
				this.logger.warn("Skipping invalid Scriptify event file {}: {}", stored.path(), exception.getMessage());
			}
		}
	}

	// ---------------------------------------------------------------- playback

	/**
	 * Starts a cue from its first step, restarting it if it is already playing.
	 *
	 * @return false when there is no enabled cue by that name
	 */
	public boolean play(String id, CommandSourceStack source) {
		return start(id, id, source);
	}

	public void runTrigger(String trigger, CommandSourceStack source) {
		for (ScriptifyEvent event : this.events.values()) {
			if (event.enabled() && event.trigger().equals(trigger)) {
				// One playback per player: two people joining together each get their own welcome.
				start(event.id() + "#" + source.getTextName(), event.id(), source);
			}
		}
	}

	private boolean start(String key, String id, CommandSourceStack source) {
		ScriptifyEvent event = this.events.get(id);
		if (event == null || !event.enabled()) {
			return false;
		}
		// Silent: a cue runs during a take, and "Teleported Steve to ..." in chat is in the shot.
		this.playing.put(key, new Running(new Playback(event, this.tick), source.withSuppressedOutput()));
		return true;
	}

	public boolean isPlaying(String id) {
		return this.playing.keySet().stream().anyMatch(key -> key.equals(id) || key.startsWith(id + "#"));
	}

	public boolean stop(String id) {
		return this.playing.keySet().removeIf(key -> key.equals(id) || key.startsWith(id + "#"));
	}

	public int stopAll() {
		int count = this.playing.size();
		this.playing.clear();
		return count;
	}

	public void tick(MinecraftServer server) {
		this.tick = server.getTickCount();
		if (this.playing.isEmpty()) {
			return;
		}
		// A copy: a step may itself play or stop a cue.
		for (var entry : List.copyOf(this.playing.entrySet())) {
			Running running = entry.getValue();
			boolean done;
			this.replaying = true;
			try {
				done = running.playback().advance(this.tick,
					action -> runStep(running.playback().event(), action, running.source()));
			} catch (RuntimeException exception) {
				this.logger.warn("Cue '{}' stopped on an error.", entry.getKey(), exception);
				done = true;
			} finally {
				this.replaying = false;
			}
			if (done) {
				// Only if it was not restarted by one of its own steps.
				this.playing.remove(entry.getKey(), running);
			}
		}
	}

	private void runStep(ScriptifyEvent event, ScriptifyEvent.Action action, CommandSourceStack source) {
		EventActionHandler handler = this.actions.get(action.type());
		if (handler == null) {
			this.logger.warn("Event '{}' references unknown action '{}'.", event.id(), action.type());
			return;
		}
		handler.run(action, source);
	}

	// ---------------------------------------------------------------- editing

	/** Saves a new or edited cue and makes it the live copy. A cue already playing finishes as it was. */
	public void save(ScriptifyEvent event) {
		Path path = this.files.get(event.id());
		String fileName = path != null ? path.getFileName().toString() : event.id() + ".json";
		this.storage.saveObject(MODULE, fileName, event.toJson());
		this.files.putIfAbsent(event.id(), this.storage.root().resolve(MODULE).resolve(fileName));
		this.events.put(event.id(), event);
	}

	public boolean delete(String id) {
		if (this.events.remove(id) == null) {
			return false;
		}
		stop(id);
		this.recordings.values().removeIf(recording -> recording.cue().equals(id));
		Path path = this.files.remove(id);
		if (path != null) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException exception) {
				this.logger.warn("Could not delete cue file {}.", path, exception);
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- recording

	/** Starts turning every command this player runs into the cue's next step, creating the cue if need be. */
	public void startRecording(ServerPlayer player, String id) {
		if (!this.events.containsKey(id)) {
			save(new ScriptifyEvent(id, true, "manual", List.of()));
		}
		this.recordings.put(player.getUUID(), new Recording(id, this.tick));
	}

	/** @return the cue that was being recorded, or null if this player was not recording */
	public String stopRecording(ServerPlayer player) {
		Recording recording = this.recordings.remove(player.getUUID());
		return recording == null ? null : recording.cue();
	}

	public String recordingOf(ServerPlayer player) {
		Recording recording = this.recordings.get(player.getUUID());
		return recording == null ? null : recording.cue();
	}

	/**
	 * Called for every command a player runs. While they are recording it becomes the cue's next step,
	 * after a delay matching the time since the previous one. {@code /cue} itself is left out, since
	 * that is what drives the recording.
	 */
	public void onCommand(ServerPlayer player, String command) {
		if (this.replaying || this.recordings.isEmpty()) {
			return;
		}
		Recording recording = this.recordings.get(player.getUUID());
		if (recording == null) {
			return;
		}
		String line = command.startsWith("/") ? command.substring(1) : command;
		String root = line.split(" ", 2)[0].toLowerCase(Locale.ROOT);
		if (line.isBlank() || root.equals("cue")) {
			return;
		}
		ScriptifyEvent event = this.events.get(recording.cue());
		if (event == null) {
			this.recordings.remove(player.getUUID());
			return;
		}
		ScriptifyEvent recorded = event.withRecorded(this.tick - recording.lastTick(), line);
		save(recorded);
		this.recordings.put(player.getUUID(), new Recording(recording.cue(), this.tick));
		Msg.actionBar(player, "● Recording " + recording.cue() + " - step " + recorded.actions().size(),
			ChatFormatting.RED);
	}

	// ---------------------------------------------------------------- built-in steps

	private void registerBuiltInActions() {
		this.triggers.register("manual");
		this.triggers.register("player_join");
		// Waiting is the playback's job; the handler only exists so a delay is a known step.
		this.actions.register("delay", (action, source) -> true);
		this.actions.register("message", (action, source) -> {
			Component text = Component.literal(text(action, "text", "Scriptify event message."));
			// Straight to the player: the cue's source is silenced, which would swallow it.
			if (source.getEntity() instanceof ServerPlayer player) {
				player.sendSystemMessage(text);
			} else {
				source.getServer().sendSystemMessage(text);
			}
			return true;
		});
		this.actions.register("actionbar", (action, source) -> {
			if (source.getEntity() instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.literal(text(action, "text", "")), true);
			}
			return true;
		});
		this.actions.register("title", (action, source) -> {
			if (source.getEntity() instanceof ServerPlayer player) {
				player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(text(action, "text", ""))));
			}
			return true;
		});
		this.actions.register("command", (action, source) -> {
			String command = text(action, "command", "");
			if (!command.isBlank()) {
				source.getServer().getCommands().performPrefixedCommand(source, command);
			}
			return true;
		});
	}

	private static String text(ScriptifyEvent.Action action, String key, String fallback) {
		return action.data().has(key) ? action.data().get(key).getAsString() : fallback;
	}
}
