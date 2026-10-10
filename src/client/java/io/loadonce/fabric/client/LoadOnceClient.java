package io.loadonce.fabric.client;

import io.loadonce.fabric.LoadOnceMod;
import net.fabricmc.api.ClientModInitializer;

public final class LoadOnceClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LoadOnceMod.LOGGER.info("Client readiness will be measured after an interactive main-menu frame; CPU and GPU initialization still run normally");
    }
}
