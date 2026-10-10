package io.loadonce.fabric;

import io.loadonce.runtime.Readiness;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Version-specific readiness bridge for the loader-independent Stage 1 runtime. */
public final class LoadOnceMod implements ModInitializer {
    public static final String MOD_ID = "loadonce";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // An integrated server is gameplay state, not the client startup milestone.
            if (server.isDedicatedServer()) {
                Readiness.markServerReady();
            }
        });
        LOGGER.info("Experimental LoadOnce readiness probe enabled; initialization and world loading still run normally");
    }
}
