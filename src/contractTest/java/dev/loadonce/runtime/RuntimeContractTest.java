package dev.loadonce.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Executable contract checks; no test framework or native libraries required. */
public final class RuntimeContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        restoresInDependencyOrder();
        gateFailsBeforeExternalStateChanges();
        preparationFailureRecoversPartialRelease();
        quiesceFailureResumesOnlyAttemptedResources();
        failedRollbackStopsEveryResource();
        failedRestoreStopsEveryResource();
        failedResumeStopsEveryResource();
        uncheckedFailureCannotLeaveResourcesRunning();
        rethrownStopCauseDoesNotSkipOtherResources();
        engineAbortRecoversAndCanRetry();
        rejectsWrongThreadAndReentrancy();
        registrationIsAtomicAndBounded();
        diagnosticHistoryIsBoundedAndSanitized();
        plainJdkReportsCracUnavailable();
        System.out.println("Runtime contract checks passed: " + checks);
    }

    private static void restoresInDependencyOrder() throws Exception {
        Fixture fixture = new Fixture();
        fixture.coordinator.prepareCheckpoint();
        equal(CheckpointCoordinator.State.PREPARED, fixture.coordinator.state());
        for (Probe probe : fixture.probes) check(!probe.running && probe.handle == 0, "all handles released before engine");
        expect(IllegalStateException.class, fixture.coordinator::prepareCheckpoint);
        fixture.coordinator.restoreCheckpoint();
        equal(List.of("quiesce:audio", "quiesce:gpu", "quiesce:window",
                "checkpoint:audio", "checkpoint:gpu", "checkpoint:window",
                "restore:window", "restore:gpu", "restore:audio",
                "resume:window", "resume:gpu", "resume:audio"), fixture.events);
        for (Probe probe : fixture.probes) {
            check(probe.running && probe.handle > 1 && probe.cpuState == 42, "fresh handle and retained CPU state");
        }
        equal(CheckpointCoordinator.State.READY, fixture.coordinator.state());
        expect(IllegalStateException.class, fixture.coordinator::restoreCheckpoint);
    }

    private static void gateFailsBeforeExternalStateChanges() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean();
        Fixture fixture = new Fixture(() -> allowed.get() ? CheckpointGate.Decision.allow("test boundary")
                : CheckpointGate.Decision.deny("world or session is active"));
        expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::prepareCheckpoint);
        check(fixture.events.isEmpty(), "denied checkpoint must not touch resource state");
        equal(CheckpointCoordinator.State.READY, fixture.coordinator.state());
        allowed.set(true);
        fixture.coordinator.prepareCheckpoint();
        fixture.coordinator.restoreCheckpoint();
        Fixture nullProof = new Fixture(() -> null);
        expect(CheckpointCoordinator.CheckpointException.class, nullProof.coordinator::prepareCheckpoint);
        check(nullProof.events.isEmpty(), "missing lifecycle proof fails closed");
        Fixture throwingProof = new Fixture(() -> { throw new AssertionError("proof unavailable"); });
        expect(CheckpointCoordinator.CheckpointException.class, throwingProof.coordinator::prepareCheckpoint);
        check(throwingProof.events.isEmpty(), "failed lifecycle proof fails closed");
    }

    private static void preparationFailureRecoversPartialRelease() throws Exception {
        Fixture fixture = new Fixture();
        fixture.gpu.failCheckpoint = true;
        expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::prepareCheckpoint);
        equal(List.of("quiesce:audio", "quiesce:gpu", "quiesce:window", "checkpoint:audio", "checkpoint:gpu",
                "restore:gpu", "restore:audio", "resume:window", "resume:gpu", "resume:audio"), fixture.events);
        equal(CheckpointCoordinator.State.READY, fixture.coordinator.state());
        check(fixture.window.handle == 1, "unreleased dependency is not rebuilt");
        check(fixture.gpu.handle == 2 && fixture.audio.handle == 2, "partially released resources are rebuilt");
        for (Probe probe : fixture.probes) check(probe.running, "rollback resumes application");
        fixture.gpu.failCheckpoint = false;
        fixture.coordinator.prepareCheckpoint();
        fixture.coordinator.restoreCheckpoint();
    }

    private static void quiesceFailureResumesOnlyAttemptedResources() throws Exception {
        Fixture fixture = new Fixture();
        fixture.gpu.failQuiesce = true;
        expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::prepareCheckpoint);
        equal(List.of("quiesce:audio", "quiesce:gpu", "resume:gpu", "resume:audio"), fixture.events);
        for (Probe probe : fixture.probes) check(probe.running && probe.handle == 1, "partial quiesce recovered without rebuild");
        equal(CheckpointCoordinator.State.READY, fixture.coordinator.state());
    }

    private static void failedRollbackStopsEveryResource() throws Exception {
        Fixture fixture = new Fixture();
        fixture.gpu.failCheckpoint = true;
        fixture.gpu.failRestore = true;
        fixture.audio.failStop = true;
        Throwable failure = expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::prepareCheckpoint);
        check(failure.getSuppressed().length == 2, "both rollback and stop failures preserved");
        assertStopped(fixture);
        check(!fixture.events.contains("restore:audio") && !fixture.events.contains("resume:window"),
                "dependent restoration and resume stop after recovery failure");
        equal(List.of("stop:audio", "stop:gpu", "stop:window"), fixture.events.subList(fixture.events.size() - 3, fixture.events.size()));
        expect(IllegalStateException.class, fixture.coordinator::prepareCheckpoint);
        expect(IllegalStateException.class, () -> fixture.coordinator.register(fixture.window));
    }

    private static void failedRestoreStopsEveryResource() throws Exception {
        Fixture fixture = new Fixture();
        fixture.coordinator.prepareCheckpoint();
        fixture.gpu.failRestore = true;
        expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::restoreCheckpoint);
        assertStopped(fixture);
        check(!fixture.events.contains("restore:audio") && !fixture.events.contains("resume:window"),
                "failed dependency is never resumed or used by dependents");
    }

    private static void failedResumeStopsEveryResource() throws Exception {
        Fixture fixture = new Fixture();
        fixture.coordinator.prepareCheckpoint();
        fixture.gpu.failResume = true;
        expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::restoreCheckpoint);
        assertStopped(fixture);
        check(fixture.events.contains("resume:window") && !fixture.events.contains("resume:audio"), "partial resume still stops all resources");
    }

    private static void uncheckedFailureCannotLeaveResourcesRunning() throws Exception {
        Fixture fixture = new Fixture();
        fixture.coordinator.prepareCheckpoint();
        fixture.gpu.restoreError = true;
        Throwable failure = expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::restoreCheckpoint);
        check(failure.getCause() instanceof AssertionError, "unchecked callback cause preserved");
        assertStopped(fixture);
    }

    private static void engineAbortRecoversAndCanRetry() throws Exception {
        Fixture fixture = new Fixture();
        fixture.coordinator.prepareCheckpoint();
        expect(NullPointerException.class, () -> fixture.coordinator.abortCheckpoint(null));
        equal(CheckpointCoordinator.State.PREPARED, fixture.coordinator.state());
        fixture.coordinator.abortCheckpoint(new Exception("engine refused"));
        equal(CheckpointCoordinator.State.READY, fixture.coordinator.state());
        for (Probe probe : fixture.probes) check(probe.running && probe.handle == 2, "engine rejection rebuilt external state");
        fixture.coordinator.prepareCheckpoint();
        fixture.coordinator.restoreCheckpoint();
    }

    private static void rethrownStopCauseDoesNotSkipOtherResources() throws Exception {
        Fixture fixture = new Fixture();
        fixture.coordinator.prepareCheckpoint();
        fixture.gpu.failRestore = true;
        fixture.audio.rethrowStopCause = true;
        expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::restoreCheckpoint);
        assertStopped(fixture);
        equal(List.of("stop:audio", "stop:gpu", "stop:window"), fixture.events.subList(fixture.events.size() - 3, fixture.events.size()));
    }

    private static void rejectsWrongThreadAndReentrancy() throws Exception {
        Fixture fixture = new Fixture();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try { fixture.coordinator.prepareCheckpoint(); } catch (Throwable caught) { failure.set(caught); }
        });
        wrongThread.start();
        wrongThread.join();
        check(failure.get() instanceof IllegalStateException && fixture.events.isEmpty(), "wrong thread cannot touch handles");
        fixture.audio.onQuiesce = fixture.coordinator::prepareCheckpoint;
        Throwable reentrant = expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::prepareCheckpoint);
        check(reentrant.getCause() instanceof IllegalStateException, "resource reentry rejected");
        equal(List.of("quiesce:audio", "resume:audio"), fixture.events);
        equal(CheckpointCoordinator.State.READY, fixture.coordinator.state());

        AtomicReference<CheckpointCoordinator> reference = new AtomicReference<>();
        CheckpointCoordinator gateReentry = new CheckpointCoordinator(() -> {
            reference.get().prepareCheckpoint();
            return CheckpointGate.Decision.allow("unreachable");
        });
        reference.set(gateReentry);
        expect(CheckpointCoordinator.CheckpointException.class, gateReentry::prepareCheckpoint);
        equal(CheckpointCoordinator.State.READY, gateReentry.state());
    }

    private static void registrationIsAtomicAndBounded() throws Exception {
        Fixture fixture = new Fixture();
        expect(IllegalArgumentException.class, () -> fixture.coordinator.register(fixture.window));
        expect(IllegalArgumentException.class, () -> fixture.coordinator.register(new Probe("window", new ArrayList<>(), new ArrayList<>(), null)));
        fixture.window.id = "different-name";
        expect(IllegalArgumentException.class, () -> fixture.coordinator.register(fixture.window));
        fixture.window.id = "window";
        expect(NullPointerException.class, () -> fixture.coordinator.register(null));
        expect(IllegalArgumentException.class, () -> fixture.coordinator.register(new Probe("unsafe\nid", new ArrayList<>(), new ArrayList<>(), null)));
        fixture.coordinator.prepareCheckpoint();
        fixture.coordinator.restoreCheckpoint();
        check(fixture.events.stream().filter(event -> event.equals("checkpoint:window")).count() == 1, "duplicate never added");

        CheckpointCoordinator bounded = new CheckpointCoordinator(() -> CheckpointGate.Decision.allow("bounded probe"));
        List<Probe> resources = new ArrayList<>();
        for (int index = 0; index < CheckpointCoordinator.MAX_RESOURCES; index++) {
            Probe probe = new Probe("resource-" + index, new ArrayList<>(), resources, null);
            resources.add(probe);
            bounded.register(probe);
        }
        expect(IllegalStateException.class, () -> bounded.register(new Probe("overflow", new ArrayList<>(), resources, null)));
        bounded.prepareCheckpoint();
        bounded.restoreCheckpoint();
    }

    private static void diagnosticHistoryIsBoundedAndSanitized() throws Exception {
        Fixture fixture = new Fixture();
        for (int cycle = 0; cycle < 20; cycle++) {
            fixture.coordinator.prepareCheckpoint();
            fixture.coordinator.restoreCheckpoint();
        }
        equal(CheckpointCoordinator.MAX_DIAGNOSTICS, fixture.coordinator.diagnostics().size());
        expect(UnsupportedOperationException.class, () -> fixture.coordinator.diagnostics().clear());
        fixture.gpu.failCheckpoint = true;
        expect(CheckpointCoordinator.CheckpointException.class, fixture.coordinator::prepareCheckpoint);
        check(fixture.coordinator.diagnostics().stream().noneMatch(diagnostic -> diagnostic.detail().contains("secret-token")),
                "diagnostics omit raw callback exception messages");
        check(fixture.coordinator.diagnostics().stream().anyMatch(diagnostic -> diagnostic.phase().equals("checkpoint")
                && diagnostic.resourceId().equals("gpu") && diagnostic.detail().contains("IllegalStateException")),
                "diagnostics retain phase and exception type");
    }

    private static void plainJdkReportsCracUnavailable() throws Exception {
        try {
            Class.forName("jdk.crac.Core");
            System.out.println("CRaC API present; real engine validation belongs to CracSmoke.");
        } catch (ClassNotFoundException absent) {
            Fixture fixture = new Fixture();
            expect(CracBridge.CracUnavailableException.class, () -> CracBridge.install(fixture.coordinator));
            check(fixture.events.isEmpty() && fixture.coordinator.state() == CheckpointCoordinator.State.READY,
                    "unavailable CRaC does not pretend to checkpoint");
        }
    }

    private static void assertStopped(Fixture fixture) {
        equal(CheckpointCoordinator.State.STOPPED, fixture.coordinator.state());
        for (Probe probe : fixture.probes) check(probe.stopped && !probe.running && probe.handle == 0, "all partial resources stopped");
    }

    private static void equal(Object expected, Object actual) { check(expected.equals(actual), "expected " + expected + ", found " + actual); }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface Attempt { void run() throws Exception; }

    private static Throwable expect(Class<? extends Throwable> type, Attempt action) throws Exception {
        checks++;
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return failure;
            throw new AssertionError("expected " + type.getSimpleName() + ", found " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final List<Probe> probes = new ArrayList<>();
        private final CheckpointCoordinator coordinator;
        private final Probe window;
        private final Probe gpu;
        private final Probe audio;

        private Fixture() { this(() -> CheckpointGate.Decision.allow("standalone reusable test state")); }

        private Fixture(CheckpointGate gate) {
            coordinator = new CheckpointCoordinator(gate);
            window = new Probe("window", events, probes, null);
            gpu = new Probe("gpu", events, probes, window);
            audio = new Probe("audio", events, probes, gpu);
            probes.addAll(List.of(window, gpu, audio));
            probes.forEach(coordinator::register);
        }
    }

    private static final class Probe implements CheckpointResource {
        private String id;
        private final List<String> events;
        private final List<Probe> all;
        private final Probe dependency;
        private final int cpuState = 42;
        private int nextHandle = 1;
        private int handle = 1;
        private boolean running = true;
        private boolean stopped;
        private boolean failQuiesce;
        private boolean failCheckpoint;
        private boolean failRestore;
        private boolean failResume;
        private boolean failStop;
        private boolean rethrowStopCause;
        private boolean restoreError;
        private Attempt onQuiesce;

        private Probe(String id, List<String> events, List<Probe> all, Probe dependency) {
            this.id = id;
            this.events = events;
            this.all = all;
            this.dependency = dependency;
        }

        @Override public String id() { return id; }

        @Override public void quiesce() throws Exception {
            events.add("quiesce:" + id);
            running = false;
            if (onQuiesce != null) onQuiesce.run();
            if (failQuiesce) throw new IllegalStateException("secret-token quiesce");
        }

        @Override public void checkpoint() {
            check(all.stream().noneMatch(probe -> probe.running), "all producers quiescent before any handle release");
            events.add("checkpoint:" + id);
            handle = 0;
            if (failCheckpoint) throw new IllegalStateException("secret-token checkpoint");
        }

        @Override public void restore() {
            check(!running && handle == 0, "restore creates fresh handles while quiescent");
            check(dependency == null || dependency.handle != 0, "dependency restored before native reconstruction");
            events.add("restore:" + id);
            handle = ++nextHandle;
            if (restoreError) throw new AssertionError("secret-token restore");
            if (failRestore) throw new IllegalStateException("secret-token restore");
        }

        @Override public void resume() {
            check(handle != 0 && !stopped, "resume requires usable native state");
            events.add("resume:" + id);
            running = true;
            if (failResume) throw new IllegalStateException("secret-token resume");
        }

        @Override public void stopSafely(Throwable cause) throws Exception {
            events.add("stop:" + id);
            running = false;
            stopped = true;
            handle = 0;
            if (rethrowStopCause) throw (Exception) cause;
            if (failStop) throw new IllegalStateException("secret-token stop");
        }
    }
}
