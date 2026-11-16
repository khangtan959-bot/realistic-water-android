package me.rwbridge;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.io.Reader;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterSettingsInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core of the Realistic Water Android Bridge.
 *
 * The bridge never modifies Realistic Water's jar. Everything it does goes through
 * defensive mixins (every injector uses require = 0 so a mismatch is a no-op, never a
 * crash) and through the mod's own reflection-based settings system.
 *
 * Philosophy since 2.1.0: "optimize only, change nothing". With mobilePreset = 0 (the
 * default) the bridge leaves every water setting at the mod's own defaults, so the
 * water looks and behaves exactly like the plain Realistic Water mod; the presets stay
 * available purely as opt-in profiles. The always-on work is lossless only: the SPIR-V
 * shader cache (async disk writes), the persistent Vulkan pipeline cache (3.0.0),
 * frame stats logging (3.1.0) and the startup safety nets.
 */
public final class MobileBridge {
        private static final Logger LOG = LoggerFactory.getLogger("RWBridge");

        private static final String RW_ID = "realistic-water";
        private static final String PATCHED_RW_VERSION = "1.3.1";

        private static Boolean androidCache;
        private static boolean initialized;
        private static String rwVersion = "?";
        private static boolean bytecodePatches;
        private static boolean loadingSettings;
        private static boolean warnedNoVulkan;
        private static boolean tunedDownAnnounced;
        private static volatile VulkanProbe.Result probeResult;

        // ---- auto-tune state ----
        private static long lastFrameNanos;
        private static double frameEmaMs = -1.0;
        private static long nextCheckMs;
        private static long cooldownUntilMs;
        private static int tuneLevel;
        private static int goodWindows;

        // ---- water frame stats (3.1.0) ----
        private static long statsFrameStartNanos;
        private static long statsPrevStartNanos;
        private static double waterEmaMs = -1.0;
        private static double wholeEmaMs = -1.0;
        private static long nextStatsLogMs;

        private MobileBridge() {
        }

        // =====================================================================
        // Detection
        // =====================================================================

