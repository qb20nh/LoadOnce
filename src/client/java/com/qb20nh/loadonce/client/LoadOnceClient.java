package com.qb20nh.loadonce.client;

import com.qb20nh.loadonce.target.fabric.FabricMainMenuProbe;
import net.fabricmc.api.ClientModInitializer;

public class LoadOnceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FabricMainMenuProbe.initialize();
	}
}
