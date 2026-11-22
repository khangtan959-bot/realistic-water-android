package me.rwmali.mixin;

import java.nio.LongBuffer;
import me.rwmali.PushDescriptors;
import me.skaffy.client.vk.Program;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Push-descriptor compatibility for GPU drivers without VK_KHR_push_descriptor
 * - the addon's fix for "Realistic Water frame failed; disabling the
 * simulation" / "Realistic Water failed: null" on phones like the Redmi 15C
 * (Mali-G52 MC2) where the driver never exposes that extension, and where
 * Vulkan Extension Emulator / Vulky has to claim it in software for
 * Minecraft's device check.
 *
 * Realistic Water builds every program the same way: one descriptor set layout
 * with the PUSH_DESCRIPTOR flag, one pipeline layout on top, and a bind() that
 * pushes the descriptors with vkCmdPushDescriptorSetKHR. On a driver without
 * that extension (native or stripped by VEE's device creation) the LWJGL
 * function pointer is null and bind() dies with a bare NullPointerException.
 *
 * Both redirects below are require = 0: if a future RW version moves either
 * call site, they silently become no-ops (the game then behaves exactly like
 * the plain mod on that version - which on such drivers means the known crash,
 * never a new one).
 *
 * 1. Constructor: while the fallback is active the PUSH_DESCRIPTOR_BIT flag is
 *    removed from the set layout RW creates. A layout with that flag can never
 *    back a real, allocated descriptor set, so without this step step 2 would
 *    have nowhere to allocate from. With the flag gone RW's own layout is a
 *    perfectly normal one, and our parallel layout (built from the same
 *    bindings) is spec-compatible with it for descriptor binding.
 * 2. bind(): the vkCmdPushDescriptorSetKHR call is replaced by
 *    PushDescriptors.bind(), which passes through while the driver can
 *    really do it, and otherwise allocates a descriptor set from an
 *    addon-managed pool, applies the very same writes and binds it with
 *    vkCmdBindDescriptorSets.
 */
@Mixin(Program.class)
public abstract class ProgramCompatMixin {

	@Shadow
	@Final
	protected long setLayout;

	/**
	 * Strip VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR while the
	 * push-descriptor fallback is active (see PushDescriptors). The same
	 * trick VEE's BindGroupLayoutMixin pulls on Minecraft's own layouts.
	 */
	@Redirect(
			method = "<init>",
			at = @At(
					value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VK10;vkCreateDescriptorSetLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
			require = 0)
	private int rwmali$plainSetLayout(VkDevice device, VkDescriptorSetLayoutCreateInfo info,
					VkAllocationCallbacks callbacks, LongBuffer pSetLayout) {
		try {
			if (PushDescriptors.emulating()) {
				info.flags(info.flags() & ~KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR);
			}
		} catch (Throwable ignored) {
			// Never block layout creation; the worst case is the unpatched behaviour.
		}
		return VK10.vkCreateDescriptorSetLayout(device, info, callbacks, pSetLayout);
	}

	/**
	 * Route Program.bind()'s push-descriptor call through the fallback, passing the
	 * program's own set layout along so the fallback can build an allocatable twin
	 * of it (the pushed pipeline layout itself is passed straight through).
	 */
	@Redirect(
			method = "bind",
			at = @At(
					value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRPushDescriptor;vkCmdPushDescriptorSetKHR(Lorg/lwjgl/vulkan/VkCommandBuffer;IJILorg/lwjgl/vulkan/VkWriteDescriptorSet$Buffer;)V"),
			require = 0)
	private void rwmali$bindDescriptors(VkCommandBuffer commandBuffer, int pipelineBindPoint, long layout, int set,
					VkWriteDescriptorSet.Buffer descriptorWrites) {
		PushDescriptors.bind(commandBuffer, pipelineBindPoint, this.setLayout, layout, set, descriptorWrites);
	}
}
