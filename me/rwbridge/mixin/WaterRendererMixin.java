package me.rwbridge.mixin;

import me.rwbridge.MobileBridge;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.VkTex;
import me.skaffy.client.water.WaterRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Renders the water front g-buffer in RGBA16F instead of RGBA32F on phones
 * (halves the memory traffic of every water render pass).
 *
 * Both injection points are value-filtered through MobileBridge.mapFormat(): only the
 * exact RGBA32F constant (109) is rewritten to RGBA16F (97), so pipeline state and
 * texture always stay consistent, and any other format or call site passes through
 * untouched. require = 0 keeps a future Realistic Water update from ever crashing -
 * worst case the patch is a no-op.
 */
@Mixin(WaterRenderer.class)
public abstract class WaterRendererMixin {

	@ModifyArg(
			method = "<init>()V",
			at = @At(
					value = "INVOKE",
					target = "Lme/skaffy/client/vk/GraphicsProgram$State;<init>(IIZZILme/skaffy/client/vk/GraphicsProgram$Blend;I)V"),
			index = 0,
			require = 0)
	private int rwbridge$frontGbufferFormat(int format) {
		return MobileBridge.mapFormat(format);
	}

	@ModifyArg(
			method = "ensureTargets",
			at = @At(value = "INVOKE", target = "Lme/skaffy/client/vk/VkTex;<init>(IIII)V"),
			index = 0,
			require = 0)
	private int rwbridge$gbufferTextureFormat(int format) {
		return MobileBridge.mapFormat(format);
	}
}
