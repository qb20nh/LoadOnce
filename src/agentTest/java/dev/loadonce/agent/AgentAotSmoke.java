package dev.loadonce.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Uses the packaged premain agent and real AOT flags without a launcher wrapper. */
public final class AgentAotSmoke {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final long MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final String EXPECTED = "APP:a b|literal$HOME|quote\"value|literal*|literal;$()|--flag=value|";

    private AgentAotSmoke() { }

    public static void main(String[] arguments) throws Exception {
        Path agent = null;
        String selectedHome = System.getenv("JAVA_HOME");
        if (selectedHome == null || selectedHome.isBlank()) selectedHome = System.getProperty("java.home");
        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            if ((!option.equals("--agent-jar") && !option.equals("--java-home")) || index + 1 == arguments.length) {
                throw new IllegalArgumentException("usage: AgentAotSmoke --agent-jar path [--java-home path]");
            }
            String value = arguments[++index];
            if (option.equals("--agent-jar")) agent = Path.of(value).toRealPath();
            else selectedHome = value;
        }
        if (agent == null || !Files.isRegularFile(agent)) throw new IOException("an agent JAR is required");
        Path javaHome = Path.of(selectedHome).toAbsolutePath();
        String suffix = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "";
        Path java = javaHome.resolve("bin/java" + suffix);
        Path javac = javaHome.resolve("bin/javac" + suffix);
        if (!Files.isRegularFile(java) || !Files.isRegularFile(javac)) throw new IOException("a full JDK home is required");

