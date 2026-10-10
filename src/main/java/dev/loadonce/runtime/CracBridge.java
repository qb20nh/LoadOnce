package dev.loadonce.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Optional bridge to a real jdk.crac runtime. No CRaC dependency is required to
 * load the common runtime. A process may install one bridge; its resource proxy
 * stays strongly referenced because CRaC contexts can hold resources weakly.
 * Callbacks must execute on the coordinator owner thread, including graphics.
 */
public final class CracBridge {
    public static final class CracUnavailableException extends Exception {
        private static final long serialVersionUID = 1L;
        private CracUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /** Proves callback execution only, not that an OS image was created or restored. */
    public record CallbackResult(long preparationCallbacks, long restorationCallbacks) { }

    private static CracBridge installed;
    private final CheckpointCoordinator coordinator;
    private final Method checkpointRestore;
    private final Object resourceProxy;
    private boolean registrationCompleted;
    private boolean invocationActive;
    private boolean beforeAttempted;
    private boolean prepared;
    private long preparations;
    private long restorations;

    private CracBridge(CheckpointCoordinator coordinator, Method checkpointRestore, Class<?> resourceClass) {
        this.coordinator = coordinator;
        this.checkpointRestore = checkpointRestore;
        resourceProxy = Proxy.newProxyInstance(resourceClass.getClassLoader(), new Class<?>[] { resourceClass },
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "LoadOnce CRaC lifecycle resource";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    switch (method.getName()) {
                        case "beforeCheckpoint" -> beforeCheckpoint();
                        case "afterRestore" -> afterRestore();
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
    }

    public static synchronized CracBridge install(CheckpointCoordinator coordinator) throws CracUnavailableException {
        Objects.requireNonNull(coordinator, "coordinator").requireOwnerThread();
        if (coordinator.state() != CheckpointCoordinator.State.READY) {
            throw new IllegalStateException("Install CRaC integration before preparation");
        }
        if (installed != null) throw new IllegalStateException("A process CRaC bridge is already installed");
        try {
            Class<?> coreClass = Class.forName("jdk.crac.Core");
            Class<?> resourceClass = Class.forName("jdk.crac.Resource");
            Class<?> contextClass = Class.forName("jdk.crac.Context");
            Object context = coreClass.getMethod("getGlobalContext").invoke(null);
            Method register = contextClass.getMethod("register", resourceClass);
            CracBridge bridge = new CracBridge(coordinator, coreClass.getMethod("checkpointRestore"), resourceClass);
            // Claim the process slot before registration: a failure must not allow
            // another resource to be installed after ambiguous registration.
            installed = bridge;
            register.invoke(context, bridge.resourceProxy);
            bridge.registrationCompleted = true;
            return bridge;
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new CracUnavailableException("A compatible jdk.crac API is unavailable or registration failed", failure);
        }
    }

    /**
     * Invoke the runtime engine on the owner thread. Engine flags and image
     * verification belong to the launcher. This never simulates CRaC callbacks.
     */
    public CallbackResult checkpointRestore() throws Exception {
        coordinator.requireOwnerThread();
        if (invocationActive) throw new IllegalStateException("Reentrant CRaC invocation");
        if (coordinator.state() != CheckpointCoordinator.State.READY) {
            throw new IllegalStateException("CRaC invocation requires a ready coordinator");
        }
        long previousPreparations = preparations;
        long previousRestorations = restorations;
        invocationActive = true;
        try {
            try {
                checkpointRestore.invoke(null);
            } catch (InvocationTargetException failure) {
                rethrow(failure.getCause());
            }
            if (preparations == previousPreparations || restorations == previousRestorations) {
                throw new IllegalStateException("CRaC returned without completing the registered lifecycle callbacks");
            }
            return new CallbackResult(preparations - previousPreparations, restorations - previousRestorations);
        } catch (Throwable failure) {
            // Some engines throw without calling afterRestore. Recover only the
            // resource lifecycle, and continue to report the engine failure.
            if (coordinator.state() == CheckpointCoordinator.State.PREPARED) {
                try {
                    coordinator.abortCheckpoint(failure);
                } catch (Throwable recoveryFailure) {
                    Exception combined = new Exception("CRaC engine failed and resource recovery failed", failure);
                    combined.addSuppressed(recoveryFailure);
                    failure = combined;
                }
            }
            rethrow(failure);
            throw new AssertionError("unreachable");
        } finally {
            beforeAttempted = false;
            prepared = false;
            invocationActive = false;
        }
    }

    private void beforeCheckpoint() throws CheckpointCoordinator.CheckpointException {
        coordinator.requireOwnerThread();
        if (!registrationCompleted) throw new IllegalStateException("CRaC bridge registration did not complete");
        if (beforeAttempted) throw new IllegalStateException("Duplicate CRaC beforeCheckpoint callback");
        beforeAttempted = true;
        coordinator.prepareCheckpoint();
        prepared = true;
        preparations++;
    }

    private void afterRestore() throws CheckpointCoordinator.CheckpointException {
        coordinator.requireOwnerThread();
        if (!beforeAttempted) throw new IllegalStateException("CRaC afterRestore without beforeCheckpoint");
        try {
            // CRaC calls afterRestore even when beforeCheckpoint failed. The
            // coordinator already rolled back a failed preparation in that case.
            if (prepared) {
                coordinator.restoreCheckpoint();
                restorations++;
            }
        } finally {
            prepared = false;
            beforeAttempted = false;
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new Exception("CRaC runtime invocation failed", failure);
    }
}
