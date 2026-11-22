package me.rwmali;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;

/**
 * Enumerates the device's RAW Vulkan driver with a private, short-lived
 * VkInstance - the addon's own probe, independent from both Minecraft's
 * Vulkan device and the official bridge's probe (whose published 1.0.0
 * result carries no subgroup information).
 *
 * This is deliberately standalone: it runs early, never touches RenderSystem,
 * and any failure just means "unknown GPU" - it can never crash the game or
 * the mods.
 *
 * IMPORTANT (kept from the bridge's probe): this probe must NEVER call
 * VK.destroy(). Minecraft loads the LWJGL Vulkan loader once during
 * NativeLibrariesBootstrap.loadLibraries() and every later VulkanBackend
 * call would throw "Vulkan library has not been loaded" if someone unloaded
 * it. We only destroy our own VkInstance; the shared loader stays loaded
 * exactly like Minecraft expects.
 *
 * What the addon asks beyond the bridge's probe:
 * - subgroup capabilities (core since Vulkan 1.1, via vkGetPhysicalDeviceProperties2
 *   + VkPhysicalDeviceSubgroupProperties): Realistic Water 1.4.1's particle
 *   compute shaders use subgroupAdd/Min/Max/Elect/BroadcastFirst; many phone
 *   drivers advertise "Vulkan 1.3" while their subgroup ops are not exposed
 *   for compute (typical on ARM Mali Bifrost, e.g. Mali-G52). That gap is what
 *   made the water crash with "Realistic Water failed" on such phones.
 * - VK_KHR_push_descriptor in the physical device's own extension list: the
 *   only honest source for whether vkCmdPushDescriptorSetKHR (Realistic
 *   Water's one and only binding mechanism) can actually work. Compatibility
 *   mods (Vulky, VEE) CLAIM this extension to Minecraft but strip it from the
 *   real device, so the device list - not the instance/Minecraft view - decides.
 */
public final class MaliProbe {

        private MaliProbe() {
        }

        // Stable Vulkan ABI bits (VkSubgroupFeatureFlagBits / VkShaderStageFlagBits).
        private static final int SUBGROUP_FEATURE_BASIC = 0x00000001;
        private static final int SUBGROUP_FEATURE_ARITHMETIC = 0x00000004;
        private static final int SUBGROUP_FEATURE_BALLOT = 0x00000008;
        private static final int SHADER_STAGE_COMPUTE = 0x00000020;

        /** The ops Realistic Water's shaders need: basic (broadcastFirst), arithmetic (add/min/max), ballot (elect). */
        static final int REQUIRED_SUBGROUP_OPS = SUBGROUP_FEATURE_BASIC | SUBGROUP_FEATURE_ARITHMETIC | SUBGROUP_FEATURE_BALLOT;

        /** Structured outcome so the addon's mixins and ShaderVariants can act on it. */
        public record Result(boolean hasInstance, boolean hasDevice, boolean rawUsable, boolean pushDescriptor, int major, int minor,
                                        int patch, String description, int subgroupSize, int subgroupOps, int subgroupStages,
                                        boolean subgroupQueried) {

                public String version() {
                        return major + "." + minor + "." + patch;
                }

                /** True when the driver exposes every subgroup op the water shaders use, in the compute stage. */
                public boolean subgroupComputeFull() {
                        return subgroupQueried && subgroupSize > 0
                                        && (subgroupStages & SHADER_STAGE_COMPUTE) != 0
                                        && (subgroupOps & REQUIRED_SUBGROUP_OPS) == REQUIRED_SUBGROUP_OPS;
                }

                /** Missing-op summary for logs and the diagnostic report; "" when nothing is missing. */
                public String subgroupGaps() {
                        if (!subgroupQueried) {
                                return "not queryable (driver below Vulkan 1.1)";
                        }
                        java.util.List<String> missing = new java.util.ArrayList<>();
                        if ((subgroupOps & SUBGROUP_FEATURE_BASIC) == 0) {
                                missing.add("basic");
                        }
                        if ((subgroupOps & SUBGROUP_FEATURE_ARITHMETIC) == 0) {
                                missing.add("arithmetic");
                        }
                        if ((subgroupOps & SUBGROUP_FEATURE_BALLOT) == 0) {
                                missing.add("ballot");
                        }
                        if ((subgroupStages & SHADER_STAGE_COMPUTE) == 0) {
                                missing.add("compute-stage");
                        }
                        if (subgroupSize <= 0) {
                                missing.add("size<1");
                        }
                        return missing.isEmpty() ? "none - full support" : String.join(",", missing);
                }

                public String subgroupLine() {
                        return "subgroups: size=" + subgroupSize + " ops=0x" + Integer.toHexString(subgroupOps)
                                        + " stages=0x" + Integer.toHexString(subgroupStages)
                                        + " (compute " + (subgroupComputeFull() ? "FULL" : "MISSING: " + subgroupGaps()) + ")";
                }
        }

