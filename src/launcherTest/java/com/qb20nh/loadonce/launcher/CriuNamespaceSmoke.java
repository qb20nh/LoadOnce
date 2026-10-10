package com.qb20nh.loadonce.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in CPU fixture only; the external upstream CRIU provider is experimental. */
public final class CriuNamespaceSmoke {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String CPU = "com.qb20nh.loadonce.runtime.CracSmoke";
    private static final Pattern INITIAL = Pattern.compile(
            "CRAC_SMOKE initial-pid=(\\d+) heap-witness=([0-9a-f-]+)");
    private static final Pattern RESTORED = Pattern.compile(
            "CRAC_SMOKE restored-pid=(\\d+) initial-pid=(\\d+) heap-witness=([0-9a-f-]+)"
                    + " value=42 coordinator-state=READY");
    private static final Pattern NAMESPACE = Pattern.compile("CRIU_NAMESPACE pid-namespace=(pid:\\[\\d+\\])");
    private static final String CALLBACKS = "CallbackResult[preparationCallbacks=1, restorationCallbacks=1]";

    private CriuNamespaceSmoke() { }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        if (options.phase() != null) {
            insideNamespace(options);
            return;
        }
        require(Files.isRegularFile(Path.of("/proc/self/stat")), "private namespace smoke requires Linux /proc");
        Path java = executable(options.java(), "--java (set -PloadonceCracJava)");
        Path criu = executable(options.criu(), "--criu (set -PloadonceCriu)");
        Path runtime = options.runtimeJar().toRealPath();
        require(Files.isRegularFile(runtime), "--runtime-jar must be the built standalone runtime JAR");
        require(LauncherFiles.jarDependencies(runtime).isEmpty(), "CPU runtime JAR must have no manifest classpath");
        Path directory = LauncherFiles.tempDirectory(options.workDirectory().toAbsolutePath().normalize(), "run-");
        Path image = LauncherFiles.privateDirectory(directory.resolve("image"));
        Path home = LauncherFiles.privateDirectory(directory.resolve("home"));
        Options selected = new Options(java, criu, runtime, directory, null);
        Map<String, String> environment = environment(home);
        String inputs = fingerprint(java.getParent().getParent(), criu, runtime);
        System.out.println("CRIU_NAMESPACE experimental-external-upstream-criu=true CPU-only=true evidence=" + directory);

