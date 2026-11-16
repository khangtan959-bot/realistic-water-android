package me.rwbridge.mixin;

import java.lang.reflect.Field;
import me.rwbridge.MobileBridge;
import me.rwbridge.MobileSettings;
import me.skaffy.client.water.WaterSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the mod's reflection-based settings system:
 * - fields(): adds the bridge's own settings (from MobileSettings) so they appear in
 *   /water, get saved to realistic-water.properties and respect reset()/defaults.
 * - load(): applies the mobile preset before the config file is read, so saved values
 *   still win.
 * - set(): re-applies the preset when the user changes mobilePreset in-game.
 */
@Mixin(WaterSettings.class)
public abstract class WaterSettingsMixin {

        @Redirect(
                        method = "fields",
                        at = @At(value = "INVOKE", target = "Ljava/lang/Class;getFields()[Ljava/lang/reflect/Field;"),
                        require = 0)
        private static Field[] rwbridge$addSettings(Class<?> owner) {
                Field[] own = owner.getFields();
                Field[] extra;
                try {
                        extra = MobileSettings.class.getFields();
                } catch (Throwable t) {
                        return own;
                }
                Field[] out = java.util.Arrays.copyOf(own, own.length + extra.length);
                System.arraycopy(extra, 0, out, own.length, extra.length);
                return out;
        }

        @Inject(method = "load", at = @At("HEAD"), require = 0)
        private static void rwbridge$beforeLoad(CallbackInfo ci) {
                MobileBridge.onLoadSettings();
        }

        @Inject(method = "load", at = @At("RETURN"), require = 0)
        private static void rwbridge$afterLoad(CallbackInfo ci) {
                MobileBridge.onLoadSettingsDone();
        }

        @Inject(method = "set", at = @At("RETURN"), require = 0)
        private static void rwbridge$afterSet(String name, String value, CallbackInfoReturnable<String> cir) {
                MobileBridge.onSettingSet(name);
        }
}
