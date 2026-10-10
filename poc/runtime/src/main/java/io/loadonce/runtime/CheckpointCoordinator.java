package io.loadonce.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Optional bridge to the real JDK CRaC API. Stock JDKs are explicitly unsupported.
 * Construction and resource registration do not create a checkpoint.
 */
public final class CheckpointCoordinator {
    private final ResourceLifecycle lifecycle;
    private final AtomicBoolean requested = new AtomicBoolean();
    private Method checkpointRestore;
    // CRaC contexts may retain resources weakly. These references must live as long as the coordinator.
    private Object rootContext;
    private Object resourceProxy;
    private volatile boolean restored;
    private String supportMessage;

    public CheckpointCoordinator() {
        this(CheckpointSafety::denied);
    }

    public CheckpointCoordinator(Supplier<CheckpointSafety> boundaryGuard) {
        lifecycle = new ResourceLifecycle(boundaryGuard);
        discoverCrac();
    }

    public boolean isSupported() {
        return checkpointRestore != null;
    }

    public String supportMessage() {
        return supportMessage;
    }

    public void register(ResourceAdapter adapter) {
        if (requested.get()) {
            throw new IllegalStateException("Cannot register a resource while requesting a checkpoint");
        }
        lifecycle.add(adapter);
    }

    /** Caller must configure the CRaC JDK's checkpoint location and retain this coordinator. */
    public CheckpointResult checkpoint() {
        if (!isSupported()) {
            return new CheckpointResult(CheckpointResult.Status.UNSUPPORTED, supportMessage);
        }
        if (!requested.compareAndSet(false, true)) {
            return new CheckpointResult(CheckpointResult.Status.FAILED, "Checkpoint request already active");
        }
        restored = false;
        try {
            CheckpointResult previousRecoveryFailure = lifecycle.beginAttempt();
            if (previousRecoveryFailure != null) return previousRecoveryFailure;
            checkpointRestore.invoke(null);
            if (!restored) {
                return rollbackFailure("CRaC returned without a restore callback");
            }
            CheckpointResult failure = lifecycle.lastFailure();
            return failure == null
                    ? new CheckpointResult(CheckpointResult.Status.RESTORED, "CRaC restored execution")
                    : failure;
        } catch (InvocationTargetException failure) {
            CheckpointResult recorded = lifecycle.lastFailure();
            if (recorded != null) {
                return recorded;
            }
            return rollbackFailure("CRaC checkpoint failed ("
                    + failure.getCause().getClass().getSimpleName() + ")");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return rollbackFailure("CRaC invocation failed (" + failure.getClass().getSimpleName() + ")");
        } finally {
            requested.set(false);
        }
    }

    private CheckpointResult rollbackFailure(String detail) {
        try {
            lifecycle.restore();
        } catch (Exception | Error failure) {
            return lifecycle.lastFailure();
        }
        return new CheckpointResult(CheckpointResult.Status.FAILED, detail);
    }

    private void discoverCrac() {
        try {
            Class<?> core = Class.forName("jdk.crac.Core");
            Class<?> resource = Class.forName("jdk.crac.Resource");
            Class<?> context = Class.forName("jdk.crac.Context");
            if (!core.getModule().isNamed() || core.getClassLoader() != null) {
                supportMessage = "jdk.crac must be supplied by a CRaC-enabled JDK; classpath emulation is refused";
                return;
            }
            Method checkpoint = core.getMethod("checkpointRestore");
            rootContext = core.getMethod("getGlobalContext").invoke(null);
            resourceProxy = Proxy.newProxyInstance(resource.getClassLoader(), new Class<?>[] { resource },
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "beforeCheckpoint" -> lifecycle.prepare();
                            case "afterRestore" -> {
                                // Some implementations invoke this even after a veto: resource rollback is idempotent.
                                boolean prepared = lifecycle.isPrepared();
                                if (prepared) Readiness.beginRestoredRun();
                                lifecycle.restore();
                                restored = prepared;
                            }
                            case "hashCode" -> { return System.identityHashCode(proxy); }
                            case "equals" -> { return proxy == arguments[0]; }
                            case "toString" -> { return "LoadOnce CRaC resource coordinator"; }
                            default -> throw new UnsupportedOperationException(method.getName());
                        }
                        return null;
                    });
            context.getMethod("register", resource).invoke(rootContext, resourceProxy);
            checkpointRestore = checkpoint;
            supportMessage = "Real JDK CRaC API available; operating-system checkpoint capability is not yet tested";
        } catch (ClassNotFoundException failure) {
            supportMessage = "Stock JDK: jdk.crac is unavailable; no process checkpoint was created";
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            supportMessage = "JDK CRaC bridge unavailable (" + failure.getClass().getSimpleName() + ")";
        }
    }
}
