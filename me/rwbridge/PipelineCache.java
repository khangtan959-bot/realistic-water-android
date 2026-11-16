package me.rwbridge;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

/**
 * Persistent Vulkan pipeline cache for phones (bridge 3.0.0).
 *
 * Why: with the SPIR-V cache the mod no longer recompiles GLSL -> SPIR-V across
 * sessions, but the driver still has to translate every SPIR-V module into Mali/Adreno
 * machine code the first time each pipeline is created in a session. That is the
 * remaining hitch when water appears. A VkPipelineCache lets the driver store and reuse
 * exactly that result; handing it the bytes saved from the previous session makes
 * pipeline creation nearly instant.
 *
 * Safety, spelled out:
 * - Passing a VkPipelineCache to vkCreateGraphicsPipelines/vkCreateComputePipelines is
 *   a pure accelerator. By spec the driver validates the blob and silently falls back
 *   to a full compile when the data doesn't match (different device, driver, anything).
 *   The resulting pipelines are identical, so rendering stays pixel-for-pixel the same.
 * - The cache handle only replaces the literal 0L at the mod's two pipeline-creation
 *   call sites (see GraphicsProgramMixin / ComputeProgramMixin). Every other argument
 *   passes through untouched.
 * - On any failure (no device, OOM, bad file) get() returns 0L, which is exactly the
 *   original behaviour.
 * - Saving happens from the render thread only (pipeline creation happens there too,
 *   inside renderFrameUnsafe), so there is no concurrent use of the cache object.
 * - The blob file lives in config/rwbridge-pipeline-cache.bin, is version-tagged with
 *   our own header and capped at 16 MiB; a stale or corrupt file is ignored.
 */
public final class PipelineCache {
        private static final String FILE_NAME = "rwbridge-pipeline-cache.bin";
        private static final byte[] MAGIC = { 'R', 'W', 'P', 'C' };
        private static final int FORMAT = 1;
        private static final int HEADER = MAGIC.length + 4;
        private static final long MAX_BLOB = 16L * 1024L * 1024L;
        private static final long SAVE_INTERVAL_MS = 15_000L;

        private static volatile long cache;      // VkPipelineCache handle, 0 = none
        private static volatile long deviceHandle; // VkDevice the cache was created on
        private static volatile boolean attempted; // creation tried for this device
        private static volatile boolean dirty;
        private static volatile long nextSaveMs;
        private static int seedKiB;

        private PipelineCache() {
        }

        /**
         * Handle for the redirected pipeline-creation calls; 0L = no cache, which is
         * exactly the original "no pipeline cache" behaviour. Never throws.
         */
        public static long get() {
                long handle = cache;
                if (handle != 0L) {
                        return handle;
                }
                if (attempted || !MobileBridge.isAndroid() || !MobileBridge.pipelineCacheOn()) {
                        return 0L;
                }
                synchronized (PipelineCache.class) {
                        if (attempted) {
                                return cache;
                        }
                        attempted = true;
                        try {
                                VkDevice device = me.skaffy.client.vk.Vk.vk();
                                deviceHandle = device.address();
                                cache = create(device, loadBlob());
                                if (cache != 0L) {
                                        MobileBridge.info("Persistent pipeline cache active ("
                                                        + seedKiB + " KiB seed from the last session)");
                                }
                        } catch (Throwable t) {
                                cache = 0L;
                                MobileBridge.warn("Pipeline cache unavailable (" + t + ") - pipelines build as usual");
                        }
                        return cache;
                }
        }

        /** Called by the redirect mixins right after a pipeline was created with our cache. */
        public static void markDirty() {
                if (cache != 0L) {
                        dirty = true;
                }
        }

        /** Periodic save, driven from the render thread every rendered frame. */
        public static void tick() {
                if (!dirty || cache == 0L || !MobileBridge.pipelineCacheOn()) {
                        return;
                }
                long now = System.currentTimeMillis();
                if (now < nextSaveMs) {
                        return;
                }
                nextSaveMs = now + SAVE_INTERVAL_MS;
                dirty = false;
                try {
                        save();
                } catch (Throwable t) {
                        MobileBridge.warn("Pipeline cache save failed (" + t + ")");
                }
        }

        private static long create(VkDevice device, ByteBuffer seed) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                        VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
                        if (seed != null && seed.hasRemaining()) {
                                info.pInitialData(seed);
                        }
                        var p = stack.mallocLong(1);
                        int err = VK10.vkCreatePipelineCache(device, info, null, p);
                        if (err != VK10.VK_SUCCESS) {
                                MobileBridge.warn("vkCreatePipelineCache returned " + err + " - continuing without it");
                                return 0L;
                        }
                        return p.get(0);
                }
        }

        private static Path file() {
                return FabricDirs.config().resolve(FILE_NAME);
        }

        /** Reads the saved blob; null when missing/foreign/corrupt. */
        private static ByteBuffer loadBlob() {
                try {
                        Path file = file();
                        if (!Files.isRegularFile(file)) {
                                return null;
                        }
                        byte[] all = Files.readAllBytes(file);
                        if (all.length <= HEADER) {
                                return null;
                        }
                        for (int i = 0; i < MAGIC.length; i++) {
                                if (all[i] != MAGIC[i]) {
                                        return null;
                                }
                        }
                        int format = ((all[4] & 0xFF) << 24) | ((all[5] & 0xFF) << 16) | ((all[6] & 0xFF) << 8) | (all[7] & 0xFF);
                        if (format != FORMAT) {
                                return null;
                        }
                        long blobSize = all.length - HEADER;
                        if (blobSize > MAX_BLOB) {
                                return null;
                        }
                        seedKiB = (int) (blobSize / 1024L);
                        ByteBuffer blob = ByteBuffer.allocateDirect((int) blobSize);
                        blob.put(all, HEADER, (int) blobSize);
                        blob.flip();
                        return blob;
                } catch (Throwable t) {
                        return null;
                }
        }

        private static void save() {
                VkDevice device = me.skaffy.client.vk.Vk.vk();
                if (device.address() != deviceHandle || cache == 0L) {
                        return; // cache belongs to a device that is gone; don't overwrite a good file
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                        PointerBuffer size = stack.mallocPointer(1);
                        VK10.vkGetPipelineCacheData(device, cache, size, (ByteBuffer) null);
                        long n = size.get(0);
                        if (n <= 0L || n > MAX_BLOB) {
                                return;
                        }
                        ByteBuffer blob = ByteBuffer.allocateDirect((int) n);
                        PointerBuffer written = stack.mallocPointer(1);
                        written.put(0, n);
                        int err = VK10.vkGetPipelineCacheData(device, cache, written, blob);
                        if (err != VK10.VK_SUCCESS) {
                                return;
                        }
                        blob.position(0).limit((int) n);
                        ByteBuffer out = ByteBuffer.allocate(HEADER + (int) n);
                        out.put(MAGIC).putInt(FORMAT).put(blob);
                        out.flip();
                        Path target = file();
                        Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
                        Files.createDirectories(target.getParent());
                        java.io.OutputStream stream = Files.newOutputStream(tmp);
                        try {
                                byte[] chunk = new byte[16 * 1024];
                                while (out.hasRemaining()) {
                                        int take = Math.min(chunk.length, out.remaining());
                                        out.get(chunk, 0, take);
                                        stream.write(chunk, 0, take);
                                }
                        } finally {
                                stream.close();
                        }
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable t) {
                        // The cache is a pure accelerator; a failed save must never matter.
                }
        }
}