        FileAttribute<?>[] permissions = Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")))
                .supportsFileAttributeView("posix")
                ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")) }
                : new FileAttribute<?>[0];
        Path root = Files.createTempDirectory("loadonce-agent-aot-", permissions);
        try {
            Map<String, String> environment = cleanEnvironment();
            Result probe = execute(List.of(java.toString(), "-XX:AOTMode=off", "-version"), root, environment, "probe");
            require(probe.exitCode() == 0, "selected JDK does not support AOT flags", probe);
            Path source = root.resolve("Smoke.java");
            Files.writeString(source, """
                    import java.nio.file.*;
                    public final class Smoke {
                        public static void main(String[] args) throws Exception {
                            Class<?> agent = Class.forName("dev.loadonce.agent.LoadOnceAgent", true,
                                    ClassLoader.getSystemClassLoader());
                            String status = (String) agent.getMethod("startupStatus").invoke(null);
                            Path actualJar = Path.of(agent.getProtectionDomain().getCodeSource()
                                    .getLocation().toURI()).toRealPath();
                            if (!actualJar.equals(Path.of(args[2]).toRealPath())) {
                                throw new IllegalStateException("agent was not loaded from the packaged JAR");
                            }
                            System.out.println("AGENT_WITNESS:" + status);
                            System.out.println("AGENT_SOURCE:packaged-jar");
                            Path counter = Path.of(args[0]);
                            int count = Integer.parseInt(Files.readString(counter));
                            Files.writeString(counter, Integer.toString(count + 1));
                            System.out.println("APP:" + String.join("|", java.util.Arrays.copyOfRange(args, 3, args.length)));
                            System.out.println("APP_PID:" + ProcessHandle.current().pid());
                            System.exit(Integer.parseInt(Files.readString(Path.of(args[1]))));
                        }
                    }
                    """, StandardCharsets.UTF_8);
            Result compiled = execute(List.of(javac.toString(), "--release", "17", source.toString()),
                    root, environment, "compile");
            require(compiled.exitCode() == 0, "fixture compilation failed", compiled);
            Path application = root.resolve("smoke.jar");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(application))) {
                output.putNextEntry(new JarEntry("Smoke.class"));
                output.write(Files.readAllBytes(root.resolve("Smoke.class")));
                output.closeEntry();
            }
            Path counter = root.resolve("counter");
            Path control = root.resolve("exit-code");
            Path archive = root.resolve("smoke.aot");
            Files.writeString(counter, "0");
            Files.writeString(control, "0");
            List<String> applicationArguments = List.of("-cp", application.toString(), "Smoke", counter.toString(),
                    control.toString(), agent.toString(), "a b", "literal$HOME", "quote\"value", "literal*", "literal;$()", "--flag=value", "");
            List<String> common = List.of(java.toString(), "-javaagent:" + agent, "-Xlog:aot");

            Result cold = execute(command(common, List.of(), applicationArguments), root, environment, "cold");
            checkApplication(cold, counter, 1, 0);
            Result training = execute(command(common, List.of("-XX:AOTCacheOutput=" + archive), applicationArguments),
                    root, environment, "train");
            checkApplication(training, counter, 2, 0);
            require(Files.isRegularFile(archive) && Files.size(archive) > 0, "training did not publish an AOT cache", training);

            // All arguments, the agent and the environment stay identical. Only
            // fixture data changes, so failures cannot pass through flag mismatch.
            List<String> warmCommand = command(common,
                    List.of("-XX:AOTMode=auto", "-XX:AOTCache=" + archive), applicationArguments);
            Result warm = execute(warmCommand, root, environment, "warm");
            checkApplication(warm, counter, 3, 0);
            checkMapped(warm);
            Files.writeString(control, "7");
            Result failed = execute(warmCommand, root, environment, "failed");
            checkApplication(failed, counter, 4, 7);
            checkMapped(failed);

            Files.writeString(archive, "not-a-hotspot-archive".repeat(50), StandardCharsets.UTF_8);
            Result rejected = execute(warmCommand, root, environment, "corrupt");
            checkApplication(rejected, counter, 5, 7);
            require(rejected.output().toLowerCase(Locale.ROOT).contains("bad magic number"),
                    "HotSpot did not reject the corrupt archive", rejected);
            require(!rejected.output().contains("Using AOT-linked classes: true"),
                    "corrupt archive unexpectedly supplied linked classes", rejected);
            System.out.println("Direct agent AOT smoke passed: quiet packaged premain, real linked-cache mapping, exact arguments, "
                    + "nonzero exit without replay, and corrupt-cache fallback in the same JVM.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static List<String> command(List<String> common, List<String> flags, List<String> arguments) {
        List<String> result = new ArrayList<>(common);
        result.addAll(flags);
        result.addAll(arguments);
        return result;
    }

    private static void checkApplication(Result result, Path counter, int expectedCount, int expectedExit) throws IOException {
        require(result.exitCode() == expectedExit, "application exit changed", result);
        require(Integer.parseInt(Files.readString(counter)) == expectedCount, "main was lost or replayed", result);
        require(occurrences(result.standardOutput(), EXPECTED) == 1, "application argument boundaries changed", result);
        require(occurrences(result.standardOutput(), "APP_PID:" + result.pid() + System.lineSeparator()) == 1,
                "application did not run in the directly launched JVM", result);
        require(occurrences(result.standardOutput(), "AGENT_WITNESS:LOADONCE_AGENT initialized=true") == 1,
                "the packaged agent did not initialize before main", result);
        require(occurrences(result.standardOutput(), "AGENT_SOURCE:packaged-jar") == 1,
                "the agent endpoint did not come from the packaged JAR", result);
        require(!result.standardError().contains("LOADONCE_AGENT"),
                "the agent emitted a default status banner on stderr", result);
    }

    private static void checkMapped(Result result) {
        require(result.output().contains("Opened AOT cache") && result.output().contains("Using AOT-linked classes: true"),
                "warm launch did not map linked AOT classes", result);
    }

    private static Result execute(List<String> command, Path root, Map<String, String> environment, String label)
            throws IOException, InterruptedException {
        Path stdout = root.resolve(label + ".stdout.log");
        Path stderr = root.resolve(label + ".stderr.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process child = builder.start();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline || Files.size(stdout) + Files.size(stderr) > MAX_OUTPUT_BYTES) {
                    throw new IOException("fixture process exceeded its time or output limit");
                }
            }
            if (Files.size(stdout) + Files.size(stderr) > MAX_OUTPUT_BYTES) {
                throw new IOException("fixture process exceeded its output limit");
            }
            return new Result(child.pid(), child.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderr, StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                try {
                    child.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static Map<String, String> cleanEnvironment() {
        Map<String, String> result = new HashMap<>();
        for (String name : List.of("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TMP", "TEMP", "SystemRoot", "WINDIR")) {
            System.getenv().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name)).findFirst()
                    .ifPresent(entry -> result.put(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static int occurrences(String output, String expected) {
        int count = 0;
        for (int index = output.indexOf(expected); index >= 0; index = output.indexOf(expected, index + expected.length())) count++;
        return count;
    }

    private static void require(boolean condition, String diagnostic, Result result) {
        if (!condition) throw new AssertionError(diagnostic + ":\n" + result.output());
    }

    private record Result(long pid, int exitCode, String standardOutput, String standardError) {
        private String output() { return standardOutput + standardError; }
    }
}
