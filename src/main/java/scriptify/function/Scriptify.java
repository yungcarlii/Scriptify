package scriptify.function;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scriptify.core.ScriptifyBootstrap;

public final class Scriptify implements ModInitializer {
	public static final String MOD_ID = "scriptify";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ScriptifyBootstrap.initialize(LOGGER);
	}
}
