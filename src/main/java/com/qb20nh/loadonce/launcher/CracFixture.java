package com.qb20nh.loadonce.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.stream.Stream;

/** Single-launch, private process images for the isolated CPU and native probes. */
public final class CracFixture {
    public static final int SCHEMA = 2;
    public static final String CPU = "com.qb20nh.loadonce.runtime.CracSmoke";
    public static final String NATIVE = "com.qb20nh.loadonce.prototype.NativeRestorationProbe";
    public static final String MANIFEST = "manifest.properties";
    private static final Set<String> FIELDS = Set.of("schema", "completed", "fixture", "engine", "fingerprint",
            "generation", "sha256", "bytes", "uid", "source_pid", "heap_witness");
    private static final Pattern INITIAL = Pattern.compile("CRAC_SMOKE initial-pid=(\\d+) heap-witness=([0-9a-f-]+)");
    private static final Pattern RESTORED = Pattern.compile("CRAC_SMOKE restored-pid=(\\d+) initial-pid=(\\d+) "
            + "heap-witness=([0-9a-f-]+) value=42 coordinator-state=READY");

    private CracFixture() { }

    public record Fixture(Path java, String classpath, boolean nativeFixture, String engine,
                          List<Path> extraInputs, List<String> nativeFlags) {
        public Fixture {
            java = java.toAbsolutePath().normalize();
            if (!Set.of("warp", "criuengine").contains(engine)) {
                throw new IllegalArgumentException("only actual Warp/CRIU process images are supported");
            }
            if (classpath.isEmpty() || classpath.indexOf('\n') >= 0 || classpath.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("fixture classpath must contain explicit absolute paths");
            }
            for (String part : classpath.split(Pattern.quote(File.pathSeparator), -1)) {
                Path path = Path.of(part);
                if (part.isEmpty() || !path.isAbsolute() || (path.getFileName() != null && path.getFileName().toString().equals("*"))) {
                    throw new IllegalArgumentException("fixture classpath must contain explicit absolute paths");
                }
            }
            extraInputs = extraInputs.stream().map(p -> p.toAbsolutePath().normalize()).toList();
            nativeFlags = List.copyOf(nativeFlags);
            if (!(nativeFlags.isEmpty() || nativeFlags.equals(List.of("--reconfigure")))
                    || (!nativeFixture && !nativeFlags.isEmpty())) {
                throw new IllegalArgumentException("unsupported isolated fixture arguments");
            }
        }

        public String application() { return nativeFixture ? NATIVE : CPU; }

        public List<String> arguments(boolean checkpoint) {
            List<String> args = new ArrayList<>(List.of("-cp", classpath, application()));
            if (nativeFixture) {
                args.addAll(List.of("--cycles=1", "--audio-loopback"));
                args.addAll(nativeFlags);
                if (checkpoint) args.add("--crac");
            } else {
                args.add(checkpoint ? "success" : "cold");
            }
            return List.copyOf(args);
        }
    }

    public record Outcome(String mode, int exitCode, long pid, Path log, String output, String reason) { }
    public record Payload(String sha256, long bytes) { }
    public record ValidImage(Path directory, Map<String, String> manifest) { }

    // Tests translate engine switches into a real, isolated Java helper process;
    // production always delegates directly to the shared child supervisor.
    @FunctionalInterface
    interface Executor {
        ChildProcess.Result execute(List<String> command, Path directory, Map<String, String> environment,
                                    Path log, Duration timeout) throws IOException, InterruptedException;
    }

    public static Map<String, String> environment(Fixture fixture, Path directory, Map<String, String> parent)
            throws IOException {
        Map<String, String> clean = new LinkedHashMap<>();
        clean.put("PATH", "/usr/bin:/bin");
        clean.put("HOME", directory.resolve("home").toString());
        clean.put("LANG", "C.UTF-8");
        if (fixture.nativeFixture()) {
            String display = parent.getOrDefault("DISPLAY", "");
            if (!display.matches(":\\d+(?:\\.\\d+)?")) {
                throw new IOException("native fixture requires an explicit local X11 DISPLAY");
            }
            clean.put("DISPLAY", display);
            clean.put("LIBGL_ALWAYS_SOFTWARE", "1");
            clean.put("ALSOFT_DRIVERS", "null");
            clean.put("XDG_RUNTIME_DIR", directory.resolve("xdg").toString());
            clean.put("MESA_SHADER_CACHE_DIR", directory.resolve("mesa-cache").toString());
            String authority = parent.getOrDefault("XAUTHORITY", "");
            if (!authority.isEmpty()) clean.put("XAUTHORITY", Path.of(authority).toRealPath().toString());
        }
        return Map.copyOf(clean);
    }

