package com.qb20nh.loadonce.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Early checkpoint protection for ordinary, externally launched applications. */
public final class LoadOnceAgent {
    private static final String NOT_STARTED = "LOADONCE_AGENT initialized=false crac_api=unprobed checkpoint_guard=false";
    private static final String UNAVAILABLE = "LOADONCE_AGENT initialized=true crac_api=unavailable checkpoint_guard=false";
    private static final String GUARDED = "LOADONCE_AGENT initialized=true crac_api=available checkpoint_guard=true policy=deny";
    private static final String FAILED = "LOADONCE_AGENT initialized=false crac_api=failed checkpoint_guard=false";
    private static final String INITIALIZATION_FAILURE = "LoadOnce agent initialization failed";
    private static final String CHECKPOINT_VETO = "LoadOnce checkpoint denied: game boundary is unavailable";
    private static boolean started;
    private static volatile String status = NOT_STARTED;
    // CRaC contexts may retain resources weakly. Keep this independently registered
    // guard strongly reachable for the entire process, including denied attempts.
    private static Object checkpointGuard;

    private LoadOnceAgent() { }

    public static synchronized void premain(String arguments, Instrumentation instrumentation) {
        if (started) {
            status = FAILED;
            throw new IllegalStateException(INITIALIZATION_FAILURE);
        }
        started = true;
        if (arguments != null && !arguments.isEmpty()) {
            status = FAILED;
            throw new IllegalStateException(INITIALIZATION_FAILURE);
        }
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        final Class<?> core;
        try {
            core = Class.forName("jdk.crac.Core", false, loader);
        } catch (ClassNotFoundException absent) {
            status = UNAVAILABLE;
            reportIfRequested();
            return;
        } catch (LinkageError | RuntimeException failure) {
            status = FAILED;
            throw new IllegalStateException(INITIALIZATION_FAILURE);
        }
        try {
            Class<?> resource = Class.forName("jdk.crac.Resource", true, loader);
            Class<?> context = Class.forName("jdk.crac.Context", true, loader);
            if (!resource.isInterface()) throw new ReflectiveOperationException();
            resource.getMethod("beforeCheckpoint", context);
            resource.getMethod("afterRestore", context);
            Object global = core.getMethod("getGlobalContext").invoke(null);
            if (!context.isInstance(global)) throw new ReflectiveOperationException();
            Method register = context.getMethod("register", resource);
            checkpointGuard = Proxy.newProxyInstance(resource.getClassLoader(), new Class<?>[] { resource },
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "LoadOnce checkpoint guard";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> throw new IllegalStateException(INITIALIZATION_FAILURE);
                            };
                        }
                        return switch (method.getName()) {
                            case "beforeCheckpoint" -> throw new CheckpointVetoException();
                            case "afterRestore" -> null;
                            default -> throw new IllegalStateException(INITIALIZATION_FAILURE);
                        };
                    });
            register.invoke(global, checkpointGuard);
            status = GUARDED;
            reportIfRequested();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            status = FAILED;
            // Reflected failures can contain launcher arguments or host details.
            // The JVM receives only this fixed diagnostic, without their causes.
            throw new IllegalStateException(INITIALIZATION_FAILURE);
        }
    }

    /** Safe system-loader endpoint for an optional mod-side startup observer. */
    public static String startupStatus() {
        return status;
    }

    private static void reportIfRequested() {
        if (Boolean.getBoolean("loadonce.agent.verbose")) System.err.println(status);
    }

    private static final class CheckpointVetoException extends Exception {
        private static final long serialVersionUID = 1L;

        private CheckpointVetoException() {
            super(CHECKPOINT_VETO);
        }
    }
}
