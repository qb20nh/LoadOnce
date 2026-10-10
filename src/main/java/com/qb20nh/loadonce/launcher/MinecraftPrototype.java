package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in Linux prototype: cache the JVM before any application or mod runs. */
public final class MinecraftPrototype {
    private static final String BOOTSTRAP = "com.qb20nh.loadonce.launcher.MinecraftPrototypeBootstrap";
    private static final String MANIFEST = "minecraft-prototype.properties";
    private static final String NAMESPACES = "--user --map-root-user --net --ipc --uts --cgroup --pid --fork --mount-proc --kill-child=SIGKILL";
    private static final Set<String> FIELDS = Set.of("schema", "completed", "uid", "generation", "inputs",
            "image", "bytes", "heap_witness", "namespace_pid", "controller_pid");
    private static final Pattern CAPTURE = Pattern.compile("LOADONCE_PROTOTYPE_CAPTURE heap-witness=([0-9a-f-]+) namespace-pid=(\\d+)");
    private static final Pattern BOOT = Pattern.compile("LOADONCE_PROTOTYPE_BOOT mode=(cold|warm) heap-witness=([0-9a-f-]+) namespace-pid=(\\d+) callbacks=(0/0|1/1)");

    private MinecraftPrototype() { }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        require(System.getProperty("os.name").equals("Linux"), "Minecraft prototype requires Linux");
        Spec spec = readSpec(options.phase() == null ? options.spec() : options.generation().resolve("launch.properties"));
        Path game = LauncherFiles.privateDirectory(spec.gameDirectory());
        Path marker = game.resolve(".loadonce-prototype");
        require(Files.isRegularFile(marker), "isolated game directory requires .loadonce-prototype marker: " + game);
        LauncherFiles.checkPrivate(marker, false);
        require(Files.readString(marker).equals("LoadOnce isolated prototype\n"),
                "isolated game directory has an invalid profile marker: " + marker);
        if (options.phase() != null) {
            LauncherFiles.checkPrivate(options.generation(), true);
            insideNamespace(options, spec);
            return;
        }
        Path cache = LauncherFiles.privateDirectory(options.cache());
        try (LauncherFiles.CacheLock ignored = LauncherFiles.lock(cache, true)) {
            switch (options.action()) {
                case "train" -> train(options, spec);
                case "cold" -> cold(options, spec);
                case "warm" -> warm(options, spec, valid(options, spec));
                case "run" -> {
                    Image image;
                    try { image = valid(options, spec); }
                    catch (IOException unavailable) { cold(options, spec); return; }
                    warm(options, spec, image);
                }
                case "smoke" -> {
                    cold(options, spec);
                    train(options, spec);
                    for (int attempt = 1; attempt <= 3; attempt++) warm(options, spec, valid(options, spec));
                    System.out.println("LOADONCE_PROTOTYPE_SMOKE PASSED cold=1 warm=3");
                }
                default -> throw new IllegalArgumentException("unknown action");
            }
        }
    }

    private static void train(Options options, Spec spec) throws Exception {
        Path generation = workspace(options.cache(), ".capture-");
        LauncherFiles.privateDirectory(generation.resolve("image"));
        control(options, spec, generation, "warm");
        String before = inputs(options, spec);
        ChildProcess.Result result = namespace(options, generation, "capture");
        String output = Files.readString(generation.resolve("capture-game.log"));
        require(result.exitCode() == 0 && result.output().contains("LOADONCE_PROTOTYPE_CHILD exit=137"),
                "checkpoint failed; inspect " + generation.resolve("capture.log"));
        Matcher captured = CAPTURE.matcher(output);
        require(captured.find(), "checkpoint heap evidence missing; inspect " + generation.resolve("capture-game.log"));
        String witness = captured.group(1), namespacePid = captured.group(2);
        require(!captured.find() && Long.parseLong(namespacePid) >= 128 && !output.contains("LOADONCE_PROTOTYPE_BOOT"),
                "checkpoint did not stop before application startup");
        boolean dumped = false;
        try (var files = Files.list(generation.resolve("image"))) {
            for (Path log : files.filter(p -> p.getFileName().toString().matches("dump\\d*\\.log")).toList()) {
                dumped |= Files.readString(log).contains("Dumping finished successfully");
            }
        }
        require(dumped, "CRIU dump completion evidence missing: " + generation.resolve("image"));
        LauncherFiles.sealTree(generation.resolve("image"));
        long bytes = imageBytes(generation.resolve("image"));
        require(bytes > 1024 * 1024 && Files.size(generation.resolve("image/inventory.img")) > 0,
                "CRIU image is incomplete: " + generation.resolve("image"));
        require(before.equals(inputs(options, spec)), "checkpoint inputs changed; cache not published");
        Map<String, String> manifest = new LinkedHashMap<>();
        manifest.put("schema", "1"); manifest.put("completed", "true");
        manifest.put("uid", LauncherFiles.ownerId()); manifest.put("generation", generation.getFileName().toString());
        manifest.put("inputs", before); manifest.put("image", hash(generation.resolve("image")));
        manifest.put("bytes", Long.toString(bytes)); manifest.put("heap_witness", witness);
        manifest.put("namespace_pid", namespacePid); manifest.put("controller_pid", Long.toString(result.pid()));
        Path pending = LauncherFiles.tempFile(options.cache(), ".manifest-", ".properties");
        try {
            LauncherFiles.writeManifest(pending, manifest);
            try (var files = Files.walk(generation.resolve("image"))) {
                for (Path path : files.toList()) LauncherFiles.sync(path);
            }
            LauncherFiles.sync(pending); LauncherFiles.sync(generation);
            Files.move(pending, options.cache().resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LauncherFiles.sync(options.cache());
        } finally { Files.deleteIfExists(pending); }
        System.out.println("LOADONCE_PROTOTYPE_TRAIN PASSED image-bytes=" + bytes + " generation=" + generation);
    }

    private static void cold(Options options, Spec spec) throws Exception {
        Path directory = workspace(options.cache(), ".cold-");
        control(options, spec, directory, "cold");
        ChildProcess.Result result = namespace(options, directory, "cold");
        verifyBoot(options, result, directory, "cold", null);
    }

    private static void warm(Options options, Spec spec, Image image) throws Exception {
        control(options, spec, image.directory(), "warm");
        // A failure after this launch is final: never repeat application startup.
        ChildProcess.Result result = namespace(options, image.directory(), "warm");
        require(image.record().get("image").equals(hash(image.directory().resolve("image"))),
                "checkpoint image changed during restoration");
        verifyBoot(options, result, image.directory(), "warm", image.record());
    }

    private static void verifyBoot(Options options, ChildProcess.Result result, Path directory, String mode,
                                   Map<String, String> manifest) throws IOException {
        Path log = directory.resolve(mode + "-game.log");
        String output = Files.readString(log);
        require(result.exitCode() == 0 && result.output().contains("LOADONCE_PROTOTYPE_CHILD exit=0"),
                mode + " boot failed; inspect " + log);
        Matcher boot = BOOT.matcher(output);
        require(boot.find() && boot.group(1).equals(mode) && boot.group(4).equals(mode.equals("warm") ? "1/1" : "0/0"),
                mode + " boot evidence missing; inspect " + log);
        if (manifest != null) {
            require(boot.group(2).equals(manifest.get("heap_witness")) && boot.group(3).equals(manifest.get("namespace_pid"))
                    && result.pid() != Long.parseLong(manifest.get("controller_pid")), "restore heap/process evidence differs");
        }
        require(!boot.find() && !output.contains("LOADONCE_PROTOTYPE_CAPTURE") && !output.contains("LOADONCE_PROTOTYPE_FAILED"),
                "duplicate startup or application failure; inspect " + log);
        require(!options.verify() || output.contains("LOADONCE_PROTOTYPE_PASSED"), "game verification missing; inspect " + log);
        if (mode.equals("warm")) {
            Path preserved = directory.resolve("warm-" + result.pid() + "-game.log");
            Files.move(log, preserved, StandardCopyOption.ATOMIC_MOVE);
            log = preserved;
        }
        System.out.println("LOADONCE_PROTOTYPE_RUN PASSED mode=" + mode + " controller-pid=" + result.pid() + " log=" + log);
    }

    private static Image valid(Options options, Spec spec) throws Exception {
        Map<String, String> record = LauncherFiles.readManifest(options.cache().resolve(MANIFEST), FIELDS);
        require(record.get("schema").equals("1") && record.get("completed").equals("true")
                && record.get("uid").equals(LauncherFiles.ownerId()) && record.get("generation").matches("\\.capture-[a-zA-Z0-9_-]+")
                && record.get("heap_witness").matches("[0-9a-f-]+"), "incompatible Minecraft prototype manifest");
        for (String name : List.of("namespace_pid", "controller_pid", "bytes")) {
            try { require(Long.parseLong(record.get(name)) > 0, "invalid manifest " + name); }
            catch (NumberFormatException error) { throw new IOException("invalid manifest " + name, error); }
        }
        Path generation = options.cache().resolve(record.get("generation"));
        LauncherFiles.checkPrivate(generation, true);
        for (String name : List.of("image", "home", "tmp")) LauncherFiles.checkPrivate(generation.resolve(name), true);
        require(record.get("inputs").equals(inputs(options, spec)), "Minecraft prototype inputs changed");
        require(record.get("image").equals(hash(generation.resolve("image")))
                && Long.parseLong(record.get("bytes")) == imageBytes(generation.resolve("image")), "Minecraft prototype image corrupt");
        return new Image(generation, record);
    }

    private static Path workspace(Path cache, String prefix) throws IOException {
        Path directory = LauncherFiles.tempDirectory(cache, prefix);
        LauncherFiles.privateDirectory(directory.resolve("home"));
        LauncherFiles.privateDirectory(directory.resolve("tmp"));
        return directory;
    }

    private static void control(Options options, Spec spec, Path directory, String mode) throws IOException {
        Properties values = new Properties();
        values.putAll(spec.values());
        values.setProperty("mode", mode); values.setProperty("verify", Boolean.toString(options.verify()));
        Path control = directory.resolve("launch.properties");
        if (Files.exists(control)) LauncherFiles.checkPrivate(control, false);
        Path pending = LauncherFiles.tempFile(directory, ".launch-", ".properties");
        try {
            try (var output = Files.newOutputStream(pending)) { values.store(output, "Current prototype launch"); }
            LauncherFiles.sync(pending);
            Files.move(pending, control, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LauncherFiles.sync(directory);
        } finally { Files.deleteIfExists(pending); }
    }

    private static ChildProcess.Result namespace(Options options, Path directory, String phase) throws Exception {
        Path helper = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        List<String> command = new ArrayList<>(List.of("/usr/bin/unshare"));
        command.addAll(List.of(NAMESPACES.split(" ")));
        command.addAll(List.of(helper.toString(), "-Xms16m", "-Xmx64m", "-cp", ownClasspath(), MinecraftPrototype.class.getName(),
                "--action", options.action(), "--java", options.java().toString(), "--criu", options.criu().toString(),
                "--spec", options.spec().toString(), "--cache-dir", options.cache().toString(), "--verify", Boolean.toString(options.verify()),
                "--timeout-seconds", Long.toString(options.timeout().toSeconds()), "--namespace-phase", phase, "--generation", directory.toString()));
        return ChildProcess.runIsolated(command, directory, environment(directory), directory.resolve(phase + ".log"), options.timeout());
    }

    private static void insideNamespace(Options options, Spec spec) throws Exception {
        Path directory = options.generation();
        Map<String, String> environment = environment(directory);
        require(ChildProcess.run(List.of("/usr/bin/ip", "link", "set", "lo", "up"), directory, environment,
                directory.resolve(options.phase() + "-network.log"), Duration.ofSeconds(20)).exitCode() == 0, "private loopback setup failed");
        if (options.phase().equals("capture")) {
            require(ChildProcess.run(List.of(options.criu().toString(), "check", "--unprivileged"), directory, environment,
                    directory.resolve("capability-check.log"), Duration.ofSeconds(30)).exitCode() == 0, "CRIU private namespace capability check failed");
            Files.writeString(Path.of("/proc/sys/kernel/ns_last_pid"), "127\n");
        }
        List<String> command = new ArrayList<>(List.of(options.java().toString()));
        command.addAll(List.of("-XX:CRaCEngine=criuengine",
                "-XX:CRaCEngineOptions=criu_location=" + options.criu() + ",upstream_criu=true"));
        if (options.phase().equals("warm")) command.add("-XX:CRaCRestoreFrom=" + directory.resolve("image"));
        else {
            // Source VM options are already retained in the image. Many are
            // explicitly forbidden on a CRaC restore command line.
            command.addAll(spec.jvm());
            command.addAll(List.of("-Xshare:off", "-Xms64m", "-Xmx1536m", "-Duser.home=" + directory.resolve("home"),
                    "-Djava.io.tmpdir=" + directory.resolve("tmp")));
            if (options.phase().equals("capture")) command.add("-XX:CRaCCheckpointTo=" + directory.resolve("image"));
            command.addAll(List.of("-cp", ownClasspath() + java.io.File.pathSeparator + String.join(java.io.File.pathSeparator, spec.classpath()),
                    BOOTSTRAP, options.phase().equals("capture") ? "--checkpoint" : "--cold", "--control-file", directory.resolve("launch.properties").toString()));
        }
        ChildProcess.Result result;
        if (options.phase().equals("capture")) {
            // CRIU finishes writing the image after killing the checkpointed JVM.
            // Keep its orphaned helper alive inside our supervised PID namespace.
            List<String> capture = new ArrayList<>(List.of("/usr/bin/setsid", "--"));
            capture.addAll(command);
            result = ChildProcess.run(capture, spec.gameDirectory(), environment,
                    directory.resolve("capture-game.log"), options.timeout());
            require(result.exitCode() == 137, "checkpoint process failed; inspect " + directory.resolve("capture-game.log"));
            awaitDump(directory.resolve("image"));
        } else {
            result = ChildProcess.runIsolated(command, spec.gameDirectory(), environment,
                    directory.resolve(options.phase() + "-game.log"), options.timeout());
        }
        System.out.println("LOADONCE_PROTOTYPE_CHILD exit=" + result.exitCode());
        require(result.exitCode() == (options.phase().equals("capture") ? 137 : 0), "Minecraft prototype child failed; no replay attempted");
    }

    private static void awaitDump(Path image) throws IOException, InterruptedException {
        long started = System.nanoTime();
        while (System.nanoTime() - started < Duration.ofSeconds(30).toNanos()) {
            try (var files = Files.list(image)) {
                for (Path log : files.filter(p -> p.getFileName().toString().matches("dump\\d*\\.log")).toList()) {
                    if (Files.size(log) <= 16 * 1024 * 1024 && Files.readString(log).contains("Dumping finished successfully")) return;
                }
            }
            Thread.sleep(25);
        }
        throw new IOException("CRIU did not finish checkpoint output; inspect " + image);
    }

    private static Map<String, String> environment(Path directory) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("HOME", directory.resolve("home").toString()); values.put("TMPDIR", directory.resolve("tmp").toString());
        values.put("PATH", "/usr/bin:/bin"); values.put("LANG", "C.UTF-8");
        for (String name : List.of("DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR", "PULSE_SERVER", "PIPEWIRE_REMOTE", "DBUS_SESSION_BUS_ADDRESS")) {
            String value = System.getenv(name);
            if (value != null) values.put(name, value);
        }
        return Map.copyOf(values);
    }

    private static String inputs(Options options, Spec spec) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : List.of("minecraft-prototype-v1", NAMESPACES, System.getProperty("os.name"),
                System.getProperty("os.arch"), System.getProperty("os.version"), "-Xshare:off -Xms64m -Xmx1536m")) add(digest, value);
        add(digest, "namespace-root-uid=" + LauncherFiles.ownerId());
        String gid = Files.readString(Path.of("/proc/self/status")).lines().filter(line -> line.startsWith("Gid:"))
                .findFirst().orElseThrow(() -> new IOException("host GID mapping unavailable")).strip().split("\\s+")[1];
        add(digest, "namespace-root-gid=" + gid);
        for (var entry : spec.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            add(digest, entry.getKey()); add(digest, entry.getValue());
        }
        LauncherFiles.digestPath(digest, options.java().getParent().getParent());
        LauncherFiles.digestPath(digest, options.criu()); LauncherFiles.digestPath(digest, Path.of(ownClasspath()));
        for (String item : spec.classpath()) LauncherFiles.digestPath(digest, Path.of(item));
        LauncherFiles.digestPath(digest, spec.gameDirectory().resolve("mods"));
        for (String option : spec.jvm()) if (option.startsWith("-Dfabric.dli.config=")) {
            LauncherFiles.digestPath(digest, Path.of(option.substring("-Dfabric.dli.config=".length())));
        }
        for (var entry : environment(options.cache()).entrySet().stream().filter(e -> !Set.of("HOME", "TMPDIR").contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey()).toList()) {
            add(digest, entry.getKey()); add(digest, entry.getValue());
            if (entry.getKey().equals("XAUTHORITY")) LauncherFiles.digestPath(digest, Path.of(entry.getValue()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); LauncherFiles.digestPath(digest, path);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long imageBytes(Path image) throws IOException {
        long bytes = 0;
        try (var files = Files.walk(image)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".img")).toList()) {
                LauncherFiles.checkPrivate(path, false); bytes = Math.addExact(bytes, Files.size(path));
            }
        }
        return bytes;
    }

    private static String ownClasspath() throws Exception {
        return Path.of(MinecraftPrototype.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().toString();
    }

    private static Spec readSpec(Path path) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) <= 1024 * 1024, "invalid Minecraft prototype spec: " + path);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) values.put(name, properties.getProperty(name));
        String main = values.get("main");
        require(main != null && main.matches("[a-zA-Z_$][a-zA-Z0-9_$.]*"), "invalid prototype main class");
        Path game = absolute(values.get("gameDirectory"));
        List<String> jvm = sequence(values, "jvm"), classpath = sequence(values, "classpath");
        sequence(values, "args");
        require(!classpath.isEmpty(), "prototype classpath is empty");
        for (String item : classpath) absolute(item);
        for (String option : jvm) require(option.startsWith("-") && !option.startsWith("-javaagent")
                && !option.startsWith("-agentlib") && !option.startsWith("-agentpath") && !option.contains("CRaC")
                && !Set.of("-cp", "-classpath", "--class-path", "-jar", "-m", "--module").contains(option), "unsupported prototype JVM option: " + option);
        return new Spec(Map.copyOf(values), game, jvm, classpath);
    }

    private static List<String> sequence(Map<String, String> values, String prefix) throws IOException {
        int count;
        try { count = Integer.parseInt(values.getOrDefault(prefix + ".count", "-1")); }
        catch (NumberFormatException error) { throw new IOException("invalid " + prefix + ".count", error); }
        require(count >= 0 && count <= 4096, "invalid " + prefix + ".count");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String value = values.get(prefix + "." + index);
            require(value != null && value.indexOf('\0') < 0, "missing or invalid " + prefix + "." + index); result.add(value);
        }
        return List.copyOf(result);
    }

    private static Path absolute(String value) throws IOException {
        require(value != null && !value.isEmpty(), "absolute prototype path required");
        Path path = Path.of(value); require(path.isAbsolute(), "absolute prototype path required: " + value);
        return path.normalize();
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private record Spec(Map<String, String> values, Path gameDirectory, List<String> jvm, List<String> classpath) { }
    private record Image(Path directory, Map<String, String> record) { }
    private record Options(String action, Path java, Path criu, Path spec, Path cache, boolean verify,
                           Duration timeout, String phase, Path generation) {
        private static Options parse(String[] arguments) throws IOException {
            Map<String, String> values = new LinkedHashMap<>();
            require(arguments.length % 2 == 0, "prototype options require values");
            Set<String> accepted = Set.of("--action", "--java", "--criu", "--spec", "--cache-dir", "--verify", "--timeout-seconds", "--namespace-phase", "--generation");
            for (int i = 0; i < arguments.length; i += 2) require(accepted.contains(arguments[i])
                    && values.putIfAbsent(arguments[i], arguments[i + 1]) == null, "unknown or duplicate prototype option");
            String action = values.get("--action"), verify = values.getOrDefault("--verify", "true"), phase = values.get("--namespace-phase");
            require(action != null && Set.of("train", "cold", "warm", "run", "smoke").contains(action), "action must be train|cold|warm|run|smoke");
            require(Set.of("true", "false").contains(verify), "verify must be true|false");
            long seconds;
            try { seconds = Long.parseLong(values.getOrDefault("--timeout-seconds", "600")); }
            catch (NumberFormatException error) { throw new IOException("invalid timeout-seconds", error); }
            require(seconds >= 1 && seconds <= 3600, "timeout-seconds must be 1..3600");
            require(phase == null || Set.of("capture", "cold", "warm").contains(phase), "invalid internal namespace phase");
            require((phase == null) == !values.containsKey("--generation"), "internal phase requires generation");
            Path java = absolute(values.get("--java")).toRealPath(), criu = absolute(values.getOrDefault("--criu", "/usr/bin/criu")).toRealPath();
            require(Files.isExecutable(java) && Files.isExecutable(criu), "Java and CRIU must be executable");
            return new Options(action, java, criu, absolute(values.get("--spec")), absolute(values.get("--cache-dir")),
                    Boolean.parseBoolean(verify), Duration.ofSeconds(seconds), phase, phase == null ? null : absolute(values.get("--generation")));
        }
    }
}
