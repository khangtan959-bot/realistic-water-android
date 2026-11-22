package me.rwmali.mixin;

import me.rwmali.ShaderVariants;
import me.skaffy.client.vk.Shaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves the addon's no-subgroup shader variants.
 *
 * Every shader source - including every #include - goes through Shaders.load(),
 * so a single HEAD hook here swaps in the variant text for the 8 files that use
 * subgroup ops, exactly when the device needs it (see ShaderVariants: only on
 * Android, only while the driver lacks compute subgroup ops, and only when the
 * installed mod's file matches the verified original byte-for-byte).
 *
 * Everything else - and desktop, and every other shader - is untouched. On a
 * cache decision the method simply doesn't cancel, and the mod's own resource
 * loads as usual. require = 0 keeps this a no-op if Shaders ever changes shape.
 */
@Mixin(Shaders.class)
public abstract class ShadersLoadMixin {

	@Inject(method = "load", at = @At("HEAD"), cancellable = true, require = 0)
	private static void rwmali$compatVariant(String name, CallbackInfoReturnable<String> cir) {
		String variant = ShaderVariants.forShader(name);
		if (variant != null) {
			cir.setReturnValue(variant);
		}
	}
}
