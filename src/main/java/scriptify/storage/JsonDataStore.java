package scriptify.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsonDataStore implements DataStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Logger LOGGER = LoggerFactory.getLogger("scriptify/storage");
	private final Path root;

	public JsonDataStore(MinecraftServer server, String directoryName) {
		this.root = resolveWithin(server.getWorldPath(LevelResource.ROOT), directoryName);
	}

	@Override
	public Path root() {
		return this.root;
	}

	@Override
	public JsonObject loadObject(String module, String fileName, Supplier<JsonObject> defaults) {
		Path path = resolveWithin(modulePath(module), fileName);
		if (!Files.exists(path)) {
			JsonObject value = defaults.get();
			saveObject(module, fileName, value);
			return value;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonObject value = GSON.fromJson(reader, JsonObject.class);
			if (value != null) {
				return value;
			}
			LOGGER.warn("JSON object at {} was empty; using defaults.", path);
		} catch (IOException | JsonParseException | IllegalStateException exception) {
			LOGGER.warn("Could not read JSON object at {}; using defaults.", path, exception);
		}
		return defaults.get();
	}

	@Override
	public void saveObject(String module, String fileName, JsonObject value) {
		Path path = resolveWithin(modulePath(module), fileName);
		JsonFile.write(GSON, path, value, LOGGER, "JSON object");
	}

	@Override
	public List<StoredJson> loadObjects(String module, String directory) {
		Path path = resolveWithin(modulePath(module), directory);
		List<StoredJson> objects = new ArrayList<>();
		if (!Files.isDirectory(path)) {
			return objects;
		}
		try (var stream = Files.list(path)) {
			stream.filter(Files::isRegularFile)
				.filter(file -> file.getFileName().toString().endsWith(".json"))
				.sorted()
				.forEach(file -> {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					JsonObject object = GSON.fromJson(reader, JsonObject.class);
					if (object != null) {
						objects.add(new StoredJson(file, object));
					}
				} catch (IOException | JsonParseException | IllegalStateException exception) {
					LOGGER.warn("Skipping unreadable JSON file {}.", file, exception);
				}
			});
		} catch (IOException exception) {
			LOGGER.warn("Could not list JSON directory {}.", path, exception);
		}
		return objects;
	}

	private Path modulePath(String module) {
		return resolveWithin(this.root, module);
	}

	/** Resolves a caller-provided storage segment without allowing it to escape its data root. */
	private static Path resolveWithin(Path parent, String segment) {
		Path base = parent.toAbsolutePath().normalize();
		// An empty directory deliberately means "the module root". Event definitions use that
		// convention, so rejecting it turned a normal reload into a server-start crash.
		if (segment == null || segment.isBlank()) {
			return base;
		}
		Path candidate = Path.of(segment);
		if (candidate.isAbsolute()) {
			throw new IllegalArgumentException("Storage path segment must be relative: " + segment);
		}
		Path resolved = base.resolve(candidate).normalize();
		if (!resolved.startsWith(base)) {
			throw new IllegalArgumentException("Storage path escapes its root: " + segment);
		}
		return resolved;
	}
}
