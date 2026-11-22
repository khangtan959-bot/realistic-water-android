package me.rwmali;

import net.fabricmc.api.ClientModInitializer;

/**
 * Entrypoint of the Realistic Water Android - Mali GPU addon.
 *
 * The real work lives in MaliCompat.init(): probing the device's Vulkan
 * driver for the two features ARM Mali drivers typically lack (compute
 * subgroup ops, VK_KHR_push_descriptor) and arming the matching fallbacks.
 */
public class MaliAddon implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MaliCompat.init();
	}
}
