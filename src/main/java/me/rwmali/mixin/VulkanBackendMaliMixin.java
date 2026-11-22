package me.rwmali.mixin;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import me.rwmali.MaliCompat;
import me.rwmali.MaliProbe;
import me.rwmali.VulkanFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "Second opinion" on the Vulkan backend availability for phones whose raw
 * driver reports below Vulkan 1.2 (plenty of Mali phones on older Android).
 *
 * The official bridge 1.0.0 answers checkBackendAvailable() from its own raw
 * probe and vetoes the Vulkan backend when the driver alone is below 1.2.
 * That veto fires BEFORE Vulkan feature mods (Vulky Enhanced, VEE) - which
 * add the missing Vulkan 1.2 features in software - ever get their chance,
 * so on such phones those mods are silently disabled.
 *
 * This mixin runs AFTER the bridge's handler (the addon's mixin config uses
 * priority 1100, i.e. lower precedence than the bridge's default 1000) and
 * re-allows the backend only when ALL of these hold:
 *  - the bridge actually vetoed (its exception is set),
 *  - we are on Android,
 *  - the probe found a real Vulkan physical device (feature mods add features
 *    ON TOP of a driver, they are not software rasterizers),
 *  - a known Vulkan feature mod is loaded and enabled.
 *
 * If the backend then still fails, Minecraft falls back to OpenGL exactly as
 * it always does - this can never hard-crash the game. require = 0 keeps it a
 * no-op on any future Minecraft version where the target moves.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMaliMixin {

	@Inject(method = "checkBackendAvailable", at = @At("HEAD"), cancellable = true, require = 0)
	private static void rwmali$featureModSecondOpinion(CallbackInfoReturnable<BackendCreationException> cir) {
		try {
			if (!cir.isCancelled()) {
				return; // the bridge had no opinion; vanilla probing runs
			}
			if (cir.getReturnValue() == null) {
				return; // the bridge already said "available"
			}
			if (!MaliCompat.isAndroid()) {
				return;
			}
			MaliProbe.Result probe = MaliCompat.probe();
			if (probe == null || !probe.hasDevice()) {
				return; // no physical device: no feature mod can help
			}
			if (!VulkanFeatures.active()) {
				return; // no enabled Vulkan feature mod: the bridge's veto stands
			}
			MaliCompat.info("Mali addon: the bridge vetoed the Vulkan backend because the raw driver is below 1.2, but "
					+ VulkanFeatures.describe() + " can add the missing features in software - letting the Vulkan"
					+ " backend start (if it still fails, the game falls back to OpenGL safely).");
			cir.setReturnValue(null); // null = available; let the feature mod do its work
		} catch (Throwable ignored) {
			// Never interfere with backend selection on an unexpected error.
		}
	}
}
