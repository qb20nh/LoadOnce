package com.qb20nh.loadonce.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Opt-in contracts against an installed Prism bootstrap, without launcher accounts or Minecraft. */
public final class PrismLauncherSmoke {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final List<String> ARGUMENTS = List.of("a b", "literal$HOME", "quote\"value", "literal;$()", "한글");
    private static final String PROPERTY = "quote\"property\\end";

    private PrismLauncherSmoke() { }

    public static void main(String[] arguments) throws Exception {
        Map<String, Path> options = new HashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (index + 1 == arguments.length || !List.of("--agent-jar", "--supervisor-jar",
                    "--redirectors-dir", "--prism-dir").contains(arguments[index])
                    || options.putIfAbsent(arguments[index], Path.of(arguments[index + 1])) != null) {
                throw new IllegalArgumentException("usage: PrismLauncherSmoke --agent-jar path --supervisor-jar path "
                        + "--redirectors-dir path --prism-dir /usr/share/PrismLauncher");
            }
        }
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            throw new IOException("prismLauncherSmoke currently requires Linux native process ownership");
        }
        Path prism = required(options, "--prism-dir");
        Path checker = prism.resolve("JavaCheck.jar");
        Path launcher = prism.resolve("NewLaunch.jar");
        for (Path jar : List.of(checker, launcher)) {
            if (!Files.isRegularFile(jar) || !Files.isReadable(jar)) {
                throw new IOException("Prism bootstrap JAR is missing or unreadable: " + jar
                        + "; set -PloadoncePrismDir to the installed directory containing JavaCheck.jar and NewLaunch.jar");
            }
        }
        Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        Path backend = javaHome.resolve("bin/java");
        Path root = Files.createTempDirectory("loadonce-prism-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        boolean passed = false;
        try {
            Map<String, String> environment = environment(root, javaHome);
            Path cache = root.resolve("must-stay-absent");
            Path runtime = RuntimeRedirectInstaller.install(javaHome, required(options, "--redirectors-dir"),
                    root.resolve("runtime"), required(options, "--agent-jar"), required(options, "--supervisor-jar"),
                    new RuntimeRedirectInstaller.Options(null, "train", cache, List.of(), false));
            Path wrapper = runtime.resolve("bin/java");
            Result directCheck = checker(backend, checker, root, environment, "checker-direct");
            Result wrappedCheck = checker(wrapper, checker, root, environment, "checker-wrapper");
            require(directCheck.exitCode() == 0 && !directCheck.stdout().isBlank(), "installed JavaCheck failed", directCheck);
            equivalent(directCheck, wrappedCheck, "installed JavaCheck output or exit changed");

            String classpath = launcher + File.pathSeparator + Path.of(PrismLauncherSmoke.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            Path record = root.resolve("fixture-pids");
            String script = script();
            Result directLaunch = execute(command(backend, classpath, record, false), script, root, environment, "launch-direct");
            Result wrappedLaunch = execute(command(wrapper, classpath, record, false), script, root, environment, "launch-wrapper");
            require(directLaunch.exitCode() == 0 && directLaunch.stdout().contains("PRISM_FIXTURE args=" + encodedArguments()),
                    "installed NewLaunch did not run the requested fixture arguments", directLaunch);
            equivalent(directLaunch, wrappedLaunch, "installed NewLaunch stdin, cwd, environment or JVM argument changed");
            require(wrappedLaunch.stdout().contains("PRISM_FIXTURE cwd=" + root)
                            && wrappedLaunch.stdout().contains("PRISM_FIXTURE property=" + PROPERTY)
                            && wrappedLaunch.stdout().contains("PRISM_FIXTURE marker=private-prism-fixture")
                            && wrappedLaunch.stderr().contains("PRISM_FIXTURE stderr"),
                    "installed NewLaunch fixture forwarding changed", wrappedLaunch);

            Result directAbort = execute(command(backend, classpath, record, false), "abort\n", root, environment, "abort-direct");
            Result wrappedAbort = execute(command(wrapper, classpath, record, false), "abort\n", root, environment, "abort-wrapper");
            require(directAbort.exitCode() != 0 && directAbort.output().contains("Launch aborted by the launcher"),
                    "installed NewLaunch abort protocol failed", directAbort);
            equivalent(directAbort, wrappedAbort, "installed NewLaunch abort changed");
            cancel(command(wrapper, classpath, record, true), script, record, root, environment);
            require(!Files.exists(cache), "Prism probe or opaque stdin startup touched the AOT cache under train policy", wrappedLaunch);
            passed = true;
            System.out.println("PRISM_SMOKE checker=installed stdout=unchanged stdin=installed-bootstrap args=unchanged "
                    + "abort=unchanged cancellation=no-orphans cache=untouched; no launcher UI, account or Minecraft restoration");
        } catch (Exception | AssertionError failure) {
            throw new IOException("Prism bootstrap smoke failed; private evidence: " + root, failure);
        } finally {
            if (passed) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private static Path required(Map<String, Path> options, String name) throws IOException {
        Path path = options.get(name);
        if (path == null) throw new IOException("required option missing: " + name);
        if (!Files.exists(path)) throw new IOException("required smoke input is missing: " + path + " (" + name + ")");
        return path.toRealPath();
    }

    private static Map<String, String> environment(Path root, Path javaHome) throws IOException {
        Path home = Files.createDirectory(root.resolve("home"));
        Path temporary = Files.createDirectory(root.resolve("tmp"));
        return Map.of("HOME", home.toString(), "JAVA_HOME", javaHome.toString(), "PATH", "/usr/bin:/bin",
                "LANG", "C.UTF-8", "TMPDIR", temporary.toString(), "LOADONCE_PRISM_MARKER", "private-prism-fixture");
    }

    private static Result checker(Path java, Path jar, Path root, Map<String, String> environment, String label) throws Exception {
        return execute(List.of(java.toString(), "-jar", jar.toString()), "", root, environment, label);
    }

    private static List<String> command(Path java, String classpath, Path record, boolean hold) {
        return List.of(java.toString(), "-Dloadonce.prism.property=" + PROPERTY,
                "-Dloadonce.prism.record=" + record, "-Dloadonce.prism.hold=" + hold,
                "-cp", classpath, "org.prismlauncher.EntryPoint");
    }

    private static String script() {
        StringBuilder result = new StringBuilder("launcher standard\nmainClass " + Fixture.class.getName() + "\nwindowParams 640x480\n");
        for (String argument : ARGUMENTS) result.append("param ").append(argument).append('\n');
        return result.append("launch\n").toString();
    }

    private static String encodedArguments() {
        List<String> expected = new ArrayList<>(ARGUMENTS);
        expected.addAll(List.of("--width", "640", "--height", "480"));
        return expected.stream().map(value -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static Process start(List<String> command, String input, Path root, Map<String, String> environment,
                                 String label) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
                .redirectOutput(root.resolve(label + ".stdout.log").toFile())
                .redirectError(root.resolve(label + ".stderr.log").toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process child = builder.start();
        try (var destination = child.getOutputStream()) {
            destination.write(input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            stop(child);
            throw failure;
        }
        return child;
    }

    private static Result execute(List<String> command, String input, Path root, Map<String, String> environment,
                                  String label) throws Exception {
        Process child = start(command, input, root, environment, label);
        Path stdout = root.resolve(label + ".stdout.log");
        Path stderr = root.resolve(label + ".stderr.log");
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            while (!child.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline || Files.size(stdout) + Files.size(stderr) > MAX_OUTPUT_BYTES) {
                    throw new IOException("Prism stdin process exceeded its time/output limit; logs: " + stdout + ", " + stderr);
                }
            }
            return result(child.exitValue(), stdout, stderr);
        } finally {
            stop(child);
        }
    }

    private static Result result(int exit, Path stdout, Path stderr) throws IOException {
        if (Files.size(stdout) + Files.size(stderr) > MAX_OUTPUT_BYTES) throw new IOException("Prism output exceeded limit: " + stdout);
        return new Result(exit, Files.readString(stdout, StandardCharsets.UTF_8), Files.readString(stderr, StandardCharsets.UTF_8));
    }

    private static void cancel(List<String> command, String input, Path record, Path root,
                               Map<String, String> environment) throws Exception {
        Files.deleteIfExists(record);
        Process child = start(command, input, root, environment, "cancel");
        List<ProcessHandle> fixtures = new ArrayList<>();
        try {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!Files.exists(record)) {
                if (!child.isAlive() || System.nanoTime() >= deadline) throw new IOException("Prism cancellation fixture did not become ready; logs: " + root.resolve("cancel.stderr.log"));
                Thread.sleep(25);
            }
            for (String pid : Files.readAllLines(record)) {
                fixtures.add(ProcessHandle.of(Long.parseLong(pid)).orElseThrow(() -> new IOException("Prism cancellation fixture exited before cancellation")));
            }
            if (fixtures.size() != 2) throw new IOException("Prism cancellation fixture PID record is incomplete: " + record);
            child.destroy();
            if (!child.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Prism wrapper did not exit on cancellation");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fixtures.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) Thread.sleep(25);
            if (fixtures.stream().anyMatch(ProcessHandle::isAlive)) throw new IOException("Prism cancellation left fixture/helper processes alive: " + record);
        } finally {
            stop(child);
            fixtures.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
    }

    private static void stop(Process child) {
        if (!child.isAlive()) return;
        child.descendants().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
        try { child.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void equivalent(Result direct, Result wrapped, String message) {
        require(direct.exitCode() == wrapped.exitCode() && direct.stdout().equals(wrapped.stdout())
                && direct.stderr().equals(wrapped.stderr()), message + "; direct:\n" + direct.output(), wrapped);
    }

    private static void require(boolean condition, String message, Result result) {
        if (!condition) throw new AssertionError(message + "; exit=" + result.exitCode() + "\n" + result.output());
    }

    private record Result(int exitCode, String stdout, String stderr) {
        String output() { return stdout + stderr; }
    }

    /** Selected by the real Prism stdin bootstrap instead of a Minecraft main class. */
    public static final class Fixture {
        private Fixture() { }

        public static void main(String[] arguments) throws Exception {
            if (arguments.length == 1 && arguments[0].equals("--helper")) {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                return;
            }
            System.out.println("PRISM_FIXTURE args=" + java.util.Arrays.stream(arguments)
                    .map(value -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                    .collect(java.util.stream.Collectors.joining(",")));
            System.out.println("PRISM_FIXTURE cwd=" + Path.of("").toRealPath());
            System.out.println("PRISM_FIXTURE property=" + System.getProperty("loadonce.prism.property"));
            System.out.println("PRISM_FIXTURE marker=" + System.getenv("LOADONCE_PRISM_MARKER"));
            System.err.println("PRISM_FIXTURE stderr");
            if (Boolean.getBoolean("loadonce.prism.hold")) {
                Process helper = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), "--helper")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Path record = Path.of(System.getProperty("loadonce.prism.record"));
                Path ready = record.resolveSibling(record.getFileName() + ".ready");
                Files.writeString(ready, ProcessHandle.current().pid() + "\n" + helper.pid() + "\n");
                Files.move(ready, record);
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            }
        }
    }
}
