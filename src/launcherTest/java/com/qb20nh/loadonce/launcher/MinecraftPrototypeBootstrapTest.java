package com.qb20nh.loadonce.launcher;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Real JVM cold-bootstrap contracts; warm restoration is exercised by the game harness. */
public final class MinecraftPrototypeBootstrapTest {
    private static int checks;

    private MinecraftPrototypeBootstrapTest() {}

    public static void main(String[] arguments) throws Exception {
        if (!System.getProperty("os.name").equals("Linux")
                || !Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")))
                        .supportsFileAttributeView("posix")) {
            System.out.println("Minecraft prototype bootstrap contracts skipped: Linux/POSIX required");
            return;
        }
        currentRequestStartsApplicationExactlyOnce();
        applicationFailureDoesNotReplay();
        invalidBootstrapAndRequestModesRejectBeforeApplication();
        untrustedProfileAndControlFilesRejectBeforeApplication();
        malformedApplicationArgumentsRejectBeforeApplication();
        System.out.println("Minecraft prototype bootstrap contract checks passed: " + checks);
    }

    private static void currentRequestStartsApplicationExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.request(List.of("obsolete argument"));
            List<String> current = List.of("", "two words", "literal$HOME", "literal*", "literal;$()",
                    "quote\"value", "back\\slash", "unicode-π-한글", "line\nfeed", "tab\tvalue",
                    "--option=value", "@literal-argument.txt");
            fixture.request(current);
            Result first = fixture.launch(false, "--cold", "--control-file", fixture.control.toString());
            equal(0, first.exitCode());
            equal(1L, fixture.initializations());
            equal(1L, fixture.invocations());
            Application firstApplication = fixture.application();
            equal(current, firstApplication.arguments());
            equal("cold", firstApplication.initializationMode());
            equal("cold", firstApplication.mode());
            equal("false", firstApplication.verify());
            equal("LoadOncePrototype", firstApplication.world());
            UUID.fromString(firstApplication.witness());
            check(firstApplication.pid() != ProcessHandle.current().pid(), "application must run in a child JVM");
            check(first.output().contains("mode=cold") && first.output().contains("callbacks=0/0")
                    && first.output().contains("application-initialization=fresh"), "cold boot evidence missing");

