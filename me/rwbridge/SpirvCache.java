package me.rwbridge;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.io.IOException;
import me.skaffy.client.vk.Shaders;
import me.skaffy.client.vk.Vk;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disk + memory cache for the SPIR-V modules Realistic Water compiles with shaderc.
 *
 * The mod compiles hundreds of shaders the first time water appears, which is a long
 * stutter on phones. The cache stores the compiled bytes keyed by the fully resolved
 * shader source, so after the first session the modules are created without shaderc.
 *
 * 2.1.0: the disk writes moved to a single background thread with a bounded queue.
 * Writing the cache files used to happen on the render thread, right in the middle of
 * that first-water stutter - now the render thread only hands the bytes over and moves
 * on. The cached SPIR-V is byte-for-byte what shaderc produced, so rendering is exactly
 * the same either way; if the queue overflows or the game exits early, entries are
 * simply recompiled next time (a cache miss, never a wrong result).
 */
public final class SpirvCache {
	private static final Logger LOG = LoggerFactory.getLogger("RWBridge");
	private static final Map<String, byte[]> MEMORY = new ConcurrentHashMap<>();
	private static final int MAX_FILES = 1600;
	private static Path dir;
	private static boolean ready;

	/** Single writer thread; drops the oldest queued write instead of ever blocking the caller. */
	private static final ExecutorService DISK = new ThreadPoolExecutor(
			1, 1, 60L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(256),
			runnable -> {
				Thread thread = new Thread(runnable, "RWBridge SPIR-V writer");
				thread.setDaemon(true);
				return thread;
			},
			new ThreadPoolExecutor.DiscardOldestPolicy());

	private SpirvCache() {
	}

	public static void prepare() {
		try {
			dir = FabricDirs.config().resolve("rwbridge-spirv");
			Files.createDirectories(dir);
			try (var stream = Files.list(dir)) {
				List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".spv")).toList();
				if (files.size() > MAX_FILES) {
					// Drop the oldest files so the cache can't grow forever.
					List<Path> oldest = files.stream()
									.sorted(Comparator.comparingLong(p -> lastModified(p)))
									.limit(files.size() - MAX_FILES / 2)
									.toList();
					for (Path p : oldest) {
						try {
							Files.deleteIfExists(p);
						} catch (IOException ignored) {
						}
					}
					LOG.info("SPIR-V cache trimmed to {} entries", MAX_FILES / 2);
				}
			}
			ready = true;
			LOG.info("SPIR-V shader cache ready ({} entries)", countFiles());
		} catch (Throwable t) {
			ready = false;
			LOG.info("SPIR-V cache unavailable ({}), shaders will compile as usual", t.toString());
		}
	}

	public static boolean enabled() {
		return ready && MobileBridge.isAndroid() && MobileBridge.spirvCacheOn();
	}

	/**
	 * Builds the cache key from the exact source the mod will compile: the shader file
	 * with the defines prepended and all includes expanded, exactly like
	 * Shaders.module() does it, plus the RW version so updates can't reuse stale modules.
	 */
	public static String key(String name, int kind, Map<String, String> defines) {
		try {
			String source = Shaders.load(name);
			int nl = source.indexOf('\n');
			StringBuilder head = new StringBuilder(source.substring(0, nl + 1));
			for (Map.Entry<String, String> e : new TreeMap<>(defines).entrySet()) {
				head.append("#define ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
			}
			String full = preprocess(head + source.substring(nl + 1), new java.util.HashSet<>());
			return sha256("rwbridge-v1|" + MobileBridge.rwVersion() + "|vulkan1.2|" + kind + "|" + full);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Mirrors Shaders.preprocess(): expands #include "file" lines once per file name. */
	private static String preprocess(String source, Set<String> seen) {
		StringBuilder out = new StringBuilder();
		for (String line : source.split("\n", -1)) {
			String trimmed = line.trim();
			if (trimmed.startsWith("#include")) {
				int a = trimmed.indexOf('"');
				int b = trimmed.lastIndexOf('"');
				String file = trimmed.substring(a + 1, b);
				if (seen.add(file)) {
					out.append(preprocess(Shaders.load(file), seen)).append('\n');
				}
			} else {
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	/** Returns a ready VkShaderModule for a cache hit, or null on any miss/problem. */
	public static Long tryCreateModule(String key) {
		if (key == null || !enabled()) {
			return null;
		}
		byte[] spv = MEMORY.get(key);
		if (spv == null) {
			spv = readDisk(key);
			if (spv == null) {
				return null;
			}
			MEMORY.put(key, spv);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var code = stack.malloc(spv.length);
			code.put(spv);
			code.flip();
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
			LongBuffer pModule = stack.mallocLong(1);
			Vk.check(VK10.vkCreateShaderModule(Vk.vk(), info, null, pModule), "rwbridge cached shader module");
			return pModule.get(0);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Stores the SPIR-V bytes that the mod just compiled, before vkCreateShaderModule runs. */
	public static void capture(String key, VkShaderModuleCreateInfo info) {
		if (key == null || !enabled() || info == null) {
			return;
		}
		try {
			var code = info.pCode();
			if (code == null) {
				return;
			}
			// duplicate() so the original buffer's position is untouched.
			var dup = code.duplicate();
			byte[] spv = new byte[dup.remaining()];
			dup.get(spv);
			MEMORY.put(key, spv);
			writeDisk(key, spv);
		} catch (Throwable ignored) {
		}
	}

	private static byte[] readDisk(String key) {
		try {
			Path file = dir.resolve(key + ".spv");
			if (Files.exists(file)) {
				return Files.readAllBytes(file);
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	/** 2.1.0: runs on the background writer thread; never blocks or fails the render thread. */
	private static void writeDisk(String key, byte[] spv) {
		try {
			DISK.execute(() -> {
				try {
					Files.write(dir.resolve(key + ".spv"), spv);
				} catch (Throwable ignored) {
				}
			});
		} catch (Throwable ignored) {
		}
	}

	private static long countFiles() {
		try (var stream = Files.list(dir)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".spv")).count();
		} catch (Throwable t) {
			return -1;
		}
	}

	private static long lastModified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	private static String sha256(String data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Throwable t) {
			return Integer.toHexString(data.hashCode()) + Integer.toHexString(data.length());
		}
	}
}
