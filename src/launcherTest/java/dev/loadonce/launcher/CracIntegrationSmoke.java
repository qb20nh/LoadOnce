package dev.loadonce.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Real, opt-in CRaC fixture integration; callback simulation never claims an image. */
public final class CracIntegrationSmoke {
    private static final String CPU = "dev.loadonce.runtime.CracSmoke";
    private static final String NATIVE = "dev.loadonce.prototype.NativeRestorationProbe";
    private static final Pattern INITIAL = Pattern.compile(
            "CRAC_SMOKE initial-pid=(\\d+) heap-witness=([0-9a-f-]+)");
    private static final Pattern RESTORED = Pattern.compile(
            "CRAC_SMOKE restored-pid=(\\d+) initial-pid=(\\d+) heap-witness=([0-9a-f-]+)"
                    + " value=42 coordinator-state=READY");
    private static final String CALLBACKS = "preparationCallbacks=1, restorationCallbacks=1";

    private CracIntegrationSmoke() { }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        require(Files.isRegularFile(Path.of("/proc/self/stat")), "process identity checks require Linux /proc");
        Path java = options.java().toRealPath();
        require(Files.isRegularFile(java) && Files.isExecutable(java), "--java must be an executable CRaC JDK java");
        String cpuClasspath = runtimeClasspath();
        Map<String, String> parentEnvironment = Map.copyOf(System.getenv());
        Path root = LauncherFiles.privateDirectory(options.workDirectory().toAbsolutePath().normalize());
        Path directory = LauncherFiles.tempDirectory(root, "run-");
        System.out.println("CRAC_SMOKE logs-and-private-images=" + directory);

        // Shared ChildProcess clears the child environment. Even variables that
        // the fixture ignores would otherwise remain inside a complete image.
        CracFixture.Fixture cpuFixture = new CracFixture.Fixture(java, cpuClasspath,
                false, "warp", List.of(), List.of());
        Path direct = workspace(directory, "direct-");
        if (!options.skipFailureCases()) {
            failureCases(cpuFixture, direct, options, parentEnvironment);
        }

        boolean nativeFixture = options.nativeClasspathFile() != null;
        String classpath = nativeFixture ? classpathFile(options.nativeClasspathFile()) : cpuClasspath;
        List<String> nativeFlags = options.nativeReconfigure() ? List.of("--reconfigure") : List.of();
        CracFixture.Fixture selected = new CracFixture.Fixture(java, classpath,
                nativeFixture, options.engine().equals("simengine") ? "warp" : options.engine(),
                List.of(), nativeFlags);
        if (options.engine().equals("simengine")) {
            simulation(selected, workspace(directory, "simulation-"), options.timeout(), parentEnvironment);
            System.out.println("CRAC_SMOKE PASS mode=simulation; callbacks verified, no process image proved");
            return;
        }

        Path declaredInput = LauncherFiles.tempFile(directory, "declared-input-", ".txt");
        Files.writeString(declaredInput, "isolated fixture dependency v1\n");
        selected = new CracFixture.Fixture(java, classpath, nativeFixture, options.engine(),
                List.of(declaredInput), nativeFlags);
        Path cache = LauncherFiles.privateDirectory(directory.resolve("fixture-cache"));
        CracFixture.Outcome captured = CracFixture.capture(selected, cache, options.timeout(), parentEnvironment);
        CracFixture.ValidImage valid = CracFixture.validImage(selected, cache, parentEnvironment);
        Map<String, String> manifest = valid.manifest();
        require(captured.pid() == Long.parseLong(manifest.get("source_pid")), "capture PID disagrees with manifest");
        String witness = checkpointWitness(captured, selected.nativeFixture());
        require(witness.equals(manifest.get("heap_witness")), "capture witness disagrees with manifest");
        assertSubstantialImage(valid.directory(), selected.engine());

        Set<Long> restoredPids = new HashSet<>();
        for (int attempt = 1; attempt <= options.restores(); attempt++) {
            CracFixture.Outcome restored = CracFixture.run(selected, cache, options.timeout(), parentEnvironment);
            require(restored.mode().equals("restore") && restored.exitCode() == 0,
                    "expected successful restore; inspect " + restored.log());
            require(restored.pid() != captured.pid() && restoredPids.add(restored.pid()),
                    "restore did not launch a distinct fresh JVM");
            verifyRestored(restored, selected.nativeFixture(), captured.pid(), witness);
            System.out.println("CRAC_SMOKE verified restore=" + attempt + " source-pid=" + captured.pid()
                    + " restored-pid=" + restored.pid());
        }

