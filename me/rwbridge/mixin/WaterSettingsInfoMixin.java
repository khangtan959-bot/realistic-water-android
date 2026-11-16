package me.rwbridge.mixin;

import java.util.List;
import java.util.Map;
import me.rwbridge.MobileBridge;
import me.skaffy.client.water.WaterSettingsInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the bridge settings real entries (slider/switch + description) in the /water
 * menu instead of the "no description" placeholders the mod creates for unknown fields.
 */
@Mixin(WaterSettingsInfo.class)
public abstract class WaterSettingsInfoMixin {

	@Accessor("BY_NAME")
	static Map<String, WaterSettingsInfo.Info> rwbridge$byName() {
		throw new AssertionError();
	}

	@Accessor("BY_CATEGORY")
	static Map<String, List<String>> rwbridge$byCategory() {
		throw new AssertionError();
	}

	@Inject(method = "<clinit>", at = @At("TAIL"), require = 0)
	private static void rwbridge$describe(CallbackInfo ci) {
		MobileBridge.registerSettingInfos(rwbridge$byName(), rwbridge$byCategory());
	}
}
