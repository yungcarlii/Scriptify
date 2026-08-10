package scriptify.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class JsonDataStore implements DataStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path root;

	public JsonDataStore(MinecraftServer server, String directoryName) {
		this.root = server.getWorldPath(LevelResource.ROOT).resolve(directoryName);
	}

	@Override
	public Path root() {
		return this.root;
	}

	@Override
	public JsonObject loadObject(String module, String fileName, Supplier<JsonObject> defaults) {
		Path path = modulePath(module).resolve(fileName);
		if (!Files.exists(path)) {
			JsonObject value = defaults.get();
			saveObject(module, fileName, value);
			return value;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, JsonObject.class);
		} catch (IOException | JsonParseException | IllegalStateException exception) {
			return defaults.get();
		}
	}

	@Override
	public void saveObject(String module, String fileName, JsonObject value) {
		Path path = modulePath(module).resolve(fileName);
		try {
			Files.createDirectories(path.getParent());
			Path temp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(value, writer);
			}
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicFailure) {
			try {
				Path temp = path.resolveSibling(path.getFileName() + ".tmp");
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
			}
		}
	}

	@Override
	public List<StoredJson> loadObjects(String module, String directory) {
		Path path = modulePath(module).resolve(directory);
		List<StoredJson> objects = new ArrayList<>();
		if (!Files.isDirectory(path)) {
			return objects;
		}
		try (var stream = Files.list(path)) {
			stream.filter(file -> file.getFileName().toString().endsWith(".json")).forEach(file -> {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					JsonObject object = GSON.fromJson(reader, JsonObject.class);
					if (object != null) {
						objects.add(new StoredJson(file, object));
					}
				} catch (IOException | JsonParseException | IllegalStateException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
		return objects;
	}

	private Path modulePath(String module) {
		return this.root.resolve(module);
	}
}
