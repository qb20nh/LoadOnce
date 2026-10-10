package com.qb20nh.loadonce.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Executable fixture contracts. Helper JVMs model engines, not real CRaC support. */
public final class CracFixtureTest {
    private static int checks;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    private static final String CLASSPATH = absoluteClasspath();

    private CracFixtureTest() { }

    public static void main(String[] args) throws Exception {
        missingAndFailedCold();
        repeatedFreshRestores();
        runtimeClasspathAndDeclaredDependencies();
        transitiveManifestDependencies();
        kernelCompatibility();
        corruptAndUnsafePayloads();
        invalidPrivateAndLegacyManifests();
        failedCaptureRetainsPreviousImage();
        captureMutationIsNotPromoted();
        failedRestoreNeverReplays();
        timeoutKillsAndReapsWithoutReplay();
        cleanCredentialFreeEnvironment();
        nativeExtractionSettingsDisplayAndProviders();
        criuCompletionAndEngineOptions();
        invalidFixtureContracts();
        System.out.println("CRaC fixture checks passed: " + checks);
    }

    private static String absoluteClasspath() {
        List<String> parts = new ArrayList<>();
        for (String part : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator), -1)) {
            parts.add(Path.of(part.isEmpty() ? "." : part).toAbsolutePath().normalize().toString());
        }
        return String.join(File.pathSeparator, parts);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        check(expected.equals(actual), "expected " + expected + ", got " + actual);
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static void fails(Class<? extends Throwable> type, Checked action) throws Exception {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getName() + ", got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName());
    }

    private static final class Harness implements AutoCloseable {
        private final Path directory = LauncherFiles.tempDirectory(null, "loadonce-crac-test-");
        private final Path root = directory.resolve("cache");
        private final Path java = directory.resolve("jdk/bin/java");
        private final Path classes = directory.resolve("classes");
        private final Path provider = directory.resolve("provider.so");
        private final Path control = directory.resolve("control");
        private final Path launches = directory.resolve("launches");
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Path> coldDirectories = new ArrayList<>();
        private Map<String, String> parent = Map.of();
        private CracFixture.Fixture fixture;
        private boolean mutateCapture;

        Harness() throws IOException {
            Files.createDirectories(java.getParent());
            Files.writeString(java, "fake-runtime-executable");
            Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
            Path home = java.getParent().getParent();
            Files.writeString(home.resolve("release"), "JAVA_VERSION=25-crac\n");
            Files.createDirectory(home.resolve("lib"));
            Files.writeString(home.resolve("lib/libjvm.so"), "runtime-v1");
            Files.createDirectory(home.resolve("conf"));
            Files.writeString(home.resolve("conf/java.security"), "config-v1");
            Files.createDirectory(classes);
            Files.writeString(classes.resolve("CracSmoke.class"), "fixture-v1");
            Files.writeString(provider, "provider-v1");
            fixture = new CracFixture.Fixture(java, classes.toString(), false, "warp", List.of(), List.of());
        }

        private ChildProcess.Result execute(List<String> command, Path cwd, Map<String, String> env,
                                            Path log, Duration timeout) throws IOException, InterruptedException {
            commands.add(command);
            List<String> helper = new ArrayList<>(List.of(JAVA.toString(), "-cp", CLASSPATH, FakeEngine.class.getName(),
                    launches.toString(), control.toString()));
            helper.addAll(command.subList(1, command.size()));
            ChildProcess.Result result = ChildProcess.run(helper, cwd, env, log, timeout);
            if (mutateCapture && command.stream().anyMatch(a -> a.startsWith("-XX:CRaCCheckpointTo="))) {
                Files.writeString(classes.resolve("CracSmoke.class"), "changed-during-capture");
            }
            return result;
        }

        private CracFixture.Outcome capture() throws IOException, InterruptedException {
            int before = commands.size();
            CracFixture.Outcome result = CracFixture.capture(fixture, root, TIMEOUT, parent, this::execute);
            equal(before + 1, commands.size());
            equal("capture", result.mode());
            equal(0, result.exitCode());
            return result;
        }

        private CracFixture.Outcome run() throws IOException, InterruptedException {
            int before = commands.size();
            CracFixture.Outcome result = CracFixture.run(fixture, root, TIMEOUT, parent, this::execute);
            equal(before + 1, commands.size());
            if (result.mode().equals("cold")) coldDirectories.add(result.log().getParent());
            return result;
        }

        private Map<String, String> manifest() throws IOException {
            return LauncherFiles.readManifest(root.resolve(CracFixture.MANIFEST), Set.of("schema", "completed", "fixture",
                    "engine", "fingerprint", "generation", "sha256", "bytes", "uid", "source_pid", "heap_witness"));
        }

        private Path generation() throws IOException { return root.resolve(manifest().get("generation")); }

        private List<String> launchRecords() throws IOException {
            return Files.exists(launches) ? Files.readAllLines(launches) : List.of();
        }

        @Override public void close() throws IOException {
            for (Path path : coldDirectories) LauncherFiles.deleteTree(path);
            LauncherFiles.deleteTree(directory);
        }
    }

    private static void missingAndFailedCold() throws Exception {
        try (Harness h = new Harness()) {
            CracFixture.Outcome cold = h.run();
            equal("cold", cold.mode());
            check(cold.output().contains("CRAC_SMOKE COLD"), "missing image starts the conventional fixture");
            check(h.commands.get(0).stream().noneMatch(a -> a.startsWith("-XX:CRaC")), "cold launch has no engine switches");
            equal("cold", h.commands.get(0).get(h.commands.get(0).size() - 1));
            Files.writeString(h.control, "cold-failure");
            CracFixture.Outcome failed = h.run();
            equal(19, failed.exitCode());
            equal(2, h.launchRecords().size());
        }
    }

    private static void repeatedFreshRestores() throws Exception {
        try (Harness h = new Harness()) {
            CracFixture.Outcome saved = h.capture();
            Set<Long> pids = new HashSet<>(Set.of(saved.pid()));
            for (int i = 0; i < 3; i++) {
                CracFixture.Outcome result = h.run();
                equal("restore", result.mode());
                check(pids.add(result.pid()), "every restore starts a fresh process");
                check(result.output().contains("initial-pid=" + saved.pid()), "retained source PID is verified");
            }
            equal(4, h.launchRecords().size());
            check(h.commands.get(0).contains("-XX:CRaCEngineOptions=checkpoint_exit_code=0"), "Warp capture uses its documented successful exit option");
            equal("2", h.manifest().get("schema"));
            try (Stream<Path> files = Files.list(h.root)) {
                check(files.noneMatch(p -> p.getFileName().toString().startsWith(".manifest-")), "atomic manifest leaves no pending file");
            }
        }
    }

    private static void runtimeClasspathAndDeclaredDependencies() throws Exception {
        try (Harness h = new Harness()) {
            Path home = h.java.getParent().getParent();
            h.fixture = new CracFixture.Fixture(h.java, h.classes.toString(), false, "warp", List.of(h.provider), List.of());
            for (Path path : List.of(h.java, home.resolve("release"), home.resolve("lib/libjvm.so"),
                    home.resolve("conf/java.security"), h.classes.resolve("CracSmoke.class"), h.provider)) {
                h.capture();
                String original = Files.readString(path);
                Files.writeString(path, original + "changed");
                equal("cold", h.run().mode());
                Files.writeString(path, original);
            }
            h.capture();
            Files.delete(h.provider);
            equal("cold", h.run().mode());
        }
    }

    private static void writeJar(Path path, String manifestClasspath, String bytes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (!manifestClasspath.isEmpty()) manifest.getMainAttributes().putValue("Class-Path", manifestClasspath);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new JarEntry("Fixture.class"));
            output.write(bytes.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void transitiveManifestDependencies() throws Exception {
        try (Harness h = new Harness()) {
            Path jar = h.directory.resolve("fixture.jar");
            Path dependency = h.directory.resolve("dependency.jar");
            writeJar(jar, "dependency.jar", "fixture");
            writeJar(dependency, "", "dependency-v1");
            h.fixture = new CracFixture.Fixture(h.java, jar.toString(), false, "warp", List.of(), List.of());
            h.capture();
            writeJar(dependency, "", "dependency-v2");
            equal("cold", h.run().mode());
        }
    }

    private static void kernelCompatibility() throws Exception {
        try (Harness h = new Harness()) {
            h.capture();
            String original = System.getProperty("os.version");
            try {
                System.setProperty("os.version", "different-kernel");
                equal("cold", h.run().mode());
            } finally {
                System.setProperty("os.version", original);
            }
            equal("restore", h.run().mode());
        }
    }

    private static void corruptAndUnsafePayloads() throws Exception {
        for (String change : List.of("corrupt", "missing", "extra", "nonprivate", "symlink", "hardlink")) {
            try (Harness h = new Harness()) {
                h.capture();
                Path image = h.generation().resolve("image");
                Path core = image.resolve("core.img");
                switch (change) {
                    case "corrupt" -> { byte[] data = Files.readAllBytes(core); data[500] ^= 1; Files.write(core, data); }
                    case "missing" -> Files.delete(core);
                    case "extra" -> { Files.writeString(image.resolve("extra"), "extra"); LauncherFiles.sealTree(image); }
                    case "nonprivate" -> Files.setPosixFilePermissions(core, PosixFilePermissions.fromString("rw-r--r--"));
                    case "symlink" -> { Files.move(core, image.resolve("original")); Files.createSymbolicLink(core, Path.of("original")); }
                    case "hardlink" -> Files.createLink(image.resolve("duplicate"), core);
                    default -> throw new AssertionError(change);
                }
                equal("cold", h.run().mode());
            }
        }
    }

    @SuppressWarnings("try")
    private static void invalidPrivateAndLegacyManifests() throws Exception {
        try (Harness h = new Harness()) {
            h.capture();
            Path manifest = h.root.resolve(CracFixture.MANIFEST);
            String original = Files.readString(manifest);
            for (Map.Entry<String, String> changed : Map.of("completed", "false", "schema", "1", "fixture", "Main", "engine", "criuengine",
                    "generation", "../escape", "uid", "other-user", "bytes", "invalid", "heap_witness", "invalid!").entrySet()) {
                Map<String, String> record = new HashMap<>(h.manifest());
                record.put(changed.getKey(), changed.getValue());
                LauncherFiles.writeManifest(manifest, record);
                equal("cold", h.run().mode());
                Files.writeString(manifest, original);
            }
            Files.writeString(manifest, "invalid manifest");
            equal("cold", h.run().mode());
            Files.writeString(manifest, original);
            Files.setPosixFilePermissions(manifest, PosixFilePermissions.fromString("rw-r--r--"));
            equal("cold", h.run().mode());
            Files.setPosixFilePermissions(manifest, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(h.root, PosixFilePermissions.fromString("rwxr-xr-x"));
            equal("cold", h.run().mode());
            Files.setPosixFilePermissions(h.root, PosixFilePermissions.fromString("rwx------"));
            try (LauncherFiles.CacheLock ignored = LauncherFiles.lock(h.root, true)) {
                equal("cold", h.run().mode());
            }
            equal("restore", h.run().mode());
            Files.delete(manifest);
            Files.writeString(h.root.resolve("manifest.json"), "{\"schema\":1,\"completed\":true}");
            equal("cold", h.run().mode());
        }
    }

    private static void failedCaptureRetainsPreviousImage() throws Exception {
        try (Harness h = new Harness()) {
            h.capture();
            String previous = Files.readString(h.root.resolve(CracFixture.MANIFEST));
            for (String failure : List.of("capture-failure", "no-boundary")) {
                Files.writeString(h.control, failure);
                int count = h.commands.size();
                fails(IOException.class, h::capture);
                equal(count + 1, h.commands.size());
                equal(previous, Files.readString(h.root.resolve(CracFixture.MANIFEST)));
            }
            Files.delete(h.control);
            equal("restore", h.run().mode());
        }
    }

    private static void captureMutationIsNotPromoted() throws Exception {
        try (Harness h = new Harness()) {
            h.mutateCapture = true;
            fails(IOException.class, h::capture);
            equal(1, h.commands.size());
            check(!Files.exists(h.root.resolve(CracFixture.MANIFEST)), "changed inputs cannot publish a completion marker");
        }
    }

    private static void failedRestoreNeverReplays() throws Exception {
        try (Harness h = new Harness()) {
            h.capture();
            Files.writeString(h.control, "restore-failure");
            CracFixture.Outcome failure = h.run();
            equal("restore", failure.mode());
            equal(23, failure.exitCode());
            equal(2, h.commands.size());
            Files.writeString(h.control, "restore-evidence-missing");
            fails(IOException.class, h::run);
            equal(3, h.commands.size());
            equal(3, h.launchRecords().size());
        }
    }

    private static void timeoutKillsAndReapsWithoutReplay() throws Exception {
        try (Harness h = new Harness()) {
            h.capture();
            Files.writeString(h.control, "timeout");
            fails(IOException.class, () -> CracFixture.run(h.fixture, h.root, Duration.ofSeconds(1), h.parent, h::execute));
            equal(2, h.commands.size());
            List<String> records = h.launchRecords();
            equal(2, records.size());
            long pid = Long.parseLong(records.get(1).split(" ")[1]);
            check(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "timeout kills and reaps the child before returning");
        }
    }

    private static void cleanCredentialFreeEnvironment() throws Exception {
        try (Harness h = new Harness()) {
            h.parent = Map.of("GH_TOKEN", "private-value", "CUSTOM_SECRET", "private-value", "JAVA_TOOL_OPTIONS", "-Dsecret=private-value",
                    "JDK_JAVA_OPTIONS", "-Xmx1g", "LD_PRELOAD", "secret.so", "HOME", "/caller/home",
                    "AWS_SESSION_TOKEN", "private-value", "HTTP_PROXY", "https://name:private-value@localhost");
            for (CracFixture.Outcome result : List.of(h.capture(), h.run())) {
                check(result.output().contains("ENV HOME,LANG,PATH"), "only the fixture environment reaches the real helper JVM");
                check(!result.output().contains("private-value"), "caller credentials never reach captured output");
                check(!result.output().contains("/caller/home"), "HOME is private and explicit");
            }
            String manifest = Files.readString(h.root.resolve(CracFixture.MANIFEST));
            check(!manifest.contains(h.java.toString()) && !manifest.contains(h.classes.toString()), "manifest stores digests instead of argument paths");
        }
    }

    private static void nativeExtractionSettingsDisplayAndProviders() throws Exception {
        try (Harness h = new Harness()) {
            Path authority = h.directory.resolve("Xauthority");
            Files.writeString(authority, "local-cookie");
            h.parent = Map.of("DISPLAY", ":12", "XAUTHORITY", authority.toString(), "PULSE_SERVER", "unwanted", "ALSOFT_DRIVERS", "pulse");
            h.fixture = new CracFixture.Fixture(h.java, h.classes.toString(), true, "warp", List.of(h.provider), List.of("--reconfigure"));
            CracFixture.Outcome saved = h.capture();
            equal("restore", h.run().mode());
            check(saved.output().contains("ENV ALSOFT_DRIVERS,DISPLAY,HOME,LANG,LIBGL_ALWAYS_SOFTWARE,MESA_SHADER_CACHE_DIR,PATH,XAUTHORITY,XDG_RUNTIME_DIR"),
                    "only approved local display/software/null-backend paths reach native helper");
            check(saved.output().contains("ALSOFT_DRIVERS=null"), "physical backend is disabled");
            check(h.commands.get(0).contains("--reconfigure"), "exact fixture settings arguments are retained");
            Path extracted = h.generation().resolve("tmp/lwjgl/libfixture.so");
            check(Files.isRegularFile(extracted), "extracted libraries are pinned under the retained private generation");
            Files.writeString(extracted, "modified-native");
            equal("cold", h.run().mode());
            h.capture();
            Files.writeString(h.provider, "provider-v2");
            equal("cold", h.run().mode());
            h.capture();
            Files.writeString(authority, "changed-cookie");
            equal("cold", h.run().mode());
            h.capture();
            h.parent = Map.of("DISPLAY", ":13", "XAUTHORITY", authority.toString());
            equal("cold", h.run().mode());
            h.parent = Map.of("DISPLAY", ":12", "XAUTHORITY", authority.toString());
            h.capture();
            h.fixture = new CracFixture.Fixture(h.java, h.classes.toString(), true, "warp", List.of(h.provider), List.of());
            equal("cold", h.run().mode());
        }
    }

    private static void criuCompletionAndEngineOptions() throws Exception {
        try (Harness h = new Harness()) {
            h.fixture = new CracFixture.Fixture(h.java, h.classes.toString(), false, "criuengine", List.of(), List.of());
            h.capture();
            check(h.commands.get(0).stream().noneMatch(a -> a.startsWith("-XX:CRaCEngineOptions=")), "CRIU never receives Warp-only options");
            equal("restore", h.run().mode());
            Files.delete(h.generation().resolve("image/inventory.img"));
            equal("cold", h.run().mode());
            Files.writeString(h.control, "no-boundary");
            fails(IOException.class, h::capture);
        }
    }

    private static void invalidFixtureContracts() throws Exception {
        try (Harness h = new Harness()) {
            fails(IllegalArgumentException.class, () -> new CracFixture.Fixture(h.java, h.classes.toString(), false, "simengine", List.of(), List.of()));
            fails(IllegalArgumentException.class, () -> new CracFixture.Fixture(h.java, "relative/classes", false, "warp", List.of(), List.of()));
            fails(IllegalArgumentException.class, () -> new CracFixture.Fixture(h.java, h.classes + File.pathSeparator + "*", false, "warp", List.of(), List.of()));
            fails(IllegalArgumentException.class, () -> new CracFixture.Fixture(h.java, h.classes.toString(), true, "warp", List.of(), List.of("--accessToken")));
            h.fixture = new CracFixture.Fixture(h.java, h.classes.toString(), true, "warp", List.of(), List.of());
            h.parent = Map.of("DISPLAY", "remote.example:0");
            fails(IOException.class, h::capture);
            check(h.commands.isEmpty(), "unsupported display fails before child startup");
        }
    }

    /** Real child JVM used by contract tests in place of a checkpoint engine. */
    public static final class FakeEngine {
        private FakeEngine() { }

        public static void main(String[] args) throws Exception {
            Path launches = Path.of(args[0]);
            Path control = Path.of(args[1]);
            List<String> original = List.of(args).subList(2, args.length);
            String checkpoint = option(original, "-XX:CRaCCheckpointTo=");
            String restore = option(original, "-XX:CRaCRestoreFrom=");
            String mode = checkpoint != null ? "capture" : restore != null ? "restore" : "cold";
            Files.writeString(launches, mode + " " + ProcessHandle.current().pid() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("ENV " + String.join(",", System.getenv().keySet().stream().sorted().toList()));
            System.out.println("HOME=" + System.getenv("HOME") + " ALSOFT_DRIVERS=" + System.getenv("ALSOFT_DRIVERS"));
            String failure = Files.exists(control) ? Files.readString(control) : "";
            if (failure.equals("timeout")) Thread.sleep(5000);
            if (restore != null && failure.equals("restore-failure")) System.exit(23);
            if (restore != null && failure.equals("restore-evidence-missing")) return;
            boolean nativeFixture = original.contains(CracFixture.NATIVE);
            if (restore != null) {
                String[] source = Files.readString(Path.of(restore).resolve("core.img")).strip().split(" ");
                nativeFixture = Boolean.parseBoolean(source[1]);
                if (nativeFixture) {
                    System.out.println("NATIVE_PROBE verified cycle=1");
                    System.out.println("NATIVE_PROBE cleanup=tracked-handles-zero native-release=ok");
                    System.out.println("NATIVE_PROBE PASS cycles=1");
                } else {
                    System.out.println("CRAC_SMOKE restored-pid=" + ProcessHandle.current().pid() + " initial-pid=" + source[0]
                            + " heap-witness=01234567-89ab value=42 coordinator-state=READY");
                }
                System.out.println("preparationCallbacks=1, restorationCallbacks=1");
            } else if (checkpoint != null) {
                Path image = Path.of(checkpoint);
                String source = ProcessHandle.current().pid() + " " + nativeFixture;
                Files.writeString(image.resolve("core.img"), source + " ".repeat(5000));
                String engine = original.contains("-XX:CRaCEngine=criuengine") ? "criuengine" : "warp";
                Files.writeString(image.resolve("engine"), engine);
                if (engine.equals("criuengine")) {
                    Files.writeString(image.resolve("inventory.img"), source);
                    Files.writeString(image.resolve("dump4.log"), failure.equals("no-boundary") ? "dump failed" : "Dumping finished successfully");
                }
                if (nativeFixture) {
                    System.out.println("NATIVE_PROBE verified cycle=0");
                    Path extracted = Path.of(option(original, "-Dorg.lwjgl.system.SharedLibraryExtractPath="));
                    Files.createDirectories(extracted);
                    Files.writeString(extracted.resolve("libfixture.so"), "extracted-native");
                } else {
                    System.out.println("CRAC_SMOKE initial-pid=" + ProcessHandle.current().pid() + " heap-witness=01234567-89ab");
                }
                if (!failure.equals("no-boundary")) System.out.println("warp: Checkpoint successful!");
                if (failure.equals("capture-failure")) System.exit(17);
            } else {
                System.out.println(nativeFixture ? "NATIVE_PROBE PASS cycles=1" : "CRAC_SMOKE COLD value=42 coordinator-state=READY");
                if (failure.equals("cold-failure")) System.exit(19);
            }
        }

        private static String option(List<String> args, String prefix) {
            return args.stream().filter(a -> a.startsWith(prefix)).map(a -> a.substring(prefix.length())).findFirst().orElse(null);
        }
    }
}
