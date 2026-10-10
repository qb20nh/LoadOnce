package io.loadonce.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Pure resource preparation logic; exercising this class does not perform a checkpoint. */
final class ResourceLifecycle {
    private final Supplier<CheckpointSafety> guard;
    private final List<ResourceAdapter> adapters = new ArrayList<>();
    private final List<ResourceAdapter> prepared = new ArrayList<>();
    private boolean preparing;
    private boolean checkpointPrepared;
    private boolean broken;
    private CheckpointResult lastFailure;

    ResourceLifecycle(Supplier<CheckpointSafety> guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    synchronized void add(ResourceAdapter adapter) {
        if (preparing || checkpointPrepared || broken) {
            throw new IllegalStateException("Cannot register resources during or after a failed lifecycle");
        }
        adapters.add(Objects.requireNonNull(adapter, "adapter"));
    }

    synchronized void prepare() throws Exception {
        if (preparing || checkpointPrepared || broken) {
            throw veto("A checkpoint is already prepared or resource recovery failed");
        }
        lastFailure = null;
        preparing = true;
        try {
            validateBoundary();
            for (ResourceAdapter adapter : adapters) {
                if (!adapter.supportsReusableCheckpoint()) {
                    throw veto("Resource adapter has not opted in: " + adapter.name());
                }
            }
            for (ResourceAdapter adapter : adapters) {
                // Include the current adapter so partial preparation is rolled back on its failure.
                prepared.add(adapter);
                adapter.beforeCheckpoint();
            }
            validateBoundary();
            checkpointPrepared = true;
        } catch (Exception | Error failure) {
            if (lastFailure == null) {
                lastFailure = new CheckpointResult(CheckpointResult.Status.FAILED,
                        "Resource preparation failed (" + failure.getClass().getSimpleName() + ")");
            }
            recover(failure);
            throw failure;
        } finally {
            preparing = false;
        }
    }

    synchronized void restore() throws Exception {
        Throwable failure = recover(null);
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    synchronized boolean isPrepared() {
        return checkpointPrepared;
    }

    synchronized CheckpointResult beginAttempt() {
        if (broken) {
            return lastFailure;
        }
        lastFailure = null;
        return null;
    }

    synchronized CheckpointResult lastFailure() {
        return lastFailure;
    }

    private void validateBoundary() throws Exception {
        CheckpointSafety safety;
        try {
            safety = guard.get();
        } catch (RuntimeException | LinkageError failure) {
            throw veto("Boundary guard failed (" + failure.getClass().getSimpleName() + ")");
        }
        String reason = safety == null ? "Boundary guard returned no evidence" : safety.vetoReason();
        if (reason != null) {
            throw veto(reason);
        }
    }

    private Exception veto(String detail) {
        lastFailure = new CheckpointResult(CheckpointResult.Status.VETOED, detail);
        return new Exception(detail);
    }

    private Throwable recover(Throwable initialFailure) {
        Throwable failure = initialFailure;
        for (int index = prepared.size() - 1; index >= 0; index--) {
            try {
                prepared.get(index).afterRestore();
            } catch (Exception | Error recoveryFailure) {
                broken = true;
                if (failure == null) {
                    failure = recoveryFailure;
                } else if (failure != recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
                lastFailure = new CheckpointResult(CheckpointResult.Status.FAILED,
                        "Resource recovery failed; further checkpoints disabled ("
                                + recoveryFailure.getClass().getSimpleName() + ")");
            }
        }
        prepared.clear();
        checkpointPrepared = false;
        return failure;
    }
}