    public static List<String> base(Fixture fixture, Path directory) throws IOException {
        Path java = fixture.java().toRealPath();
        if (!Files.isRegularFile(java) || !Files.isExecutable(java)) throw new IOException("Java executable unavailable");
        List<String> args = new ArrayList<>(List.of(java.toString(), "-Duser.home=" + directory.resolve("home"),
                "-Djava.io.tmpdir=" + directory.resolve("tmp"), "-Xms32m", "-Xmx128m"));
        if (fixture.nativeFixture()) {
            args.add("--enable-native-access=ALL-UNNAMED");
            args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + directory.resolve("tmp/lwjgl"));
        }
        return List.copyOf(args);
    }

    private static Path workspace(Path root) throws IOException {
        Path directory = LauncherFiles.tempDirectory(root, root == null ? "loadonce-fixture-" : ".capture-");
        for (String name : List.of("home", "tmp", "xdg", "mesa-cache")) {
            LauncherFiles.privateDirectory(directory.resolve(name));
        }
        return directory;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void value(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    public static String fingerprint(Fixture fixture, Path directory, Map<String, String> parent) throws IOException {
        Map<String, String> clean = environment(fixture, directory, parent);
        MessageDigest digest = digest();
        value(digest, Integer.toString(SCHEMA));
        value(digest, fixture.engine());
        for (String arg : base(fixture, directory)) value(digest, arg);
        for (String arg : fixture.arguments(true)) value(digest, arg);
        for (Map.Entry<String, String> entry : clean.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            value(digest, entry.getKey());
            value(digest, entry.getValue());
        }
        for (String property : List.of("os.name", "os.arch", "os.version")) value(digest, System.getProperty(property));
        Path java = fixture.java().toRealPath();
        Path home = java.getParent().getParent();
        Set<Path> inputs = new HashSet<>(List.of(java, home.resolve("release"), home.resolve("lib"), home.resolve("conf")));
        inputs.addAll(fixture.extraInputs());
        List<Path> pending = new ArrayList<>();
        for (String part : fixture.classpath().split(Pattern.quote(File.pathSeparator))) pending.add(Path.of(part));
        Set<Path> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Path path = pending.remove(pending.size() - 1).toAbsolutePath().normalize();
            if (!seen.add(path)) continue;
            inputs.add(path);
            pending.addAll(LauncherFiles.jarDependencies(path));
        }
        if (clean.containsKey("XAUTHORITY")) inputs.add(Path.of(clean.get("XAUTHORITY")));
        for (Path path : inputs.stream().sorted().toList()) LauncherFiles.digestPath(digest, path);
        return HexFormat.of().formatHex(digest.digest());
    }

    public static Payload payload(Fixture fixture, Path directory) throws IOException {
        Path image = directory.resolve("image");
        Path expected = image.resolve(fixture.engine().equals("warp") ? "core.img" : "inventory.img");
        MessageDigest digest = digest();
        long size = 0;
        boolean substantial = false;
        for (Path tree : List.of(image, directory.resolve("tmp"))) {
            LauncherFiles.checkPrivate(tree, true);
            try (Stream<Path> stream = Files.walk(tree)) {
                for (Path path : stream.toList()) {
                    LauncherFiles.checkPrivate(path, Files.isDirectory(path) && !Files.isSymbolicLink(path));
                    if (Files.isRegularFile(path)) {
                        long count = Files.size(path);
                        size = Math.addExact(size, count);
                        substantial |= tree.equals(image) && count > 4096;
                    }
                }
            }
            LauncherFiles.digestPath(digest, tree);
        }
        LauncherFiles.checkPrivate(expected, false);
        if (Files.size(expected) == 0 || !substantial) throw new IOException("engine image is incomplete");
        Path engine = image.resolve("engine");
        if (Files.exists(engine) && !Files.readString(engine).strip().equals(fixture.engine())) {
            throw new IOException("engine image belongs to a different engine");
        }
        return new Payload(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static Outcome execute(List<String> command, Fixture fixture, Path directory,
                                   Map<String, String> parent, Duration timeout, String mode, Executor executor)
            throws IOException, InterruptedException {
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("per-process timeout must be between 1 and 60 seconds");
        }
        Path log = LauncherFiles.tempFile(directory, mode + "-", ".log");
        ChildProcess.Result result = executor.execute(List.copyOf(command), directory,
                environment(fixture, directory, parent), log, timeout);
        return new Outcome(mode, result.exitCode(), result.pid(), log, result.output(), "");
    }

    public static String boundary(Fixture fixture, Outcome result, Path directory) throws IOException {
        if (fixture.engine().equals("warp")) {
            if (result.exitCode() != 0 || !result.output().contains("warp: Checkpoint successful!")) {
                throw new IOException("Warp checkpoint did not finish successfully; inspect " + result.log());
            }
        } else {
            boolean completed = false;
            try (Stream<Path> files = Files.list(directory.resolve("image"))) {
                for (Path path : files.filter(p -> p.getFileName().toString().matches("dump\\d*\\.log")).toList()) {
                    LauncherFiles.checkPrivate(path, false);
                    completed |= Files.readString(path).contains("Dumping finished successfully");
                }
            }
            // Java reports a signal-killed CRIU checkpoint child as 128 + SIGKILL.
            if ((result.exitCode() != 0 && result.exitCode() != 137) || !completed) {
                throw new IOException("CRIU checkpoint completion evidence is missing; inspect " + result.log());
            }
        }
        if (fixture.nativeFixture()) {
            if (!result.output().contains("NATIVE_PROBE verified cycle=0") || result.output().contains("NATIVE_PROBE PASS")) {
                throw new IOException("native fixture did not stop at its prepared boundary");
            }
            return "";
        }
        Matcher initial = INITIAL.matcher(result.output());
        if (!initial.find() || !initial.group(1).equals(Long.toString(result.pid())) || result.output().contains("restored-pid=")) {
            throw new IOException("CPU fixture did not stop at its prepared boundary");
        }
        return initial.group(2);
    }

    public static Outcome capture(Fixture fixture, Path root, Duration timeout) throws IOException, InterruptedException {
        return capture(fixture, root, timeout, System.getenv());
    }

    public static Outcome capture(Fixture fixture, Path root, Duration timeout, Map<String, String> parent)
            throws IOException, InterruptedException {
        return capture(fixture, root, timeout, parent, ChildProcess::runIsolated);
    }

    @SuppressWarnings("try")
    static Outcome capture(Fixture fixture, Path rootPath, Duration timeout, Map<String, String> parent, Executor executor)
            throws IOException, InterruptedException {
        Path root = LauncherFiles.privateDirectory(rootPath);
        try (LauncherFiles.CacheLock ignored = LauncherFiles.lock(root, true)) {
            Path directory = workspace(root);
            LauncherFiles.privateDirectory(directory.resolve("image"));
            String before = fingerprint(fixture, directory, parent);
            List<String> command = new ArrayList<>(base(fixture, directory));
            command.add("-XX:CRaCEngine=" + fixture.engine());
            command.add("-XX:CRaCCheckpointTo=" + directory.resolve("image"));
            if (fixture.engine().equals("warp")) command.add("-XX:CRaCEngineOptions=checkpoint_exit_code=0");
            command.addAll(fixture.arguments(true));
            Outcome result = execute(command, fixture, directory, parent, timeout, "capture", executor);
            // Private generation ancestors prevent access while the engine writes
            // ordinary files; seal all generated children before publishing them.
            LauncherFiles.sealTree(directory.resolve("image"));
            LauncherFiles.sealTree(directory.resolve("tmp"));
            String witness = boundary(fixture, result, directory);
            Payload payload = payload(fixture, directory);
            if (!fingerprint(fixture, directory, parent).equals(before)) {
                throw new IOException("fixture inputs changed during capture; image not promoted");
            }
            Map<String, String> record = new LinkedHashMap<>();
            record.put("schema", Integer.toString(SCHEMA));
            record.put("completed", "true");
            record.put("fixture", fixture.application());
            record.put("engine", fixture.engine());
            record.put("fingerprint", before);
            record.put("generation", directory.getFileName().toString());
            record.put("sha256", payload.sha256());
            record.put("bytes", Long.toString(payload.bytes()));
            record.put("uid", LauncherFiles.ownerId());
            record.put("source_pid", Long.toString(result.pid()));
            record.put("heap_witness", witness);
            Path pending = LauncherFiles.tempFile(root, ".manifest-", ".properties");
            try {
                LauncherFiles.writeManifest(pending, record);
                LauncherFiles.sync(pending);
                for (Path tree : List.of(directory.resolve("image"), directory.resolve("tmp"))) {
                    try (Stream<Path> files = Files.walk(tree)) {
                        for (Path path : files.toList()) LauncherFiles.sync(path);
                    }
                }
                LauncherFiles.sync(directory);
                // Images retain absolute HOME/tmp/native paths. Promote only the
                // complete marker; never move or rename a captured generation.
                Files.move(pending, root.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                LauncherFiles.sync(root);
            } finally {
                Files.deleteIfExists(pending);
            }
            return result;
        }
    }

    private static long positive(Map<String, String> record, String name) throws IOException {
        try {
            if (!record.get(name).matches("[1-9]\\d*")) throw new NumberFormatException();
            long value = Long.parseLong(record.get(name));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IOException("invalid fixture manifest number", error);
        }
    }

    public static ValidImage validImage(Fixture fixture, Path root, Map<String, String> parent) throws IOException {
        Map<String, String> record = LauncherFiles.readManifest(root.resolve(MANIFEST), FIELDS);
        if (!record.get("schema").equals(Integer.toString(SCHEMA)) || !record.get("completed").equals("true")
                || !record.get("fixture").equals(fixture.application()) || !record.get("engine").equals(fixture.engine())
                || !record.get("uid").equals(LauncherFiles.ownerId())
                || !record.get("generation").matches("\\.capture-[a-zA-Z0-9_-]+")
                || !(fixture.nativeFixture() ? record.get("heap_witness").isEmpty() : record.get("heap_witness").matches("[0-9a-f-]+"))) {
            throw new IOException("incomplete or incompatible fixture manifest");
        }
        positive(record, "source_pid");
        long size = positive(record, "bytes");
        Path directory = root.resolve(record.get("generation"));
        LauncherFiles.checkPrivate(directory, true);
        for (String name : List.of("home", "tmp", "xdg", "mesa-cache")) LauncherFiles.checkPrivate(directory.resolve(name), true);
        if (!record.get("fingerprint").equals(fingerprint(fixture, directory, parent))) {
            throw new IOException("fixture inputs are incompatible");
        }
        Payload payload = payload(fixture, directory);
        if (!record.get("sha256").equals(payload.sha256()) || size != payload.bytes()) {
            throw new IOException("fixture payload is corrupt");
        }
        return new ValidImage(directory, Map.copyOf(record));
    }

    public static void verifyRestore(Fixture fixture, Outcome result, Map<String, String> record) throws IOException {
        if (result.exitCode() != 0) return;
        if (result.pid() == positive(record, "source_pid")) throw new IOException("restore did not run in a fresh process; no replay attempted");
        if (fixture.nativeFixture()) {
            if (!result.output().contains("NATIVE_PROBE verified cycle=1") || result.output().contains("NATIVE_PROBE verified cycle=0")
                    || !result.output().contains("NATIVE_PROBE PASS cycles=1") || !result.output().contains("native-release=ok")
                    || !result.output().contains("preparationCallbacks=1, restorationCallbacks=1")) {
                throw new IOException("native restore evidence is incomplete; no replay attempted");
            }
        } else {
            Matcher restored = RESTORED.matcher(result.output());
            if (!restored.find() || !restored.group(1).equals(Long.toString(result.pid()))
                    || !restored.group(2).equals(record.get("source_pid")) || !restored.group(3).equals(record.get("heap_witness"))
                    || !result.output().contains("preparationCallbacks=1, restorationCallbacks=1")) {
                throw new IOException("CPU restore evidence is incomplete; no replay attempted");
            }
        }
    }

    public static Outcome run(Fixture fixture, Path root, Duration timeout) throws IOException, InterruptedException {
        return run(fixture, root, timeout, System.getenv());
    }

    public static Outcome run(Fixture fixture, Path root, Duration timeout, Map<String, String> parent)
            throws IOException, InterruptedException {
        return run(fixture, root, timeout, parent, ChildProcess::runIsolated);
    }

    @SuppressWarnings("try")
    static Outcome run(Fixture fixture, Path rootPath, Duration timeout, Map<String, String> parent, Executor executor)
            throws IOException, InterruptedException {
        LauncherFiles.CacheLock lock = null;
        ValidImage valid;
        try {
            Path root = LauncherFiles.privateDirectory(rootPath);
            lock = LauncherFiles.lock(root, false);
            valid = validImage(fixture, root, parent);
        } catch (IOException unavailable) {
            if (lock != null) lock.close();
            Path directory = workspace(null);
            List<String> command = new ArrayList<>(base(fixture, directory));
            command.addAll(fixture.arguments(false));
            Outcome result = execute(command, fixture, directory, parent, timeout, "cold", executor);
            return new Outcome(result.mode(), result.exitCode(), result.pid(), result.log(), result.output(),
                    "compatible complete fixture image unavailable");
        }
        try (LauncherFiles.CacheLock held = lock) {
            List<String> command = new ArrayList<>(base(fixture, valid.directory()));
            command.add("-XX:CRaCRestoreFrom=" + valid.directory().resolve("image"));
            Outcome result = execute(command, fixture, valid.directory(), parent, timeout, "restore", executor);
            verifyRestore(fixture, result, valid.manifest());
            return result;
        }
    }
}
