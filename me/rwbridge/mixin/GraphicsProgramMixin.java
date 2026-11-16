package me.rwbridge.mixin;

import java.nio.LongBuffer;
import me.rwbridge.MobileBridge;
import me.rwbridge.PipelineCache;
import me.skaffy.client.vk.GraphicsProgram;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hands the mod's graphics-pipeline creations a persistent VkPipelineCache instead of
 * VK_NULL_HANDLE. Only the cache argument changes - every other argument (device,
 * create info, allocator, output) passes through exactly as the mod passed it, so the
 * created pipelines are identical. With a cold or incompatible cache the driver just
 * compiles as usual; there is no behaviour that depends on the cache being there.
 *
 * require = 0 keeps a future Realistic Water update from ever crashing - worst case
 * the patch is a no-op and pipelines build without a cache, like before.
 */
@Mixin(GraphicsProgram.class)
public abstract class GraphicsProgramMixin {

        @Redirect(
                        method = "<init>",
                        at = @At(
                                        value = "INVOKE",
                                        target = "Lorg/lwjgl/vulkan/VK10;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
                        require = 0)
        private static int rwbridge$cachedPipeline(VkDevice device, long cache, VkGraphicsPipelineCreateInfo.Buffer info,
                        VkAllocationCallbacks callbacks, LongBuffer pPipelines) {
                long handle = MobileBridge.pipelineCacheHandle();
                int result = VK10.vkCreateGraphicsPipelines(device, handle, info, callbacks, pPipelines);
                if (result == VK10.VK_SUCCESS && handle != 0L) {
                        PipelineCache.markDirty();
                }
                return result;
        }
}
