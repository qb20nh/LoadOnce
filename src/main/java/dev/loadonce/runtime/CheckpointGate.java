package dev.loadonce.runtime;

import java.util.Objects;

/**
 * Host integration must prove a reusable lifecycle boundary: no credentials,
 * sessions, stale world state, live connections, or untracked native resources.
 * Evaluate without changing resource state. An absent or failed proof denies
 * the checkpoint. This is not a sandbox.
 */
@FunctionalInterface
public interface CheckpointGate {
    Decision evaluate() throws Exception;

    record Decision(boolean approved, String reason) {
        public Decision {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank() || reason.length() > 256) {
                throw new IllegalArgumentException("A short, non-sensitive gate reason is required");
            }
        }

        public static Decision allow(String reason) { return new Decision(true, reason); }
        public static Decision deny(String reason) { return new Decision(false, reason); }
    }
}
