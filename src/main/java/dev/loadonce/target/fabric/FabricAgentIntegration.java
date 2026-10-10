package dev.loadonce.target.fabric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps the agent endpoint in the system loader and game ownership in Knot. */
public final class FabricAgentIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger("loadonce");

    private FabricAgentIntegration() {}

    public static void initialize() {
        try {
            Class<?> agent = Class.forName("dev.loadonce.agent.LoadOnceAgent", false,
                    ClassLoader.getSystemClassLoader());
            Object status = agent.getMethod("startupStatus").invoke(null);
            // The endpoint returns only fixed capability/policy identifiers.
            if (!(status instanceof String text) || text.length() > 512
                    || !text.matches("[A-Za-z0-9_.=; -]+")) {
                throw new IllegalStateException("Invalid agent status");
            }
            LOGGER.info("LOADONCE_FABRIC_AGENT {} gameCheckpoint=disabled", text);
        } catch (ClassNotFoundException absent) {
            LOGGER.info("LOADONCE_FABRIC_AGENT present=false gameCheckpoint=disabled");
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException failure) {
            // Reflection failures can contain paths or launch arguments.
            LOGGER.warn("LOADONCE_FABRIC_AGENT status=unavailable gameCheckpoint=disabled");
        }
    }
}