        public static boolean isAndroid() {
                Boolean cached = androidCache;
                if (cached == null) {
                        cached = detectAndroid();
                        androidCache = cached;
                }
                return cached;
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

        /**
         * Default for the mobilePreset setting; safe to call during WaterSettings' class init.
         * Since 2.1.0: 0 everywhere - Android included. The bridge must not change any
         * water setting unless the player explicitly picks a preset.
         */
        public static int defaultPreset() {
                return 0;
        }

        public static void init() {
                if (initialized) {
                        return;
                }
                initialized = true;
                try {
                        if (!isAndroid()) {
                                LOG.info("Desktop environment detected - the Android bridge stays dormant (no behaviour changes)");
                                return;
                        }
                        rwVersion = FabricLoader.getInstance().getModContainer(RW_ID)
                                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                                        .orElse("missing");
                        // Bytecode-level patches are only enabled on the exact version they were verified against.
                        bytecodePatches = PATCHED_RW_VERSION.equals(rwVersion);
                        probeResult = VulkanProbe.probe();
                        String gpu = probeResult.description();
                        LOG.info("Android detected; Realistic Water {} - mobile profile {} - GPU: {}",
                                        rwVersion,
                                        bytecodePatches ? "fully active" : "partially active (bytecode patches off for this RW version)",
                                        gpu);
                        int preset = configPreset();
                        LOG.info("Mobile preset: {} - water settings stay exactly at the mod's own defaults until you pick a profile."
                                        + " Tune it in-game with /water set mobilePreset 0..3 (0=off 1=low 2=balanced 3=high);"
                                        + " every bridge setting lives under the 'Android bridge' category of /water", presetName(preset));
                        SpirvCache.prepare();
                } catch (Throwable t) {
                        LOG.warn("Realistic Water Android Bridge init problem, continuing without mobile patches: {}", t.toString());
                }
        }

        private static String presetName(int p) {
                return switch (p) {
                        case 1 -> "LOW (maximum FPS)";
                        case 2 -> "BALANCED";
                        case 3 -> "HIGH (mod defaults)";
                        default -> "OFF (mod defaults)";
                };
        }

        // =====================================================================
        // Bridge settings (registered into WaterSettings by WaterSettingsMixin)
        // =====================================================================

        /** Called from WaterSettingsMixin so the bridge settings appear in /water and the config file. */
        public static void addBridgeSettings(Map<String, Field> fields) {
                try {
                        MobileSettings.collect(fields);
                } catch (Throwable ignored) {
                }
        }

        public static int preset() {
                return MobileSettings.mobilePreset;
        }

        public static boolean halfGbuffer() {
                return MobileSettings.mobileHalfGbuffer;
        }

        public static int maxRegions() {
                return MobileSettings.mobileMaxRegions;
        }

        public static boolean spirvCacheOn() {
                return MobileSettings.mobileSpirvCache;
        }

        public static boolean pipelineCacheOn() {
                return MobileSettings.mobilePipelineCache;
        }

        /**
         * VkPipelineCache handle for the pipeline-creation redirects (0 = none).
         * Never throws - a broken cache path degrades to the original behaviour.
         */
        public static long pipelineCacheHandle() {
                try {
                        return PipelineCache.get();
                } catch (Throwable t) {
                        return 0L;
                }
        }

        public static boolean autoTuneOn() {
                return MobileSettings.mobileAutoTune;
        }

        /**
         * Verdict for the VulkanBackend safety-net mixin (VulkanBackendMixin).
         * 0 = no opinion (run Minecraft's own check), 1 = Vulkan backend usable,
         * 2 = Vulkan backend not usable on this device.
         */
        public static int vulkanVerdict() {
                VulkanProbe.Result r = ensureProbed();
                if (r == null) {
                        return 0;
                }
                return r.usable() ? 1 : 2;
        }

        /** Human-readable GPU/Vulkan summary for log lines and error messages. */
        public static String gpuDescription() {
                VulkanProbe.Result r = ensureProbed();
                return r == null ? "unknown GPU" : r.description();
        }

        private static VulkanProbe.Result ensureProbed() {
                VulkanProbe.Result r = probeResult;
                if (r == null) {
                        // init() normally ran at client init already; this is a lazy fallback
                        // (e.g. entrypoint ordering). The probe never throws and never
                        // unloads the shared Vulkan loader, so this is safe to call late.
                        try {
                                r = VulkanProbe.probe();
                                probeResult = r;
                        } catch (Throwable t) {
                                return null;
                        }
                }
                return r;
        }

        public static String rwVersion() {
                return rwVersion;
        }

        /** Log helpers for the mixins (mixin classes avoid their own static state). */
        public static void info(String message) {
                LOG.info(message);
        }

        public static void warn(String message) {
                LOG.warn(message);
        }

        /** Reads mobilePreset straight from the config file so it can drive the preset pass in load(). */
        private static int configPreset() {
                try {
                        Path file = FabricLoader.getInstance().getConfigDir().resolve("realistic-water.properties");
                        if (Files.exists(file)) {
                                Properties props = new Properties();
                                try (Reader in = Files.newBufferedReader(file)) {
                                        props.load(in);
                                }
                                String value = props.getProperty("mobilePreset");
                                if (value != null && !value.isBlank()) {
                                        return Math.max(0, Math.min(3, Integer.parseInt(value.trim())));
                                }
                        }
                } catch (Throwable ignored) {
                }
                return defaultPreset();
        }

        // =====================================================================
        // Mobile preset
        // =====================================================================

        private static final String[] MANAGED = {
                        "resolution", "boxMargin", "maxStepsPerFrame", "renderMaxStepsPerFrame", "cgIterations", "mgCoarseSweeps",
                        "solverBottomCells", "solverSmallCells", "whitewater", "wwCellLimit", "wwMaxPerParticle", "surfaceSubdivision",
                        "sandResolution", "sandMaxStepsPerFrame", "leafResolution", "leafMaxStepsPerFrame", "kelpRadius",
                        "rainRadius", "rainDensity", "oceanWhitecapRadius"
        };

        private static final String[][] PRESET_LOW = {
                        {"resolution", "4"}, {"boxMargin", "4"}, {"maxStepsPerFrame", "2"}, {"renderMaxStepsPerFrame", "8"},
                        {"cgIterations", "3"}, {"mgCoarseSweeps", "6"}, {"solverBottomCells", "2048"}, {"solverSmallCells", "8192"},
                        {"whitewater", "false"}, {"wwCellLimit", "8"}, {"wwMaxPerParticle", "2"}, {"surfaceSubdivision", "1"},
                        {"sandResolution", "6"}, {"sandMaxStepsPerFrame", "6"}, {"leafResolution", "4"}, {"leafMaxStepsPerFrame", "3"},
                        {"kelpRadius", "24"}, {"rainRadius", "16"}, {"rainDensity", "4"}, {"oceanWhitecapRadius", "32"}
        };

        private static final String[][] PRESET_BALANCED = {
                        {"resolution", "5"}, {"boxMargin", "5"}, {"maxStepsPerFrame", "3"}, {"renderMaxStepsPerFrame", "16"},
                        {"cgIterations", "4"}, {"mgCoarseSweeps", "8"}, {"solverBottomCells", "4096"}, {"solverSmallCells", "12000"},
                        {"sandResolution", "8"}, {"sandMaxStepsPerFrame", "8"}, {"leafResolution", "6"}, {"leafMaxStepsPerFrame", "4"},
                        {"kelpRadius", "32"}, {"rainRadius", "24"}, {"rainDensity", "6"}
        };

        /** Called at HEAD of WaterSettings.load(): apply the preset, then the config file overrides it. */
        public static void onLoadSettings() {
                if (!isAndroid()) {
                        return;
                }
                loadingSettings = true;
                try {
                        int p = configPreset();
                        if (p > 0) {
                                applyPreset(p);
                                LOG.info("Applied mobile preset {} before loading the config file (saved values still win)", presetName(p));
                        } else {
                                LOG.info("Mobile preset is off - every Realistic Water setting stays at the mod's own defaults");
                        }
                } catch (Throwable t) {
                        LOG.warn("Couldn't apply the mobile preset: {}", t.toString());
                }
        }

        /** Called at RETURN of WaterSettings.load(). */
        public static void onLoadSettingsDone() {
                loadingSettings = false;
        }

        /** Called at RETURN of WaterSettings.set(): re-apply when the user changes mobilePreset in-game. */
        public static void onSettingSet(String name) {
                if (loadingSettings || !isAndroid() || !"mobilePreset".equals(name)) {
                        return;
                }
                try {
                        applyPreset(preset());
                        WaterSettings.save();
                        LOG.info("Mobile preset re-applied ({})", presetName(preset()));
                } catch (Throwable t) {
                        LOG.warn("Couldn't re-apply the mobile preset: {}", t.toString());
                }
        }

        private static void applyPreset(int preset) {
                for (String name : MANAGED) {
                        resetQuiet(name);
                }
                if (preset == 1) {
                        for (String[] pair : PRESET_LOW) {
                                setQuiet(pair[0], pair[1]);
                        }
                } else if (preset == 2) {
                        for (String[] pair : PRESET_BALANCED) {
                                setQuiet(pair[0], pair[1]);
                        }
                }
                // preset 3 (HIGH) and 0 (OFF) keep the mod's own defaults.
        }

        private static void setQuiet(String name, String value) {
                try {
                        WaterSettings.set(name, value);
                } catch (Throwable ignored) {
                }
        }

        private static void resetQuiet(String name) {
                try {
                        WaterSettings.reset(name);
                } catch (Throwable ignored) {
                }
        }

        // =====================================================================
        // Runtime gates for the bytecode patches
        // =====================================================================

        public static final int RGBA32F = 109; // VK_FORMAT_R32G32B32A32_SFLOAT
        public static final int RGBA16F = 97;  // VK_FORMAT_R16G16B16A16_SFLOAT

        /**
         * Maps the water g-buffer format RGBA32F -> RGBA16F on mobile when enabled.
         * Value-filtered: any other format (and any other GraphicsProgram.State or VkTex
         * call site) passes through untouched, which keeps the patch safe even if the mod
         * adds more render targets later.
         */
        public static int mapFormat(int format) {
                if (format != RGBA32F || !isAndroid() || !bytecodePatches || !halfGbuffer()) {
                        return format;
                }
                return RGBA16F;
        }

        /**
         * Caps the number of simultaneously simulated water regions (original max 16).
         * Only active while a mobile preset is chosen (preset 0 = fully vanilla region
         * behaviour, exactly like the plain mod).
         */
        public static int capRegions(int original) {
                if (original != 16 || !isAndroid() || !bytecodePatches || preset() <= 0) {
                        return original;
                }
                return Math.max(1, Math.min(16, maxRegions()));
        }

        // =====================================================================
        // Clearer "needs Vulkan" message on Android
        // =====================================================================

        public static void vulkanReadyAndroid(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
                if (!isAndroid()) {
                        return;
                }
                boolean ready;
                try {
                        ready = me.skaffy.client.vk.Vk.tryDevice() != null;
                } catch (Throwable t) {
                        ready = false;
                }
                if (ready) {
                        cir.setReturnValue(true);
                        return;
                }
                if (!warnedNoVulkan) {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                                warnedNoVulkan = true;
                                mc.player.sendSystemMessage(Component.literal(
                                                "Realistic Water needs Minecraft to actually run on Vulkan, but no Vulkan device was found. "
                                                                + "Keep 'Prefer Vulkan' selected in Video Settings (that is what turns MC's own Vulkan renderer on); "
                                                                + "the launcher renderer (MobileGlues etc.) is only the OpenGL fallback and can never run this mod. "
                                                                + "Minecraft silently falls back to OpenGL when its Vulkan init fails - on phones that almost always means "
                                                                + "the GPU driver is below Vulkan 1.2, which MC 26.2+ requires. Check with the DevCheck app or press F3: "
                                                                + "if the graphics API says OpenGL, this device cannot run MC's Vulkan renderer. Adreno phones can install "
                                                                + "the Turnip driver to fix it; Mali phones have no equivalent."));
                        }
                }
                cir.setReturnValue(false);
        }

