package io.loadonce.runtime;

/** An audited integration must explicitly opt in and implement reversible preparation. */
public interface ResourceAdapter {
    String name();

    /** Default veto prevents unknown native resources from silently entering an image. */
    default boolean supportsReusableCheckpoint() {
        return false;
    }

    /** May partially prepare before throwing; afterRestore must also undo that partial preparation. */
    void beforeCheckpoint() throws Exception;

    /** Recreates resources after a restore, and also rolls preparation back after a failed attempt. */
    void afterRestore() throws Exception;
}
