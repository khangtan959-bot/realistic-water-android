package me.rwbridge;

import net.fabricmc.api.ClientModInitializer;

public class Bridge implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MobileBridge.init();
	}
}
