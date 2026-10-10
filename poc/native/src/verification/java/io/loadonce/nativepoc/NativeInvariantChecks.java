package io.loadonce.nativepoc;

import java.util.concurrent.atomic.AtomicReference;

/** Heap-data and thread-ownership checks. These are deliberately separate from native verification. */
public final class NativeInvariantChecks {
    private static int checks;

    public static void main(String[] arguments) throws InterruptedException {
        byte[] pixels = new byte[4];
        float[] vertices = {-1, -1, 3, -1, -1, 3};
        short[] pcm = {1, 2, 3};
        CpuReplayAssets assets = new CpuReplayAssets(1, 1, pixels, vertices, pcm, 8_000, "vertex", "fragment");
        pixels[0] = 10;
        vertices[0] = 10;
        pcm[0] = 10;
        require(assets.rgba()[0] == 0 && assets.vertices()[0] == -1 && assets.pcm()[0] == 1,
                "Replay inputs must not alias caller arrays");
        assets.rgba()[0] = 20;
        assets.vertices()[0] = 20;
        assets.pcm()[0] = 20;
        require(assets.rgba()[0] == 0 && assets.vertices()[0] == -1 && assets.pcm()[0] == 1,
                "Replay accessors must not expose mutable cache state");
        rejects(() -> new CpuReplayAssets(0, 1, pixels, vertices, pcm, 8_000, "v", "f"));
        rejects(() -> new CpuReplayAssets(4_097, 1, pixels, vertices, pcm, 8_000, "v", "f"));
        rejects(() -> new CpuReplayAssets(1, 2, pixels, vertices, pcm, 8_000, "v", "f"));
        rejects(() -> new CpuReplayAssets(1, 1, pixels, new float[2], pcm, 8_000, "v", "f"));
        rejects(() -> new CpuReplayAssets(1, 1, pixels, new float[] {Float.NaN, 0, 0, 0, 0, 0}, pcm, 8_000, "v", "f"));
        rejects(() -> new CpuReplayAssets(1, 1, pixels, vertices, new short[0], 8_000, "v", "f"));
        rejects(() -> new CpuReplayAssets(1, 1, pixels, vertices, pcm, 0, "v", "f"));
        rejects(() -> new CpuReplayAssets(1, 1, pixels, vertices, pcm, 8_000, " ", "f"));

        OwnerThread owner = new OwnerThread();
        owner.requireOwner();
        AtomicReference<Throwable> offThreadFailure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try { owner.requireOwner(); } catch (Throwable failure) { offThreadFailure.set(failure); }
        });
        other.start();
        other.join();
        require(offThreadFailure.get() instanceof IllegalStateException, "Owner guard must reject a different thread");
        owner.requireOwner();
        System.out.println("Native heap/thread invariant checks passed: " + checks + "; native integration not exercised");
    }

    private static void rejects(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Invalid replay asset was accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
        checks++;
    }
}
