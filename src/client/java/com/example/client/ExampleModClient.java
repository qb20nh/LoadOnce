package com.example.client;

import dev.loadonce.target.fabric.FabricMainMenuProbe;
import net.fabricmc.api.ClientModInitializer;

public class ExampleModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		FabricMainMenuProbe.initialize();
	}
}
