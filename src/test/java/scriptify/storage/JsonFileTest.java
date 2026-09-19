package scriptify.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class JsonFileTest {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	@TempDir
	Path temporaryDirectory;

	@Test
	void replacesExistingJsonOnlyAfterTheNewDocumentIsWritten() throws Exception {
		Path file = this.temporaryDirectory.resolve("state.json");
		JsonObject first = new JsonObject();
		first.addProperty("revision", 1);
		assertTrue(JsonFile.write(GSON, file, first, LoggerFactory.getLogger("test"), "test state"));

		JsonObject replacement = new JsonObject();
		replacement.addProperty("revision", 2);
		replacement.addProperty("enabled", true);
		assertTrue(JsonFile.write(GSON, file, replacement, LoggerFactory.getLogger("test"), "test state"));

		JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		assertEquals(2, saved.get("revision").getAsInt());
		assertTrue(saved.get("enabled").getAsBoolean());
	}

	@Test
	void reportsFailureWhenTheParentCannotBeCreated() throws Exception {
		Path parentFile = this.temporaryDirectory.resolve("not-a-directory");
		Files.writeString(parentFile, "occupied");

		assertFalse(JsonFile.write(GSON, parentFile.resolve("state.json"), new JsonObject(),
			LoggerFactory.getLogger("test"), "test state"));
		assertEquals("occupied", Files.readString(parentFile));
	}
}
