package dev.loadonce.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Exercises actual AOT policy through the executable selected by an external launcher. */
public final class SupervisorAotSmoke {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final long MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final List<String> APP_ARGUMENTS = List.of(
            "a b", "literal$HOME", "quote\"value", "literal*", "literal;$()", "--flag=value", "");
    private static final String JVM_PROPERTY = "quote\"property\\end";
    private static final byte[] EMPTY_INPUT = new byte[0];

    private SupervisorAotSmoke() { }

    public static void main(String[] arguments) throws Exception {
        Map<String, Path> options = new HashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (index + 1 == arguments.length || !List.of("--agent-jar", "--supervisor-jar",
                    "--redirectors-dir", "--java-home").contains(arguments[index])
                    || options.putIfAbsent(arguments[index], Path.of(arguments[index + 1])) != null) {
                throw new IllegalArgumentException("usage: SupervisorAotSmoke --agent-jar path "
                        + "--supervisor-jar path --redirectors-dir path [--java-home path]");
            }
        }
        Path agent = required(options, "--agent-jar");
        Path supervisor = required(options, "--supervisor-jar");
        Path redirectors = required(options, "--redirectors-dir");
        String selectedHome = System.getenv("JAVA_HOME");
        if (selectedHome == null || selectedHome.isBlank()) selectedHome = System.getProperty("java.home");
        Path javaHome = options.getOrDefault("--java-home", Path.of(selectedHome)).toRealPath();
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String suffix = windows ? ".exe" : "";
        Path java = javaHome.resolve("bin/java" + suffix);
        Path javac = javaHome.resolve("bin/javac" + suffix);
        if (!Files.isRegularFile(java) || !Files.isRegularFile(javac)) throw new IOException("a full JDK home is required");

