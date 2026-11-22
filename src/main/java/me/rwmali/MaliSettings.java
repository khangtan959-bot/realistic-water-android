package me.rwmali;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.skaffy.client.water.WaterSettingsInfo;

/**
 * The addon's own settings. They are appended (by WaterSettingsAddonMixin) to
 * the very same /water settings list the official bridge already extends, so
 * they show up in /water, get saved to realistic-water.properties and can be
 * changed in-game like any other setting.
 *
 * Name prefix "mali..." keeps them apart from the bridge's "mobile..."
 * settings - same menu, clearly different owner.
 */
public final class MaliSettings {

        /** No-subgroup shader variants: 0=auto (probe decides), 1=force on, 2=force off. Restart to apply. */
        public static int maliSubgroupCompat = 0;

        /** Bind Realistic Water's descriptors through an addon-managed pool on drivers without VK_KHR_push_descriptor. */
        public static boolean maliPushDescriptors = true;

        /** Comma-separated mod ids of further Vulkan-feature mods to recognise (built-ins: vulky, vulkanextensionemulator). */
        public static String maliVulkanFeatureMods = "";

        private MaliSettings() {
        }

        /** Gives the addon settings real entries in the /water menu (called from WaterSettingsInfoAddonMixin). */
        public static void register(Map<String, WaterSettingsInfo.Info> byName, Map<String, List<String>> byCategory) {
                try {
                        String category = "Mali GPU addon";
                        byCategory.computeIfAbsent(category, k -> new ArrayList<>());
                        List<String> other = byCategory.get("Other");
                        entry(byName, byCategory, other, category, "maliSubgroupCompat", "Subgroup compat shaders",
                                                        WaterSettingsInfo.Kind.INTEGER, WaterSettingsInfo.Cost.LOW,
                                                        "No-subgroup shader variants for phone GPUs that report Vulkan 1.1+ but never expose the"
                                                                                        + " subgroup operations Realistic Water's particle simulation uses (typical on ARM"
                                                                                        + " Mali Bifrost, e.g. Mali-G52 - the driver says 'Vulkan 1.3' yet the ops are missing,"
                                                                                        + " so every water pipeline fails with 'Realistic Water failed' and placing water"
                                                                                        + " stops working). On such devices the addon swaps in hash-verified copies of the"
                                                                                        + " 8 affected shaders with the group ops replaced by plain atomics - identical"
                                                                                        + " physics and looks, slightly higher GPU cost. 0=auto (recommended), 1=always on,"
                                                                                        + " 2=off. Restart the game to apply.",
                                                        0, 2, 1);
                        entry(byName, byCategory, other, category, "maliPushDescriptors", "Push-descriptor fallback",
                                                        WaterSettingsInfo.Kind.SWITCH, WaterSettingsInfo.Cost.LOW,
                                                        "Runs Realistic Water on GPU drivers without VK_KHR_push_descriptor - every Mali phone"
                                                                                        + " driver so far, and every setup where Vulky/VEE supplies that extension in"
                                                                                        + " software (they claim it to Minecraft but strip it from the real device, which"
                                                                                        + " crashes the water with 'Realistic Water failed: null'). The addon then binds"
                                                                                        + " the water shaders' descriptors through its own descriptor pool instead -"
                                                                                        + " identical rendering, tiny per-bind CPU cost. Leave ON. Restart to apply.",
                                                        0, 1, 1);
                        entry(byName, byCategory, other, category, "maliVulkanFeatureMods", "Extra Vulkan feature mods",
                                                        WaterSettingsInfo.Kind.TEXT, WaterSettingsInfo.Cost.NONE,
                                                        "Comma-separated mod ids of further mods that add or emulate Vulkan features (built-in"
                                                                                        + " already: vulky, vulkanextensionemulator). The addon will trust them for the"
                                                                                        + " Vulkan backend verdict exactly like the built-ins, so newly released Vulkan"
                                                                                        + " compatibility mods work without an addon update. Example: 'somemod, anothermod'."
                                                                                        + " The JVM property -Drwmali.vulkanFeatureMods=... does the same. Restart to apply.",
                                                        0, 1, 1);
                } catch (Throwable t) {
                        MaliCompat.warn("Couldn't register the Mali addon settings in the /water menu: " + t);
                }
        }

        private static void entry(Map<String, WaterSettingsInfo.Info> byName, Map<String, List<String>> byCategory,
                                        List<String> other, String category, String name, String label, WaterSettingsInfo.Kind kind,
                                        WaterSettingsInfo.Cost cost, String description, int min, int max, int step) {
                if (other != null) {
                        other.remove(name);
                }
                byName.put(name, new WaterSettingsInfo.Info(name, category, label, kind, cost, "", description, min, max, step));
                byCategory.get(category).add(name);
        }
}
