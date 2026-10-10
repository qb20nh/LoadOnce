package dev.loadonce.agent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Real native runtime redirect contracts, including the Windows GUI launcher. */
public final class RuntimeRedirectTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final String VM_VALUE = "option with spaces, quote\", slash\\, Unicode-한글-π";
    private static final String STDIN = "stdin with spaces and 한글-π\nsecond\tline\n";
    private static final List<String> ARGUMENTS = List.of("", "a b", "tab\tvalue", "\"quoted\"", "\\",
            "trailing\\", "two\\\\", "slash\\\"quote", "한글-π-🧪", "$HOME", "$(echo literal)",
            "`literal`", ";|&<>*?", "--flag=value");
    private static int checks;

    private RuntimeRedirectTest() { }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        try (Fixture fixture = new Fixture(options)) {
            for (String name : WINDOWS ? List.of("java.exe", "javaw.exe") : List.of("java")) {
                Path redirector = fixture.output.resolve("bin").resolve(name);
                check(Files.isRegularFile(redirector), "installed redirector is missing");
                check(Files.mismatch(options.redirectorsDirectory().resolve(name), redirector) == -1,
                        "installed redirector differs from the supplied native binary");
                fixture.exactInvocation(redirector, false, 0);
                fixture.exactInvocation(redirector, false, 7);
                fixture.exactInvocation(redirector, true, 0);
                // javaw information/usage paths can open native JDK dialogs on a headless runner.
                if (!name.equals("javaw.exe")) fixture.probes(redirector);
                fixture.configurationFailures(redirector);
                fixture.helperSurvivesNormalExit(redirector);
                fixture.cancellation(redirector);
            }
        }
        System.out.println("Native runtime redirect contracts passed: " + checks
                + " platform=" + (WINDOWS ? "Windows-java-and-javaw" : "Linux-exec"));
    }

    private static final class Fixture implements AutoCloseable {
        final Path root;
        final Path output;
        final Path javaHome;
        final Path backend;
        final Path agent;
        final Path configuration;
        final Path cwd;
        final Path checkerJar;
        final Map<String, String> environment;
        final Map<Long, ProcessHandle> observed = new LinkedHashMap<>();
        final List<Process> processes = new ArrayList<>();
        final Thread shutdown;
        boolean closed;

        Fixture(Options options) throws Exception {
            root = Files.createTempDirectory("loadonce redirect π ").toAbsolutePath();
            output = root.resolve("selected runtime 한글");
            javaHome = options.javaHome().toRealPath();
            backend = javaHome.resolve("bin").resolve(WINDOWS ? "java.exe" : "java").toRealPath();
            cwd = Files.createDirectory(root.resolve("working directory π"));
            checkerJar = root.resolve("checker fixture π.jar");
            shutdown = new Thread(this::stopAll, "loadonce-redirect-contract-cleanup");
            try {
                RuntimeRedirectInstaller.install(javaHome, options.redirectorsDirectory(), output, options.agentJar());
                agent = output.resolve("loadonce-agent.jar").toRealPath();
                check(Files.mismatch(options.agentJar().toRealPath(), agent) == -1,
                        "installed agent differs from the supplied JAR");
                configuration = output.resolve("bin/loadonce-runtime.conf");
                check(Files.isRegularFile(configuration), "installed redirector configuration is missing");
                environment = cleanEnvironment(root, javaHome);
                writeCheckerJar(checkerJar);
                Runtime.getRuntime().addShutdownHook(shutdown);
            } catch (Exception | Error failure) {
                deleteTree(root);
                throw failure;
            }
        }

        void exactInvocation(Path redirector, boolean jar, int exit) throws Exception {
            Invocation invocation = invocation(redirector, jar, exit, 0);
            Result result = run(invocation.command(), Duration.ofSeconds(30), STDIN.getBytes(StandardCharsets.UTF_8));
            equal(exit, result.exitCode(), "application exit status changed");
            WitnessRecord record = readRecord(invocation.record());
            remember(record.pid());
            equal(ARGUMENTS, record.arguments(), "application argv changed");
            equal(VM_VALUE, record.vmProperty(), "JVM option value changed");
            equal(javaHome, Path.of(record.javaHome()).toRealPath(), "redirector changed the selected JDK");
            equal(cwd.toRealPath(), Path.of(record.cwd()).toRealPath(), "redirector changed the working directory");
            equal(environment.get("LOADONCE_REDIRECT_TEST_MARKER"), record.marker(), "redirector changed the explicit environment");
            equal(STDIN, record.stdin(), "redirector changed application stdin");
            equal(List.of("-javaagent:" + agent), record.agents(), "agent must be injected exactly once");
            equal(agent, Path.of(record.agentSource()).toRealPath(), "agent was not loaded from the installed JAR");
            check(record.agentStatus().startsWith("LOADONCE_AGENT initialized=true"), "agent premain did not initialize");
            equal(0, occurrences(result.output(), "LOADONCE_AGENT initialized=true"), "agent changed ordinary application output");
            equal("REDIRECT_WITNESS_MAIN" + System.lineSeparator(), result.stdout(), "redirector changed application stdout");
            equal("REDIRECT_WITNESS_STDERR" + System.lineSeparator(), result.stderr(), "redirector changed application stderr");
            assertIdentity(result.pid(), record);
            equal(List.of(Long.toString(record.pid())), Files.readAllLines(invocation.starts()), "application main replayed");
            check(!alive(record.pid()), "application remained alive after its reported exit");
        }

        void probes(Path redirector) throws Exception {
            Path matchingBackend = WINDOWS
                    ? javaHome.resolve("bin").resolve(redirector.getFileName()).toRealPath() : backend;
            for (List<String> flags : List.of(List.of("-version"), List.of("-XshowSettings", "-version"), List.<String>of())) {
                List<String> direct = new ArrayList<>(List.of(matchingBackend.toString()));
                direct.addAll(flags);
                List<String> redirected = new ArrayList<>(List.of(redirector.toString()));
                redirected.addAll(flags);
                Result expected = run(direct, Duration.ofSeconds(20));
                Result actual = run(redirected, Duration.ofSeconds(20));
                equal(expected.exitCode(), actual.exitCode(), "JVM probe exit status changed");
                equal(expected.stdout(), actual.stdout(), "redirector changed JVM probe stdout");
                equal(expected.stderr(), actual.stderr(), "redirector changed JVM probe stderr");
                equal(occurrences(expected.output(), "LOADONCE_AGENT initialized=true"),
                        occurrences(actual.output(), "LOADONCE_AGENT initialized=true"), "JVM probe premain behavior changed");
                check(!actual.output().contains("REDIRECT_WITNESS_MAIN"), "a JVM information probe ran fixture main");
                if (flags.isEmpty()) {
                    check(actual.output().toLowerCase(Locale.ROOT).contains("usage"), "no-argument JVM usage was lost");
                } else {
                    check(actual.output().contains("version") && actual.output().contains("Runtime Environment"),
                            "JVM version information was lost");
                    if (flags.size() > 1) {
                        check(actual.output().contains("java.home = " + javaHome), "JVM settings report the wrong JDK");
                    }
                }
            }
        }

        void configurationFailures(Path redirector) throws Exception {
            byte[] original = Files.readAllBytes(configuration);
            try {
                Files.writeString(configuration, "invalid configuration\n", StandardCharsets.UTF_8);
                failsWithoutMain(redirector, "malformed configuration");
                writeConfiguration(root.resolve("missing backend").resolve(WINDOWS ? "java.exe" : "java"), agent);
                failsWithoutMain(redirector, "missing backend");
                writeConfiguration(redirector.toAbsolutePath(), agent);
                failsWithoutMain(redirector, "recursive redirector configuration");
                writeConfiguration(backend, root.resolve("missing agent.jar"));
                failsWithoutMain(redirector, "missing agent");
            } finally {
                Files.write(configuration, original);
            }
        }

        void failsWithoutMain(Path redirector, String scenario) throws Exception {
            Invocation invocation = invocation(redirector, false, 0, 0);
            Result result = run(invocation.command(), Duration.ofSeconds(10));
            check(result.exitCode() != 0, scenario + " did not fail promptly");
            check(!Files.exists(invocation.record()) && !Files.exists(invocation.starts())
                    && !result.output().contains("REDIRECT_WITNESS_MAIN"), scenario + " executed application main");
        }

        void cancellation(Path redirector) throws Exception {
            Invocation invocation = invocation(redirector, false, 0, 60_000);
            Running running = start(invocation.command(), STDIN.getBytes(StandardCharsets.UTF_8));
            WitnessRecord record = awaitRecord(running.process(), invocation.record(), Duration.ofSeconds(30));
            remember(record.pid());
            assertIdentity(running.process().pid(), record);
            running.process().destroyForcibly();
            check(running.process().waitFor(10, TimeUnit.SECONDS), "cancelled redirector did not exit");
            long started = System.nanoTime();
            while (alive(record.pid()) && System.nanoTime() - started < Duration.ofSeconds(10).toNanos()) {
                TimeUnit.MILLISECONDS.sleep(25);
            }
            check(!alive(record.pid()), "killing the redirector left its actual JVM alive");
            equal(List.of(Long.toString(record.pid())), Files.readAllLines(invocation.starts()), "cancelled application replayed");
        }

        void helperSurvivesNormalExit(Path redirector) throws Exception {
            Invocation invocation = invocation(redirector, true, 7, -1);
            Result result = run(invocation.command(), Duration.ofSeconds(30), STDIN.getBytes(StandardCharsets.UTF_8));
            equal(7, result.exitCode(), "normal helper-owning application exit was changed");
            WitnessRecord record = readRecord(invocation.record());
            remember(record.helperPid());
            assertIdentity(result.pid(), record);
            check(record.helperPid() > 0, "helper did not reach main before its parent exited");
            // Windows job termination can arrive just after its wrapper process exits.
            TimeUnit.MILLISECONDS.sleep(150);
            check(alive(record.helperPid()), "redirector terminated an application helper during an ordinary exit");
            equal(List.of(Long.toString(record.pid())), Files.readAllLines(invocation.starts()), "helper-owning application replayed");
            ProcessHandle.of(record.helperPid()).ifPresent(ProcessHandle::destroyForcibly);
        }

        Invocation invocation(Path redirector, boolean jar, int exit, long sleepMillis) throws IOException {
            String id = UUID.randomUUID().toString();
            Path record = root.resolve(id + ".record");
            Path starts = root.resolve(id + ".starts");
            List<String> command = new ArrayList<>(List.of(redirector.toString(), "-Dloadonce.redirect.test.option=" + VM_VALUE));
            if (jar) command.addAll(List.of("-jar", checkerJar.toString()));
            else command.addAll(List.of("-cp", fixtureClasspath(), Witness.class.getName()));
            command.addAll(List.of(record.toString(), starts.toString(), Integer.toString(exit), Long.toString(sleepMillis)));
            command.addAll(ARGUMENTS);
            return new Invocation(List.copyOf(command), record, starts);
        }

        void writeConfiguration(Path java, Path agentJar) throws IOException {
            Files.writeString(configuration, "java=" + java.toAbsolutePath() + "\nagent=" + agentJar.toAbsolutePath() + "\n",
                    StandardCharsets.UTF_8);
        }

        synchronized Running start(List<String> command, byte[] stdin) throws IOException {
            if (closed) throw new IOException("redirect fixture is closed");
            Path log = Files.createTempFile(root, "child-", ".log");
            Path error = Files.createTempFile(root, "child-stderr-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile())
                    .redirectOutput(log.toFile()).redirectError(error.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            Process process = builder.start();
            processes.add(process);
            observed.put(process.pid(), process.toHandle());
            try (var input = process.getOutputStream()) {
                input.write(stdin);
            }
            return new Running(process, log, error);
        }

        Result run(List<String> command, Duration timeout) throws Exception {
            return run(command, timeout, new byte[0]);
        }

        Result run(List<String> command, Duration timeout, byte[] stdin) throws Exception {
            Running running = start(command, stdin);
            long started = System.nanoTime();
            while (running.process().isAlive()) {
                sample();
                check(Files.size(running.log()) + Files.size(running.error()) <= 4 * 1024 * 1024, "redirector exceeded its output limit");
                if (System.nanoTime() - started >= timeout.toNanos()) {
                    throw new IOException("redirector did not finish within the bounded test deadline");
                }
                running.process().waitFor(25, TimeUnit.MILLISECONDS);
            }
            int exit = running.process().waitFor();
            check(Files.size(running.log()) + Files.size(running.error()) <= 4 * 1024 * 1024, "redirector exceeded its output limit");
            return new Result(running.process().pid(), exit,
                    new String(Files.readAllBytes(running.log()), StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(running.error()), StandardCharsets.UTF_8));
        }

        WitnessRecord awaitRecord(Process process, Path record, Duration timeout) throws Exception {
            long started = System.nanoTime();
            while (!Files.exists(record)) {
                sample();
                check(process.isAlive(), "redirector exited before the actual JVM reached main");
                if (System.nanoTime() - started >= timeout.toNanos()) {
                    throw new IOException("redirector did not reach application main before cancellation deadline");
                }
                TimeUnit.MILLISECONDS.sleep(25);
            }
            return readRecord(record);
        }

        synchronized void remember(long pid) {
            ProcessHandle.of(pid).ifPresent(handle -> observed.put(pid, handle));
        }

        synchronized void sample() {
            for (ProcessHandle process : new ArrayList<>(observed.values())) {
                if (process.isAlive()) {
                    try (var children = process.descendants()) {
                        children.forEach(child -> observed.put(child.pid(), child));
                    }
                }
            }
        }

        synchronized void stopAll() {
            closed = true;
            sample();
            for (Process process : processes) if (process.isAlive()) process.destroyForcibly();
            for (ProcessHandle process : observed.values()) if (process.isAlive()) process.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            for (Process process : processes) {
                long started = System.nanoTime();
                while (System.nanoTime() - started < Duration.ofSeconds(5).toNanos()) {
                    try {
                        if (process.waitFor(25, TimeUnit.MILLISECONDS)) break;
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        @Override public void close() throws Exception {
            stopAll();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already owns the registered cleanup.
            }
            deleteTree(root);
        }
    }

    private static void assertIdentity(long redirectorPid, WitnessRecord record) {
        if (WINDOWS) {
            check(redirectorPid != record.pid(), "Windows wrapper must supervise a distinct JVM process");
            equal(redirectorPid, record.parentPid(), "actual JVM is not the native redirector's child");
        } else {
            equal(redirectorPid, record.pid(), "Linux redirector did not exec the actual JVM in place");
        }
    }

    private static WitnessRecord readRecord(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (!input.readUTF().equals("runtime-redirect-witness-1")) throw new IOException("invalid witness record");
            WitnessRecord result = new WitnessRecord(input.readLong(), input.readLong(), input.readLong(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), readList(input), readList(input));
            if (input.read() != -1) throw new IOException("unexpected trailing witness bytes");
            return result;
        }
    }

    private static List<String> readList(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > 1000) throw new IOException("invalid witness argument count");
        List<String> values = new ArrayList<>();
        for (int index = 0; index < size; index++) values.add(input.readUTF());
        return List.copyOf(values);
    }

    private static Map<String, String> cleanEnvironment(Path root, Path javaHome) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        Path home = Files.createDirectory(root.resolve("home"));
        Path temporary = Files.createDirectory(root.resolve("tmp"));
        result.put("HOME", home.toString());
        result.put("USERPROFILE", home.toString());
        result.put("JAVA_HOME", javaHome.toString());
        result.put("LANG", "C.UTF-8");
        result.put("TMPDIR", temporary.toString());
        result.put("TMP", temporary.toString());
        result.put("TEMP", temporary.toString());
        if (WINDOWS) {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null) systemRoot = System.getenv("SYSTEMROOT");
            if (systemRoot == null || systemRoot.isBlank()) throw new IOException("Windows SystemRoot is required");
            result.put("SystemRoot", systemRoot);
            result.put("PATH", javaHome.resolve("bin") + File.pathSeparator + Path.of(systemRoot).resolve("System32"));
        } else {
            result.put("PATH", "/usr/bin:/bin");
        }
        result.put("LOADONCE_REDIRECT_TEST_MARKER", UUID.randomUUID().toString());
        return Map.copyOf(result);
    }

    private static String fixtureClasspath() throws IOException {
        try {
            // Exclude production loose classes: premain provenance must be the installed agent JAR.
            return Path.of(RuntimeRedirectTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toRealPath().toString();
        } catch (Exception failure) {
            throw new IOException("test fixture class location is unavailable");
        }
    }

    private static void writeCheckerJar(Path path) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Witness.class.getName());
        String entry = Witness.class.getName().replace('.', '/') + ".class";
        try (InputStream bytes = RuntimeRedirectTest.class.getClassLoader().getResourceAsStream(entry);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (bytes == null) throw new IOException("compiled checker fixture is missing");
            output.putNextEntry(new JarEntry(entry));
            bytes.transferTo(output);
            output.closeEntry();
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static int occurrences(String text, String expected) {
        int count = 0;
        for (int index = text.indexOf(expected); index >= 0; index = text.indexOf(expected, index + expected.length())) count++;
        return count;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message);
    }

    private record Invocation(List<String> command, Path record, Path starts) { }
    private record Running(Process process, Path log, Path error) { }
    private record Result(long pid, int exitCode, String stdout, String stderr) {
        String output() { return stdout + stderr; }
    }
    private record WitnessRecord(long pid, long parentPid, long helperPid, String javaHome, String cwd, String marker,
                                 String vmProperty, String agentStatus, String agentSource, String stdin,
                                 List<String> arguments, List<String> agents) { }

    private record Options(Path agentJar, Path redirectorsDirectory, Path javaHome) {
        static Options parse(String[] arguments) throws IOException {
            Map<String, Path> values = new LinkedHashMap<>();
            for (int index = 0; index < arguments.length; index += 2) {
                String key = arguments[index];
                if (!List.of("--agent-jar", "--redirectors-dir", "--java-home").contains(key)
                        || index + 1 >= arguments.length || values.containsKey(key)) {
                    throw new IOException("Usage: RuntimeRedirectTest --agent-jar PATH --redirectors-dir PATH [--java-home PATH]");
                }
                values.put(key, Path.of(arguments[index + 1]).toAbsolutePath().normalize());
            }
            if (!values.containsKey("--agent-jar") || !values.containsKey("--redirectors-dir")) {
                throw new IOException("--agent-jar and --redirectors-dir are required");
            }
            return new Options(values.get("--agent-jar"), values.get("--redirectors-dir"),
                    values.getOrDefault("--java-home", Path.of(System.getProperty("java.home"))));
        }
    }

    /** Self-contained checker JAR entrypoint; records the actual VM before exiting or sleeping. */
    public static final class Witness {
        public static void main(String[] arguments) throws Exception {
            if (arguments[0].equals("--helper")) {
                Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                TimeUnit.SECONDS.sleep(60);
                return;
            }
            long pid = ProcessHandle.current().pid();
            Path record = Path.of(arguments[0]);
            Path starts = Path.of(arguments[1]);
            Files.writeString(starts, Long.toString(pid) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Class<?> agent = Class.forName("dev.loadonce.agent.LoadOnceAgent", false, ClassLoader.getSystemClassLoader());
            String status = (String) agent.getMethod("startupStatus").invoke(null);
            String agentSource = Path.of(agent.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().toString();
            String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> agents = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(value -> value.startsWith("-javaagent:")).toList();
            long helperPid = 0;
            long sleepMillis = Long.parseLong(arguments[3]);
            if (sleepMillis == -1) {
                Path helperRecord = record.resolveSibling(record.getFileName() + ".helper");
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                Process helper = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                        "-cp", System.getProperty("java.class.path"), Witness.class.getName(), "--helper", helperRecord.toString())
                        .redirectInput(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                long started = System.nanoTime();
                while (!Files.exists(helperRecord)) {
                    if (!helper.isAlive() || System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(10)) {
                        helper.destroyForcibly();
                        throw new IOException("application helper did not reach main");
                    }
                    TimeUnit.MILLISECONDS.sleep(25);
                }
                helperPid = helper.pid();
            }
            Path staged = record.resolveSibling(record.getFileName() + ".tmp");
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(staged))) {
                output.writeUTF("runtime-redirect-witness-1");
                output.writeLong(pid);
                output.writeLong(ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L));
                output.writeLong(helperPid);
                output.writeUTF(System.getProperty("java.home"));
                output.writeUTF(Path.of("").toAbsolutePath().normalize().toString());
                output.writeUTF(System.getenv().getOrDefault("LOADONCE_REDIRECT_TEST_MARKER", ""));
                output.writeUTF(System.getProperty("loadonce.redirect.test.option", ""));
                output.writeUTF(status);
                output.writeUTF(agentSource);
                output.writeUTF(stdin);
                output.writeInt(arguments.length - 4);
                for (int index = 4; index < arguments.length; index++) output.writeUTF(arguments[index]);
                output.writeInt(agents.size());
                for (String value : agents) output.writeUTF(value);
            }
            Files.move(staged, record);
            System.out.println("REDIRECT_WITNESS_MAIN");
            System.err.println("REDIRECT_WITNESS_STDERR");
            if (sleepMillis > 0) TimeUnit.MILLISECONDS.sleep(sleepMillis);
            System.exit(Integer.parseInt(arguments[2]));
        }
    }
}