        // =====================================================================
        // Auto-tune
        // =====================================================================

        /** Called at HEAD of WaterWorld.renderFrame() every rendered frame (cache save slot). */
        public static void onFrameCacheTick() {
                if (!isAndroid() || !initialized) {
                        return;
                }
                try {
                        PipelineCache.tick();
                } catch (Throwable ignored) {
                }
        }

        /**
         * Water frame profiler (3.1.0). Measures how long the mod's own renderFrame()
         * call takes vs the whole frame, and logs one line every 10 seconds so the next
         * latest.log shows exactly whether the phone is slow because of the water or
         * because of the game itself. Pure logging - changes no behaviour.
         */
        public static void onFrameStart() {
                statsPrevStartNanos = statsFrameStartNanos;
                statsFrameStartNanos = System.nanoTime();
        }

        /** Called at RETURN of WaterWorld.renderFrame(). */
        public static void onFrameEnd() {
                if (!isAndroid() || !initialized || !MobileSettings.mobileWaterStats) {
                        return;
                }
                try {
                        long now = System.nanoTime();
                        double whole = (now - statsPrevStartNanos) / 1_000_000.0;
                        double water = (now - statsFrameStartNanos) / 1_000_000.0;
                        if (whole <= 0.0 || whole > 500.0 || water < 0.0 || water > 500.0) {
                                return; // paused, menu or world switch
                        }
                        waterEmaMs = waterEmaMs < 0.0 ? water : waterEmaMs * 0.9 + water * 0.1;
                        wholeEmaMs = wholeEmaMs < 0.0 ? whole : wholeEmaMs * 0.9 + whole * 0.1;
                        long nowMs = now / 1_000_000L;
                        if (nowMs < nextStatsLogMs) {
                                return;
                        }
                        nextStatsLogMs = nowMs + 10_000L;
                        double share = wholeEmaMs > 0.0 ? 100.0 * waterEmaMs / wholeEmaMs : 0.0;
                        String verdict = share > 60.0
                                        ? "water dominates - pick a mobile preset (/water set mobilePreset 1) to trade a little water detail for FPS"
                                        : share > 30.0 ? "water is significant but not the only cost - a preset helps, game tuning helps more"
                                                        : "game-bound - tune the game (rendering mods, JVM, driver), not the water";
                        LOG.info(String.format("[RW Bridge] frame stats: water %.1f ms of a %.1f ms frame (%.0f%%) - %s",
                                        waterEmaMs, wholeEmaMs, share, verdict));
                } catch (Throwable ignored) {
                }
        }

