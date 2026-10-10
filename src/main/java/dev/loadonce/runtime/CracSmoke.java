package dev.loadonce.runtime;

/** Standalone CRaC engine probe. Compile independently with javac --release 17. */
public final class CracSmoke {
    private CracSmoke() { }

    public static void main(String[] args) throws Exception {
        CheckpointCoordinator coordinator = new CheckpointCoordinator(() ->
                CheckpointGate.Decision.allow("standalone probe has no game, native handles, worlds, or credentials"));
        SmokeResource resource = new SmokeResource();
        coordinator.register(resource);
        CracBridge bridge = CracBridge.install(coordinator);
        System.out.println("Invoking real jdk.crac.Core.checkpointRestore; engine behavior depends on VM flags.");
        CracBridge.CallbackResult callbacks = bridge.checkpointRestore();
        if (coordinator.state() != CheckpointCoordinator.State.READY || resource.value != 42 || resource.detached) {
            throw new IllegalStateException("Smoke lifecycle invariants failed");
        }
        System.out.println("CRaC callbacks completed: " + callbacks);
        System.out.println("Callback completion alone does not verify a kernel snapshot or Minecraft restoration.");
    }

    private static final class SmokeResource implements CheckpointResource {
        private int value = 42;
        private boolean detached;
        @Override public String id() { return "smoke-cpu-state"; }
        @Override public void checkpoint() {
            if (detached) throw new IllegalStateException("already detached");
            detached = true;
        }
        @Override public void restore() {
            if (!detached) throw new IllegalStateException("not detached");
            detached = false;
        }
        @Override public void stopSafely(Throwable cause) { detached = true; }
    }
}
