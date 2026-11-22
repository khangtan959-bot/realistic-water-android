package me.rwmali;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import net.fabricmc.loader.api.FabricLoader;

/**
 * One-file diagnostic report: config/rw-mali-report.txt.
 *
 * Every addon decision that matters for support - the raw Vulkan probe (device,
 * api version, subgroup capabilities, push-descriptor extension), the Vulkan
 * feature mods, the subgroup-compat verdict and the per-shader variant
 * outcomes - lands in this single file so a player can send ONE file instead
 * of digging through latest.log. It is rewritten fresh at every game start
 * and appended to as decisions happen; every write is best-effort and can
 * never throw into the game.
 */
final class MaliReport {

	private static Path file;
	private static boolean broken;

	private MaliReport() {
	}

	/** Starts a fresh report for this session (called from MaliCompat.init()). */
	static void start(String... lines) {
		try {
			file = FabricLoader.getInstance().getConfigDir().resolve("rw-mali-report.txt");
			Files.createDirectories(file.getParent());
			StringBuilder out = new StringBuilder();
			out.append("Realistic Water Android - Mali GPU addon - diagnostic report").append('\n');
			out.append("written: ").append(LocalDateTime.now().withNano(0)).append('\n');
			out.append("send this file together with latest.log when reporting problems").append('\n');
			for (String line : lines) {
				out.append(line).append('\n');
			}
			Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
		} catch (Throwable t) {
			broken = true;
		}
	}

	/** Appends one "key: value" line; failures are silently dropped. */
	static void note(String key, Object value) {
		try {
			if (file == null || broken) {
				return;
			}
			Files.writeString(file, key + ": " + value + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException retryable) {
			// A locked/read-only file just disables the report; never surface this.
		}
	}
}
