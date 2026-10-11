import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Lightweight namespace admission check; no display, credentials or connections. */
public final class FabricLanPeerNamespaceContract {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--inside")) {
            RunFabricPackIsolated.peerNamespaces(ProcessHandle.current());
            System.out.println("PASS: owned loopback-only namespace admission");
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("No arguments expected");
        var existing = new java.util.HashSet<java.nio.file.Path>();
        try (var entries = java.nio.file.Files.newDirectoryStream(java.nio.file.Path.of("/tmp"), "loadonce-peer-origin-*.properties")) {
            for (var path : entries) existing.add(path);
        }
        var printed = new java.io.ByteArrayOutputStream();
        var originalOutput = System.out;
        try (var capture = new java.io.PrintStream(printed, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            RunFabricPackIsolated.main(new String[] { RunFabricPackIsolated.PRISM_ROOT.toString(), "--namespace-origin" });
        } finally {
            System.setOut(originalOutput);
        }
        var primed = java.nio.file.Path.of(printed.toString(StandardCharsets.UTF_8).strip());
        try {
            isolatedCheck(true);
            isolatedCheck(false, false, "Peer and host require the same isolated net namespace");
            System.out.println("PASS: exact CLI primer and isolated child admission");
            System.out.println("PASS: original host network namespace rejected inside a new user namespace");
        } finally {
            if (!existing.contains(primed)) java.nio.file.Files.delete(primed);
        }
        var marker = RunFabricPackIsolated.captureNamespaceOrigin(ProcessHandle.current());
        try {
            isolatedCheck(true);
            var proof = new java.util.Properties();
            try (var input = java.nio.file.Files.newInputStream(marker)) { proof.load(input); }
            proof.setProperty("originStarted", "1970-01-01T00:00:00Z");
            try (var output = java.nio.file.Files.newOutputStream(marker)) { proof.store(output, "Synthetic stale proof"); }
            isolatedCheck(false);
            java.nio.file.Files.delete(marker);
            RunFabricPackIsolated.captureNamespaceOrigin(ProcessHandle.current());
            try (var input = java.nio.file.Files.newInputStream(marker)) { proof.load(input); }
            proof.setProperty("originUid", "1001");
            try (var output = java.nio.file.Files.newOutputStream(marker)) { proof.store(output, "Synthetic altered identity"); }
            isolatedCheck(false);
            java.nio.file.Files.delete(marker);
            RunFabricPackIsolated.captureNamespaceOrigin(ProcessHandle.current());
            java.nio.file.Files.setPosixFilePermissions(marker, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            isolatedCheck(false);
            System.out.println("PASS: stale, altered identity and nonprivate namespace proofs rejected");
        } finally {
            java.nio.file.Files.deleteIfExists(marker);
        }
        try {
            RunFabricPackIsolated.peerNamespaces(ProcessHandle.current());
            throw new AssertionError("Original host namespace was admitted");
        } catch (IOException rejected) {
            if (!"Peer namespace must map only UID/GID 0 to 1000".equals(rejected.getMessage())) {
                throw new AssertionError("Host rejection was not the intended containment guard", rejected);
            }
        }
        System.out.println("PASS: original host namespace rejected");
    }

    private static void isolatedCheck(boolean accepted) throws Exception {
        isolatedCheck(accepted, true, "Cannot prove original host namespaces from a live owned ancestor");
    }

    private static void isolatedCheck(boolean accepted, boolean privateNetwork, String rejection) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("/usr/bin/unshare", "--user", "--map-root-user"));
        if (privateNetwork) command.add("--net");
        command.addAll(java.util.List.of("/usr/bin/sh", "-c", privateNetwork ? "/usr/bin/ip link set lo up && exec \"$@\"" : "exec \"$@\"", "loadonce-namespace-contract",
                "/usr/lib/jvm/java-17-openjdk/bin/java", "-Xmx64m", "-cp", System.getProperty("java.class.path"),
                FabricLanPeerNamespaceContract.class.getName(), "--inside"));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!child.waitFor(15, TimeUnit.SECONDS)) throw new AssertionError("Namespace fixture timed out");
            String output = new String(child.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
            if (accepted) {
                if (child.exitValue() != 0) throw new AssertionError("Namespace admission failed: " + output);
                System.out.print(output);
            } else if (child.exitValue() == 0 || !output.contains(rejection)) {
                throw new AssertionError("Invalid namespace proof was not rejected by its intended guard: " + output);
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }
}
