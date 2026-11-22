package me.rwmali.mixin;

import java.lang.reflect.Field;
import java.util.Map;
import me.rwmali.MaliSettings;
import me.skaffy.client.water.WaterSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Appends the addon's own settings (from MaliSettings) to the mod's
 * reflection-based settings map, so they appear in /water, get saved to
 * realistic-water.properties and respect reset()/defaults - exactly like the
 * official bridge's settings, which the bridge adds by redirecting the
 * Class.getFields() call INSIDE fields().
 *
 * Verified against RW 1.4.1 bytecode: fields() builds a mutable TreeMap of
 * every static public field (mod's own + whatever the bridge's redirect
 * contributed) keyed by field name. This mixin hooks at the RETURN and simply
 * puts the addon's fields into that same map, so the bridge's redirect and
 * this append can never collide. If the addon is ever installed without the
 * bridge, its settings still register the same way.
 */
@Mixin(WaterSettings.class)
public abstract class WaterSettingsAddonMixin {

	@Inject(method = "fields", at = @At("RETURN"), require = 0)
	private static void rwmali$appendSettings(CallbackInfoReturnable<Map<String, Field>> cir) {
		try {
			Map<String, Field> fields = cir.getReturnValue();
			if (fields == null) {
				return;
			}
			for (Field field : MaliSettings.class.getFields()) {
				fields.put(field.getName(), field);
			}
		} catch (Throwable ignored) {
			// Never break the settings map; worst case the addon settings stay hidden.
		}
	}
}
