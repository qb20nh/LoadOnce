package com.qb20nh.loadonce.fixture;

import java.util.UUID;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Independent optional mod witnessing initialization and an opaque retained thread. */
public final class AdditionalModFixture {
    private static final UUID identity = UUID.randomUUID();
    private static int calls;
    private static Thread worker;
    private static volatile long counter;

    private AdditionalModFixture() { }

    public static void initialize() {
        if (++calls != 1) throw new IllegalStateException("Additional mod initialized more than once");
        String mode = System.getProperty("loadonce.prototype.boot");
        if (!("cold".equals(mode) || "warm".equals(mode))) {
            throw new IllegalStateException("Additional mod executed before the prototype boot boundary");
        }
        System.out.println("LOADONCE_ADDITIONAL_MOD_INIT identity=" + identity + " mode=" + mode
                + " calls=" + calls + " pid=" + ProcessHandle.current().pid());
        String directory = System.getProperty("loadonce.prototype.opaqueDirectory");
        if (directory != null) {
            if (!Boolean.getBoolean("loadonce.prototype.verify")) {
                throw new IllegalStateException("Opaque fixture requires prototype verification");
            }
            Path workspace = Path.of(directory).toAbsolutePath().normalize();
            try {
                checkPrivate(workspace, true);
                if (!workspace.equals(workspace.toRealPath())
                        || !"loadonce-opaque-v1\n".equals(readPrivate(workspace.resolve(".loadonce-opaque")))) {
                    throw new IOException("Opaque fixture workspace marker is invalid");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Opaque fixture workspace is unavailable", failure);
            }
            worker = new Thread(() -> witness(workspace), "LoadOnce additional mod opaque witness");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void witness(Path workspace) {
        Thread retainedThread = Thread.currentThread();
        UUID threadIdentity = UUID.randomUUID();
        try {
            counter++;
            writePrivate(workspace, "mod-ready.properties", evidence(retainedThread, threadIdentity, counter));
            String generation = null;
            long beforeRelease = 0;
            while (true) {
                Thread.sleep(20);
                counter++;
                if (generation != null) {
                    if (worker != retainedThread || calls != 1 || counter <= beforeRelease) {
                        throw new IOException("Opaque fixture thread or initialization changed");
                    }
                    writePrivate(workspace, "mod-resumed-" + generation + ".properties",
                            "generation=" + generation + "\ncounter_before_release=" + beforeRelease + "\n"
                                    + evidence(retainedThread, threadIdentity, counter));
                    System.out.println("LOADONCE_ADDITIONAL_MOD_RESUMED generation=" + generation + " identity=" + identity
                            + " calls=" + calls + " threadIdentity=" + threadIdentity + " threadId=" + retainedThread.threadId()
                            + " counterBefore=" + beforeRelease + " counterAfter=" + counter);
                    // Continue this original thread after publishing its continuation witness.
                    while (true) {
                        Thread.sleep(20);
                        counter++;
                    }
                }
                Path release = workspace.resolve("release");
                if (Files.exists(release, LinkOption.NOFOLLOW_LINKS)) {
                    generation = readPrivate(release);
                    if (!generation.matches("[1-3]\\n")) throw new IOException("Opaque release generation is invalid");
                    generation = generation.strip();
                    beforeRelease = counter;
                }
            }
        } catch (Exception failure) {
            System.out.println("LOADONCE_ADDITIONAL_MOD_OPAQUE_FAILED identity=" + identity + " workspace=" + workspace + " reason=" + failure);
        }
    }

    private static String evidence(Thread thread, UUID threadIdentity, long value) {
        return "identity=" + identity + "\ncalls=" + calls + "\nthread_identity=" + threadIdentity
                + "\nthread_id=" + thread.threadId() + "\nthread_object_identity=" + System.identityHashCode(thread)
                + "\ncounter=" + value + "\nwall_millis=" + System.currentTimeMillis() + "\nnano_time=" + System.nanoTime() + "\n";
    }

    private static void checkPrivate(Path path, boolean directory) throws IOException {
        var attributes = Files.readAttributes(path, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || !(directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                || !attributes.get("uid").equals(Files.getAttribute(Path.of("/proc/self"), "unix:uid"))
                || (((Number) attributes.get("mode")).intValue() & 07777) != (directory ? 0700 : 0600)
                || (!directory && ((Number) attributes.get("nlink")).intValue() != 1)) {
            throw new IOException("Opaque fixture requires private owner-controlled files: " + path);
        }
    }

    private static String readPrivate(Path path) throws IOException {
        checkPrivate(path, false);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("Opaque fixture evidence exceeds 4096 bytes");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void writePrivate(Path workspace, String name, String content) throws IOException {
        checkPrivate(workspace, true);
        Path temporary = Files.createTempFile(workspace, ".mod-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, workspace.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
