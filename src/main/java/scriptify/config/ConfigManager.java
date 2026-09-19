package scriptify.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import scriptify.storage.JsonFile;

public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path path;
	private final Logger logger;
	private ScriptifyConfig config = new ScriptifyConfig();

	public ConfigManager(Path path, Logger logger) {
		this.path = path;
		this.logger = logger;
	}

	public ScriptifyConfig get() {
		return this.config;
	}

	public Path path() {
		return this.path;
	}

	public void load() {
		if (!Files.exists(this.path)) {
			this.config = new ScriptifyConfig();
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(this.path, StandardCharsets.UTF_8)) {
			ScriptifyConfig loaded = GSON.fromJson(reader, ScriptifyConfig.class);
			this.config = loaded == null ? new ScriptifyConfig() : loaded;
			this.config.validate();
			save();
		} catch (IOException | JsonParseException | IllegalStateException exception) {
			this.logger.error("Failed to load Scriptify config at {}, using defaults.", this.path, exception);
			this.config = new ScriptifyConfig();
			save();
		}
	}


	public void save() {
		this.config.validate();
		JsonFile.write(GSON, this.path, this.config, this.logger, "Scriptify config");
	}
}
