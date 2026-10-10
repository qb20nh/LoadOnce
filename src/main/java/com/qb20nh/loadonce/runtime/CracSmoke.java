package com.qb20nh.loadonce.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/** Standalone CRaC engine probe. Compile independently with javac --release 17. */
public final class CracSmoke {
    private CracSmoke() { }

    public static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "success" : args[0];
        if (args.length > 1 || !Set.of("cold", "success", "denied", "prepare-failure", "restore-failure", "descriptor-rejection").contains(scenario)) {
            throw new IllegalArgumentException("Usage: CracSmoke [cold|success|denied|prepare-failure|restore-failure|descriptor-rejection]");
        }
        // Read the kernel's PID, rather than relying on a cached Java process handle.
        // The clean smoke driver compares this with its actual restored child PID.
        long initialPid = kernelPid();
        CheckpointCoordinator coordinator = new CheckpointCoordinator(() ->
                scenario.equals("denied") ? CheckpointGate.Decision.deny("isolated gate denial test")
                        : CheckpointGate.Decision.allow("isolated fixture; launcher must exclude credentials from environment"));
        SmokeResource resource = new SmokeResource(scenario);
        coordinator.register(resource);
        if (scenario.equals("cold")) {
            System.out.println("CRAC_SMOKE COLD pid=" + initialPid + " heap-witness=" + resource.witness
                    + " value=" + resource.value + " coordinator-state=" + coordinator.state());
            return;
        }
        CracBridge bridge = CracBridge.install(coordinator);
        System.out.println("CRAC_SMOKE initial-pid=" + initialPid + " heap-witness=" + resource.witness);
        System.out.println("Invoking real jdk.crac.Core.checkpointRestore; engine behavior depends on VM flags.");
        CracBridge.CallbackResult callbacks;
        Path untrackedPath = null;
        FileInputStream untrackedDescriptor = null;
        try {
            if (scenario.equals("descriptor-rejection")) {
                untrackedPath = Files.createTempFile("loadonce-untracked-", ".txt");
                Files.writeString(untrackedPath, "isolated descriptor rejection fixture");
                untrackedDescriptor = new FileInputStream(untrackedPath.toFile());
                if (untrackedDescriptor.read() < 0) throw new IllegalStateException("descriptor fixture is empty");
            }
            callbacks = bridge.checkpointRestore();
        } catch (Exception failure) {
            boolean descriptorRejection = scenario.equals("descriptor-rejection");
            String expectedFailure = descriptorRejection ? ".CheckpointOpenFileException" : ".CheckpointCoordinator$CheckpointException";
            if (scenario.equals("success") || !hasFailureType(failure,
                    Collections.newSetFromMap(new IdentityHashMap<>()), expectedFailure)) throw failure;
            CheckpointCoordinator.State expected = scenario.equals("restore-failure")
                    ? CheckpointCoordinator.State.STOPPED : CheckpointCoordinator.State.READY;
            if (coordinator.state() != expected || resource.detached != (expected == CheckpointCoordinator.State.STOPPED)
                    || (descriptorRejection && (resource.preparations != 1 || resource.restorations != 1))) {
                throw new IllegalStateException("CRaC failure recovery invariants failed", failure);
            }
            System.out.println("CRAC_SMOKE expected-failure=" + scenario + " coordinator-state=" + expected);
            return;
        } finally {
            if (untrackedDescriptor != null) untrackedDescriptor.close();
            if (untrackedPath != null) Files.deleteIfExists(untrackedPath);
        }
        if (!scenario.equals("success")) throw new IllegalStateException("Expected CRaC lifecycle failure did not occur");
        if (coordinator.state() != CheckpointCoordinator.State.READY || resource.value != 42 || resource.detached
                || resource.preparations != 1 || resource.restorations != 1) {
            throw new IllegalStateException("Smoke lifecycle invariants failed");
        }
        System.out.println("CRAC_SMOKE restored-pid=" + kernelPid() + " initial-pid=" + initialPid
                + " heap-witness=" + resource.witness + " value=" + resource.value + " coordinator-state=" + coordinator.state());
        System.out.println("CRaC callbacks completed: " + callbacks);
        System.out.println("Callback completion alone does not verify a kernel snapshot or Minecraft restoration.");
    }

    private static long kernelPid() throws Exception {
        String stat = Files.readString(Path.of("/proc/self/stat"));
        return Long.parseLong(stat.substring(0, stat.indexOf(' ')));
    }

    private static boolean hasFailureType(Throwable failure, Set<Throwable> seen, String suffix) {
        if (failure == null || !seen.add(failure)) return false;
        if (failure.getClass().getName().endsWith(suffix)) return true;
        if (hasFailureType(failure.getCause(), seen, suffix)) return true;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (hasFailureType(suppressed, seen, suffix)) return true;
        }
        return false;
    }

    private static final class SmokeResource implements CheckpointResource {
        private final String scenario;
        private final UUID witness = UUID.randomUUID();
        private int value = 42;
        private boolean detached;
        private int preparations;
        private int restorations;
        private SmokeResource(String scenario) { this.scenario = scenario; }
        @Override public String id() { return "smoke-cpu-state"; }
        @Override public void checkpoint() {
            if (detached) throw new IllegalStateException("already detached");
            detached = true;
            preparations++;
            if (scenario.equals("prepare-failure")) throw new IllegalStateException("isolated preparation failure");
        }
        @Override public void restore() {
            if (!detached) throw new IllegalStateException("not detached");
            restorations++;
            if (scenario.equals("restore-failure")) throw new IllegalStateException("isolated restoration failure");
            detached = false;
        }
        @Override public void stopSafely(Throwable cause) { detached = true; }
    }
}
