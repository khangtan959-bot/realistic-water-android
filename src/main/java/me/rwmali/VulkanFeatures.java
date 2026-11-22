package me.rwmali;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Recognition of Vulkan capabilities that were ADDED TO THE GAME BY OTHER MODS
 * instead of being present in the hardware driver.
 *
 * Why the addon needs this: many Mali phones ship drivers that only report
 * Vulkan 1.1, while Minecraft 26.x requires Vulkan 1.2 plus a set of modern
 * extensions. Compatibility mods - Vulky Enhanced and the Vulkan Extension
 * Emulator (VEE) - hook into Minecraft's Vulkan backend and add exactly the
 * missing pieces in software (dynamic rendering becomes classic render passes,
 * synchronization2 becomes old-style barriers, push descriptors become normal
 * descriptor sets, multi-draw gets fallbacks, and the Vulkan 1.1/1.2 version
 * gate gets bypassed when the rest is present).
 *
 * The official bridge 1.0.0 vetoes the Vulkan backend purely on the raw
 * driver version, before those mods can run. The addon's
 * VulkanBackendMaliMixin uses this class to give a second opinion in exactly
 * that situation: feature mod present AND enabled AND a Vulkan device exists
 * - then let the backend start and let the feature mod do its work.
 *
 * IMPORTANT, verified against both mods' real jars:
 * - Both mods only ever ADD features ON TOP of a driver that already exposes a
 *   Vulkan physical device. Neither one bundles a software Vulkan driver
 *   (no native libraries in their jars), so with no Vulkan driver loaded in the
 *   game process they can do nothing - the launcher's Vulkan driver option
 *   still has to be enabled first. Vulky's own page states it plainly: "It
 *   cannot add Vulkan support to hardware without a working Vulkan driver."
 * - Both expose the same reflective API: static canEmulateExtension(String),
 *   canEmulateFeature(String) and config() with a public "enabled" field, and
 *   both write config/<modid>.properties (verified: dev.vulky.Vulky with
 *   config/vulky.properties, com.muhibiscr1t1kal.vulkanextensionemulator.
 *   VulkanExtensionEmulator with config/vulkanextensionemulator.properties).
 * - VEE declares HARD dependencies on fabric-api, entity_model_features (EMF)
 *   and moreculling. If any of those is missing, Fabric refuses to load VEE at
 *   all - which looks exactly like "VEE does nothing". The addon therefore
 *   also scans the mods folder for their jars and reports them as
 *   "present but not loaded" instead of staying silent.
 *
 * Everything here is reflection-based and failure-tolerant: an unreadable mod
 * counts as "present, details unknown" and can never crash the game.
 */
public final class VulkanFeatures {

	/** A mod that is known to add Vulkan features to the game. */
	private record KnownMod(String modId, String displayName, String mainClass, String configFile, String needs) {
	}

	// Order = priority. Both mods expose the same API surface (Vulky is a
	// close relative of VEE), so one reflective path covers both.
	private static final KnownMod[] KNOWN_MODS = {
			new KnownMod("vulky", "Vulky Enhanced", "dev.vulky.Vulky", "vulky.properties",
					"fabric-lifecycle-events-v1 (part of Fabric API)"),
			new KnownMod("vulkanextensionemulator", "Vulkan Extension Emulator (VEE)",
					"com.muhibiscr1t1kal.vulkanextensionemulator.VulkanExtensionEmulator",
					"vulkanextensionemulator.properties",
					"Fabric API, Entity Model Features (EMF) and MoreCulling"),
	};

	/** Vulkan extensions the known mods can add in software (union of their own lists). */
	private static final String[] KNOWN_EXTENSIONS = {
			"VK_KHR_dynamic_rendering",
			"VK_KHR_synchronization2",
			"VK_KHR_push_descriptor",
			"VK_EXT_vertex_attribute_divisor",
			"VK_EXT_multi_draw"
	};

	/** Vulkan features the known mods can emulate in software (union of their own lists). */
	private static final String[] KNOWN_FEATURES = {
			"dynamicRendering",
			"synchronization2",
			"multiDraw",
			"multiDrawIndirect",
			"fillModeNonSolid",
			"vertexAttributeInstanceRateDivisor",
			"depthClamp",
			"shaderDrawParameters",
			"timelineSemaphore",
			"hostQueryReset"
	};

	/** One detected Vulkan feature mod. */
	private record ActiveMod(String modId, String displayName, String version, boolean enabled, List<String> adds,
					boolean fromConfigFile) {

		String title() {
			return displayName + (version == null || version.isBlank() || "?".equals(version) ? "" : " " + version)
					+ " [" + modId + "]";
		}
	}

