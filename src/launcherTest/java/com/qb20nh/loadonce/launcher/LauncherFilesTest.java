package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** File trust and subprocess contracts use the real file system and JVM children. */
public final class LauncherFilesTest {
    private static int checks;

    private LauncherFilesTest() {}

    public static void main(String[] args) throws Exception {
        Path root = LauncherFiles.tempDirectory(null, "loadonce-files-contract-");
        try {
            Path file = LauncherFiles.tempFile(root, "payload-", ".bin");
            Files.writeString(file, "version1");
            LauncherFiles.checkPrivate(file, false);
            String first = digest(file);
            FileTime time = Files.getLastModifiedTime(file);
            Files.writeString(file, "version2");
            Files.setLastModifiedTime(file, time);
            check(!first.equals(digest(file)), "same-size/same-time content mutation must change fingerprint");

            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            fails(() -> LauncherFiles.checkPrivate(file, false), "nonprivate file accepted");
            LauncherFiles.sealTree(root);
            LauncherFiles.checkPrivate(file, false);
            check(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")), "generated output not sealed");

            Path link = root.resolve("hardlink");
            Files.createLink(link, file);
            fails(() -> LauncherFiles.checkPrivate(file, false), "multiply linked file accepted");
            fails(() -> LauncherFiles.sealTree(root), "generated hardlink accepted");
            Files.delete(link);
            Path symbolic = root.resolve("symbolic");
            Files.createSymbolicLink(symbolic, root);
            fails(() -> LauncherFiles.privateDirectory(symbolic.resolve("cache")), "symlink ancestor accepted");
            fails(() -> digest(root), "symbolic link cycle accepted");
            Files.delete(symbolic);

            Path manifest = LauncherFiles.tempFile(root, "manifest-", ".properties");
            Map<String, String> fields = Map.of("schema", "3", "completed", "true", "sha256", "abc123");
            LauncherFiles.writeManifest(manifest, fields);
            check(LauncherFiles.readManifest(manifest, fields.keySet()).equals(fields), "manifest did not round-trip");
            Files.writeString(manifest, "schema=3\nschema=3\ncompleted=true\nsha256=abc123\n");
            fails(() -> LauncherFiles.readManifest(manifest, fields.keySet()), "duplicate manifest accepted");
            Files.writeString(manifest, "schema=3\ncompleted=true\nsha256=" + "x".repeat(4096));
            fails(() -> LauncherFiles.readManifest(manifest, fields.keySet()), "oversized manifest accepted");
            try (var lock = LauncherFiles.lock(root, true)) {
                fails(() -> { try (var unused = LauncherFiles.lock(root, false)) { throw new AssertionError("busy cache locked twice"); } },
                        "busy cache accepted");
            }

            Map<String, String> environment = new HashMap<>();
            environment.put("PATH", "/usr/bin:/bin");
            environment.put("LANG", "C.UTF-8");
            environment.put("LOADONCE_TEST_VALUE", "passed-as-explicit-environment");
            List<String> command = child("arguments", "a b", "literal$HOME", "quote\"value", "--flag=value", "");
            Path log = LauncherFiles.tempFile(root, "child-", ".log");
            var result = ChildProcess.run(command, root, environment, log, Duration.ofSeconds(10));
            check(result.exitCode() == 7, "child nonzero exit changed");
            check(result.pid() != ProcessHandle.current().pid(), "child did not launch independently");
            String encoded = String.join("|", command.subList(command.size() - 5, command.size()).stream()
                    .map(value -> Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList());
            check(result.output().contains("ARGS:" + encoded), "argument boundaries changed");
            check(result.output().contains("ENV:passed-as-explicit-environment"), "explicit environment missing");
            check(result.output().contains("ENV_SIZE:3"), "parent environment leaked into child");

            Path stdout = LauncherFiles.tempFile(root, "stdout-", ".log");
            Path stderr = LauncherFiles.tempFile(root, "stderr-", ".log");
            var streams = ChildProcess.run(child("streams"), root, environment, stdout, stderr, Duration.ofSeconds(10));
            check(streams.exitCode() == 0 && Files.readString(stdout).contains("STDOUT_ONLY")
                    && !Files.readString(stdout).contains("STDERR_ONLY"), "stdout capture mixed streams");
            check(Files.readString(stderr).equals("STDERR_ONLY\n") && streams.output().contains("STDERR_ONLY"),
                    "separate stderr capture missing");

            Path timeoutLog = LauncherFiles.tempFile(root, "timeout-", ".log");
            fails(() -> ChildProcess.run(child("sleep"), root, environment, timeoutLog, Duration.ofSeconds(2)), "child deadline ignored");
            check(!alive(pidFrom(timeoutLog)), "timed-out child still alive");

            // A briefly lived intermediate exits before ProcessHandle polling can
            // discover its child. The isolated fixture's process group must stop it.
            Path orphanPid = root.resolve("orphan.pid");
            Path orphanLog = LauncherFiles.tempFile(root, "orphan-", ".log");
            try {
                fails(() -> ChildProcess.runIsolated(child("orphan", orphanPid.toString()), root, environment,
                        orphanLog, Duration.ofSeconds(2)), "isolated child deadline ignored");
                check(Files.isRegularFile(orphanPid), "orphan regression did not create its grandchild");
                long orphan = Long.parseLong(Files.readString(orphanPid));
                check(!running(orphan), "orphaned fixture helper survived process-group cancellation");
            } finally {
                if (Files.isRegularFile(orphanPid)) {
                    long orphan = Long.parseLong(Files.readString(orphanPid));
                    ProcessHandle.of(orphan).ifPresent(ProcessHandle::destroyForcibly);
                }
            }

            // The display harness can supervise a Java driver which owns a
            // second isolated engine session. Cancellation must stop both groups.
            Path nestedPid = root.resolve("nested-orphan.pid");
            Path nestedLog = LauncherFiles.tempFile(root, "nested-", ".log");
            AtomicReference<Throwable> nestedFailure = new AtomicReference<>();
            Thread nestedWorker = new Thread(() -> {
                try {
                    ChildProcess.runIsolated(child("nested", nestedPid.toString()), root, environment,
                            nestedLog, Duration.ofSeconds(15));
                    nestedFailure.set(new AssertionError("nested cancellation returned successfully"));
                } catch (InterruptedException expected) {
                    // The outer driver may be killed before its own hook runs.
                } catch (Throwable unexpected) {
                    nestedFailure.set(unexpected);
                }
            }, "loadonce-nested-cancellation-contract");
            try {
                nestedWorker.start();
                long readyDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (!Files.isRegularFile(nestedPid) && nestedWorker.isAlive() && System.nanoTime() < readyDeadline) Thread.sleep(10);
                check(Files.isRegularFile(nestedPid), "nested engine did not create its orphaned helper");
                // Allow an outer process sample after the inner session exists.
                Thread.sleep(100);
                nestedWorker.interrupt();
                nestedWorker.join(10_000);
                check(!nestedWorker.isAlive(), "nested cancellation did not finish");
                if (nestedFailure.get() != null) throw new AssertionError("nested cleanup failed", nestedFailure.get());
                check(!running(Long.parseLong(Files.readString(nestedPid))), "nested isolated engine helper survived cancellation");
            } finally {
                nestedWorker.interrupt();
                nestedWorker.join(10_000);
                if (Files.isRegularFile(nestedPid)) ProcessHandle.of(Long.parseLong(Files.readString(nestedPid)))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Path interruptedLog = LauncherFiles.tempFile(root, "interrupted-", ".log");
            Thread worker = new Thread(() -> {
                try {
                    ChildProcess.run(child("sleep"), root, environment, interruptedLog, null);
                    failure.set(new AssertionError("interrupted launch returned successfully"));
                } catch (InterruptedException expected) {
                    // Cancellation must terminate the launched child rather than start another.
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                }
            }, "loadonce-interruption-contract");
            worker.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (Files.size(interruptedLog) == 0 && worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            worker.interrupt();
            worker.join(10_000);
            check(!worker.isAlive(), "interrupted launch did not finish");
            if (failure.get() != null) throw new AssertionError("interrupt cleanup failed", failure.get());
            check(!alive(pidFrom(interruptedLog)), "interrupted child still alive");
            System.out.println("Java file/process contract checks passed: " + checks);
        } finally {
            LauncherFiles.deleteTree(root);
        }
    }

    private static List<String> child(String... args) {
        List<String> result = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", absoluteClasspath(), Child.class.getName()));
        result.addAll(List.of(args));
        return result;
    }

    private static String absoluteClasspath() {
        return String.join(java.io.File.pathSeparator, java.util.Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)).map(value -> Path.of(value.isEmpty() ? "." : value)
                .toAbsolutePath().normalize().toString()).toList());
    }

    private static long pidFrom(Path log) throws IOException {
        String output = Files.readString(log);
        return Long.parseLong(output.lines().filter(line -> line.startsWith("PID:")).findFirst()
                .orElseThrow(() -> new IOException("child did not report identity")).substring(4));
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean running(long pid) throws IOException {
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (!Files.exists(stat)) return false;
        String status = Files.readString(stat);
        return status.charAt(status.lastIndexOf(')') + 2) != 'Z';
    }

    private static String digest(Path path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        LauncherFiles.digestPath(hash, path);
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void check(boolean success, String message) {
        checks++;
        if (!success) throw new AssertionError(message);
    }

    private static void fails(Checked operation, String message) throws Exception {
        checks++;
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError(message);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }

    public static final class Child {
        private Child() {}
        public static void main(String[] args) throws Exception {
            System.out.println("PID:" + ProcessHandle.current().pid());
            if (args[0].equals("streams")) {
                System.out.println("STDOUT_ONLY");
                System.err.println("STDERR_ONLY");
                return;
            }
            if (args[0].equals("nested")) {
                Path directory = Path.of(args[1]).getParent();
                ChildProcess.runIsolated(child("orphan", args[1]), directory, System.getenv(),
                        LauncherFiles.tempFile(directory, "engine-", ".log"), Duration.ofSeconds(30));
                return;
            }
            if (args[0].equals("detached")) {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(30_000);
                return;
            }
            if (args[0].equals("handoff")) {
                new ProcessBuilder(child("detached", args[1])).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                return;
            }
            if (args[0].equals("orphan")) {
                new ProcessBuilder(child("handoff", args[1])).inheritIO().start().waitFor();
                Thread.sleep(30_000);
                return;
            }
            if (args[0].equals("sleep")) {
                System.out.flush();
                Thread.sleep(30_000);
                return;
            }
            System.out.println("ARGS:" + String.join("|", java.util.Arrays.stream(args).skip(1)
                    .map(value -> Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList()));
            System.out.println("ENV:" + System.getenv("LOADONCE_TEST_VALUE"));
            System.out.println("ENV_SIZE:" + System.getenv().size());
            System.exit(7);
        }
    }
}