        ChildProcess.Result capture = namespace(selected, "capture", environment);
        require(capture.exitCode() == 0, "capture helper failed; inspect " + directory.resolve("capture.log"));
        String captured = Files.readString(directory.resolve("capture-cpu.log"));
        Matcher initial = INITIAL.matcher(captured);
        require(initial.find(), "capture lacks initial heap witness; inspect " + directory.resolve("capture-cpu.log"));
        String sourcePid = initial.group(1);
        String witness = initial.group(2);
        require(!initial.find(), "capture emitted more than one initial heap witness");
        require(Long.parseLong(sourcePid) >= 128, "capture did not reserve low namespace PIDs for the restore helper");
        require(capture.output().contains("CRIU_NAMESPACE cpu-exit=137"), "capture must exit 137 after checkpoint");
        Path dump = image.resolve("dump4.log");
        require(Files.isRegularFile(dump) && Files.readString(dump).contains("Dumping finished successfully"),
                "CRIU dump did not report successful capture; inspect " + dump);
        long imageBytes = imageBytes(image);
        require(imageBytes > 1024 * 1024, "CRIU image is not substantial; inspect " + image);
        LauncherFiles.sealTree(image);
        require(inputs.equals(fingerprint(java.getParent().getParent(), criu, runtime)),
                "runtime/provider inputs changed during capture; no restore attempted");
        String imageDigest = fingerprint(image);
        String parentNamespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString();
        require(!parentNamespace.equals(namespaceId(capture)), "capture did not run in a private PID namespace");
        Set<Long> controllers = new HashSet<>();
        controllers.add(capture.pid());
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("integration", "experimental-external-upstream-criu-cpu-only");
        evidence.put("inputs_sha256", inputs);
        evidence.put("image_sha256", imageDigest);
        evidence.put("source_namespace_pid", sourcePid);
        evidence.put("heap_witness", witness);
        evidence.put("capture_controller_pid", Long.toString(capture.pid()));
        evidence.put("capture_pid_namespace", namespaceId(capture));
        for (int attempt = 1; attempt <= 3; attempt++) {
            require(inputs.equals(fingerprint(java.getParent().getParent(), criu, runtime)),
                    "runtime/provider input mutation before restore " + attempt + "; no restore attempted");
            require(imageDigest.equals(fingerprint(image)),
                    "image mutation before restore " + attempt + "; no restore attempted");
            ChildProcess.Result restored = namespace(selected, "restore-" + attempt, environment);
            require(restored.exitCode() == 0 && restored.output().contains("CRIU_NAMESPACE cpu-exit=0"),
                    "restore " + attempt + " failed; no cold fallback or replay; inspect "
                            + directory.resolve("restore-" + attempt + ".log"));
            require(controllers.add(restored.pid()), "restore did not launch a fresh host namespace controller");
            String namespace = namespaceId(restored);
            // Namespace inode IDs may be recycled after the previous namespace
            // exits. Distinct controllers each execute a fresh unshare --pid.
            require(!parentNamespace.equals(namespace), "restore did not run in a private PID namespace");
            String output = Files.readString(directory.resolve("restore-" + attempt + "-cpu.log"));
            Matcher resumed = RESTORED.matcher(output);
            require(resumed.find() && resumed.group(2).equals(sourcePid) && resumed.group(3).equals(witness),
                    "restore lacks retained heap witness/value/READY state; inspect restore-" + attempt + "-cpu.log");
            String restoredPid = resumed.group(1);
            require(!resumed.find() && output.contains(CALLBACKS), "restore lacks exact one-time callback evidence");
            require(!INITIAL.matcher(output).find(), "restore unexpectedly executed cold startup");
            evidence.put("restore_" + attempt + "_controller_pid", Long.toString(restored.pid()));
            evidence.put("restore_" + attempt + "_pid_namespace", namespace);
            evidence.put("restore_" + attempt + "_namespace_pid", restoredPid);
            System.out.println("CRIU_NAMESPACE verified restore=" + attempt + " host-controller-pid=" + restored.pid()
                    + " pid-namespace=" + namespace + " source-namespace-pid=" + sourcePid
                    + " restored-namespace-pid=" + restoredPid);
        }
        require(imageDigest.equals(fingerprint(image)), "image changed during restoration; inspect " + image);
        evidence.put("restores", "3");
        evidence.put("image_bytes", Long.toString(imageBytes));
        LauncherFiles.writeManifest(directory.resolve("evidence.properties"), evidence);
        LauncherFiles.sealTree(directory);
        System.out.println("CRIU_NAMESPACE PASS experimental-external-upstream-criu=true restores=3 image-bytes="
                + imageBytes + "; CPU fixture only; Minecraft and hardware restoration unverified");
    }

    private static ChildProcess.Result namespace(Options options, String phase, Map<String, String> environment)
            throws Exception {
        Path helperJava = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        List<String> command = new ArrayList<>(List.of("/usr/bin/unshare", "--user", "--map-root-user",
                "--net", "--ipc", "--uts", "--cgroup", "--pid", "--fork", "--mount-proc", "--kill-child=SIGKILL",
                helperJava.toString(), "-Xms16m", "-Xmx64m", "-cp", helperClasspath(), CriuNamespaceSmoke.class.getName(),
                "--java", options.java().toString(), "--criu", options.criu().toString(),
                "--runtime-jar", options.runtimeJar().toString(), "--work-directory", options.workDirectory().toString(),
                "--namespace-phase", phase));
        return ChildProcess.runIsolated(command, options.workDirectory(), environment,
                options.workDirectory().resolve(phase + ".log"), TIMEOUT);
    }

    private static void insideNamespace(Options options) throws Exception {
        String phase = options.phase();
        Path directory = options.workDirectory();
        Map<String, String> environment = environment(directory.resolve("home"));
        String namespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString();
        System.out.println("CRIU_NAMESPACE pid-namespace=" + namespace);
        require(ChildProcess.run(List.of("/usr/bin/ip", "link", "set", "lo", "up"), directory, environment,
                directory.resolve(phase + "-network.log"), TIMEOUT).exitCode() == 0,
                "private loopback setup failed; inspect " + directory.resolve(phase + "-network.log"));
        if (phase.equals("capture")) {
            require(ChildProcess.run(List.of(options.criu().toString(), "check", "--unprivileged"), directory,
                    environment, directory.resolve("capability-check.log"), TIMEOUT).exitCode() == 0,
                    "CRIU capability check failed inside private namespaces; inspect capability-check.log");
            Files.writeString(Path.of("/proc/sys/kernel/ns_last_pid"), "127\n");
        }
        List<String> command = new ArrayList<>(List.of(options.java().toString(), "-Xshare:off", "-Xms32m", "-Xmx128m",
                "-XX:CRaCEngine=criuengine",
                "-XX:CRaCEngineOptions=criu_location=" + options.criu() + ",upstream_criu=true"));
        if (phase.equals("capture")) {
            command.addAll(List.of("-XX:CRaCCheckpointTo=" + directory.resolve("image"), "-cp",
                    options.runtimeJar().toString(), CPU, "success"));
        } else {
            command.add("-XX:CRaCRestoreFrom=" + directory.resolve("image"));
        }
        ChildProcess.Result result = ChildProcess.runIsolated(command, directory, environment,
                directory.resolve(phase + "-cpu.log"), TIMEOUT);
        System.out.println("CRIU_NAMESPACE cpu-exit=" + result.exitCode());
        require(result.exitCode() == (phase.equals("capture") ? 137 : 0),
                "CPU " + phase + " failed; no fallback/replay; inspect " + directory.resolve(phase + "-cpu.log"));
    }

    private static Map<String, String> environment(Path home) {
        return Map.of("HOME", home.toString(), "PATH", "/usr/bin:/bin", "LANG", "C.UTF-8");
    }

    private static String helperClasspath() {
        List<String> paths = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            paths.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        return String.join(File.pathSeparator, paths);
    }

    private static String namespaceId(ChildProcess.Result result) throws IOException {
        Matcher marker = NAMESPACE.matcher(result.output());
        require(marker.find(), "namespace helper did not report its PID namespace");
        String namespace = marker.group(1);
        require(!marker.find(), "namespace helper reported duplicate namespace markers");
        return namespace;
    }

    private static String fingerprint(Path... inputs) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path input : inputs) LauncherFiles.digestPath(digest, input);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long imageBytes(Path image) throws IOException {
        try (var paths = Files.walk(image)) {
            long bytes = 0;
            for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".img")).toList()) {
                if (Files.isRegularFile(file)) bytes = Math.addExact(bytes, Files.size(file));
            }
            return bytes;
        }
    }

    private static Path executable(Path path, String label) throws IOException {
        require(path != null, label + " is required; this smoke is opt-in");
        Path real = path.toRealPath();
        require(Files.isRegularFile(real) && Files.isExecutable(real), label + " must be an executable file: " + real);
        return real;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private record Options(Path java, Path criu, Path runtimeJar, Path workDirectory, String phase) {
        private static Options parse(String[] arguments) {
            Path java = null, criu = Path.of("/usr/bin/criu"), runtimeJar = null;
            Path directory = Path.of("build/criu-namespace-validation");
            String phase = null;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < arguments.length; i += 2) {
                if (i + 1 >= arguments.length || !seen.add(arguments[i])) throw new IllegalArgumentException("Invalid smoke options");
                switch (arguments[i]) {
                    case "--java" -> java = Path.of(arguments[i + 1]);
                    case "--criu" -> criu = Path.of(arguments[i + 1]);
                    case "--runtime-jar" -> runtimeJar = Path.of(arguments[i + 1]);
                    case "--work-directory" -> directory = Path.of(arguments[i + 1]);
                    case "--namespace-phase" -> phase = arguments[i + 1];
                    default -> throw new IllegalArgumentException("Unknown smoke option: " + arguments[i]);
                }
            }
            if (runtimeJar == null || (phase != null && !phase.matches("capture|restore-[1-3]"))) {
                throw new IllegalArgumentException("--runtime-jar required; internal namespace phase must be capture or restore-1..3");
            }
            return new Options(java, criu, runtimeJar, directory, phase);
        }
    }
}
