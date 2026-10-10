package com.qb20nh.loadonce.runtime;

/**
 * A resource owned by the coordinator's thread. Register dependencies first.
 * Checkpoint must release native/OS handles; restore must create fresh handles
 * from reusable CPU state, never reuse saved handle values.
 */
public interface CheckpointResource {
    String id();

    /** Stop producers/callbacks without destroying reconstruction data. */
    default void quiesce() throws Exception { }

    /** Release external state after every resource has quiesced. */
    void checkpoint() throws Exception;

    /** Recover even a partially failed checkpoint; remain quiescent. */
    void restore() throws Exception;

    /** Resume after all required resources have been recreated. */
    default void resume() throws Exception { }

    /** Leave the resource stopped; tolerate partially released or recreated state. */
    void stopSafely(Throwable cause) throws Exception;
}