            Properties next = fixture.request(List.of());
            next.setProperty("verify", "true");
            fixture.write(next);
            Result second = fixture.launch(false, "--cold", "--control-file", fixture.control.toString());
            equal(0, second.exitCode());
            equal(2L, fixture.initializations());
            equal(2L, fixture.invocations());
            Application secondApplication = fixture.application();
            equal(List.of(), secondApplication.arguments());
            equal("true", secondApplication.verify());
            equal("cold", secondApplication.initializationMode());
            check(!firstApplication.witness().equals(secondApplication.witness()), "cold JVM state was reused");
        }
    }

    private static void applicationFailureDoesNotReplay() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> arguments = List.of("fail once", "literal;$()");
            fixture.request(arguments);
            Result result = fixture.launch(true, "--cold", "--control-file", fixture.control.toString());
            check(result.exitCode() != 0, "application failure must propagate");
            check(result.output().contains("Application fixture failed after startup"), "application failure missing");
            equal(1L, fixture.initializations());
            equal(1L, fixture.invocations());
            equal(arguments, fixture.application().arguments());
        }
    }

    private static void invalidBootstrapAndRequestModesRejectBeforeApplication() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.request(List.of("must not execute"));
            for (List<String> arguments : List.of(List.<String>of(), List.of("--warm", "--control-file", fixture.control.toString()),
                    List.of("--cold", "--request", fixture.control.toString()),
                    List.of("--cold", "--control-file", fixture.control.toString(), "extra"))) {
                fixture.reject(arguments, "Expected --checkpoint|--cold");
            }
            for (String mode : List.of("warm", "invalid", "")) {
                Properties request = fixture.request(List.of());
                request.setProperty("mode", mode);
                fixture.write(request);
                fixture.reject("mode");
            }
            Properties request = fixture.request(List.of());
            request.remove("mode");
            fixture.write(request);
            fixture.reject("mode");
            request = fixture.request(List.of());
            request.setProperty("main", MinecraftPrototypeBootstrapTest.class.getName());
            fixture.write(request);
            fixture.reject("Fabric development launcher");
        }
    }

    private static void untrustedProfileAndControlFilesRejectBeforeApplication() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.request(List.of());
            Files.delete(fixture.marker);
            fixture.reject(".loadonce-prototype");
            Files.createFile(fixture.marker,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(fixture.marker, "not a prototype profile\n");
            fixture.reject("profile marker");
            Files.writeString(fixture.marker, "LoadOnce isolated prototype\n");
            Files.setPosixFilePermissions(fixture.marker, PosixFilePermissions.fromString("rw-r--r--"));
            fixture.reject("private");
            Files.setPosixFilePermissions(fixture.marker, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(fixture.game, PosixFilePermissions.fromString("rwxr-xr-x"));
            fixture.reject("private");
            Files.setPosixFilePermissions(fixture.game, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(fixture.control, PosixFilePermissions.fromString("rw-r--r--"));
            fixture.reject("private");
            Files.setPosixFilePermissions(fixture.control, PosixFilePermissions.fromString("rw-------"));
            Path link = fixture.base.resolve("request-link.properties");
            Files.createSymbolicLink(link, fixture.control);
            fixture.reject(List.of("--cold", "--control-file", link.toString()), "link");
            Path missing = fixture.base.resolve("missing-request.properties");
            fixture.reject(List.of("--cold", "--control-file", missing.toString()), "Launch request");
            fixture.reject(List.of("--cold", "--control-file", fixture.game.toString()), "Launch request");
            Files.writeString(fixture.control, "x".repeat(1024 * 1024 + 1));
            fixture.reject("Launch request");
        }
    }

    private static void malformedApplicationArgumentsRejectBeforeApplication() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String count : List.of("not-an-integer", "-1", "4097", "2147483648")) {
                Properties request = fixture.request(List.of());
                request.setProperty("args.count", count);
                fixture.write(request);
                fixture.reject("argument count");
            }
            Properties request = fixture.request(List.of());
            request.remove("args.count");
            fixture.write(request);
            fixture.reject("args.count");
            request = fixture.request(List.of("first", "second"));
            request.remove("args.1");
            fixture.write(request);
            fixture.reject("argument 1");
            request = fixture.request(List.of("before\0after"));
            fixture.write(request);
            fixture.reject("argument 0");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path base = LauncherFiles.tempDirectory(null, "loadonce-prototype-contract-");
        private final Path game = LauncherFiles.privateDirectory(base.resolve("game"));
        private final Path marker = game.resolve(".loadonce-prototype");
        private final Path control = LauncherFiles.tempFile(base, "request-", ".properties");
        private final Path initializationFile = base.resolve("initializations.txt");
        private final Path invocationFile = base.resolve("invocations.txt");
        private final Path argumentFile = base.resolve("application.bin");

        Fixture() throws IOException {
            Files.createFile(marker, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(marker, "LoadOnce isolated prototype\n");
        }

        Properties request(List<String> arguments) throws IOException {
            Properties request = new Properties();
            request.setProperty("mode", "cold");
            request.setProperty("main", "net.fabricmc.devlaunchinjector.Main");
            request.setProperty("gameDirectory", game.toString());
            request.setProperty("verify", "false");
            request.setProperty("args.count", Integer.toString(arguments.size()));
            for (int index = 0; index < arguments.size(); index++) request.setProperty("args." + index, arguments.get(index));
            write(request);
            return request;
        }

        void write(Properties request) throws IOException {
            try (var output = Files.newOutputStream(control)) { request.store(output, "prototype test request"); }
        }

        Result launch(boolean failApplication, String... arguments) throws Exception {
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", absoluteClasspath(), MinecraftPrototypeBootstrap.class.getName()));
            command.addAll(List.of(arguments));
            Path log = LauncherFiles.tempFile(base, "child-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command).directory(base.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            for (String injection : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS")) {
                builder.environment().remove(injection);
            }
            builder.environment().put("LOADONCE_TEST_INITIALIZATIONS", initializationFile.toString());
            builder.environment().put("LOADONCE_TEST_INVOCATIONS", invocationFile.toString());
            builder.environment().put("LOADONCE_TEST_ARGUMENTS", argumentFile.toString());
            builder.environment().put("LOADONCE_TEST_FAILURE", Boolean.toString(failApplication));
            Process child = builder.start();
            try {
                check(child.waitFor(15, TimeUnit.SECONDS), "bootstrap child timed out");
                return new Result(child.exitValue(), Files.readString(log));
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }

        void reject(String responsibleInput) throws Exception {
            reject(List.of("--cold", "--control-file", control.toString()), responsibleInput);
        }

        void reject(List<String> arguments, String responsibleInput) throws Exception {
            Result result = launch(false, arguments.toArray(String[]::new));
            check(result.exitCode() != 0, "invalid input unexpectedly succeeded: " + responsibleInput);
            check(result.output().contains(responsibleInput), "rejection did not identify " + responsibleInput + ": " + result.output());
            equal(0L, initializations());
            equal(0L, invocations());
            check(!Files.exists(argumentFile), "rejected request reached application entry point");
        }

        long initializations() throws IOException { return lines(initializationFile); }
        long invocations() throws IOException { return lines(invocationFile); }

        Application application() throws IOException {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(argumentFile))) {
                long pid = input.readLong();
                String initializationMode = input.readUTF();
                String mode = input.readUTF();
                String witness = input.readUTF();
                String verify = input.readUTF();
                String world = input.readUTF();
                int count = input.readInt();
                List<String> arguments = new ArrayList<>();
                for (int index = 0; index < count; index++) arguments.add(input.readUTF());
                equal(-1, input.read());
                return new Application(pid, initializationMode, mode, witness, verify, world, List.copyOf(arguments));
            }
        }

        @Override public void close() throws IOException { LauncherFiles.deleteTree(base); }
    }

    private static long lines(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        try (var lines = Files.lines(path)) { return lines.count(); }
    }

    private static String absoluteClasspath() {
        return String.join(java.io.File.pathSeparator, java.util.Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)).map(value -> Path.of(value.isEmpty() ? "." : value)
                .toAbsolutePath().normalize().toString()).toList());
    }

    private static void check(boolean success, String message) {
        checks++;
        if (!success) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        check(java.util.Objects.equals(expected, actual), "expected " + expected + ", got " + actual);
    }

    private record Result(int exitCode, String output) {}
    private record Application(long pid, String initializationMode, String mode, String witness, String verify,
                               String world, List<String> arguments) {}
}