        FileAttribute<?>[] permissions = Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")))
                .supportsFileAttributeView("posix")
                ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")) }
                : new FileAttribute<?>[0];
        Path root = Files.createTempDirectory("loadonce-supervisor-aot-", permissions);
        try {
            Map<String, String> environment = cleanEnvironment();
            Result probe = execute(List.of(java.toString(), "-XX:AOTMode=off", "-version"),
                    root, environment, "probe", EMPTY_INPUT);
            require(probe.exitCode() == 0, "selected JDK does not support AOT flags", probe);
            Path application = compileFixture(javac, root, environment);
            String applicationHash = sha256(Files.readAllBytes(application));
            Path cache = root.resolve("cache");
            Path runtime = RuntimeRedirectInstaller.install(javaHome, redirectors, root.resolve("runtime"),
                    agent, supervisor, new RuntimeRedirectInstaller.Options(null, "train", cache, List.of(), false));
            Path wrapper = runtime.resolve("bin/java" + suffix);
            Path policy = runtime.resolve("loadonce-policy.conf");
            Path installedAgent = runtime.resolve("loadonce-agent.jar");
            Path counter = root.resolve("counter");
            Path control = root.resolve("exit-code");
            Files.writeString(counter, "0");
            Files.writeString(control, "0");
            List<String> command = applicationCommand(wrapper, application, counter, control, installedAgent,
                    "SupervisorFixture", List.of(), APP_ARGUMENTS);

            int expectedCount = 0;
            Result training = execute(command, root, environment, "train", EMPTY_INPUT);
            checkApplication(training, counter, ++expectedCount, 0, APP_ARGUMENTS, EMPTY_INPUT);
            setPolicy(policy, "auto", cache);
            Result warm = execute(command, root, environment, "warm", EMPTY_INPUT);
            checkApplication(warm, counter, ++expectedCount, 0, APP_ARGUMENTS, EMPTY_INPUT);
            Files.writeString(control, "7");
            Result failed = execute(command, root, environment, "failed", EMPTY_INPUT);
            checkApplication(failed, counter, ++expectedCount, 7, APP_ARGUMENTS, EMPTY_INPUT);

            if (windows) {
                require(!mapped(training) && !mapped(warm) && !mapped(failed),
                        "Windows startup claimed a cache without supported private ownership", failed);
                require(findArchives(cache).isEmpty(), "Windows training published an unsupported cache", training);
            } else {
                List<Path> archives = findArchives(cache);
                require(archives.size() == 1 && Files.size(archives.get(0)) > 0,
                        "native wrapper training did not publish one completed AOT cache", training);
                checkMapped(warm);
                checkMapped(failed);
                Path archive = archives.get(0);
                Path manifest = archive.getParent().getParent().resolve("manifest.properties");
                String archiveHash = sha256(Files.readAllBytes(archive));
                String previousManifest = Files.readString(manifest);

                setPolicy(policy, "train", cache);
                Result failedTraining = execute(command, root, environment, "failed-training", EMPTY_INPUT);
                checkApplication(failedTraining, counter, ++expectedCount, 7, APP_ARGUMENTS, EMPTY_INPUT);
                require(previousManifest.equals(Files.readString(manifest))
                                && archiveHash.equals(sha256(Files.readAllBytes(archive)))
                                && findArchives(cache).equals(List.of(archive)),
                        "failed training changed the previously completed cache", failedTraining);
                setPolicy(policy, "auto", cache);

                Files.writeString(archive, "not-a-hotspot-archive".repeat(50), StandardCharsets.UTF_8);
                Result integrity = execute(command, root, environment, "integrity", EMPTY_INPUT);
                checkApplication(integrity, counter, ++expectedCount, 7, APP_ARGUMENTS, EMPTY_INPUT);
                require(!mapped(integrity) && !integrity.output().toLowerCase(Locale.ROOT).contains("bad magic number"),
                        "an archive with invalid outer integrity reached HotSpot", integrity);

                // Repair only the outer digest and length. The real JVM must reject
                // the inner archive format while running this one application once.
                replaceManifestField(manifest, "sha256", sha256(Files.readAllBytes(archive)));
                replaceManifestField(manifest, "bytes", Long.toString(Files.size(archive)));
                Result rejected = execute(command, root, environment, "jvm-rejected", EMPTY_INPUT);
                checkApplication(rejected, counter, ++expectedCount, 7, APP_ARGUMENTS, EMPTY_INPUT);
                require(rejected.output().toLowerCase(Locale.ROOT).contains("bad magic number") && !mapped(rejected),
                        "HotSpot did not reject the corrupt inner archive during a single launch", rejected);

                Path changingInput = root.resolve("declared-input");
                Path publicationCache = root.resolve("post-main-cache");
                Files.writeString(changingInput, "before");
                Files.writeString(control, "0");
                setPolicy(policy, "train", publicationCache);
                replaceManifestField(policy, "input.count", "1");
                Files.writeString(policy, Files.readString(policy) + "input.0=" + changingInput + "\n");
                Map<String, String> mutatingEnvironment = new HashMap<>(environment);
                mutatingEnvironment.put("LOADONCE_TEST_MUTATE_INPUT", changingInput.toString());
                Result publicationFailure = execute(command, root, mutatingEnvironment, "post-main-failed", EMPTY_INPUT);
                checkApplication(publicationFailure, counter, ++expectedCount, 1, APP_ARGUMENTS, EMPTY_INPUT, false);
                require(Files.readString(changingInput).equals("after") && findArchives(publicationCache).isEmpty()
                                && occurrences(publicationFailure.standardError(), "loadonce: supervisor startup failed") == 1,
                        "post-main publication refusal replayed main or promoted a cache", publicationFailure);
                Files.writeString(policy, Files.readString(policy).replace("input.0=" + changingInput + "\n", ""));
                replaceManifestField(policy, "input.count", "0");
                Files.writeString(control, "7");
            }

            Path untouchedCache = root.resolve("must-stay-absent");
            setPolicy(policy, "train", untouchedCache);
            List<String> credentialArguments = new ArrayList<>(APP_ARGUMENTS);
            credentialArguments.addAll(List.of("--accessToken", "synthetic-fixture-value"));
            Map<String, String> credentials = new HashMap<>(environment);
            credentials.put("GH_TOKEN", "synthetic-environment-value");
            Result credentialLaunch = execute(applicationCommand(wrapper, application, counter, control,
                    installedAgent, "SupervisorFixture", List.of(), credentialArguments),
                    root, credentials, "credentials", EMPTY_INPUT);
            checkApplication(credentialLaunch, counter, ++expectedCount, 7, credentialArguments, EMPTY_INPUT);
            require(!Files.exists(untouchedCache) && !mapped(credentialLaunch),
                    "credential-bearing startup touched the cache", credentialLaunch);
            require(!credentialLaunch.output().contains("synthetic-environment-value"),
                    "supervisor logged a credential environment value", credentialLaunch);

            byte[] input = new byte[131072];
            for (int index = 0; index < input.length; index++) input[index] = (byte) (index * 31 + 255);
            Result opaque = execute(applicationCommand(wrapper, application, counter, control, installedAgent,
                    "org.prismlauncher.launcher.SupervisorOpaqueMain", List.of(), APP_ARGUMENTS),
                    root, environment, "prism-opaque", input);
            checkApplication(opaque, counter, ++expectedCount, 7, APP_ARGUMENTS, input);
            require(!Files.exists(untouchedCache) && !mapped(opaque),
                    "opaque Prism startup touched the cache or consumed stdin", opaque);

            Result refused = execute(applicationCommand(wrapper, application, counter, control, installedAgent,
                    "SupervisorFixture", List.of("-XX:AOTMode=off"), APP_ARGUMENTS),
                    root, environment, "prelaunch-refused", EMPTY_INPUT);
            checkApplication(refused, counter, ++expectedCount, 7, APP_ARGUMENTS, EMPTY_INPUT);
            require(!Files.exists(untouchedCache) && !mapped(refused),
                    "a prelaunch training refusal touched the cache", refused);
            require(applicationHash.equals(sha256(Files.readAllBytes(application))),
                    "the fixture JAR changed between training and reuse", refused);
            System.out.println(windows
                    ? "Native supervisor AOT smoke passed: Windows private-cache refusal runs main once, exact arguments, "
                        + "exit 7, credential and Prism cache bypass, and untouched binary stdin."
                    : "Native supervisor AOT smoke passed: real linked-cache reuse, exact arguments, exit 7, failed "
                        + "training and post-main publication refusal without replay, integrity and JVM archive rejection, "
                        + "credential and Prism bypass, and untouched binary stdin.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Path required(Map<String, Path> options, String name) throws IOException {
        Path path = options.get(name);
        if (path == null) throw new IOException("required option missing: " + name);
        return path.toRealPath();
    }

    private static Path compileFixture(Path javac, Path root, Map<String, String> environment) throws Exception {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path ordinary = sources.resolve("SupervisorFixture.java");
        Path opaque = sources.resolve("SupervisorOpaqueMain.java");
        Files.writeString(ordinary, fixtureSource("", "SupervisorFixture"));
        Files.writeString(opaque, fixtureSource("org.prismlauncher.launcher", "SupervisorOpaqueMain"));
        Result compiled = execute(List.of(javac.toString(), "--release", "17", "-d", classes.toString(),
                ordinary.toString(), opaque.toString()), root, environment, "compile", EMPTY_INPUT);
        require(compiled.exitCode() == 0, "fixture compilation failed", compiled);
        Path application = root.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(application));
             var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return application;
    }