        /** Called at HEAD of WaterWorld.renderFrame() every rendered frame. */
        public static void onRenderFrame() {
                if (!isAndroid() || preset() <= 0 || !autoTuneOn()) {
                        return;
                }
                long now = System.nanoTime();
                if (lastFrameNanos == 0L) {
                        lastFrameNanos = now;
                        nextCheckMs = now / 1_000_000L + 6000L;
                        return;
                }
                double ms = (now - lastFrameNanos) / 1_000_000.0;
                lastFrameNanos = now;
                if (ms <= 0.0 || ms > 500.0) {
                        return; // paused, menu or world switch
                }
                frameEmaMs = frameEmaMs < 0.0 ? ms : frameEmaMs * 0.9 + ms * 0.1;
                long nowMs = now / 1_000_000L;
                if (nowMs < nextCheckMs || nowMs < cooldownUntilMs) {
                        return;
                }
                nextCheckMs = nowMs + 5000L;
                if (frameEmaMs > 47.0 && tuneLevel < 3) {
                        applyTuneLevel(tuneLevel + 1, true);
                } else if (frameEmaMs < 25.0 && tuneLevel > 0) {
                        goodWindows++;
                        if (goodWindows >= 2) {
                                applyTuneLevel(tuneLevel - 1, false);
                        }
                        return;
                }
                goodWindows = 0;
        }

