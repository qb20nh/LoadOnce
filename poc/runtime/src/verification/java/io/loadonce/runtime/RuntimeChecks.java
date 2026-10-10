package io.loadonce.runtime;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dependency-free contract tests. Lifecycle tests never emulate an operating-system checkpoint. */
public final class RuntimeChecks {
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        readiness();
        readinessFailure();
        readinessAcrossClassloaders();
        resourceOrdering();
        partialPreparationRollback();
        vetoes();
        guardRechecked();
        failedRecoveryStopsFutureCheckpoints();
        linkageFailureRollback();
        assertionFailureDuringPreparationRollback();
        assertionFailureDuringRecoveryStopsRetry();
        assertionFailureInGuardRollsBack();
        stockJdkIsUnsupported();
        System.out.println("Runtime checks passed: " + checks);
    }

    private static void readiness() throws Exception {
        Path directory = Files.createTempDirectory("loadonce-readiness-");
        Path report = directory.resolve("run.json");
        System.setProperty("loadonce.report", report.toString());
        System.setProperty("loadonce.runId", "run\"\\\n\ud83c\udf0d");
        Readiness.beginRun(System.nanoTime() - 1_000_000, false);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    Readiness.markClientMenuReady();
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        String first = Files.readString(report);
        require(first.contains("\"milestone\":\"client_menu_ready\""), "Explicit client milestone");
        require(first.contains("\"runId\":\"run\\\"\\\\\\u000a\\ud83c\\udf0d\""), "JSON escaping");
        Matcher elapsed = Pattern.compile("\"elapsedNanos\":([0-9]+)").matcher(first);
        require(elapsed.find() && Long.parseLong(elapsed.group(1)) >= 1_000_000,
                "Monotonic elapsed time includes initialization");
        Readiness.markServerReady();
        require(first.equals(Files.readString(report)), "First milestone is idempotent across callers");
        try (var files = Files.list(directory)) {
            require(files.count() == 1, "Only complete report remains after atomic publication");
        }
        Readiness.beginRestoredRun();
        Readiness.markServerReady();
        String restored = Files.readString(report);
        require(!restored.contains("run\\\""), "A restored run does not reuse a captured run ID");
        require(restored.contains("\"milestone\":\"server_ready\""), "Restored readiness can be marked again");
        Files.delete(report);
        Files.delete(directory);
        System.clearProperty("loadonce.report");
        System.clearProperty("loadonce.runId");
    }

    private static void readinessFailure() throws IOException {
        Path occupied = Files.createTempFile("loadonce-readiness-parent-", ".file");
        System.setProperty("loadonce.report", occupied.resolve("report.json").toString());
        Readiness.beginRun(System.nanoTime(), false);
        Readiness.markServerReady();
        require(Files.size(occupied) == 0, "Report failure neither aborts readiness nor replaces another file");
        Files.delete(occupied);
        System.clearProperty("loadonce.report");
    }

    private static void readinessAcrossClassloaders() throws Exception {
        Path directory = Files.createTempDirectory("loadonce-loader-readiness-");
        Path report = directory.resolve("ready.json");
        String originalClock = System.getProperty("loadonce.agentStartNanos");
        String originalId = System.getProperty("loadonce.runId");
        String originalReport = System.getProperty("loadonce.report");
        try {
            LoadOnceAgent.premain(null, null);
            long agentClock = Long.parseLong(System.getProperty("loadonce.agentStartNanos"));
            require(System.nanoTime() - agentClock >= 0, "Agent publishes its process-wide monotonic start");
            System.setProperty("loadonce.report", report.toString());
            System.setProperty("loadonce.runId", "isolated-initial");
            long earlierStart = System.nanoTime() - 5_000_000_000L;
            System.setProperty("loadonce.agentStartNanos", Long.toString(earlierStart));
            String initial = isolatedReadiness(report);
            require(elapsedNanos(initial) >= 5_000_000_000L,
                    "Independent loader includes initialization before its readiness class was loaded");
            require(initial.contains("\"runId\":\"isolated-initial\""), "Independent loader uses launcher's fresh identity");

            System.setProperty("loadonce.agentStartNanos", "not-a-monotonic-clock");
            System.setProperty("loadonce.runId", "isolated-next-launch");
            String invalid = isolatedReadiness(report);
            require(elapsedNanos(invalid) < 3_600_000_000_000L,
                    "Malformed process clock falls back to first API load");
            require(invalid.contains("\"runId\":\"isolated-next-launch\""),
                    "A new loader uses the current run identity rather than a captured identity");

            System.setProperty("loadonce.agentStartNanos", Long.toString(System.nanoTime() + 3_600_000_000_000L));
            String future = isolatedReadiness(report);
            require(elapsedNanos(future) < 3_600_000_000_000L, "Future process clocks are rejected");

            System.setProperty("loadonce.runId", "captured-image-id");
            Readiness.beginRun(earlierStart, false);
            Readiness.beginRestoredRun();
            long restoredStart = Long.parseLong(System.getProperty("loadonce.agentStartNanos"));
            require(restoredStart - earlierStart > 0, "Restoration replaces the captured process clock");
            String restoredId = System.getProperty("loadonce.runId");
            require(!restoredId.equals("captured-image-id"), "Restoration publishes a fresh process run identity");
            String restored = isolatedReadiness(report);
            require(restored.contains("\"runId\":\"" + restoredId + "\""),
                    "A loader initialized after restoration uses the refreshed process identity");
        } finally {
            restoreProperty("loadonce.agentStartNanos", originalClock);
            restoreProperty("loadonce.runId", originalId);
            restoreProperty("loadonce.report", originalReport);
            Files.deleteIfExists(report);
            Files.delete(directory);
        }
    }

    private static String isolatedReadiness(Path report) throws Exception {
        var codeSource = Readiness.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] { codeSource }, ClassLoader.getPlatformClassLoader())) {
            Class<?> isolated = Class.forName("io.loadonce.runtime.Readiness", true, loader);
            require(isolated != Readiness.class && isolated.getClassLoader() == loader,
                    "Readiness is loaded independently of the agent's application classloader");
            isolated.getMethod("markServerReady").invoke(null);
        }
        return Files.readString(report);
    }

    private static long elapsedNanos(String report) {
        Matcher elapsed = Pattern.compile("\"elapsedNanos\":([0-9]+)").matcher(report);
        require(elapsed.find(), "Readiness report contains a nonnegative elapsed duration");
        return Long.parseLong(elapsed.group(1));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private static void resourceOrdering() throws Exception {
        List<String> events = new ArrayList<>();
        ResourceLifecycle lifecycle = new ResourceLifecycle(CheckpointSafety::reusableInitializationOnly);
        lifecycle.add(adapter("A", events, false, false));
        lifecycle.add(adapter("B", events, false, false));
        lifecycle.prepare();
        require(lifecycle.isPrepared(), "Successful resource preparation is recorded");
        lifecycle.restore();
        require(events.equals(List.of("before A", "before B", "after B", "after A")),
                "Preparation forward, restore reverse");
        lifecycle.restore();
        require(events.size() == 4, "Repeated CRaC failure restore callbacks do not recover resources twice");
    }

    private static void partialPreparationRollback() throws Exception {
        List<String> events = new ArrayList<>();
        ResourceLifecycle lifecycle = new ResourceLifecycle(CheckpointSafety::reusableInitializationOnly);
        lifecycle.add(adapter("A", events, false, false));
        lifecycle.add(adapter("B", events, true, false));
        expectFailure(lifecycle::prepare);
        require(events.equals(List.of("before A", "before B", "after B", "after A")),
                "Partially prepared failing adapter is recovered before earlier resources");
        require(lifecycle.lastFailure().status() == CheckpointResult.Status.FAILED, "Preparation failure reported");
        lifecycle.restore();
        require(events.size() == 4, "Failed preparation rollback is not repeated");
    }

    private static void vetoes() throws Exception {
        List<CheckpointSafety> unsafe = List.of(
                CheckpointSafety.denied(),
                new CheckpointSafety(true, true, false, false, false),
                new CheckpointSafety(true, false, true, false, false),
                new CheckpointSafety(true, false, false, true, false),
                new CheckpointSafety(true, false, false, false, true));
        for (CheckpointSafety safety : unsafe) {
            List<String> events = new ArrayList<>();
            ResourceLifecycle lifecycle = new ResourceLifecycle(() -> safety);
            lifecycle.add(adapter("untouched", events, false, false));
            expectFailure(lifecycle::prepare);
            require(events.isEmpty(), "Unsafe boundary is vetoed before adapters mutate resources");
            require(lifecycle.lastFailure().status() == CheckpointResult.Status.VETOED, "Safety veto reported");
        }
        ResourceLifecycle lifecycle = new ResourceLifecycle(CheckpointSafety::reusableInitializationOnly);
        lifecycle.add(new ResourceAdapter() {
            public String name() { return "not audited"; }
            public void beforeCheckpoint() { throw new AssertionError("Must not run"); }
            public void afterRestore() { throw new AssertionError("Must not run"); }
        });
        expectFailure(lifecycle::prepare);
        require(lifecycle.lastFailure().status() == CheckpointResult.Status.VETOED, "Adapter defaults to veto");
    }

    private static void guardRechecked() throws Exception {
        List<String> events = new ArrayList<>();
        AtomicBoolean safe = new AtomicBoolean(true);
        ResourceLifecycle lifecycle = new ResourceLifecycle(() -> safe.get()
                ? CheckpointSafety.reusableInitializationOnly() : CheckpointSafety.denied());
        lifecycle.add(new ResourceAdapter() {
            public String name() { return "changed boundary"; }
            public boolean supportsReusableCheckpoint() { return true; }
            public void beforeCheckpoint() { events.add("before"); safe.set(false); }
            public void afterRestore() { events.add("after"); }
        });
        expectFailure(lifecycle::prepare);
        require(events.equals(List.of("before", "after")), "Boundary change during preparation rolls resources back");
    }

    private static void failedRecoveryStopsFutureCheckpoints() throws Exception {
        List<String> events = new ArrayList<>();
        ResourceLifecycle lifecycle = new ResourceLifecycle(CheckpointSafety::reusableInitializationOnly);
        lifecycle.add(adapter("A", events, false, false));
        lifecycle.add(adapter("B", events, false, true));
        lifecycle.prepare();
        expectFailure(lifecycle::restore);
        require(events.equals(List.of("before A", "before B", "after B", "after A")),
                "All adapters are recovered despite one recovery failure");
        require(lifecycle.lastFailure().detail().contains("further checkpoints disabled"), "Broken lifecycle reported clearly");
        expectFailure(lifecycle::prepare);
        require(events.size() == 4, "A broken lifecycle cannot prepare resources again");
    }

    private static void linkageFailureRollback() throws Exception {
        List<String> events = new ArrayList<>();
        ResourceLifecycle lifecycle = new ResourceLifecycle(CheckpointSafety::reusableInitializationOnly);
        lifecycle.add(new ResourceAdapter() {
            public String name() { return "missing native library"; }
            public boolean supportsReusableCheckpoint() { return true; }
            public void beforeCheckpoint() { events.add("before"); throw new UnsatisfiedLinkError("fixture"); }
            public void afterRestore() { events.add("after"); }
        });
        try {
            lifecycle.prepare();
            throw new AssertionError("Expected native linkage failure");
        } catch (UnsatisfiedLinkError expected) {
            require(events.equals(List.of("before", "after")), "Native linkage failures roll back preparation");
        }
    }

    private static void stockJdkIsUnsupported() {
        CheckpointCoordinator coordinator = new CheckpointCoordinator();
        if (!coordinator.isSupported()) {
            require(coordinator.checkpoint().status() == CheckpointResult.Status.UNSUPPORTED,
                    "Stock JDK cannot claim to checkpoint");
        } else {
            require(coordinator.supportMessage().contains("not yet tested"), "API availability does not prove CRIU works");
        }
    }

    private static void assertionFailureDuringPreparationRollback() throws Exception {
        List<String> events = new ArrayList<>();
        ResourceLifecycle lifecycle = new ResourceLifecycle(CheckpointSafety::reusableInitializationOnly);
        lifecycle.add(adapter("A", events, false, false));
        lifecycle.add(new ResourceAdapter() {
            public String name() { return "B"; }
            public boolean supportsReusableCheckpoint() { return true; }
            public void beforeCheckpoint() { events.add("before B"); throw new AssertionError("fixture preparation error"); }
            public void afterRestore() { events.add("after B"); }
        });
        lifecycle.add(adapter("C", events, false, false));
        expectAssertionFailure(lifecycle::prepare);
        require(events.equals(List.of("before A", "before B", "after B", "after A")),
                "Unchecked preparation errors recover partial and earlier adapters in reverse order");
        require(lifecycle.lastFailure().status() == CheckpointResult.Status.FAILED,
                "Unchecked preparation errors are recorded as failures");
        lifecycle.restore();
        require(events.size() == 4, "Error preparation rollback is not repeated by a later restore callback");
    }

    private static void assertionFailureDuringRecoveryStopsRetry() throws Exception {
        List<String> events = new ArrayList<>();
        ResourceLifecycle lifecycle = new ResourceLifecycle(CheckpointSafety::reusableInitializationOnly);
        lifecycle.add(adapter("A", events, false, false));
        lifecycle.add(new ResourceAdapter() {
            public String name() { return "B"; }
            public boolean supportsReusableCheckpoint() { return true; }
            public void beforeCheckpoint() { events.add("before B"); }
            public void afterRestore() { events.add("after B"); throw new AssertionError("fixture recovery error"); }
        });
        lifecycle.add(adapter("C", events, false, false));
        lifecycle.prepare();
        expectAssertionFailure(lifecycle::restore);
        require(events.equals(List.of("before A", "before B", "before C", "after C", "after B", "after A")),
                "Unchecked recovery errors do not skip the remaining reverse recovery callbacks");
        require(lifecycle.lastFailure().status() == CheckpointResult.Status.FAILED,
                "Unchecked recovery errors are recorded as failures");
        require(lifecycle.beginAttempt().detail().contains("further checkpoints disabled"),
                "Unchecked recovery errors disable further checkpoint requests");
        lifecycle.restore();
        require(events.size() == 6, "Cleanup after unchecked recovery errors is not repeated");
        expectFailure(lifecycle::prepare);
        require(events.size() == 6, "Broken lifecycle rejects retry after unchecked recovery errors");
    }

    private static void assertionFailureInGuardRollsBack() throws Exception {
        List<String> events = new ArrayList<>();
        AtomicInteger validations = new AtomicInteger();
        ResourceLifecycle lifecycle = new ResourceLifecycle(() -> {
            if (validations.incrementAndGet() == 2) throw new AssertionError("fixture guard error");
            return CheckpointSafety.reusableInitializationOnly();
        });
        lifecycle.add(adapter("A", events, false, false));
        lifecycle.add(adapter("B", events, false, false));
        expectAssertionFailure(lifecycle::prepare);
        require(events.equals(List.of("before A", "before B", "after B", "after A")),
                "Unchecked guard errors after preparation trigger reverse rollback");
        lifecycle.restore();
        require(events.size() == 4, "Guard error rollback is not repeated by a restore callback");
    }

    private static ResourceAdapter adapter(String name, List<String> events, boolean failBefore, boolean failAfter) {
        return new ResourceAdapter() {
            public String name() { return name; }
            public boolean supportsReusableCheckpoint() { return true; }
            public void beforeCheckpoint() throws Exception {
                events.add("before " + name);
                if (failBefore) throw new IOException("fixture preparation failure");
            }
            public void afterRestore() throws Exception {
                events.add("after " + name);
                if (failAfter) throw new IOException("fixture recovery failure");
            }
        };
    }

    private static void expectFailure(CheckedOperation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Expected operation failure");
        } catch (Exception expected) {
            checks++;
        }
    }

    private static void expectAssertionFailure(CheckedOperation operation) throws Exception {
        boolean failed = false;
        try {
            operation.run();
        } catch (AssertionError expected) {
            failed = true;
        }
        require(failed, "Adapter or guard assertion failure propagates after recovery");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }

    private interface CheckedOperation {
        void run() throws Exception;
    }
}
