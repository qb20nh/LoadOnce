package com.qb20nh.loadonce.client;

import com.qb20nh.loadonce.target.fabric.FabricMainMenuProbe;
import com.qb20nh.loadonce.target.fabric.FabricPrototypeBootProbe;
import net.fabricmc.api.ClientModInitializer;

public class LoadOnceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FabricMainMenuProbe.initialize();
		if (Boolean.getBoolean("loadonce.prototype.verify")) {
			FabricPrototypeBootProbe.initialize();
		}
	}
}
