package me.rwbridge;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;

final class FabricDirs {
	private FabricDirs() {
	}

	static Path config() {
		return FabricLoader.getInstance().getConfigDir();
	}
}
