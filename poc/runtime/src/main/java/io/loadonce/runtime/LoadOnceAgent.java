package io.loadonce.runtime;

import java.lang.instrument.Instrumentation;

/** Measures application readiness without transforming classes or requesting checkpoints. */
public final class LoadOnceAgent {
    private LoadOnceAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        long launchStart = System.nanoTime();
        try {
            // Loader-isolated Readiness copies must observe the same premain clock.
            System.setProperty("loadonce.agentStartNanos", Long.toString(launchStart));
            Readiness.beginRun(launchStart, false);
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("LoadOnce: readiness instrumentation unavailable ("
                    + failure.getClass().getSimpleName() + ").");
        }
    }
}
