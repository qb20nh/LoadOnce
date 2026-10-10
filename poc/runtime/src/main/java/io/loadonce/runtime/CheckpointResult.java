package io.loadonce.runtime;

public record CheckpointResult(Status status, String detail) {
    public enum Status {
        UNSUPPORTED, VETOED, FAILED, RESTORED
    }
}
