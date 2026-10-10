package com.qb20nh.loadonce;

import com.qb20nh.loadonce.target.fabric.FabricAgentIntegration;
import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoadOnceMod implements ModInitializer {
	public static final String MOD_ID = "loadonce";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		FabricAgentIntegration.initialize();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
