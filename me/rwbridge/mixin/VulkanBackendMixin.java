package me.rwbridge.mixin;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import me.rwbridge.MobileBridge;
import org.lwjgl.vulkan.VK;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Safety net around Minecraft's own Vulkan backend probing (v2.0.1).
 *
 * Why this exists: when the graphics API option is left on "Default",
 * Minecraft.<init> calls VulkanBackend.checkBackendAvailable(), and with
 * "Prefer Vulkan" the backend loop calls VulkanBackend.loadLibrary(). Both call
 * VK.getFunctionProvider(), which throws a plain IllegalStateException when the
 * LWJGL Vulkan loader is not loaded - and neither call site catches it, so the
 * game hard-crashes at startup instead of falling back to OpenGL. Exactly that
 * happened on Android (bridge 2.0.0 unloaded the loader after probing; the
 * VulkanProbe no longer does, but this guard also protects against any other
 * source of the same state).
 *
 * Both injectors use require = 0, so on any future Minecraft version where the
 * targets move they simply do not apply - the bridge never breaks the game.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {

        /**
         * On Android, answer the "is the Vulkan backend available" question from the
         * bridge's own early probe instead of running Minecraft's heavier probe
         * (which creates a full Vulkan instance + physical-device scan that has never
         * been exercised on phone drivers and can throw unchecked exceptions).
         * On desktop this does nothing - vanilla behaviour is untouched.
         */
        @Inject(method = "checkBackendAvailable", at = @At("HEAD"), cancellable = true, require = 0)
        private static void rwbridge$androidAvailability(CallbackInfoReturnable<BackendCreationException> cir) {
                if (!MobileBridge.isAndroid()) {
                        return;
                }
                int verdict = MobileBridge.vulkanVerdict();
                if (verdict == 1) {
                        MobileBridge.info("Vulkan backend available (bridge verdict) - skipping Minecraft's own availability probe");
                        cir.setReturnValue(null); // null = available
                } else if (verdict == 2) {
                        MobileBridge.warn("Vulkan not usable on this device (" + MobileBridge.gpuDescription()
                                        + ") - the game will fall back to OpenGL, Realistic Water needs Vulkan");
                        cir.setReturnValue(new BackendCreationException(
                                        "Android bridge: Vulkan not usable on this device (" + MobileBridge.gpuDescription() + ")",
                                        BackendCreationException.Reason.VULKAN_LOADER_MISSING));
                }
                // verdict 0 (no data): no opinion, run the vanilla probe - still guarded by the redirect below.
        }

        /**
         * Makes "Vulkan loader went away between bootstrap and now" a clean, catchable
         * backend failure instead of an uncatchable IllegalStateException:
         * VulkanBackend.loadLibrary() checks NativeLibrariesBootstrap.isVulkanLoaderAvailable()
         * first and throws BackendCreationException when it returns false, which every
         * caller handles properly (GL fallback). We re-check the real LWJGL state behind
         * that flag so the stale-true case degrades the same safe way.
         */
        @Redirect(method = "loadLibrary",
                        at = @At(value = "INVOKE",
                                        target = "Lcom/mojang/blaze3d/platform/NativeLibrariesBootstrap;isVulkanLoaderAvailable()Z"),
                        require = 0)
        private static boolean rwbridge$loaderReallyAvailable() {
                if (!NativeLibrariesBootstrap.isVulkanLoaderAvailable()) {
                        return false;
                }
                try {
                        VK.getFunctionProvider();
                        return true;
                } catch (Throwable t) {
                        MobileBridge.warn("Vulkan loader was loaded at bootstrap but is gone now (" + t
                                        + ") - reporting the Vulkan backend as unavailable so the game falls back to OpenGL");
                        return false;
                }
        }
}
