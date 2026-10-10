package com.qb20nh.loadonce.launcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Stock JVM protocol checks; these do not prove checkpoint/restore. */
public final class OpaqueStateFixtureTest {
    private static int checks;

    private OpaqueStateFixtureTest() { }

    public static void main(String[] arguments) throws Exception {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            System.out.println("Opaque state fixture protocol checks skipped: POSIX privacy unavailable");
            return;
        }
        Path root = Files.createTempDirectory("loadonce-opaque-contract-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            continuedStateAndColdRestart(root);
            insufficientPauseIsRejected(root);
            System.out.println("Opaque state fixture protocol checks passed: " + checks
                    + "; no process image restore proved");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static void continuedStateAndColdRestart(Path root) throws Exception {
        Path directory = privateDirectory(root.resolve("continue"));
        Process process = start(directory, "first.log");
        try {
            awaitReady(process, directory);
            Properties ready = read(directory.resolve("ready.properties"));
            signal(directory, "contract-generation", 0);
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
                    "ordinary continuation failed; inspect " + directory.resolve("first.log"));
            Properties result = read(directory.resolve("result.properties"));
            check("passed".equals(result.getProperty("fixture_validation")), "fixture validation result");
            check("contract-generation".equals(result.getProperty("generation")), "generation signal is acknowledged");
            for (String key : List.of("heap_witness", "initialization_count", "object_identity", "parked_stack_witness")) {
                check(ready.getProperty(key).equals(result.getProperty(key)), key + " is preserved");
            }
            check("1".equals(result.getProperty("initialization_count")), "initialization runs once");
            check(Long.parseLong(result.getProperty("continued_progress"))
                            > Long.parseLong(result.getProperty("ready_progress")),
                    "background worker continues initialized progress");
            check(Long.parseLong(result.getProperty("resource_offset_after"))
                            > Long.parseLong(ready.getProperty("resource_offset")),
                    "existing open file is read from its initialized offset");
            Process cold = start(directory, "cold.log");
            try {
                check(cold.waitFor(10, TimeUnit.SECONDS) && cold.exitValue() != 0,
                        "cold restart must not imitate restored initialized state");
                check(Files.readString(directory.resolve("cold.log")).contains("FileAlreadyExistsException"),
                        "cold restart identifies existing initialization evidence");
            } finally {
                stop(cold);
            }
        } finally {
            stop(process);
        }
    }

    private static void insufficientPauseIsRejected(Path root) throws Exception {
        Path directory = privateDirectory(root.resolve("insufficient-pause"));
        Process process = start(directory, "fixture.log");
        try {
            awaitReady(process, directory);
            signal(directory, "requires-real-pause", 60_000);
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() != 0,
                    "ordinary continuation must fail a required suspension gap");
            check(!Files.exists(directory.resolve("result.properties")), "failed witnesses do not emit a passing result");
            check(Files.readString(directory.resolve("fixture.log")).contains("below requested 60000ms"),
                    "pause failure identifies expected and observed duration");
        } finally {
            stop(process);
        }
    }

    private static Process start(Path directory, String log) throws Exception {
        List<String> classpath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator), -1)) {
            classpath.add(Path.of(entry.isEmpty() ? "." : entry).toAbsolutePath().normalize().toString());
        }
        Path input = directory.resolve("stdin");
        if (!Files.exists(input)) Files.createFile(input);
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+UseSerialGC", "-Xms16m", "-Xmx32m", "-XX:-UsePerfData", "-cp",
                String.join(File.pathSeparator, classpath), OpaqueStateFixture.class.getName(),
                directory.toString(), "20000");
        builder.environment().clear();
        return builder.redirectInput(input.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve(log).toFile()).start();
    }

    private static void awaitReady(Process process, Path directory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(directory.resolve("ready.properties"))) {
            check(process.isAlive(), "fixture exited before ready; inspect " + directory);
            if (System.nanoTime() >= deadline) throw new AssertionError("fixture ready timeout: " + directory);
            Thread.sleep(10);
        }
    }

    private static void signal(Path directory, String generation, long minimumPause) throws Exception {
        Path temporary = directory.resolve("signal.tmp");
        Files.writeString(temporary, "generation=" + generation + "\nminimum_pause_millis=" + minimumPause + "\n",
                StandardCharsets.UTF_8);
        Files.move(temporary, directory.resolve("continue.properties"));
    }

    private static Properties read(Path file) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Path privateDirectory(Path directory) throws Exception {
        return Files.createDirectory(directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
