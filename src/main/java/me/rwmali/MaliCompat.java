package me.rwmali;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core of the Realistic Water Android - Mali GPU addon (1.0.0).
 *
 * This is an ADD-ON for the OFFICIAL Realistic Water Android bridge
 * ("realistic-water-android", github.com/khangtan959-bot/realistic-water-android),
 * not a replacement: the bridge stays installed and keeps doing what it does
 * (backend safety nets, SPIR-V/pipeline caches, mobile settings). The addon
 * only adds the layer the bridge itself does not ship - support for ARM Mali
 * phone GPUs:
 *
 *  1. No-subgroup shader variants (ShaderVariants). Many Mali drivers report
 *     "Vulkan 1.2/1.3" while their subgroup operations are not exposed for
 *     the compute stage (typical on Bifrost, e.g. Mali-G52 MC2 on a Redmi
 *     15C / Helio G81 Ultra). Realistic Water 1.4.1's particle compute
 *     shaders need subgroupAdd/Min/Max/Elect/BroadcastFirst, so every water
 *     pipeline fails with "Realistic Water failed" and the mod then disables
 *     its simulation - which is why water could not even be PLACED any more.
 *     The addon swaps in hash-verified copies of the 8 affected shader files
 *     with identical physics (group ops become plain atomics).
 *
 *  2. Push-descriptor fallback (PushDescriptors). Realistic Water binds every
 *     shader through vkCmdPushDescriptorSetKHR; Mali drivers do not expose
 *     VK_KHR_push_descriptor, and Vulky/VEE claim the extension to Minecraft
 *     but strip it from the real device, so the call dies with a bare
 *     NullPointerException ("Realistic Water failed: null"). The addon binds
 *     the very same descriptor writes through its own descriptor pool.
 *
 *  3. Vulkan backend second opinion (VulkanBackendMaliMixin). The official
 *     bridge vetoes Minecraft's Vulkan backend when the raw driver reports
 *     below Vulkan 1.2. On Mali phones with such drivers that veto fires
 *     BEFORE Vulkan feature mods (Vulky Enhanced, VEE) get their chance, so
 *     the addon re-allows the backend when such a mod is loaded and enabled
 *     and a Vulkan device exists - exactly the "trust mod-added features"
 *     behaviour of newer bridge builds, layered on top without touching the
 *     official bridge's classes.
 *
 *  4. One-file diagnostic report (MaliReport, config/rw-mali-report.txt):
 *     probe results, feature-mod detection, and every per-shader variant
 *     decision, so a player can send ONE file when asking for help.
 *
 * Everything is Android-only and dormant on desktop; every failure path
 * degrades to the unmodified behaviour of the bridge + the mod itself.
 */
public final class MaliCompat {

        private static final Logger LOG = LoggerFactory.getLogger("RWMali");

        /** Keep in sync with gradle.properties (version) - logged so player logs identify the build. */
        public static final String ADDON_VERSION = "1.0.0+rw1.4.1";

        private static final String BRIDGE_ID = "realistic-water-android";
        private static final String RW_ID = "realistic-water";

        private static Boolean androidCache;
        private static boolean initialized;
        private static volatile MaliProbe.Result probeResult;

        private MaliCompat() {
        }

