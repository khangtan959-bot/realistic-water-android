package me.rwmali;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.client.vk.Vk;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Push-descriptor fallback (Mali addon).
 *
 * WHY THIS EXISTS. Every Realistic Water shader binding goes through
 * me.skaffy.client.vk.Program.bind(), which calls vkCmdPushDescriptorSetKHR -
 * the one Vulkan 1.2-era mechanism the mod uses that is NOT core Vulkan and
 * is missing from every phone Mali driver checked so far (verified on a
 * Redmi 15C / Helio G81 Ultra / Mali-G52 MC2, Vulkan 1.3.278:
 * VK_KHR_push_descriptor [NO]). When Vulkan Extension Emulator (VEE) or
 * Vulky is installed they CLAIM the extension to satisfy Minecraft's device
 * check but strip it again from the real vkCreateDevice call, so LWJGL's
 * function pointer stays NULL - and RW dies on the first water frame with a
 * bare NullPointerException ("Realistic Water frame failed; disabling the
 * simulation" / "Realistic Water failed: null" in latest.log).
 *
 * WHAT WE DO. Exactly what VEE itself does for Minecraft's own draws, but for
 * Realistic Water's private Vulkan code:
 *  1. ProgramCompatMixin strips VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR
 *     from the set layouts RW creates (a set layout with that flag can never
 *     back a real descriptor set), and
 *  2. ProgramCompatMixin redirects the vkCmdPushDescriptorSetKHR call in bind()
 *     to bind() below, which allocates a descriptor set from an addon-managed
 *     pool, vkUpdateDescriptorSets()s the very same writes into it and binds
 *     it with plain vkCmdBindDescriptorSets(). Same writes, same bindings,
 *     same push constants (RW pushes those separately with its own pipeline
 *     layout, which keeps working untouched) - no visual or behavioural
 *     difference, just a normal descriptor set instead of a pushed one.
 *
 * POOL LIFECYCLE. bind() is called from RW's recording fibers, so everything
 * is guarded by one lock. Descriptor sets are allocated fresh on every bind
 * (a set must never be rewritten while an earlier frame still references it)
 * from a ring of POOL_RING pools; WaterWorldFrameMixin rotates the ring at
 * the head of every WaterWorld.renderFrame() and resets the pool that comes
 * back into rotation. That pool was last touched POOL_RING render-frame calls
 * ago - far beyond any frames-in-flight window (RW's own Vk.FRAME_RING, VEE's
 * 3 frames in flight), so resetting it can never invalidate a command buffer
 * that has not finished executing yet. If a pool runs out, sizing doubles
 * for future pools and a one-off spare pool serves the current frame.
 *
 * SAFETY. Every entry point is failure-tolerant: if anything here throws, the
 * worst case is the same "frame failed" RW already handles. A system property
 * (-Drwmali.pushDescriptors=off) and the /water setting maliPushDescriptors
 * (default true) can turn the whole mechanism off.
 */
public final class PushDescriptors {

	private static final Logger LOG = LoggerFactory.getLogger("RWMali");

	/** Pool ring size; must stay comfortably above the game's frames-in-flight (2-3). */
	private static final int POOL_RING = 12;

	/** Mirrors Program.PUSH_SIZE (RW's push-constant block) without loading RW classes early. */
	private static final int PUSH_SIZE = 64;

	/** Mirrors the stage flags Program uses for every binding and push range. */
	private static final int STAGES = VK10.VK_SHADER_STAGE_ALL;

	private static final Object LOCK = new Object();

	/** 0 = not decided yet, 1 = driver-native push descriptors (pass through), 2 = emulate. */
	private static volatile int mode;

	private static boolean announced;

	private static final long[] pools = new long[POOL_RING];
	private static volatile int frameSlot = -1;
	private static int setsPerPool = 2048;
	private static final List<Long> sparePools = new ArrayList<>();

	/** RW's (possibly still push-flagged) set layout handle -> our plain, allocatable set layout. */
	private static final Map<Long, Long> plainSetLayouts = new HashMap<>();
	/** Our plain set layout -> our pipeline layout with the same bindings + push range. */
	private static final Map<Long, Long> pipelineLayouts = new HashMap<>();

	private PushDescriptors() {
	}

	// =====================================================================
	// Mode
	// =====================================================================

	/**
	 * True when RW's push-descriptor calls must be replaced by our descriptor-set
	 * path: Android + driver (as reported by the raw probe) has no
	 * VK_KHR_push_descriptor + the fallback is not disabled. The decision is made
	 * lazily (first Program constructor / first bind) so the /water config is
	 * guaranteed to be loaded, and it can still be overridden at runtime by the
	 * NPE latch below when the probe was wrong.
	 */
	public static boolean emulating() {
		int m = mode;
		if (m == 0) {
			m = decide();
		}
		return m == 2;
	}

	private static int decide() {
		int m = 1;
		try {
			if ("off".equalsIgnoreCase(System.getProperty("rwmali.pushDescriptors"))) {
				m = 1; // explicit user override: leave RW completely untouched
			} else if (MaliCompat.isAndroid() && MaliSettings.maliPushDescriptors) {
				MaliProbe.Result r = MaliCompat.probe();
				if (r != null && r.hasDevice() && !r.pushDescriptor()) {
					m = 2;
				}
			}
		} catch (Throwable ignored) {
			// Any surprise here means "don't touch anything".
		}
		mode = m;
		return m;
	}

	// =====================================================================
	// Entry points (called from ProgramCompatMixin / WaterWorldFrameMixin)
	// =====================================================================

	/**
	 * Replacement for Program's KHRPushDescriptor.vkCmdPushDescriptorSetKHR call.
	 * Passes through untouched while the driver can really do it; on every other
	 * device allocates a descriptor set, writes the same descriptors into it and
	 * binds it the classic way.
	 */
	public static void bind(VkCommandBuffer cb, int bindPoint, long rwSetLayout, long pipelineLayout, int set,
					VkWriteDescriptorSet.Buffer writes) {
		if (!emulating()) {
			try {
				KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, bindPoint, pipelineLayout, set, writes);
				return;
			} catch (NullPointerException e) {
				// The probe thought the driver had the extension but the device-level
				// function pointer is null (e.g. a compatibility mod claimed it and the
				// real device was created without it). Latch to emulation permanently.
				mode = 2;
				LOG.warn("vkCmdPushDescriptorSetKHR is not actually available on this device -"
						+ " switching Realistic Water to the addon's descriptor-set fallback"
						+ " for the rest of this session");
			}
		}
		emulatedBind(cb, bindPoint, rwSetLayout, pipelineLayout, set, writes);
	}

	/** Called at HEAD of WaterWorld.renderFrame() - rotates (and resets) the pool ring. */
	public static void onFrame() {
		try {
			if (!emulating()) {
				return;
			}
			synchronized (LOCK) {
				if (frameSlot < 0) {
					frameSlot = 0; // nothing allocated yet, nothing to rotate
					return;
				}
				frameSlot++;
				int slot = frameSlot % POOL_RING;
				if (pools[slot] != 0L) {
					VK10.vkResetDescriptorPool(Vk.vk(), pools[slot], 0);
				}
			}
		} catch (Throwable ignored) {
			// Bookkeeping must never break a frame; the pool simply stays un-reset.
		}
	}

	// =====================================================================
	// The emulated bind
	// =====================================================================

	private static void emulatedBind(VkCommandBuffer cb, int bindPoint, long rwSetLayout, long pipelineLayout,
					int set, VkWriteDescriptorSet.Buffer writes) {
		if (!announced) {
			announced = true;
			LOG.info("[RW Mali] Push-descriptor fallback ACTIVE: this driver has no usable"
					+ " VK_KHR_push_descriptor, so Realistic Water's shader bindings go through an"
					+ " addon-managed descriptor pool instead (identical result, tiny per-bind CPU"
					+ " cost). Turn off with /water set maliPushDescriptors false");
			MaliReport.note("push-descriptor fallback", "ACTIVE");
		}
		long setLayout = plainLayoutFor(rwSetLayout, writes);
		long ownPipelineLayout = pipelineLayoutFor(setLayout);
		long descriptorSet = allocate(setLayout);
		// Re-target the very writes RW built for the push call into our set, then
		// update + bind. Push constants are untouched: RW records those separately
		// with its own pipeline layout, which stays valid.
		for (VkWriteDescriptorSet w : writes) {
			w.dstSet(descriptorSet);
		}
		VK10.vkUpdateDescriptorSets(Vk.vk(), writes, null);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer sets = stack.longs(descriptorSet);
			VK10.vkCmdBindDescriptorSets(cb, bindPoint, ownPipelineLayout, set, sets, null);
		}
	}

	// =====================================================================
	// Layouts
	// =====================================================================

	/**
	 * Our own set layout for an RW program, built from the writes of the bind call
	 * (they cover every binding - Program.bind writes all of them) with the same
	 * binding numbers, types and counts, stage flags for everything, but WITHOUT
	 * the push-descriptor flag so real sets can be allocated from it. Cached per RW
	 * set-layout handle.
	 */
	private static long plainLayoutFor(long rwSetLayout, VkWriteDescriptorSet.Buffer writes) {
		synchronized (LOCK) {
			Long cached = plainSetLayouts.get(rwSetLayout);
			if (cached != null) {
				return cached;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				int n = writes.remaining();
				VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(n, stack);
				for (int i = 0; i < n; i++) {
					VkWriteDescriptorSet w = writes.get(i);
					bindings.get(i)
							.binding(w.dstBinding())
							.descriptorType(w.descriptorType())
							.descriptorCount(Math.max(1, w.descriptorCount()))
							.stageFlags(STAGES);
				}
				VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
								.sType$Default()
								.flags(0) // plain layout - the whole point
								.pBindings(bindings);
				LongBuffer p = stack.mallocLong(1);
				Vk.check(VK10.vkCreateDescriptorSetLayout(Vk.vk(), info, null, p),
								"RW Mali plain descriptor set layout");
				long handle = p.get(0);
				plainSetLayouts.put(rwSetLayout, handle);
				return handle;
			}
		}
	}

	/**
	 * Our own pipeline layout for that set layout: identical bindings plus the same
	 * push-constant range RW uses (0..64, all stages). "Compatible for set 0" with
	 * the pipeline's own layout (same binding definitions once the push flag was
	 * stripped), which is all vkCmdBindDescriptorSets needs.
	 */
	private static long pipelineLayoutFor(long setLayout) {
		synchronized (LOCK) {
			Long cached = pipelineLayouts.get(setLayout);
			if (cached != null) {
				return cached;
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
								.stageFlags(STAGES).offset(0).size(PUSH_SIZE);
				VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
								.sType$Default()
								.pSetLayouts(stack.longs(setLayout))
								.pPushConstantRanges(range);
				LongBuffer p = stack.mallocLong(1);
				Vk.check(VK10.vkCreatePipelineLayout(Vk.vk(), info, null, p),
								"RW Mali fallback pipeline layout");
				long handle = p.get(0);
				pipelineLayouts.put(setLayout, handle);
				return handle;
			}
		}
	}

	// =====================================================================
	// Pools
	// =====================================================================

	/** Allocates one fresh descriptor set for the given (plain) set layout. */
	private static long allocate(long setLayout) {
		synchronized (LOCK) {
			VkDevice device = Vk.vk();
			int slot = frameSlot < 0 ? 0 : frameSlot % POOL_RING;
			if (pools[slot] == 0L) {
				pools[slot] = createPool(device, setsPerPool);
			}
			long handle = tryAllocate(device, pools[slot], setLayout);
			if (handle == 0L) {
				// Ring pool exhausted: double future sizing and serve this frame
				// from a one-off spare pool (never reset, never destroyed).
				setsPerPool = Math.min(16384, setsPerPool * 2);
				long spare = createPool(device, setsPerPool);
				sparePools.add(spare);
				handle = tryAllocate(device, spare, setLayout);
			}
			if (handle == 0L) {
				throw new IllegalStateException("RW Mali push-descriptor fallback: the driver"
						+ " refuses further descriptor sets. Turn the water off, or disable"
						+ " the fallback with /water set maliPushDescriptors false");
			}
			return handle;
		}
	}

	private static long tryAllocate(VkDevice device, long pool, long setLayout) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
							.sType$Default()
							.descriptorPool(pool)
							.pSetLayouts(stack.longs(setLayout));
			LongBuffer out = stack.mallocLong(1);
			int err = VK10.vkAllocateDescriptorSets(device, info, out);
			return err == VK10.VK_SUCCESS ? out.get(0) : 0L;
		} catch (Throwable t) {
			return 0L;
		}
	}

	private static long createPool(VkDevice device, int maxSets) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Generous per-type caps; mobile drivers commit pool memory lazily, and
			// a pool that turns out too small just triggers the grow path above.
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(8, stack);
			sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4 * maxSets);
			sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(4 * maxSets);
			sizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2 * maxSets);
			sizes.get(3).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2 * maxSets);
			sizes.get(4).type(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(2 * maxSets);
			sizes.get(5).type(VK10.VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(maxSets);
			sizes.get(6).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER).descriptorCount(maxSets);
			sizes.get(7).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER).descriptorCount(maxSets);
			VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
							.sType$Default()
							.flags(0)
							.maxSets(maxSets)
							.pPoolSizes(sizes);
			LongBuffer p = stack.mallocLong(1);
			Vk.check(VK10.vkCreateDescriptorPool(device, info, null, p), "RW Mali descriptor pool");
			return p.get(0);
		}
	}
}
