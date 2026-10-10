package io.loadonce.runtime;

/**
 * Integration-provided, dynamically rechecked evidence about a reusable boundary.
 * This declaration is not a scanner: an integration must audit its whole application, including mods.
 */
public record CheckpointSafety(
        boolean reusableInitialization,
        boolean containsWorldState,
        boolean containsSessionState,
        boolean containsSecrets,
        boolean hasLiveExternalHandles) {

    public static CheckpointSafety denied() {
        return new CheckpointSafety(false, true, true, true, true);
    }

    /** Only for an audited initialization heap with none of the unsafe state below. */
    public static CheckpointSafety reusableInitializationOnly() {
        return new CheckpointSafety(true, false, false, false, false);
    }

    String vetoReason() {
        if (!reusableInitialization) return "Boundary is not reusable initialization";
        if (containsWorldState) return "World state must not enter a reusable image";
        if (containsSessionState) return "Session state must not enter a reusable image";
        if (containsSecrets) return "Secrets must not enter a reusable image";
        if (hasLiveExternalHandles) return "External handles require an audited restoration strategy";
        return null;
    }
}
