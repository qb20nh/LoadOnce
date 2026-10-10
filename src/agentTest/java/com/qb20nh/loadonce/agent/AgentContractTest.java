package com.qb20nh.loadonce.agent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Java 17 subprocess contracts against an actual packaged premain agent. */
public final class AgentContractTest {
    private static final String AGENT = "com.qb20nh.loadonce.agent.LoadOnceAgent";
    private static final String STATUS = "LOADONCE_AGENT initialized=true crac_api=";
    private static final String VETO = "LoadOnce checkpoint denied: game boundary is unavailable";
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int OUTPUT_LIMIT = 4 * 1024 * 1024;
    private static int checks;

    private AgentContractTest() { }

    public static void main(String[] args) throws Exception {
        if ((args.length != 2 && args.length != 4) || !args[0].equals("--agent-jar")) {
            throw new IllegalArgumentException("Usage: AgentContractTest --agent-jar <agent.jar> [--crac-java <java executable>]");
        }
        Path agent = Path.of(args[1]).toRealPath();
        Path crac = null;
        if (args.length == 4) {
            if (!args[2].equals("--crac-java")) throw new IllegalArgumentException("Unsupported test option");
            crac = Path.of(args[3]).toRealPath();
        }
        verifyJar(agent);
        Path directory = Files.createTempDirectory("loadonce-agent-test-").toAbsolutePath();
        try {
            ordinaryStartupAndArguments(agent, directory);
            explicitVerboseStartup(agent, directory);
            nonzeroMainIsNotReplayed(agent, directory);
            rejectArgumentsAndDuplicates(agent, directory);
            launcherCapabilityProbe(agent, directory);
            incompatibleApiCannotReportInstalled(agent, directory);
            if (crac != null) repeatedRealCheckpointVeto(agent, crac, directory);
            System.out.println("Agent contract checks passed: " + checks
                    + " real-crac-veto=" + (crac != null));
        } finally {
            deleteTree(directory);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        check(expected.equals(actual), "expected " + expected + ", got " + actual);
    }

    private static void verifyJar(Path agent) throws IOException {
        try (JarFile archive = new JarFile(agent.toFile())) {
            equal(AGENT, archive.getManifest().getMainAttributes().getValue("Premain-Class"));
            check(archive.getEntry("com/qb20nh/loadonce/agent/LoadOnceAgent.class") != null, "packaged agent entrypoint exists");
            check(archive.getManifest().getMainAttributes().getValue("Main-Class") == null, "agent does not replace application main");
            for (String attribute : List.of("Can-Redefine-Classes", "Can-Retransform-Classes")) {
                String capability = archive.getManifest().getMainAttributes().getValue(attribute);
                check(capability == null || capability.equals("false"), "agent requests no bytecode transformation capability");
            }
            for (var entry : archive.stream().toList()) {
                if (entry.getName().endsWith(".class")) {
                    check(entry.getName().startsWith("com/qb20nh/loadonce/agent/"), "agent artifact contains only agent classes");
                }
            }
        }
    }

    private static String fixtureClasspath() throws IOException {
        Path location;
        try {
            location = Path.of(AgentContractTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        } catch (Exception failure) {
            throw new IOException("Test class location is unavailable");
        }
        // Keep production main output off the child classpath so provenance is
        // proved against the packaged agent rather than a duplicate loose class.
        return location.toString();
    }

    private static List<String> fixtureCommand(Path java, Path agent, Path record, String mode, List<String> applicationArgs)
            throws IOException {
        List<String> command = new ArrayList<>(List.of(java.toString(), "-javaagent:" + agent,
                "-cp", fixtureClasspath(), FixtureMain.class.getName(), record.toString(), mode));
        command.addAll(applicationArgs);
        return command;
    }

    private static Map<String, String> cleanEnvironment() {
        Map<String, String> parent = System.getenv();
        java.util.HashMap<String, String> clean = new java.util.HashMap<>();
        // Windows requires its system directory for some JVM dependencies.
        // Linux uses the same explicit launch environment without credentials.
        for (String name : List.of("SystemRoot", "WINDIR", "PATH", "TEMP", "TMP", "LANG")) {
            String value = parent.get(name);
            if (value != null) clean.put(name, value);
        }
        return Map.copyOf(clean);
    }

    private record Child(long pid, int code, String stdout, String stderr) { }

    private static Child child(List<String> command, Path directory) throws Exception {
        Path out = Files.createTempFile(directory, "stdout-", ".log");
        Path err = Files.createTempFile(directory, "stderr-", ".log");
        ProcessBuilder builder = new ProcessBuilder(WindowsCommandLine.forProcessBuilder(command));
        builder.directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(cleanEnvironment());
        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        Process process = builder.start();
        process.getOutputStream().close();
        Set<ProcessHandle> remembered = new HashSet<>();
        remembered.add(process.toHandle());
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            while (process.isAlive()) {
                process.descendants().forEach(remembered::add);
                if (Files.size(out) > OUTPUT_LIMIT || Files.size(err) > OUTPUT_LIMIT) throw new IOException("Agent test output exceeded its limit");
                if (System.nanoTime() >= deadline) throw new IOException("Agent test child timed out");
                process.waitFor(25, TimeUnit.MILLISECONDS);
            }
            if (Files.size(out) > OUTPUT_LIMIT || Files.size(err) > OUTPUT_LIMIT) throw new IOException("Agent test output exceeded its limit");
            return new Child(process.pid(), process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            for (ProcessHandle descendant : remembered) if (descendant.isAlive()) descendant.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static void once(Child child, Path record, Path agent) throws IOException {
        equal(1, Files.readAllLines(record).size());
        equal(Long.toString(child.pid()), Files.readString(record).strip());
        check(child.stdout().contains("AGENT_FIXTURE pid=" + child.pid()), "main and premain remain in the launched JVM");
        equal(0L, child.stderr().lines().filter(line -> line.startsWith("LOADONCE_AGENT")).count());
        check(!child.stdout().contains("LOADONCE_AGENT"), "premain status never changes application stdout");
        check(child.stdout().contains("AGENT_PROVENANCE " + encoded(agent.toUri().normalize().toString())),
                "entrypoint was loaded from the actual packaged agent");
    }

    private static void ordinaryStartupAndArguments(Path agent, Path directory) throws Exception {
        Path record = directory.resolve("ordinary-main");
        List<String> arguments = List.of("argument with spaces", "", "literal $() ` & ;", "한국어", "--accessToken", "opaque-app-value");
        List<String> plainCommand = new ArrayList<>(List.of(JAVA.toString(), "-cp", fixtureClasspath(), PlainArgumentsMain.class.getName()));
        plainCommand.addAll(arguments);
        Child plain = child(plainCommand, directory);
        equal(0, plain.code());
        String expectedArguments = argumentRecord(arguments);
        String backendArguments = argumentOutput(plain);
        boolean unicodePreserved = expectedArguments.equals(backendArguments);
        List<String> observed = decodedArguments(backendArguments);
        equal(arguments.size(), observed.size());
        for (int index = 0; index < arguments.size(); index++) {
            if (arguments.get(index).chars().allMatch(character -> character < 128)) {
                equal(arguments.get(index), observed.get(index));
            }
        }
        if (!System.getProperty("os.name").startsWith("Windows") || unicodePreserved) {
            equal(expectedArguments, backendArguments);
        }
        Child child = child(fixtureCommand(JAVA, agent, record, "normal", arguments), directory);
        equal(0, child.code());
        once(child, record, agent);
        // Some Windows JDK launchers convert their native command line through
        // the active code page before premain. Measure that unchanged backend;
        // the agent must preserve every argument the same JVM actually receives.
        equal(backendArguments, argumentOutput(child));
        System.out.println("AGENT_BACKEND unicode_arguments_preserved=" + unicodePreserved);
        check(child.stdout().contains("AGENT_STATUS " + encoded(STATUS + "unavailable checkpoint_guard=false")),
                "ordinary JVM exposes unavailable through the endpoint without disrupting startup");
        check(child.stdout().contains("AGENT_STATUS_SAFE true"), "public endpoint contains only fixed booleans and identifiers");
        check(child.stderr().lines().noneMatch(line -> line.contains("opaque-app-value")), "agent logs exclude application arguments");
        check(!Files.exists(directory.resolve("image")), "ordinary startup creates no process image");
    }

    private static void explicitVerboseStartup(Path agent, Path directory) throws Exception {
        Path record = directory.resolve("verbose-main");
        List<String> command = fixtureCommand(JAVA, agent, record, "normal", List.of());
        command.add(1, "-Dloadonce.agent.verbose=true");
        Child child = child(command, directory);
        equal(0, child.code());
        equal(1, Files.readAllLines(record).size());
        equal(Long.toString(child.pid()), Files.readString(record).strip());
        equal(1L, child.stderr().lines().filter(line -> line.equals(STATUS + "unavailable checkpoint_guard=false")).count());
        check(!child.stdout().contains("LOADONCE_AGENT"), "explicit verbose agent logging still leaves stdout untouched");
    }

    private static void nonzeroMainIsNotReplayed(Path agent, Path directory) throws Exception {
        Path record = directory.resolve("nonzero-main");
        Child child = child(fixtureCommand(JAVA, agent, record, "exit23", List.of()), directory);
        equal(23, child.code());
        once(child, record, agent);
    }

    private static void rejectArgumentsAndDuplicates(Path agent, Path directory) throws Exception {
        for (String mode : List.of("arguments", "duplicate")) {
            Path record = directory.resolve(mode + "-main");
            List<String> command = fixtureCommand(JAVA, agent, record, "normal", List.of());
            if (mode.equals("arguments")) command.set(1, "-javaagent:" + agent + "=private-agent-option");
            else command.add(2, "-javaagent:" + agent);
            Child child = child(command, directory);
            check(child.code() != 0, "invalid or duplicate premain fails initialization");
            check(!Files.exists(record), "failed premain cannot start main");
            check(child.stderr().contains("LoadOnce agent initialization failed"), "premain emits a generic failure");
            equal(0L, child.stderr().lines().filter(line -> line.startsWith("LOADONCE_AGENT")).count());
            check(!child.stderr().contains("private-agent-option"), "agent argument value is not logged");
            check(!child.stdout().contains("AGENT_FIXTURE"), "invalid startup does not run an application");
        }
    }

    private static void launcherCapabilityProbe(Path agent, Path directory) throws Exception {
        Child ordinary = child(List.of(JAVA.toString(), "-version"), directory);
        Child child = child(List.of(JAVA.toString(), "-javaagent:" + agent, "-version"), directory);
        equal(0, child.code());
        equal(ordinary.stdout(), child.stdout());
        equal(ordinary.stderr(), child.stderr());
        equal(0L, child.stderr().lines().filter(line -> line.startsWith("LOADONCE_AGENT")).count());
        Child verbose = child(List.of(JAVA.toString(), "-Dloadonce.agent.verbose=true", "-javaagent:" + agent, "-version"), directory);
        equal(0, verbose.code());
        equal("", verbose.stdout());
        equal(1L, verbose.stderr().lines().filter(line -> line.equals(STATUS + "unavailable checkpoint_guard=false")).count());
        equal(ordinary.stderr(), verbose.stderr().lines().filter(line -> !line.startsWith("LOADONCE_AGENT"))
                .reduce("", (output, line) -> output + line + System.lineSeparator()));
    }

    private static void incompatibleApiCannotReportInstalled(Path agent, Path directory) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("Agent contract tests require a JDK compiler");
        Path classes = directory.resolve("incompatible-api");
        Path sources = classes.resolve("jdk/crac");
        Files.createDirectories(sources);
        Path core = sources.resolve("Core.java");
        Path resource = sources.resolve("Resource.java");
        Path context = sources.resolve("Context.java");
        Files.writeString(core, "package jdk.crac; public class Core { public static Context getGlobalContext() { return new Context(); } }");
        Files.writeString(resource, "package jdk.crac; public interface Resource { void beforeCheckpoint(Context context) throws Exception; void afterRestore(Context context) throws Exception; }");
        Files.writeString(context, "package jdk.crac; public class Context { public void register(Resource resource) { throw new IllegalStateException(\"private-reflection-cause\"); } }");
        equal(0, compiler.run(null, null, null, "--release", "17", "-d", classes.toString(), core.toString(), resource.toString(), context.toString()));
        Path record = directory.resolve("failed-registration-main");
        List<String> command = fixtureCommand(JAVA, agent, record, "normal", List.of());
        int cp = command.indexOf("-cp") + 1;
        command.set(cp, classes + File.pathSeparator + fixtureClasspath());
        Child child = child(command, directory);
        check(child.code() != 0 && !Files.exists(record), "present API registration failure aborts initialization before main");
        check(child.stderr().contains("LoadOnce agent initialization failed"), "registration failure is generic");
        check(!child.stderr().contains("checkpoint_guard=true") && !child.stderr().contains("crac_api=unavailable"),
                "registration failure cannot be reported as installed or unavailable");
        check(!child.stderr().contains("private-reflection-cause"), "reflection failures do not expose their causes");
        Files.delete(classes.resolve("jdk/crac/Resource.class"));
        Child missing = child(command, directory);
        check(missing.code() != 0 && !Files.exists(record), "a partial present API cannot start an unprotected application");
        check(missing.stderr().contains("LoadOnce agent initialization failed") && !missing.stderr().contains("crac_api=unavailable"),
                "a missing Resource class after Core discovery is initialization failure");
    }

    private static void repeatedRealCheckpointVeto(Path agent, Path java, Path directory) throws Exception {
        Path image = directory.resolve("crac-image");
        Files.createDirectory(image);
        Path record = directory.resolve("real-crac-main");
        List<String> command = fixtureCommand(java, agent, record, "checkpoint", List.of());
        command.addAll(1, List.of("-XX:CRaCEngine=simengine", "-XX:CRaCCheckpointTo=" + image));
        Child child = child(command, directory);
        equal(0, child.code());
        once(child, record, agent);
        check(child.stdout().contains("AGENT_STATUS " + encoded(STATUS + "available checkpoint_guard=true policy=deny")),
                "actual CRaC runtime registers the independent guard while default startup remains quiet");
        equal(3L, child.stdout().lines().filter(line -> line.startsWith("AGENT_VETO attempt=")).count());
        check(child.stdout().contains("AGENT_OBJECT_METHODS valid=true"), "registered proxy obeys the Object contracts");
        check(child.stdout().contains("AGENT_VETO attempts=3 prevented=true"), "three actual callback attempts propagate the guard veto");
        try (Stream<Path> files = Files.list(image)) {
            check(files.findAny().isEmpty(), "denied attempts create no engine image");
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String argumentRecord(List<String> arguments) {
        return "AGENT_ARGUMENTS " + arguments.stream().map(AgentContractTest::encoded).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String argumentOutput(Child child) {
        return child.stdout().lines().filter(line -> line.startsWith("AGENT_ARGUMENTS ")).findFirst().orElse("");
    }

    private static List<String> decodedArguments(String output) {
        check(output.startsWith("AGENT_ARGUMENTS "), "backend reports the application argument record");
        String encoded = output.substring("AGENT_ARGUMENTS ".length());
        return Stream.of(encoded.split(",", -1))
                .map(value -> new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).toList();
    }

    private static void deleteTree(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    /** Application fixture; no compile-time agent or CRaC dependency. */
    public static final class PlainArgumentsMain {
        private PlainArgumentsMain() { }

        public static void main(String[] args) {
            System.out.println(argumentRecord(List.of(args)));
        }
    }

    /** Application fixture; no compile-time agent or CRaC dependency. */
    public static final class FixtureMain {
        private FixtureMain() { }

        public static void main(String[] args) throws Exception {
            Path record = Path.of(args[0]);
            String mode = args[1];
            Files.writeString(record, ProcessHandle.current().pid() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Class<?> agent = Class.forName(AGENT, false, ClassLoader.getSystemClassLoader());
            String status = (String) agent.getMethod("startupStatus").invoke(null);
            URI source = Path.of(agent.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().toUri().normalize();
            System.out.println("AGENT_FIXTURE pid=" + ProcessHandle.current().pid());
            System.out.println("AGENT_PROVENANCE " + encoded(source.toString()));
            System.out.println("AGENT_STATUS " + encoded(status));
            System.out.println("AGENT_STATUS_SAFE " + status.matches("LOADONCE_AGENT initialized=(?:true|false) crac_api=(?:unprobed|unavailable|available|failed) checkpoint_guard=(?:true|false)(?: policy=deny)?"));
            List<String> application = List.of(args).subList(2, args.length);
            System.out.println(argumentRecord(application));
            if (mode.equals("exit23")) System.exit(23);
            if (mode.equals("checkpoint")) {
                verifyGuardObjects(agent);
                Class<?> core = Class.forName("jdk.crac.Core");
                for (int attempt = 1; attempt <= 3; attempt++) {
                    System.gc();
                    try {
                        core.getMethod("checkpointRestore").invoke(null);
                        throw new AssertionError("unguarded checkpoint returned successfully");
                    } catch (InvocationTargetException denied) {
                        if (!containsVeto(denied.getCause(), java.util.Collections.newSetFromMap(new IdentityHashMap<>()))) {
                            throw new AssertionError("checkpoint did not report the independent guard veto");
                        }
                    }
                    System.out.println("AGENT_VETO attempt=" + attempt);
                }
                System.out.println("AGENT_VETO attempts=3 prevented=true");
            }
        }

        private static void verifyGuardObjects(Class<?> agent) throws Exception {
            var field = agent.getDeclaredField("checkpointGuard");
            field.setAccessible(true);
            Object guard = field.get(null);
            if (guard == null || !guard.equals(guard) || guard.equals(null) || guard.equals(new Object())
                    || guard.hashCode() != System.identityHashCode(guard) || !guard.toString().equals("LoadOnce checkpoint guard")) {
                throw new AssertionError("checkpoint proxy violates its Object contracts");
            }
            System.out.println("AGENT_OBJECT_METHODS valid=true");
        }

        private static boolean containsVeto(Throwable failure, Set<Throwable> seen) {
            if (failure == null || !seen.add(failure)) return false;
            if (VETO.equals(failure.getMessage())) return true;
            if (containsVeto(failure.getCause(), seen)) return true;
            for (Throwable suppressed : failure.getSuppressed()) if (containsVeto(suppressed, seen)) return true;
            return false;
        }
    }
}