        /** rawUsable = the driver itself reports Vulkan 1.2 or newer (what Minecraft's renderer needs). */
        public static Result probe() {
                // Create the LWJGL binding if (and only if) it is not loaded yet. Minecraft
                // normally already loaded it at bootstrap; a second create() is a no-op.
                try {
                        org.lwjgl.vulkan.VK.create();
                } catch (Throwable ignored) {
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                        VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                                        .pApplicationName(stack.UTF8("RWMali probe"))
                                        .apiVersion(VK10.VK_API_VERSION_1_0);
                        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
                        PointerBuffer pInstance = stack.mallocPointer(1);
                        int err = VK10.vkCreateInstance(info, null, pInstance);
                        if (err != VK10.VK_SUCCESS) {
                                return new Result(false, false, false, false, 0, 0, 0,
                                                "no Vulkan instance (VkResult 0x" + Integer.toHexString(err) + ")", 0, 0, 0, false);
                        }
                        VkInstance instance = new VkInstance(pInstance.get(0), info);
                        try {
                                IntBuffer count = stack.mallocInt(1);
                                if (VK10.vkEnumeratePhysicalDevices(instance, count, null) != VK10.VK_SUCCESS || count.get(0) == 0) {
                                        return new Result(true, false, false, false, 0, 0, 0, "no Vulkan physical devices", 0, 0, 0, false);
                                }
                                PointerBuffer devices = stack.mallocPointer(count.get(0));
                                VK10.vkEnumeratePhysicalDevices(instance, count, devices);
                                VkPhysicalDevice device = new VkPhysicalDevice(devices.get(0), instance);
                                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                                VK10.vkGetPhysicalDeviceProperties(device, props);
                                int api = props.apiVersion();
                                int major = api >> 22;
                                int minor = (api >> 12) & 0x3FF;
                                int patch = api & 0xFFF;
                                String vendor = vendorName(props.vendorID());
                                boolean usable = api >= VK10.VK_MAKE_VERSION(1, 2, 0);
                                // Does the RAW driver expose VK_KHR_push_descriptor? See the class comment.
                                boolean pushDescriptor = false;
                                try {
                                        IntBuffer extCount = stack.mallocInt(1);
                                        if (VK10.vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, extCount, null) == VK10.VK_SUCCESS
                                                                        && extCount.get(0) > 0) {
                                                VkExtensionProperties.Buffer exts = VkExtensionProperties.malloc(extCount.get(0), stack);
                                                if (VK10.vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, extCount, exts) == VK10.VK_SUCCESS) {
                                                        for (VkExtensionProperties ext : exts) {
                                                                if ("VK_KHR_push_descriptor".equals(ext.extensionNameString())) {
                                                                        pushDescriptor = true;
                                                                        break;
                                                                }
                                                        }
                                                }
                                        }
                                } catch (Throwable ignored) {
                                        // Extension listing is best-effort; "absent" just arms the fallback.
                                }
                                // Subgroup capabilities. Core since Vulkan 1.1, so on a 1.0 driver the
                                // honest answer is "not queryable" - and Vulkan 1.0 guarantees nothing
                                // about subgroups, so that case counts as NOT supported (variants stay armed).
                                int subgroupSize = 0;
                                int subgroupOps = 0;
                                int subgroupStages = 0;
                                boolean subgroupQueried = false;
                                if (api >= VK10.VK_MAKE_VERSION(1, 1, 0)) {
                                        try {
                                                VkPhysicalDeviceSubgroupProperties sub = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
                                                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(sub.address());
                                                VK11.vkGetPhysicalDeviceProperties2(device, props2);
                                                subgroupSize = sub.subgroupSize();
                                                subgroupOps = sub.supportedOperations();
                                                subgroupStages = sub.supportedStages();
                                                subgroupQueried = true;
                                        } catch (Throwable ignored) {
                                                // Best-effort: a driver that rejects the query is treated as unknown
                                                // (not full support), so the compat variants stay armed.
                                        }
                                }
                                boolean subgroupFull = subgroupQueried && subgroupSize > 0
                                                && (subgroupStages & SHADER_STAGE_COMPUTE) != 0
                                                && (subgroupOps & REQUIRED_SUBGROUP_OPS) == REQUIRED_SUBGROUP_OPS;
                                String tail = usable ? ""
                                                : " (below 1.2 on its own - Minecraft's Vulkan renderer needs 1.2,"
                                                                                + " which a compatibility mod such as Vulky or VEE can add in software)";
                                return new Result(true, true, usable, pushDescriptor, major, minor, patch,
                                                vendor + " '" + props.deviceNameString() + "', Vulkan " + major + "." + minor
                                                                                + "." + patch + tail
                                                                                + (subgroupFull ? "" : " [subgroup ops incomplete for compute]"),
                                                subgroupSize, subgroupOps, subgroupStages, subgroupQueried);
                        } finally {
                                VK10.vkDestroyInstance(instance, null);
                        }
                } catch (Throwable t) {
                        return new Result(false, false, false, false, 0, 0, 0,
                                        "probe failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")", 0, 0, 0, false);
                }
                // Deliberately NO VK.destroy() here - see the class comment.
        }

        private static String vendorName(int vendorId) {
                return switch (vendorId) {
                        case 0x5143 -> "Qualcomm Adreno";
                        case 0x13B5 -> "ARM Mali";
                        case 0x1010 -> "Imagination PowerVR";
                        case 0x10DE -> "NVIDIA";
                        case 0x1002, 0x1022 -> "AMD";
                        case 0x8086 -> "Intel";
                        case 0x13E5 -> "Huawei";
                        default -> "vendor 0x" + Integer.toHexString(vendorId);
                };
        }
}
