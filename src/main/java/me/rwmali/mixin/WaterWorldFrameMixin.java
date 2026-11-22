package me.rwmali.mixin;

import me.skaffy.client.compat.FrameTarget;
import me.skaffy.client.water.WaterWorld;
import me.rwmali.PushDescriptors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the addon's push-descriptor fallback a tick at the head of every
 * rendered water frame: it rotates (and resets) the descriptor-pool ring of
 * the fallback (a no-op unless the fallback is active on this device - see
 * PushDescriptors).
 *
 * The official bridge has its own HEAD handler on this method for its frame
 * stats; the two injections are independent and both run - Mixin happily
 * composes any number of injectors on the same target.
 */
@Mixin(WaterWorld.class)
public abstract class WaterWorldFrameMixin {

	@Inject(method = "renderFrame", at = @At("HEAD"), require = 0)
	private void rwmali$frameTick(FrameTarget target, CallbackInfo ci) {
		PushDescriptors.onFrame();
	}
}