    private static String fixtureSource(String packageName, String className) {
        return (packageName.isEmpty() ? "" : "package " + packageName + ";\n") + "public final class " + className + " {\n" + """
                    public static void main(String[] args) throws Exception {
                        Class<?> agent = Class.forName("dev.loadonce.agent.LoadOnceAgent", true,
                                ClassLoader.getSystemClassLoader());
                        String status = (String) agent.getMethod("startupStatus").invoke(null);
                        java.nio.file.Path actualJar = java.nio.file.Path.of(agent.getProtectionDomain()
                                .getCodeSource().getLocation().toURI()).toRealPath();
                        if (!actualJar.equals(java.nio.file.Path.of(args[2]).toRealPath())) {
                            throw new IllegalStateException("agent was not loaded from the installed packaged JAR");
                        }
                        java.nio.file.Path counter = java.nio.file.Path.of(args[0]);
                        int count = Integer.parseInt(java.nio.file.Files.readString(counter));
                        java.nio.file.Files.writeString(counter, Integer.toString(count + 1));
                        String changingInput = System.getenv("LOADONCE_TEST_MUTATE_INPUT");
                        if (changingInput != null) java.nio.file.Files.writeString(java.nio.file.Path.of(changingInput), "after");
                        byte[] input = System.in.readAllBytes();
                        System.out.println("AGENT_WITNESS:" + status);
                        System.out.println("AGENT_SOURCE:installed-jar");
                        System.out.println("APP:" + String.join("|", java.util.Arrays.copyOfRange(args, 3, args.length)));
                        System.out.println("JVM_PROPERTY:" + System.getProperty("loadonce.supervisor.smoke"));
                        System.out.println("STDIN_SHA256:" + java.util.HexFormat.of().formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(input)));
                        System.out.println("APP_PID:" + ProcessHandle.current().pid());
                        System.err.println("APP_STDERR_WITNESS");
                        System.exit(Integer.parseInt(java.nio.file.Files.readString(java.nio.file.Path.of(args[1]))));
                    }
                }
                """;
    }

