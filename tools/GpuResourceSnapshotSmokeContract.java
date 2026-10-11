import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Runs without a GPU provider; checks that shutdown cannot admit a later child. */
public final class GpuResourceSnapshotSmokeContract {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("loadonce-gpu-cleanup-check-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        GpuResourceSnapshotSmoke.cleanup();
        try {
            GpuResourceSnapshotSmoke.run(root.resolve("missing-provider.so"), root, "capture", "test-token");
            throw new AssertionError("Cleanup allowed a renderer process to start");
        } catch (IllegalArgumentException failure) {
            if (!failure.getMessage().contains("closed")) {
                throw new AssertionError("Expected rejection before child launch; got: " + failure.getMessage(), failure);
            }
        }
        if (!GpuResourceSnapshotSmoke.CHILDREN.isEmpty()) throw new AssertionError("A child remains registered");
        System.out.println("PASS: closed coordinator rejects a later child launch");
    }
}
