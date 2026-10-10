package io.loadonce.nativepoc;

import io.loadonce.runtime.CheckpointCoordinator;
import io.loadonce.runtime.CheckpointResult;
import io.loadonce.runtime.CheckpointSafety;

import java.util.concurrent.atomic.AtomicReference;

/** Real native integration verification; failures are never converted into skipped/successful checks. */
public final class NativeFixture {
    private NativeFixture() {}

    public static void main(String[] arguments) throws Exception {
        int cycles = 20;
        boolean checkpoint = false;
        boolean fixtureSafe = false;
        for (int index = 0; index < arguments.length; index++) {
            switch (arguments[index]) {
                case "--cycles" -> {
                    if (++index == arguments.length) throw new IllegalArgumentException("--cycles requires an integer");
                    cycles = Integer.parseInt(arguments[index]);
                }
                case "--checkpoint" -> checkpoint = true;
                case "--fixture-safe" -> fixtureSafe = true;
                default -> throw new IllegalArgumentException("Options: --cycles N, --checkpoint --fixture-safe");
            }
        }
        if (cycles < 1 || cycles > 10_000) throw new IllegalArgumentException("--cycles must be in [1, 10000]");
        if (checkpoint && !fixtureSafe) {
            throw new IllegalArgumentException("Checkpoint requires --fixture-safe for this isolated, unauthenticated, world-free fixture");
        }
        if (!"null".equals(System.getenv("ALSOFT_DRIVERS"))) {
            throw new IllegalArgumentException("Fixture requires ALSOFT_DRIVERS=null to select the audited null audio backend");
        }

        try (NativeReplayAdapter adapter = new NativeReplayAdapter(CpuReplayAssets.fixture())) {
            adapter.resumeOnOwnerThread();
            adapter.verifyNativeState();
            adapter.verifyWindowCallback();
            System.out.println("native renderer=" + adapter.rendererIdentity());
            System.out.println("audio backend=OpenAL Soft null; this does not exercise a physical audio device");
            verifyWrongThreadRejected(adapter);

            for (int cycle = 0; cycle < cycles; cycle++) {
                adapter.suspendOnOwnerThread();
                adapter.assertSuspended();
                adapter.beforeCheckpoint();
                adapter.afterRestore();
                adapter.afterRestore(); // Duplicate callback scheduling must be harmless.
                adapter.resumeOnOwnerThread();
                adapter.verifyNativeState();
                adapter.verifyWindowCallback();
            }
            int partialFailures = 0;
            for (NativeReplayAdapter.FailurePoint point : NativeReplayAdapter.FailurePoint.values()) {
                if (point == NativeReplayAdapter.FailurePoint.NONE) continue;
                adapter.suspendOnOwnerThread();
                adapter.failNextConstructionAt(point);
                try {
                    adapter.resumeOnOwnerThread();
                    throw new AssertionError("Expected actual partial reconstruction failure at " + point);
                } catch (NativeReplayAdapter.InjectedFailure expected) {
                    adapter.assertSuspended();
                    if (!adapter.supportsReusableCheckpoint()) throw new AssertionError("Partial failure leaked tracked resources");
                    partialFailures++;
                }
                adapter.afterRestore();
                adapter.resumeOnOwnerThread();
                adapter.verifyNativeState();
            }
            adapter.suspendOnOwnerThread();
            adapter.invalidateShaderIdentityForFixture();
            int compilesBefore = adapter.sourceCompiles();
            adapter.resumeOnOwnerThread();
            adapter.verifyNativeState();
            if (adapter.sourceCompiles() != compilesBefore + 1) {
                throw new AssertionError("A mismatched driver identity must recompile from CPU shader sources");
            }

            if (checkpoint) {
                CheckpointCoordinator coordinator = new CheckpointCoordinator(CheckpointSafety::reusableInitializationOnly);
                coordinator.register(adapter);
                if (!coordinator.isSupported()) throw new IllegalStateException(coordinator.supportMessage());
                adapter.suspendOnOwnerThread();
                adapter.assertSuspended();
                System.out.println("native fixture resources released; requesting actual CRaC checkpoint");
                CheckpointResult result = coordinator.checkpoint();
                if (result.status() != CheckpointResult.Status.RESTORED) {
                    // A failed checkpoint must still leave the fixture usable on its native owner thread.
                    adapter.resumeOnOwnerThread();
                    adapter.verifyNativeState();
                    System.out.println("NATIVE_ROLLBACK_VERIFIED checkpointFailed=true");
                    throw new IllegalStateException(result.status() + ": " + result.detail());
                }
                adapter.resumeOnOwnerThread();
                adapter.verifyNativeState();
                adapter.verifyWindowCallback();
                System.out.println("native fixture reconstructed after real CRaC restore");
            }

            System.out.println("NATIVE_VERIFIED cycles=" + cycles + " generations=" + adapter.generation()
                    + " partialFailures=" + partialFailures + " shaderBinaryReuses=" + adapter.binaryReuses()
                    + " sourceCompiles=" + adapter.sourceCompiles()
                    + " checkpoint=" + checkpoint);
        }
        // Cached library entry points must also survive creating another adapter in this process.
        try (NativeReplayAdapter next = new NativeReplayAdapter(CpuReplayAssets.fixture())) {
            next.resumeOnOwnerThread();
            next.verifyNativeState();
            next.verifyWindowCallback();
        }
        System.out.println("NATIVE_CLEANUP_VERIFIED sequentialAdapter=true");
    }

    private static void verifyWrongThreadRejected(NativeReplayAdapter adapter) throws InterruptedException {
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                adapter.suspendOnOwnerThread();
            } catch (Throwable failure) {
                result.set(failure);
            }
        }, "native-owner-violation-check");
        other.start();
        other.join();
        if (!(result.get() instanceof IllegalStateException) || adapter.state() != NativeReplayAdapter.State.ACTIVE) {
            throw new AssertionError("Off-thread native operation was not rejected before changing native state");
        }
        try {
            adapter.beforeCheckpoint();
            throw new AssertionError("Live native contexts must veto checkpoint preparation");
        } catch (IllegalStateException expected) {
            adapter.verifyNativeState();
        }
    }
}