    private static List<String> applicationCommand(Path wrapper, Path application, Path counter, Path control,
                                                    Path agent, String mainClass, List<String> flags,
                                                    List<String> arguments) {
        List<String> result = new ArrayList<>(List.of(wrapper.toString()));
        result.addAll(flags);
        result.addAll(List.of("-Xlog:aot", "-Dloadonce.supervisor.smoke=" + JVM_PROPERTY, "-cp",
                application.toString(), mainClass, counter.toString(), control.toString(), agent.toString()));
        result.addAll(arguments);
        return result;
    }

    private static void checkApplication(Result result, Path counter, int expectedCount, int expectedExit,
                                          List<String> arguments, byte[] input) throws Exception {
        checkApplication(result, counter, expectedCount, expectedExit, arguments, input, true);
    }

    private static void checkApplication(Result result, Path counter, int expectedCount, int expectedExit,
                                          List<String> arguments, byte[] input, boolean quiet) throws Exception {
        require(result.exitCode() == expectedExit, "application exit changed", result);
        require(Integer.parseInt(Files.readString(counter)) == expectedCount, "main was lost or replayed", result);
        require(occurrences(result.standardOutput(), "APP:" + String.join("|", arguments) + System.lineSeparator()) == 1,
                "application argument boundaries changed", result);
        require(occurrences(result.standardOutput(), "AGENT_WITNESS:LOADONCE_AGENT initialized=true") == 1
                        && occurrences(result.standardOutput(), "AGENT_SOURCE:installed-jar") == 1,
                "the backend packaged agent did not initialize once before main", result);
        require(occurrences(result.standardOutput(), "JVM_PROPERTY:" + JVM_PROPERTY + System.lineSeparator()) == 1,
                "the original JVM property changed", result);
        require(occurrences(result.standardOutput(), "STDIN_SHA256:" + sha256(input) + System.lineSeparator()) == 1,
                "the supervisor consumed or changed application stdin", result);
        require(occurrences(result.standardOutput(), "APP_PID:") == 1
                        && !result.standardOutput().contains("APP_PID:" + result.pid() + System.lineSeparator()),
                "application did not execute in the supervised backend JVM", result);
        require(occurrences(result.standardError(), "APP_STDERR_WITNESS") == 1,
                "application stderr was lost or replayed", result);
        require(!result.standardError().contains("LOADONCE_AGENT") && (!quiet || !result.output().contains("loadonce:")),
                "default supervisor emitted a status banner", result);
    }

    private static boolean mapped(Result result) {
        return result.output().contains("Opened AOT cache") && result.output().contains("Using AOT-linked classes: true");
    }

    private static void checkMapped(Result result) {
        require(mapped(result), "wrapper warm launch did not map linked AOT classes", result);
    }

    private static List<Path> findArchives(Path cache) throws IOException {
        if (!Files.exists(cache)) return List.of();
        try (var files = Files.walk(cache)) {
            return files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals("cache.aot"))
                    .sorted().toList();
        }
    }

    private static void setPolicy(Path policy, String mode, Path cache) throws IOException {
        replaceManifestField(policy, "aot", mode);
        replaceManifestField(policy, "cache", cache.toAbsolutePath().toString());
    }

    private static void replaceManifestField(Path file, String key, String value) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        int count = 0;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(key + "=")) {
                lines.set(index, key + "=" + value);
                count++;
            }
        }
        if (count != 1) throw new IOException("expected one installed field: " + key);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static Result execute(List<String> command, Path root, Map<String, String> environment, String label,
                                  byte[] input) throws IOException, InterruptedException {
        Path stdout = root.resolve(label + ".stdout.log");
        Path stderr = root.resolve(label + ".stderr.log");
        ProcessBuilder builder = new ProcessBuilder(WindowsCommandLine.forProcessBuilder(command)).directory(root.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process child = builder.start();
        AtomicReference<IOException> inputFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try (OutputStream destination = child.getOutputStream()) {
                destination.write(input);
            } catch (IOException failure) {
                inputFailure.set(failure);
            }
        }, "loadonce-supervisor-smoke-input");
        writer.setDaemon(true);
        writer.start();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline || Files.size(stdout) + Files.size(stderr) > MAX_OUTPUT_BYTES) {
                    throw new IOException("fixture process exceeded its time or output limit");
                }
            }
            writer.join(1000);
            if (writer.isAlive()) throw new IOException("application stdin did not complete");
            if (inputFailure.get() != null && input.length > 0) throw new IOException("application stdin write failed", inputFailure.get());
            if (Files.size(stdout) + Files.size(stderr) > MAX_OUTPUT_BYTES) {
                throw new IOException("fixture process exceeded its output limit");
            }
            return new Result(child.pid(), child.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderr, StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                try { child.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            try { child.getOutputStream().close(); } catch (IOException ignored) { }
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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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
