package me.rwbridge.mixin;

import me.rwbridge.MobileBridge;
import me.skaffy.client.compat.FrameTarget;
import me.skaffy.client.water.WaterWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * - Caps the number of simultaneously simulated water regions on mobile
 *   (the first "16" constant in renderFrameUnsafe is the MAX_REGIONS check).
 * - Feeds frame timings to the auto-tuner every rendered frame.
 * - Replaces the "needs Vulkan" chat message with a phone-specific explanation
 *   (Prefer Vulkan in-game, MobileGlues is GL-only, Turnip for Adreno, Vulkan 1.2+).
 */
@Mixin(WaterWorld.class)
public abstract class WaterWorldMixin {

        /**
         * The slice ends at the first WaterRenderer constructor call (verified at bytecode
         * offset 1651); the MAX_REGIONS check's 16 (offset 875) is the only 16 before it.
         * The two later 16s (VkBuf.UNIFORM at 1722, slot math at 3407) stay untouched.
         */
        @ModifyConstant(
                        method = "renderFrameUnsafe",
                        constant = @Constant(intValue = 16),
                        slice = @Slice(to = @At(value = "INVOKE", target = "Lme/skaffy/client/water/WaterRenderer;<init>()V")),
                        require = 0)
        private int rwbridge$maxRegions(int original) {
                return MobileBridge.capRegions(original);
        }

        @Inject(method = "renderFrame", at = @At("HEAD"), require = 0)
        private void rwbridge$frameTick(FrameTarget target, CallbackInfo ci) {
                MobileBridge.onFrameStart();
                MobileBridge.onFrameCacheTick();
                MobileBridge.onRenderFrame();
        }

        @Inject(method = "renderFrame", at = @At("RETURN"), require = 0)
        private void rwbridge$frameEnd(FrameTarget target, CallbackInfo ci) {
                MobileBridge.onFrameEnd();
        }

        @Inject(method = "vulkanReady", at = @At("HEAD"), cancellable = true, require = 0)
        private void rwbridge$androidVulkanMessage(CallbackInfoReturnable<Boolean> cir) {
                MobileBridge.vulkanReadyAndroid(cir);
        }
}