        preflightFallbacks(selected, cache, declaredInput, valid, options.timeout(), parentEnvironment);
        if (selected.engine().equals("warp")) {
            failedRestoreIsNotReplayed(selected, cache, valid, options.timeout(), parentEnvironment);
        }
        LauncherFiles.sealTree(directory);
        LauncherFiles.checkPrivate(cache.resolve("manifest.properties"), false);
        CracFixture.validImage(selected, cache, parentEnvironment);
        long imageBytes = imageBytes(valid.directory().resolve("image"));
        System.out.println("CRAC_SMOKE PASS mode=process-image engine=" + selected.engine()
                + " native=" + selected.nativeFixture() + " restores=" + options.restores()
                + " image-bytes=" + imageBytes + "; isolated fixture only");
    }

    private static void failureCases(CracFixture.Fixture fixture, Path directory, Options options,
                                     Map<String, String> parentEnvironment) throws Exception {
        for (String scenario : List.of("denied", "prepare-failure", "restore-failure")) {
            String state = scenario.equals("restore-failure") ? "STOPPED" : "READY";
            Path simulationImage = LauncherFiles.privateDirectory(directory.resolve("simulation-" + scenario));
            Path log = LauncherFiles.tempFile(directory, "failure-" + scenario + "-", ".log");
            ChildProcess.Result result = execute(fixture, directory, parentEnvironment,
                    List.of("-XX:CRaCEngine=simengine", "-XX:CRaCCheckpointTo=" + simulationImage,
                            "-cp", fixture.classpath(), CPU, scenario),
                    log, options.timeout());
            require(result.exitCode() == 0 && result.output().contains(
                    "CRAC_SMOKE expected-failure=" + scenario + " coordinator-state=" + state),
                    "real CRaC failure recovery evidence missing; inspect " + log);
            System.out.println("CRAC_SMOKE verified scenario=" + scenario + " coordinator-state=" + state);
        }
        if (options.engine().equals("warp")) {
            Path image = LauncherFiles.privateDirectory(directory.resolve("rejected-image"));
            Path log = LauncherFiles.tempFile(directory, "failure-descriptor-rejection-", ".log");
            ChildProcess.Result result = execute(fixture, directory, parentEnvironment,
                    List.of("-XX:CRaCEngine=warp", "-XX:CRaCCheckpointTo=" + image,
                            "-cp", fixture.classpath(), CPU, "descriptor-rejection"), log, options.timeout());
            boolean empty;
            try (var contents = Files.list(image)) {
                empty = contents.findAny().isEmpty();
            }
            require(result.exitCode() == 0 && result.output().contains(
                    "CRAC_SMOKE expected-failure=descriptor-rejection coordinator-state=READY") && empty,
                    "engine descriptor rejection did not recover with an empty image; inspect " + log);
            System.out.println("CRAC_SMOKE verified scenario=descriptor-rejection coordinator-state=READY image-files=0");
        }
        LauncherFiles.sealTree(directory);
    }

    private static void simulation(CracFixture.Fixture fixture, Path directory, Duration timeout,
                                   Map<String, String> parentEnvironment) throws Exception {
        Path image = LauncherFiles.privateDirectory(directory.resolve("image"));
        List<String> tail = new ArrayList<>(List.of("-XX:CRaCEngine=simengine", "-XX:CRaCCheckpointTo=" + image,
                "-cp", fixture.classpath(),
                fixture.nativeFixture() ? NATIVE : CPU));
        if (fixture.nativeFixture()) {
            tail.addAll(List.of("--cycles=1", "--crac", "--audio-loopback"));
            tail.addAll(fixture.nativeFlags());
        } else {
            tail.add("success");
        }
        Path log = LauncherFiles.tempFile(directory, "simulation-", ".log");
        ChildProcess.Result result = execute(fixture, directory, parentEnvironment, tail, log, timeout);
        require(result.exitCode() == 0, "simulation exited unsuccessfully; inspect " + log);
        verifyRestored(new CracFixture.Outcome("simulation", result.exitCode(), result.pid(), log,
                result.output(), ""), fixture.nativeFixture(), result.pid(),
                fixture.nativeFixture() ? "" : initialWitness(result.output(), result.pid()));
        LauncherFiles.sealTree(directory);
    }

    private static ChildProcess.Result execute(CracFixture.Fixture fixture, Path directory,
                                               Map<String, String> parentEnvironment, List<String> tail,
                                               Path log, Duration timeout) throws Exception {
        List<String> command = new ArrayList<>(CracFixture.base(fixture, directory));
        command.addAll(tail);
        return ChildProcess.runIsolated(command, directory,
                CracFixture.environment(fixture, directory, parentEnvironment), log, timeout);
    }

    private static String checkpointWitness(CracFixture.Outcome captured, boolean nativeFixture) throws IOException {
        if (nativeFixture) {
            require(captured.output().contains("NATIVE_PROBE verified cycle=0")
                    && !captured.output().contains("NATIVE_PROBE PASS"),
                    "native checkpoint did not stop at preparation boundary");
            return "";
        }
        require(!captured.output().contains("restored-pid="), "capture unexpectedly resumed ordinary startup");
        return initialWitness(captured.output(), captured.pid());
    }

    private static String initialWitness(String output, long pid) throws IOException {
        var initial = INITIAL.matcher(output);
        require(initial.find() && Long.parseLong(initial.group(1)) == pid, "CPU initial PID/witness evidence missing");
        return initial.group(2);
    }

    private static void verifyRestored(CracFixture.Outcome result, boolean nativeFixture,
                                       long initialPid, String witness) throws IOException {
        require(result.output().contains(CALLBACKS), "real preparation/restoration callback evidence missing");
        if (nativeFixture) {
            boolean simulation = result.mode().equals("simulation");
            require(result.output().contains("NATIVE_PROBE verified cycle=1")
                    && (simulation || !result.output().contains("NATIVE_PROBE verified cycle=0"))
                    && result.output().contains("NATIVE_PROBE PASS cycles=1")
                    && result.output().contains("native-release=ok"),
                    "native restore did not verify reconstructed resources; inspect " + result.log());
        } else {
            var restored = RESTORED.matcher(result.output());
            require(restored.find() && Long.parseLong(restored.group(1)) == result.pid()
                    && Long.parseLong(restored.group(2)) == initialPid && restored.group(3).equals(witness),
                    "retained CPU heap/process identity mismatch; inspect " + result.log());
        }
    }

    private static void preflightFallbacks(CracFixture.Fixture fixture, Path cache, Path declaredInput,
                                           CracFixture.ValidImage valid, Duration timeout,
                                           Map<String, String> parentEnvironment) throws Exception {
        Path manifestPath = cache.resolve("manifest.properties");
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        try {
            Files.writeString(manifestPath, "schema=invalid\n");
            expectCold(fixture, cache, timeout, parentEnvironment, "manifest-corruption");
        } finally {
            Files.write(manifestPath, manifestBytes);
        }
        byte[] originalInput = Files.readAllBytes(declaredInput);
        FileTime inputTime = Files.getLastModifiedTime(declaredInput);
        try {
            Files.writeString(declaredInput, "isolated fixture dependency v2\n");
            expectCold(fixture, cache, timeout, parentEnvironment, "dependency-change");
        } finally {
            Files.write(declaredInput, originalInput);
            Files.setLastModifiedTime(declaredInput, inputTime);
        }
        Path absent = declaredInput.resolveSibling(declaredInput.getFileName() + ".absent");
        Files.move(declaredInput, absent, StandardCopyOption.ATOMIC_MOVE);
        try {
            expectCold(fixture, cache, timeout, parentEnvironment, "dependency-missing");
        } finally {
            Files.move(absent, declaredInput, StandardCopyOption.ATOMIC_MOVE);
        }

        Path image = valid.directory().resolve("image").resolve(fixture.engine().equals("warp") ? "core.img" : "inventory.img");
        byte[] header = imageHeader(image);
        try {
            overwriteHeader(image, corruptHeader(header));
            expectCold(fixture, cache, timeout, parentEnvironment, "image-corruption");
        } finally {
            overwriteHeader(image, header);
        }
        CracFixture.validImage(fixture, cache, parentEnvironment);
    }

    private static void expectCold(CracFixture.Fixture fixture, Path cache, Duration timeout,
                                    Map<String, String> parentEnvironment, String scenario) throws Exception {
        CracFixture.Outcome result = CracFixture.run(fixture, cache, timeout, parentEnvironment);
        LauncherFiles.sealTree(result.log().getParent());
        require(result.mode().equals("cold") && result.exitCode() == 0 && !result.reason().isEmpty(),
                "preflight mismatch did not select one successful cold launch; inspect " + result.log());
        if (fixture.nativeFixture()) {
            require(result.output().contains("NATIVE_PROBE verified cycle=0")
                    && result.output().contains("NATIVE_PROBE PASS cycles=1")
                    && result.output().contains("native-release=ok")
                    && !result.output().contains(CALLBACKS), "native cold evidence missing; inspect " + result.log());
        } else {
            require(occurrences(result.output(), "CRAC_SMOKE COLD pid=") == 1
                    && !result.output().contains("restored-pid=")
                    && !result.output().contains("CRAC_SMOKE initial-pid="),
                    "cold fallback replayed or executed checkpoint path; inspect " + result.log());
        }
        System.out.println("CRAC_SMOKE verified preflight=" + scenario + " mode=cold pid=" + result.pid());
    }

    private static void failedRestoreIsNotReplayed(CracFixture.Fixture fixture, Path cache,
                                                  CracFixture.ValidImage valid, Duration timeout,
                                                  Map<String, String> parentEnvironment) throws Exception {
        Path image = valid.directory().resolve("image/core.img");
        Path manifestPath = cache.resolve("manifest.properties");
        byte[] header = imageHeader(image);
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        Set<Path> logsBefore = logs(valid.directory());
        try {
            // Matching checksums deliberately move this test past preflight. The
            // isolated engine must reject the invalid ELF header after child start.
            overwriteHeader(image, corruptHeader(header));
            CracFixture.Payload payload = CracFixture.payload(fixture, valid.directory());
            Map<String, String> updated = new LinkedHashMap<>(valid.manifest());
            updated.put("sha256", payload.sha256());
            updated.put("bytes", Long.toString(payload.bytes()));
            LauncherFiles.writeManifest(manifestPath, updated);
            CracFixture.validImage(fixture, cache, parentEnvironment);
            CracFixture.Outcome result = CracFixture.run(fixture, cache, timeout, parentEnvironment);
            require(result.mode().equals("restore") && result.exitCode() != 0
                    && !result.output().contains("CRAC_SMOKE COLD")
                    && !result.output().contains("NATIVE_PROBE PASS"),
                    "failed restore was hidden or replayed; inspect " + result.log());
            Set<Path> logsAfter = logs(valid.directory());
            logsAfter.removeAll(logsBefore);
            require(logsAfter.equals(Set.of(result.log())), "failed restore launched unexpected extra fixture children");
            System.out.println("CRAC_SMOKE verified scenario=failed-restore-no-replay mode=restore exit="
                    + result.exitCode() + " pid=" + result.pid());
        } finally {
            overwriteHeader(image, header);
            Files.write(manifestPath, manifestBytes);
        }
        CracFixture.validImage(fixture, cache, parentEnvironment);
    }

    private static Set<Path> logs(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            Set<Path> result = new HashSet<>();
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.matches("(?:capture|restore|cold)-.*\\.log")) result.add(child);
            }
            return result;
        }
    }

    private static byte[] imageHeader(Path image) throws IOException {
        require(Files.size(image) >= 64, "engine payload is too short for corruption test");
        ByteBuffer header = ByteBuffer.allocate(64);
        try (FileChannel input = FileChannel.open(image, StandardOpenOption.READ)) {
            while (header.hasRemaining()) require(input.read(header) >= 0, "engine header ended unexpectedly");
        }
        return header.array();
    }

    private static byte[] corruptHeader(byte[] original) {
        byte[] changed = original.clone();
        changed[0] ^= (byte) 0xff;
        return changed;
    }

    private static void overwriteHeader(Path image, byte[] bytes) throws IOException {
        try (FileChannel output = FileChannel.open(image, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }

    private static void assertSubstantialImage(Path generation, String engine) throws IOException {
        Path image = generation.resolve("image");
        Path expected = image.resolve(engine.equals("warp") ? "core.img" : "inventory.img");
        require(Files.isRegularFile(expected) && Files.size(expected) > 0,
                "engine did not create its image completion payload");
        boolean substantial;
        try (var files = Files.walk(image)) {
            substantial = files.filter(Files::isRegularFile).anyMatch(path -> {
                try { return Files.size(path) > 4096; }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        }
        require(substantial, "engine exited without substantial image data");
    }

    private static long imageBytes(Path image) throws IOException {
        long total = 0;
        try (var files = Files.walk(image)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) total = Math.addExact(total, Files.size(file));
        }
        return total;
    }

    private static Path workspace(Path root, String prefix) throws IOException {
        Path directory = LauncherFiles.tempDirectory(root, prefix);
        for (String name : List.of("home", "tmp", "xdg", "mesa-cache")) {
            LauncherFiles.privateDirectory(directory.resolve(name));
        }
        return directory;
    }

    private static String runtimeClasspath() throws Exception {
        // Use the already built runtime artifact, rather than recompiling sources
        // with a different compiler as part of engine validation.
        Class<?> runtime = Class.forName(CPU, false, CracIntegrationSmoke.class.getClassLoader());
        Path origin = Path.of(runtime.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        return origin.toString();
    }

    private static String classpathFile(Path file) throws IOException {
        String classpath = Files.readString(file.toRealPath()).strip();
        require(!classpath.isEmpty() && !classpath.contains("\n") && !classpath.contains("\r"),
                "native classpath must contain one nonempty line");
        for (String element : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
            Path path = Path.of(element);
            require(!element.isEmpty() && path.isAbsolute() && Files.exists(path) && !element.contains("*"),
                    "native classpath must contain explicit existing absolute paths");
        }
        return classpath;
    }

    private static int occurrences(String text, String marker) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(marker, at)) >= 0; at += marker.length()) count++;
        return count;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private record Options(Path java, String engine, Path workDirectory, int restores, Duration timeout,
                           Path nativeClasspathFile, boolean nativeReconfigure, boolean skipFailureCases) {
        static Options parse(String[] arguments) {
            Path java = null;
            String engine = "warp";
            Path directory = Path.of("build/crac-validation");
            int restores = 3;
            Duration timeout = Duration.ofSeconds(60);
            Path nativeClasspath = null;
            boolean reconfigure = false;
            boolean skipFailures = false;
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                if (option.equals("--native-reconfigure")) { reconfigure = true; continue; }
                if (option.equals("--skip-failure-cases")) { skipFailures = true; continue; }
                if (++index >= arguments.length) throw new IllegalArgumentException("missing value for " + option);
                String value = arguments[index];
                switch (option) {
                    case "--java" -> java = Path.of(value);
                    case "--engine" -> engine = value;
                    case "--work-dir" -> directory = Path.of(value);
                    case "--restores" -> restores = Integer.parseInt(value);
                    case "--timeout" -> {
                        double seconds = Double.parseDouble(value);
                        if (!Double.isFinite(seconds) || seconds < 1 || seconds > 60) {
                            throw new IllegalArgumentException("timeout must be in 1..60 seconds");
                        }
                        timeout = Duration.ofNanos((long) (seconds * 1_000_000_000));
                    }
                    case "--native-classpath-file" -> nativeClasspath = Path.of(value);
                    default -> throw new IllegalArgumentException("unknown CRaC smoke option " + option);
                }
            }
            if (java == null || !Set.of("warp", "criuengine", "simengine").contains(engine)
                    || restores < 1 || restores > 10 || (reconfigure && nativeClasspath == null)) {
                throw new IllegalArgumentException("Usage: crac-smoke --java /verified/crac-jdk/bin/java"
                        + " [--engine warp|criuengine|simengine] [--work-dir path] [--restores 1..10]"
                        + " [--timeout 1..60] [--native-classpath-file path] [--native-reconfigure] [--skip-failure-cases]");
            }
            return new Options(java, engine, directory, restores, timeout, nativeClasspath, reconfigure, skipFailures);
        }
    }
}