        public static void init() {
                if (initialized) {
                        return;
                }
                initialized = true;
                try {
                        if (!isAndroid()) {
                                LOG.info("Desktop environment detected - the Mali GPU addon stays dormant (Android phones only)."
                                        + " It is an add-on for the Realistic Water Android bridge and is not needed on PC.");
                                return;
                        }
                        probeResult = MaliProbe.probe();
                        String bridge = versionOf(BRIDGE_ID);
                        String rw = versionOf(RW_ID);
                        LOG.info("Android detected; RW Mali addon {} - bridge {} - Realistic Water {} - GPU: {}",
                                        ADDON_VERSION,
                                        bridge,
                                        rw,
                                        probeResult.description());
                        MaliReport.start(
                                        "addon: Realistic Water Android - Mali GPU support " + ADDON_VERSION,
                                        "bridge (realistic-water-android): " + bridge,
                                        "realistic-water: " + rw,
                                        "platform: Android",
                                        "gpu: " + probeResult.description(),
                                        "push-descriptor (raw driver): " + probeResult.pushDescriptor(),
                                        "subgroups: " + probeResult.subgroupLine());
                        if (bridge.equals("missing")) {
                                LOG.warn("The OFFICIAL Realistic Water Android bridge was not found - this addon needs it."
                                                + " Install it from https://github.com/khangtan959-bot/realistic-water-android"
                                                + " (the addon extends that bridge, it does not replace it).");
                                MaliReport.note("bridge", "MISSING - the addon requires the official bridge!");
                        }
                        if (probeResult.hasDevice()) {
                                LOG.info("Driver subgroup support: {}", probeResult.subgroupLine());
                                if (probeResult.pushDescriptor()) {
                                        LOG.info("Driver has native VK_KHR_push_descriptor - Realistic Water's shader bindings stay untouched");
                                } else {
                                        LOG.info("Driver has NO VK_KHR_push_descriptor (every Mali driver so far) - the addon's"
                                                        + " push-descriptor fallback will bind the water shaders through its own descriptor"
                                                        + " pool instead of crashing with 'Realistic Water failed: null'"
                                                        + " (toggle: /water set maliPushDescriptors false)");
                                }
                        }
                        if (VulkanFeatures.anyLoaded()) {
                                LOG.info("Vulkan feature mods present: {} - they add missing Vulkan features in software,"
                                                + " and the addon honours them for the backend verdict", VulkanFeatures.describe());
                        }
                        String unloaded = VulkanFeatures.describeUnloaded();
                        if (!unloaded.isBlank()) {
                                LOG.warn("Vulkan feature mod jars found but NOT loaded: {}", unloaded);
                                MaliReport.note("vulkan-feature-mods", "unloaded jars: " + unloaded);
                        }
                        // NOTE: the subgroup-compat decision itself is made LAZILY (on the first
                        // water shader load, inside ShaderVariants), because the player's saved
                        // /water settings (realistic-water.properties) are only applied after
                        // client init - an eager decision here could ignore a saved
                        // maliSubgroupCompat value. The probe verdict is already logged above.
                } catch (Throwable t) {
                        LOG.warn("RW Mali addon init problem, continuing without the Mali compatibility layer: {}", t.toString());
                }
        }

        /** The cached probe result (probing lazily if init has not run yet). Never throws. */
        public static MaliProbe.Result probe() {
                MaliProbe.Result r = probeResult;
                if (r == null) {
                        try {
                                r = MaliProbe.probe();
                                probeResult = r;
                        } catch (Throwable t) {
                                return null;
                        }
                }
                return r;
        }

        /**
         * Android check. Delegates to the official bridge's own detection (the addon
         * is guaranteed to run after the bridge by its fabric.mod.json dependency);
         * only if the bridge class is unreachable does the addon fall back to its own
         * identical check, so a missing bridge degrades loudly elsewhere but never
         * crashes here.
         */
        public static boolean isAndroid() {
                Boolean cached = androidCache;
                if (cached != null) {
                        return cached;
                }
                boolean android;
                try {
                        android = me.rwbridge.MobileBridge.isAndroid();
                } catch (Throwable t) {
                        android = detectAndroid();
                }
                androidCache = android;
                return android;
        }

        private static boolean detectAndroid() {
                try {
                        String os = System.getProperty("os.name", "").toLowerCase();
                        if (os.contains("android")) {
                                return true;
                        }
                        if (System.getenv("ANDROID_ROOT") != null || System.getenv("ANDROID_DATA") != null) {
                                return true;
                        }
                        return Files.exists(Path.of("/system/build.prop"));
                } catch (Throwable t) {
                        return false;
                }
        }

        /** Friendly version of a loaded mod ("missing" when not installed). Never throws. */
        public static String versionOf(String modId) {
                try {
                        return FabricLoader.getInstance().getModContainer(modId)
                                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                                        .orElse("missing");
                } catch (Throwable t) {
                        return "?";
                }
        }

        /** Log helpers for the mixins (mixin classes avoid their own static state). */
        public static void info(String message) {
                LOG.info(message);
        }

        public static void warn(String message) {
                LOG.warn(message);
        }
}
