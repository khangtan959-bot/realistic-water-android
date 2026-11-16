package me.rwbridge;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * The bridge's own settings. They are registered into Realistic Water's settings system
 * (WaterSettings.fields()) by WaterSettingsMixin, so they show up in /water, get saved
 * to realistic-water.properties and can be changed in-game like any other setting.
 */
public final class MobileSettings {
        public static int mobilePreset = 0; // 0=off (mod defaults, pure optimization) 1=low 2=balanced 3=high
        public static boolean mobileHalfGbuffer = true;
        public static int mobileMaxRegions = 8;
        public static boolean mobileSpirvCache = true;
        public static boolean mobilePipelineCache = true;
        public static boolean mobileWaterStats = true;
        public static boolean mobileAutoTune = true;

        private MobileSettings() {
        }

        static void collect(Map<String, Field> out) {
                for (Field field : MobileSettings.class.getFields()) {
                        out.put(field.getName(), field);
                }
        }
}
