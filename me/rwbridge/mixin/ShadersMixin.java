package me.rwbridge.mixin;

import java.nio.LongBuffer;
import java.util.Map;
import me.rwbridge.SpirvCache;
import me.skaffy.client.vk.Shaders;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches compiled SPIR-V on disk so the hundreds of shader compiles the mod does the
 * first time water appears don't repeat at every game start.
 *
 * HEAD: on a cache hit the module is created straight from the cached bytes.
 * Redirect: right before the mod creates the module, the fresh SPIR-V is stored.
 * Every failure path silently falls back to the normal shaderc compile.
 */
@Mixin(Shaders.class)
public abstract class ShadersMixin {

	@Unique
	private static final ThreadLocal<String> rwbridge$pendingKey = new ThreadLocal<>();

	@Inject(method = "module", at = @At("HEAD"), cancellable = true, require = 0)
	private static void rwbridge$cacheHit(String name, int kind, Map<String, String> defines, CallbackInfoReturnable<Long> cir) {
		if (!SpirvCache.enabled()) {
			return;
		}
		String key = SpirvCache.key(name, kind, defines);
		if (key == null) {
			return;
		}
		rwbridge$pendingKey.set(key);
		Long module = SpirvCache.tryCreateModule(key);
		if (module != null) {
			cir.setReturnValue(module);
		}
	}

	@Redirect(
			method = "module",
			at = @At(
					value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VK10;vkCreateShaderModule(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkShaderModuleCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
			require = 0)
	private static int rwbridge$captureSpirov(VkDevice device, VkShaderModuleCreateInfo info, VkAllocationCallbacks callbacks,
			LongBuffer pModule) {
		SpirvCache.capture(rwbridge$pendingKey.get(), info);
		return VK10.vkCreateShaderModule(device, info, callbacks, pModule);
	}
}
