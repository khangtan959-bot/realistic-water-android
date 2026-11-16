package me.rwbridge.mixin;

import java.nio.LongBuffer;
import me.rwbridge.MobileBridge;
import me.rwbridge.PipelineCache;
import me.skaffy.client.vk.ComputeProgram;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same persistent pipeline cache as GraphicsProgramMixin, for the ~60 compute pipelines
 * the mod builds per session (the bulk of the first-visit hitch). Only the cache
 * argument changes; everything else is forwarded untouched and require = 0 keeps the
 * patch a no-op instead of a crash if the mod ever restructures this call.
 */
@Mixin(ComputeProgram.class)
public abstract class ComputeProgramMixin {

        @Redirect(
                        method = "<init>",
                        at = @At(
                                        value = "INVOKE",
                                        target = "Lorg/lwjgl/vulkan/VK10;vkCreateComputePipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkComputePipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
                        require = 0)
        private static int rwbridge$cachedPipeline(VkDevice device, long cache, VkComputePipelineCreateInfo.Buffer info,
                        VkAllocationCallbacks callbacks, LongBuffer pPipelines) {
                long handle = MobileBridge.pipelineCacheHandle();
                int result = VK10.vkCreateComputePipelines(device, handle, info, callbacks, pPipelines);
                if (result == VK10.VK_SUCCESS && handle != 0L) {
                        PipelineCache.markDirty();
                }
                return result;
        }
}
