package me.rwbridge;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/**
 * Enumerates the device's Vulkan driver with a private, short-lived VkInstance.
 *
 * This is deliberately independent from Minecraft's own Vulkan device: it runs early,
 * never touches RenderSystem, and any failure just means "unknown GPU" - it can never
 * crash the game or the mod.
 *
 * IMPORTANT (v2.0.1 fix): this probe must NEVER call VK.destroy(). Minecraft loads the
 * LWJGL Vulkan loader once during NativeLibrariesBootstrap.loadLibraries() (Main.main,
 * before any mod code runs) and every later VulkanBackend.loadLibrary() call - including
 * VulkanBackend.checkBackendAvailable() from Minecraft.<init> - calls
 * VK.getFunctionProvider(), which throws "Vulkan library has not been loaded" if someone
 * unloaded it. Bridge 2.0.0 unloaded it here after probing, which crashed the game at
 * startup with exactly that exception. We only destroy our own VkInstance; the shared
 * loader stays loaded exactly like Minecraft expects.
 */
final class VulkanProbe {
        private VulkanProbe() {
        }

        /** Structured outcome so the VulkanBackend safety-net mixin can act on it. */
        record Result(boolean usable, String description) {
        }

        /** usable = a physical device was found whose driver reports Vulkan 1.2 or newer. */
        static Result probe() {
                // Create the LWJGL binding if (and only if) it is not loaded yet. Minecraft
                // normally already loaded it at bootstrap; a second create() is a no-op.
                // If it throws for any reason we still try the calls below and let the
                // surrounding catch turn it into a clean failure string.
                try {
                        org.lwjgl.vulkan.VK.create();
                } catch (Throwable ignored) {
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                        VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                                        .pApplicationName(stack.UTF8("RWBridge probe"))
                                        .apiVersion(VK10.VK_API_VERSION_1_0);
                        VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
                        PointerBuffer pInstance = stack.mallocPointer(1);
                        int err = VK10.vkCreateInstance(info, null, pInstance);
                        if (err != VK10.VK_SUCCESS) {
                                return new Result(false, "no Vulkan instance (VkResult 0x" + Integer.toHexString(err) + ")");
                        }
                        VkInstance instance = new VkInstance(pInstance.get(0), info);
                        try {
                                IntBuffer count = stack.mallocInt(1);
                                if (VK10.vkEnumeratePhysicalDevices(instance, count, null) != VK10.VK_SUCCESS || count.get(0) == 0) {
                                        return new Result(false, "no Vulkan physical devices");
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
                                return new Result(usable, vendor + " '" + props.deviceNameString() + "', Vulkan " + major + "." + minor
                                                + "." + patch
                                                + (usable ? "" : " (below 1.2 - MC's own Vulkan renderer will not start on this device)"));
                        } finally {
                                VK10.vkDestroyInstance(instance, null);
                        }
                } catch (Throwable t) {
                        return new Result(false, "probe failed (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
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
