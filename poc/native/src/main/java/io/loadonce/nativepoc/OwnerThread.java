package io.loadonce.nativepoc;

/** GLFW operations stay on the creating thread; CRaC callbacks may only change lifecycle flags. */
final class OwnerThread {
    private final Thread owner = Thread.currentThread();

    void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Native reconstruction must run on its owning thread");
        }
    }
}
