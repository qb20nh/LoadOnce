package dev.loadonce.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Synchronous, bounded resource lifecycle. This prepares an application for a
 * checkpoint engine; it does not save or restore a process itself. Callbacks
 * must run on the captured owner thread and must not reenter the coordinator.
 */
public final class CheckpointCoordinator {
    public static final int MAX_RESOURCES = 64;
    public static final int MAX_DIAGNOSTICS = 128;
    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public enum State { READY, PREPARING, PREPARED, RESTORING, STOPPED }

    /** Diagnostic details omit exception messages, handles, and application data. */
    public record Diagnostic(long generation, String phase, String resourceId, String detail) { }

    public static final class CheckpointException extends Exception {
        private static final long serialVersionUID = 1L;
        private CheckpointException(String message, Throwable cause) { super(message, cause); }
    }

    private record Entry(String id, CheckpointResource resource) { }
    private final Thread owner = Thread.currentThread();
    private final CheckpointGate gate;
    private final List<Entry> resources = new ArrayList<>();
    private final IdentityHashMap<CheckpointResource, Boolean> registered = new IdentityHashMap<>();
    private final ArrayDeque<Diagnostic> diagnostics = new ArrayDeque<>();
    private final List<Entry> quiesced = new ArrayList<>();
    private final List<Entry> released = new ArrayList<>();
    private volatile State state = State.READY;
    private boolean active;
    private long generation;

    public CheckpointCoordinator(CheckpointGate gate) { this.gate = Objects.requireNonNull(gate, "gate"); }

    public State state() { return state; }

    public void requireOwnerThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Checkpoint lifecycle must execute on its owner thread");
        }
    }

    public List<Diagnostic> diagnostics() {
        requireOwnerThread();
        return List.copyOf(diagnostics);
    }

    public void register(CheckpointResource resource) {
        requireIdle(State.READY);
        Objects.requireNonNull(resource, "resource");
        // id() is user code too: prevent it from recursively modifying registration.
        active = true;
        try {
            String id = Objects.requireNonNull(resource.id(), "resource id");
            if (!RESOURCE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Resource id must use 1-128 letters, digits, . _ : or -");
            }
            if (registered.containsKey(resource) || resources.stream().anyMatch(entry -> entry.id().equals(id))) {
                throw new IllegalArgumentException("Resource instance or id is already registered");
            }
            if (resources.size() == MAX_RESOURCES) {
                throw new IllegalStateException("Resource registration limit reached");
            }
            resources.add(new Entry(id, resource));
            registered.put(resource, Boolean.TRUE);
        } finally {
            active = false;
        }
    }

    public void prepareCheckpoint() throws CheckpointException {
        requireIdle(State.READY);
        active = true;
        state = State.PREPARING;
        generation++;
        try {
            CheckpointGate.Decision decision;
            try {
                decision = Objects.requireNonNull(gate.evaluate(), "gate decision");
            } catch (Throwable failure) {
                noteFailure("gate", "-", failure);
                throw new CheckpointException("Checkpoint lifecycle proof failed", failure);
            }
            if (!decision.approved()) {
                note("gate", "-", "denied");
                throw new CheckpointException("Checkpoint denied: " + decision.reason(), null);
            }
            note("gate", "-", "approved");
            try {
                for (int index = resources.size() - 1; index >= 0; index--) {
                    Entry entry = resources.get(index);
                    // Include the failing callback: it might have changed state before throwing.
                    quiesced.add(entry);
                    call(entry, "quiesce", entry.resource()::quiesce);
                }
                for (int index = resources.size() - 1; index >= 0; index--) {
                    Entry entry = resources.get(index);
                    released.add(entry);
                    call(entry, "checkpoint", entry.resource()::checkpoint);
                }
            } catch (Throwable failure) {
                CheckpointException result = new CheckpointException("Checkpoint preparation failed", failure);
                recoverPreparation(result);
                throw result;
            }
            state = State.PREPARED;
            note("prepared", "-", "external handles released");
        } finally {
            if (state == State.PREPARING) state = State.READY;
            active = false;
        }
    }

    /** Engine callback after restoration, or an explicitly labelled in-process rehearsal. */
    public void restoreCheckpoint() throws CheckpointException {
        requireIdle(State.PREPARED);
        active = true;
        state = State.RESTORING;
        try {
            try {
                for (Entry entry : resources) call(entry, "restore", entry.resource()::restore);
                for (Entry entry : resources) call(entry, "resume", entry.resource()::resume);
                clearAttempt();
                state = State.READY;
                note("ready", "-", "resource lifecycle completed");
            } catch (Throwable failure) {
                CheckpointException result = new CheckpointException("Resource restoration failed; application stopped", failure);
                stopAll(result);
                throw result;
            }
        } finally {
            active = false;
        }
    }

    /** Recover preparation when an engine rejects checkpoint creation. */
    public void abortCheckpoint(Throwable cause) throws CheckpointException {
        requireIdle(State.PREPARED);
        Objects.requireNonNull(cause, "cause");
        active = true;
        state = State.RESTORING;
        try {
            CheckpointException result = new CheckpointException("Checkpoint engine aborted", cause);
            recoverPreparation(result);
            if (state == State.STOPPED) throw result;
        } finally {
            active = false;
        }
    }

    private void recoverPreparation(CheckpointException result) {
        state = State.RESTORING;
        // Attempt dependent resources only after dependencies have recovered.
        try {
            for (int index = released.size() - 1; index >= 0; index--) {
                Entry entry = released.get(index);
                call(entry, "rollback-restore", entry.resource()::restore);
            }
            for (int index = quiesced.size() - 1; index >= 0; index--) {
                Entry entry = quiesced.get(index);
                call(entry, "rollback-resume", entry.resource()::resume);
            }
            clearAttempt();
            state = State.READY;
            note("rollback", "-", "resource lifecycle recovered");
        } catch (Throwable recoveryFailure) {
            suppress(result, recoveryFailure);
            stopAll(result);
        }
    }

    private void stopAll(CheckpointException cause) {
        state = State.STOPPED;
        for (int index = resources.size() - 1; index >= 0; index--) {
            Entry entry = resources.get(index);
            try {
                call(entry, "stop", () -> entry.resource().stopSafely(cause));
            } catch (Throwable stopFailure) {
                suppress(cause, stopFailure);
            }
        }
        clearAttempt();
        note("stopped", "-", "normal execution must not resume");
    }

    private void clearAttempt() { quiesced.clear(); released.clear(); }

    private static void suppress(Throwable cause, Throwable failure) {
        // A stop callback is given cause and might rethrow it. Self-suppression
        // must not prevent the remaining resources from being stopped.
        if (cause != failure) cause.addSuppressed(failure);
    }

    private void requireIdle(State expected) {
        requireOwnerThread();
        if (active) throw new IllegalStateException("Reentrant checkpoint lifecycle operation");
        if (state != expected) throw new IllegalStateException("Expected " + expected + ", found " + state);
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }

    private void call(Entry entry, String phase, Operation operation) throws Throwable {
        try {
            operation.run();
            note(phase, entry.id(), "completed");
        } catch (Throwable failure) {
            noteFailure(phase, entry.id(), failure);
            throw failure;
        }
    }

    private void noteFailure(String phase, String id, Throwable failure) {
        note(phase, id, "failed: " + failure.getClass().getName());
    }

    private void note(String phase, String id, String detail) {
        if (diagnostics.size() == MAX_DIAGNOSTICS) diagnostics.removeFirst();
        diagnostics.addLast(new Diagnostic(generation, phase, id, detail));
    }
}