        private static void applyTuneLevel(int level, boolean down) {
                tuneLevel = level;
                goodWindows = 0;
                cooldownUntilMs = System.currentTimeMillis() + 8000L;
                try {
                        applyPreset(preset());
                        if (level >= 1) {
                                setQuiet("maxStepsPerFrame", "2");
                                setQuiet("renderMaxStepsPerFrame", "8");
                        }
                        if (level >= 2) {
                                setQuiet("resolution", "4");
                                setQuiet("cgIterations", "3");
                                setQuiet("mgCoarseSweeps", "6");
                        }
                        if (level >= 3) {
                                setQuiet("whitewater", "false");
                                setQuiet("surfaceSubdivision", "1");
                                setQuiet("rainDensity", "3");
                                setQuiet("sandMaxStepsPerFrame", "4");
                                setQuiet("leafMaxStepsPerFrame", "2");
                                setQuiet("kelpRadius", "24");
                        }
                        String message = "[RW Bridge] Auto-tune " + (down ? "turned water down" : "turned water back up")
                                        + " - level " + level + "/3 (avg frame " + Math.round(frameEmaMs) + " ms)."
                                        + " Disable with /water set mobileAutoTune false";
                        LOG.info(message);
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                                mc.player.sendSystemMessage(Component.literal(message));
                        }
                        tunedDownAnnounced = down;
                } catch (Throwable t) {
                        LOG.warn("Auto-tune failed: {}", t.toString());
                }
        }

        // =====================================================================
        // Settings menu descriptions (called from WaterSettingsInfoMixin)
        // =====================================================================

        private record InfoSpec(String name, String label, WaterSettingsInfo.Kind kind, WaterSettingsInfo.Cost cost,
                        String description, double min, double max, double step) {
        }

        private static final InfoSpec[] INFO_SPECS = {
                        new InfoSpec("mobilePreset", "Mobile preset", WaterSettingsInfo.Kind.INTEGER, WaterSettingsInfo.Cost.EXTREME,
                                        "Performance profile for phones. 0=off (default: every water setting stays exactly at the mod's "
                                                        + "own defaults, so the water looks and behaves like the plain mod), 1=low (max FPS), "
                                                        + "2=balanced, 3=high (also mod defaults). Changing it resets the managed simulation "
                                                        + "settings to the chosen profile; restart is not needed. Only has an effect on Android.",
                                        0, 3, 1),
                        new InfoSpec("mobileHalfGbuffer", "Half-float water g-buffer", WaterSettingsInfo.Kind.SWITCH, WaterSettingsInfo.Cost.HIGH,
                                        "Stores the water g-buffer in RGBA16F instead of RGBA32F, roughly halving the memory traffic of every water "
                                                        + "render pass. Recommended on phones; turn it off if distant water shows banding. Restart the game to apply.",
                                        0, 1, 1),
                        new InfoSpec("mobileMaxRegions", "Max simulated regions", WaterSettingsInfo.Kind.INTEGER, WaterSettingsInfo.Cost.HIGH,
                                        "Upper limit on simultaneously simulated water bodies on mobile (the mod allows 16). Far-away water simply "
                                                        + "stops simulating until a closer body settles. Lower = fewer GPU dispatches per frame. "
                                                        + "Only active while a mobile preset (mobilePreset 1-3) is selected.",
                                        2, 16, 1),
                        new InfoSpec("mobileSpirvCache", "SPIR-V shader cache", WaterSettingsInfo.Kind.SWITCH, WaterSettingsInfo.Cost.LOW,
                                        "Caches compiled shaders on disk (config/rwbridge-spirv) so the game doesn't recompile hundreds of water "
                                                        + "shaders at every start. The disk writes run on a background thread, so caching itself costs no "
                                                        + "frame time. Removes the long stutter when water appears for the first time in a session.",
                                        0, 1, 1),
                        new InfoSpec("mobilePipelineCache", "Persistent pipeline cache", WaterSettingsInfo.Kind.SWITCH, WaterSettingsInfo.Cost.LOW,
                                        "Keeps a Vulkan pipeline cache on disk (config/rwbridge-pipeline-cache.bin). The driver stores its "
                                                        + "shader-to-GPU-code results there, so pipelines built in an earlier session come back almost "
                                                        + "instantly instead of hitching again. Pure loading-time optimization: the rendered result "
                                                        + "is pixel-for-pixel identical, and a missing or stale cache just falls back to normal compiling. "
                                                        + "Needs Vulkan 1.2 (every device that can run this mod has it).",
                                        0, 1, 1),
                        new InfoSpec("mobileWaterStats", "Frame stats logging", WaterSettingsInfo.Kind.SWITCH, WaterSettingsInfo.Cost.LOW,
                                        "Logs one line every 10 s with how many milliseconds the water mod costs per frame vs the whole "
                                                        + "frame (see latest.log: 'frame stats'). Use it to tell whether the phone is slow because of "
                                                        + "the water or because of the game itself, and attach the log when reporting problems.",
                                        0, 1, 1),
                        new InfoSpec("mobileAutoTune", "Auto performance tune", WaterSettingsInfo.Kind.SWITCH, WaterSettingsInfo.Cost.MEDIUM,
                                        "Watches the average frame time and turns water settings down (and back up) in 3 steps when the frame rate "
                                                        + "drops below ~21 fps. Announces every change in chat. Only active while a mobile preset is selected.",
                                        0, 1, 1)
        };

        public static void registerSettingInfos(Map<String, WaterSettingsInfo.Info> byName, Map<String, List<String>> byCategory) {
                try {
                        String category = "Android bridge";
                        byCategory.computeIfAbsent(category, k -> new ArrayList<>());
                        List<String> other = byCategory.get("Other");
                        for (InfoSpec spec : INFO_SPECS) {
                                if (other != null) {
                                        other.remove(spec.name());
                                }
                                byName.put(spec.name(), new WaterSettingsInfo.Info(spec.name(), category, spec.label(), spec.kind(),
                                                spec.cost(), "", spec.description(), spec.min(), spec.max(), spec.step()));
                                byCategory.get(category).add(spec.name());
                        }
                        if (other != null && other.isEmpty()) {
                                byCategory.remove("Other");
                        }
                } catch (Throwable t) {
                        LOG.warn("Couldn't register the bridge settings in the /water menu: {}", t.toString());
                }
        }
}