	/** A Vulkan feature mod jar that sits in the mods folder but was not loaded. */
	public record UnloadedJar(String fileName, String modId, String displayName, String needs) {
	}

	private VulkanFeatures() {
	}

	/**
	 * True when at least one loaded mod is known (or registered) to add Vulkan
	 * features AND is not disabled by its own config. This is the "trust the
	 * mod-added features over the raw driver" signal.
	 */
	public static boolean active() {
		for (ActiveMod mod : detect()) {
			if (mod.enabled()) {
				return true;
			}
		}
		return false;
	}

	/** True when such a mod is merely loaded, even if its config disabled it. */
	public static boolean anyLoaded() {
		return !detect().isEmpty();
	}

	/**
	 * Human-readable summary for log lines and error messages.
	 * Empty string when no Vulkan feature mod is loaded.
	 */
	public static String describe() {
		List<ActiveMod> mods = detect();
		if (mods.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (ActiveMod mod : mods) {
			if (out.length() > 0) {
				out.append("; ");
			}
			out.append(mod.title());
			if (!mod.enabled()) {
				out.append(" (disabled in its own config - ignored)");
			} else if (mod.adds().isEmpty()) {
				out.append(" (declares the Vulkan 1.2 compatibility fallbacks; details not queryable yet)");
			} else {
				out.append(" (adds ").append(String.join(", ", mod.adds())).append(", plus the Vulkan 1.1/1.2 gate bypass)");
			}
		}
		return out.toString();
	}

	/**
	 * Vulkan feature mod jars found in the mods folder that Fabric did NOT load.
	 * The classic cause: VEE hard-depends on Fabric API, Entity Model Features
	 * and MoreCulling - missing any of them silently turns VEE off. This is
	 * reported separately so the player gets told why "nothing changed".
	 */
	public static List<UnloadedJar> unloadedJars() {
		List<UnloadedJar> found = new ArrayList<>();
		try {
			Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
			if (!Files.isDirectory(modsDir)) {
				return found;
			}
			try (var stream = Files.list(modsDir)) {
				stream.filter(p -> {
					String n = p.getFileName().toString().toLowerCase();
					return n.endsWith(".jar") && !n.endsWith(".disabled");
				}).forEach(p -> {
					String name = p.getFileName().toString();
					String lower = name.toLowerCase();
					for (KnownMod known : KNOWN_MODS) {
						if (lower.contains(known.modId()) && !isLoaded(known.modId())) {
							found.add(new UnloadedJar(name, known.modId(), known.displayName(), known.needs()));
							break;
						}
					}
				});
			}
		} catch (Throwable ignored) {
			// Mods dir unreadable: no report, never a crash.
		}
		return found;
	}

	/** Human-readable "why is my Vulky/VEE not working" text for the unloaded jars, or "". */
	public static String describeUnloaded() {
		List<UnloadedJar> jars = unloadedJars();
		if (jars.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (UnloadedJar jar : jars) {
			if (out.length() > 0) {
				out.append("; ");
			}
			out.append(jar.displayName()).append(" (").append(jar.fileName())
					.append(") is in the mods folder but was NOT loaded by Fabric - it additionally requires ")
					.append(jar.needs()).append(". Check latest.log for the exact reason");
		}
		return out.toString();
	}

	// =====================================================================
	// Detection
	// =====================================================================

	private static List<ActiveMod> detect() {
		List<ActiveMod> found = new ArrayList<>();
		for (KnownMod known : KNOWN_MODS) {
			if (isLoaded(known.modId())) {
				found.add(inspect(known));
			}
		}
		for (String extraId : extraIds()) {
			boolean already = false;
			for (KnownMod known : KNOWN_MODS) {
				if (known.modId().equals(extraId)) {
					already = true;
					break;
				}
			}
			if (!already && isLoaded(extraId)) {
				// User-registered mod: no known main class, trust its presence.
				found.add(new ActiveMod(extraId, extraId, versionOf(extraId), true, List.of(), false));
			}
		}
		return found;
	}

	private static boolean isLoaded(String modId) {
		try {
			return FabricLoader.getInstance().isModLoaded(modId);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Reads everything we dare to read from the mod: its version, whether its
	 * own config left it enabled, and which capabilities it currently claims
	 * to add. Two sources, in order:
	 * 1. the mod's own API (static config() + canEmulateExtension/Feature) -
	 *    verified present in Vulky 0.25.5 and VEE 0.0.4;
	 * 2. its config file (config/<modid>.properties, key "enabled") as a
	 *    fallback for future versions where the API moves.
	 * Every step is optional - a missing/renamed API just degrades the
	 * description, never the verdict.
	 */
	private static ActiveMod inspect(KnownMod known) {
		String version = versionOf(known.modId());
		boolean enabled = true; // presence counts; config says otherwise only when readable
		List<String> adds = List.of();
		boolean fromFile = false;
		try {
			Class<?> main = Class.forName(known.mainClass());
			// config().enabled - the mod leaves the Vulkan gate untouched when disabled.
			try {
				Object config = main.getMethod("config").invoke(null);
				if (config != null) {
					Object flag = config.getClass().getField("enabled").get(config);
					if (flag instanceof Boolean value) {
						enabled = value;
					}
				}
			} catch (Throwable ignored) {
				// Config not initialized (or renamed) at this point: keep "enabled".
			}
			// canEmulateExtension / canEmulateFeature - static queries over the
			// mod's own capability lists. Only meaningful once its config loaded.
			if (enabled) {
				List<String> claimed = new ArrayList<>();
				try {
					var ext = main.getMethod("canEmulateExtension", String.class);
					for (String name : KNOWN_EXTENSIONS) {
						if (Boolean.TRUE.equals(ext.invoke(null, name))) {
							claimed.add(name);
						}
					}
				} catch (Throwable ignored) {
				}
				try {
					var feature = main.getMethod("canEmulateFeature", String.class);
					for (String name : KNOWN_FEATURES) {
						if (Boolean.TRUE.equals(feature.invoke(null, name))) {
							claimed.add(name);
						}
					}
				} catch (Throwable ignored) {
				}
				if (!claimed.isEmpty()) {
					adds = List.copyOf(claimed);
				}
			}
		} catch (Throwable ignored) {
			// Main class unreadable (future version): fall through to the config file.
		}
		if (!enabled || adds.isEmpty()) {
			// Config-file fallback: the file is authoritative for "enabled"
			// even when the API was not reachable yet.
			Boolean fileEnabled = readEnabledFromFile(known.configFile());
			if (fileEnabled != null) {
				fromFile = true;
				enabled = fileEnabled;
			}
		}
		return new ActiveMod(known.modId(), known.displayName(), version, enabled, adds, fromFile);
	}

	/** Reads the "enabled" key from config/<file>; null when absent or unreadable. */
	private static Boolean readEnabledFromFile(String configFile) {
		try {
			Path file = FabricLoader.getInstance().getConfigDir().resolve(configFile);
			if (!Files.isReadable(file)) {
				return null; // no config yet = the mod's default (enabled)
			}
			Properties props = new Properties();
			try (Reader in = Files.newBufferedReader(file)) {
				props.load(in);
			}
			String value = props.getProperty("enabled");
			return value == null ? null : Boolean.parseBoolean(value.trim());
		} catch (Throwable t) {
			return null;
		}
	}

	private static String versionOf(String modId) {
		try {
			return FabricLoader.getInstance().getModContainer(modId)
					.map(c -> c.getMetadata().getVersion().getFriendlyString())
					.orElse("?");
		} catch (Throwable t) {
			return "?";
		}
	}

	// =====================================================================
	// User-registered extra mods
	// =====================================================================

	/**
	 * Mod ids of other Vulkan-feature mods the player wants the addon to
	 * recognise. Sources, both optional and combined:
	 * - the JVM property -Drwmali.vulkanFeatureMods=id1,id2
	 * - the addon setting maliVulkanFeatureMods in /water (saved to
	 *   realistic-water.properties)
	 */
	private static List<String> extraIds() {
		Set<String> ids = new LinkedHashSet<>();
		collect(System.getProperty("rwmali.vulkanFeatureMods", ""), ids);
		try {
			collect(MaliSettings.maliVulkanFeatureMods, ids);
		} catch (Throwable ignored) {
		}
		return List.copyOf(ids);
	}

	private static void collect(String csv, Set<String> out) {
		if (csv == null || csv.isBlank()) {
			return;
		}
		for (String part : csv.split("[,;\\s]+")) {
			String id = part.trim().toLowerCase();
			if (!id.isEmpty() && !id.equals("realistic-water") && !id.equals("realistic-water-android")
					&& !id.equals("realistic-water-android-mali")) {
				out.add(id);
			}
		}
	}
}
