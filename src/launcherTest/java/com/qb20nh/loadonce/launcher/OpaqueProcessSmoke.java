package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Experimental external OS checkpoint: initialized state receives no CRaC callbacks. */
public final class OpaqueProcessSmoke {
    private static final Duration TIMEOUT = Duration.ofSeconds(600);
    private static final String SELF = OpaqueProcessSmoke.class.getName();
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    private OpaqueProcessSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--phase")) {
            Path directory = Path.of(args[2]).toRealPath();
            LauncherFiles.checkPrivate(directory, true);
            switch (args[1]) {
                case "capture" -> capture(directory);
                case "restore" -> restore(directory);
                case "tree" -> tree(directory);
                default -> throw new IllegalArgumentException("Unknown opaque smoke phase");
            }
            return;
        }
        Path work = null, spec = null, xvfb = null;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Expected option/value pairs");
            switch (args[i]) {
                case "--work-directory" -> work = Path.of(args[i + 1]);
                case "--spec" -> spec = Path.of(args[i + 1]);
                case "--xvfb" -> xvfb = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown opaque smoke option: " + args[i]);
            }
        }
        require(work != null && (spec == null) == (xvfb == null),
                "Use --work-directory PATH, optionally with --spec PATH --xvfb PATH");
        require(System.getProperty("os.name").equals("Linux"), "Opaque process smoke requires Linux");
        Path directory = LauncherFiles.tempDirectory(work, "opaque-");
        Properties config = new Properties();
        config.setProperty("java", JAVA.toRealPath().toString());
        List<String> classpath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator, -1)) {
            classpath.add(Path.of(entry).toAbsolutePath().normalize().toString());
        }
        config.setProperty("classpath", String.join(java.io.File.pathSeparator, classpath));
        if (spec != null) {
            LauncherFiles.checkPrivate(spec, false);
            Properties launch = read(spec);
            launch.setProperty("mode", "cold");
            launch.setProperty("verify", "true");
            store(directory.resolve("launch.properties"), launch);
            config.setProperty("xvfb", xvfb.toRealPath().toString());
        }
        store(directory.resolve("config.properties"), config);
        privateText(directory.resolve(".loadonce-opaque"), "loadonce-opaque-v1\n");
        for (String name : List.of("home", "tmp", "xdg", "mesa-cache", "image", "no-plugins")) {
            LauncherFiles.privateDirectory(directory.resolve(name));
        }
        System.out.println("OPAQUE_SMOKE workspace=" + directory + " target=" + (spec == null ? "fixture" : "minecraft"));
        namespace(directory, "capture", "capture.log");
        Properties ready = read(directory.resolve(spec == null ? "ready.properties" : "probe-ready.properties"));
        for (int cycle = 1; cycle <= 3; cycle++) {
            for (String prefix : List.of("helper-completed-", "probe-resumed-", "probe-passed-", "mod-resumed-")) {
                require(!Files.exists(directory.resolve(prefix + cycle + ".properties")), "Opaque generation evidence already exists");
            }
            if (spec == null) {
                Properties resume = new Properties();
                resume.setProperty("generation", Integer.toString(cycle));
                resume.setProperty("minimum_pause_millis", "1000");
                store(directory.resolve("continue.properties"), resume);
                Files.deleteIfExists(directory.resolve("result.properties"));
            } else {
                privateText(directory.resolve("release"), cycle + "\n");
            }
            config.setProperty("generation", Integer.toString(cycle));
            config.setProperty("logOffset", Long.toString(Files.size(directory.resolve("application.log"))));
            store(directory.resolve("config.properties"), config);
            // The elapsed pause must be observable in the retained application.
            Thread.sleep(1100);
            namespace(directory, "restore", "restore-" + cycle + ".log");
            Properties completed = read(directory.resolve("helper-completed-" + cycle + ".properties"));
            require(Integer.toString(cycle).equals(completed.getProperty("generation"))
                    && "0".equals(completed.getProperty("exit")), "Restored application completion is missing");
            Path result = directory.resolve(spec == null ? "result.properties" : "probe-resumed-" + cycle + ".properties");
            Properties resumed = read(result);
            if (spec == null) {
                require("passed".equals(resumed.getProperty("fixture_validation")), "Opaque fixture did not pass: " + result);
                require(ready.getProperty("heap_witness").equals(resumed.getProperty("heap_witness")),
                        "Opaque fixture heap was reinitialized: " + result);
                require(Integer.toString(cycle).equals(resumed.getProperty("generation"))
                        && "1".equals(resumed.getProperty("initialization_count")), "Opaque fixture replay detected: " + result);
                for (String key : List.of("object_identity", "parked_stack_witness")) {
                    require(ready.getProperty(key).equals(resumed.getProperty(key)), "Retained state changed: " + key);
                }
                store(directory.resolve("result-" + cycle + ".properties"), resumed);
            } else {
                validateGame(directory, ready, resumed, cycle);
            }
            System.out.println("OPAQUE_SMOKE restored-generation=" + cycle + " evidence=" + result);
        }
        System.out.println("OPAQUE_SMOKE PASSED workspace=" + directory);
    }

    private static void namespace(Path directory, String phase, String log) throws Exception {
        Properties config = read(directory.resolve("config.properties"));
        List<String> command = new ArrayList<>(List.of("/usr/bin/unshare", "--user", "--map-root-user",
                "--net", "--ipc", "--uts", "--cgroup", "--pid", "--fork", "--mount-proc", "--kill-child=SIGKILL"));
        command.addAll(helper(config, phase, directory));
        ChildProcess.Result result = ChildProcess.runIsolated(command, directory, environment(directory),
                directory.resolve(log), TIMEOUT);
        require(result.exitCode() == 0, "Opaque " + phase + " failed; no replay attempted. Inspect " + directory.resolve(log));
    }

    private static void setup(Path directory) throws Exception {
        run(List.of("/usr/bin/ip", "link", "set", "lo", "up"), directory, "network.log");
        run(List.of("/usr/bin/mount", "--bind", directory.resolve("tmp").toString(), "/tmp"), directory, "mount.log");
        // Mesa can open render nodes even when llvmpipe is selected. Hide those
        // nodes in this private mount namespace so no host GPU state can enter.
        if (Files.isDirectory(Path.of("/dev/dri"))) {
            run(List.of("/usr/bin/mount", "-t", "tmpfs", "-o", "size=4096,mode=0700", "tmpfs", "/dev/dri"),
                    directory, "device-mask.log");
        }
        if (Files.exists(Path.of("/dev/udmabuf"))) {
            run(List.of("/usr/bin/mount", "--bind", "/dev/null", "/dev/udmabuf"), directory, "dmabuf-mask.log");
        }
        try (var devices = Files.newDirectoryStream(Path.of("/dev"), path -> path.getFileName().toString().startsWith("nvidia")
                || path.getFileName().toString().equals("kfd"))) {
            for (Path device : devices) {
                if (Files.isDirectory(device)) {
                    run(List.of("/usr/bin/mount", "-t", "tmpfs", "-o", "size=4096,mode=0700", "tmpfs", device.toString()),
                            directory, "vendor-device-mask.log");
                } else {
                    run(List.of("/usr/bin/mount", "--bind", "/dev/null", device.toString()), directory, "vendor-device-mask.log");
                }
            }
        }
    }

    private static void capture(Path directory) throws Exception {
        setup(directory);
        run(List.of("/usr/bin/criu", "check", "--unprivileged"), directory, "check.log");
        Files.writeString(Path.of("/proc/sys/kernel/ns_last_pid"), "127\n");
        Properties config = read(directory.resolve("config.properties"));
        List<String> command = new ArrayList<>(List.of("/usr/bin/setsid", "--"));
        command.addAll(helper(config, "tree", directory));
        Process tree = start(command, directory, environment(directory), directory.resolve("tree.log"));
        try {
            Path ready = directory.resolve(config.containsKey("xvfb") ? "probe-ready.properties" : "ready.properties");
            awaitFile(ready, tree, TIMEOUT);
            inspectDescriptors(tree.toHandle(), directory);
            run(List.of("/usr/bin/criu", "dump", "--tree", Long.toString(tree.pid()), "--images-dir",
                    directory.resolve("image").toString(), "--unprivileged", "--shell-job", "--file-locks",
                    "--libdir", directory.resolve("no-plugins").toString(),
                    "-v4", "-o", "dump.log"), directory, "dump-command.log");
            require(tree.waitFor(30, TimeUnit.SECONDS), "Captured tree did not exit after dump");
            require(Files.isRegularFile(directory.resolve("image/inventory.img")), "CRIU image is incomplete");
            System.out.println("OPAQUE_SMOKE captured-tree=" + tree.pid());
        } finally {
            if (tree.isAlive()) tree.destroyForcibly();
        }
    }

    private static void restore(Path directory) throws Exception {
        setup(directory);
        Properties config = read(directory.resolve("config.properties"));
        Files.deleteIfExists(directory.resolve("restored.pid"));
        run(List.of("/usr/bin/criu", "restore", "--images-dir", directory.resolve("image").toString(),
                "--unprivileged", "--shell-job", "--file-locks", "--restore-detached", "--pidfile",
                directory.resolve("restored.pid").toString(), "--libdir", directory.resolve("no-plugins").toString(),
                "-v4", "-o", "restore.log"), directory, "restore-command.log");
        long pid = Long.parseLong(Files.readString(directory.resolve("restored.pid")).trim());
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        // PID 1 can retain exited children as zombies. /proc state is the reliable completion evidence here.
        while (Files.exists(Path.of("/proc", Long.toString(pid))) && System.nanoTime() < deadline) {
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+");
            if (fields[0].equals("Z")) {
                require(fields.length > 49 && fields[49].equals("0"), "Restored tree exited unsuccessfully");
                break;
            }
            Thread.sleep(100);
        }
        require(System.nanoTime() < deadline, "Restored tree timed out; no replay attempted");
        if (config.containsKey("xvfb")) {
            require(newLog(directory, config).contains("LOADONCE_PROTOTYPE_PASSED"),
                    "Minecraft world acceptance failed: " + directory.resolve("application.log"));
        } else {
            require(Files.isRegularFile(directory.resolve("result.properties")), "Opaque fixture result is missing");
        }
    }

    private static void tree(Path directory) throws Exception {
        Properties config = read(directory.resolve("config.properties"));
        Map<String, String> environment = environment(directory);
        Process display = null;
        try {
            List<String> app = new ArrayList<>(List.of(config.getProperty("java"), "-Xshare:off",
                    "-XX:+PerfDisableSharedMem", "-XX:+DisableAttachMechanism"));
            Path cwd = directory;
            if (config.containsKey("xvfb")) {
                // All X11 peers are in the captured tree. No host GPU or audio connection is inherited.
                display = start(List.of(config.getProperty("xvfb"), ":117", "-screen", "0", "854x480x24",
                        "-nolisten", "tcp", "-ac", "-noreset"),
                        directory, environment, directory.resolve("display.log"));
                awaitFile(Path.of("/tmp/.X11-unix/X117"), display, Duration.ofSeconds(20));
                environment.put("DISPLAY", ":117");
                Properties spec = read(directory.resolve("launch.properties"));
                int count = Integer.parseInt(spec.getProperty("jvm.count"));
                for (int i = 0; i < count; i++) app.add(spec.getProperty("jvm." + i));
                List<String> paths = new ArrayList<>();
                count = Integer.parseInt(spec.getProperty("classpath.count"));
                for (int i = 0; i < count; i++) paths.add(spec.getProperty("classpath." + i));
                app.addAll(List.of("-Xms64m", "-Xmx1536m", "-Duser.home=" + directory.resolve("home"),
                        "-Djnidispatch.preserve=true",
                        "-Djava.io.tmpdir=" + directory.resolve("tmp"), "-Dloadonce.prototype.opaqueDirectory=" + directory,
                        "-cp", String.join(java.io.File.pathSeparator, paths), MinecraftPrototypeBootstrap.class.getName(),
                        "--cold", "--control-file", directory.resolve("launch.properties").toString()));
                cwd = Path.of(spec.getProperty("gameDirectory"));
            } else {
                app.addAll(List.of("-XX:+UseSerialGC", "-XX:-UsePerfData", "-Xms16m", "-Xmx32m", "-cp", config.getProperty("classpath"),
                        OpaqueStateFixture.class.getName(), directory.toString()));
            }
            Process application = start(app, cwd, environment, directory.resolve("application.log"));
            require(application.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "Opaque application timed out");
            require(application.exitValue() == 0, "Opaque application exited " + application.exitValue());
        } finally {
            if (display != null && display.isAlive()) {
                display.destroy();
                if (!display.waitFor(5, TimeUnit.SECONDS)) display.destroyForcibly();
            }
        }
        Properties current = read(directory.resolve("config.properties"));
        String generation = current.getProperty("generation");
        require(generation != null && generation.matches("[1-3]"), "Current opaque generation is absent");
        Properties completed = new Properties();
        completed.setProperty("generation", generation);
        completed.setProperty("exit", "0");
        store(directory.resolve("helper-completed-" + generation + ".properties"), completed);
    }

    private static List<String> helper(Properties config, String phase, Path directory) {
        return List.of(config.getProperty("java"), "-Xshare:off", "-XX:+PerfDisableSharedMem", "-XX:-UsePerfData", "-XX:+UseSerialGC", "-Xms16m", "-Xmx64m",
                "-cp", config.getProperty("classpath"), SELF, "--phase", phase, directory.toString());
    }

    private static Process start(List<String> command, Path cwd, Map<String, String> environment, Path log) throws IOException {
        if (!Files.exists(log)) privateText(log, "");
        LauncherFiles.checkPrivate(log, false);
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.redirectInput(Path.of("/dev/null").toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return builder.start();
    }

    private static Map<String, String> environment(Path directory) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("PATH", "/usr/bin:/bin"); result.put("LANG", "C.UTF-8");
        result.put("HOME", directory.resolve("home").toString());
        result.put("TMPDIR", directory.resolve("tmp").toString());
        result.put("XDG_RUNTIME_DIR", directory.resolve("xdg").toString());
        result.put("MESA_SHADER_CACHE_DIR", directory.resolve("mesa-cache").toString());
        result.put("LIBGL_ALWAYS_SOFTWARE", "1"); result.put("GALLIUM_DRIVER", "llvmpipe");
        result.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
        result.put("SDL_VIDEODRIVER", "x11"); result.put("ALSOFT_DRIVERS", "null");
        return result;
    }

    private static void run(List<String> command, Path directory, String log) throws Exception {
        ChildProcess.Result result = ChildProcess.run(command, directory, environment(directory), directory.resolve(log), Duration.ofSeconds(60));
        require(result.exitCode() == 0, "Command failed (" + result.exitCode() + "): " + command.get(0)
                + "; inspect " + directory.resolve(log) + " and " + directory.resolve("image"));
    }

    private static void awaitFile(Path path, Process child, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
        require(Files.exists(path), "Readiness missing: " + path + "; childAlive=" + child.isAlive());
    }

    private static void inspectDescriptors(ProcessHandle root, Path directory) throws IOException {
        List<ProcessHandle> processes = new ArrayList<>(List.of(root));
        processes.addAll(root.descendants().toList());
        StringBuilder inventory = new StringBuilder();
        boolean hardware = false;
        Object nullDevice = Files.getAttribute(Path.of("/dev/null"), "unix:rdev");
        for (ProcessHandle process : processes) {
            Path descriptors = Path.of("/proc", Long.toString(process.pid()), "fd");
            try (var entries = Files.newDirectoryStream(descriptors)) {
                for (Path entry : entries) {
                    String target;
                    try { target = Files.readSymbolicLink(entry).toString(); }
                    catch (java.nio.file.NoSuchFileException closed) { continue; }
                    Object device = Files.getAttribute(entry, "unix:rdev");
                    inventory.append(process.pid()).append('/').append(entry.getFileName()).append(' ').append(target)
                            .append(" rdev=").append(device).append('\n');
                    hardware |= (target.startsWith("/dev/dri/") || target.startsWith("/dev/nvidia")
                            || target.equals("/dev/kfd") || target.equals("/dev/udmabuf")) && !nullDevice.equals(device);
                }
            }
        }
        privateText(directory.resolve("descriptors.txt"), inventory.toString());
        require(!hardware, "Opaque software boundary contains a host GPU descriptor; inspect " + directory.resolve("descriptors.txt"));
    }

    private static String newLog(Path directory, Properties config) throws IOException {
        Path log = directory.resolve("application.log");
        long offset = Long.parseLong(config.getProperty("logOffset"));
        long size = Files.size(log) - offset;
        require(size >= 0 && size <= 16 * 1024 * 1024, "Opaque generation log is invalid: " + log);
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate((int) size);
        try (var channel = Files.newByteChannel(log)) {
            channel.position(offset);
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
        }
        return new String(bytes.array(), 0, bytes.position(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void validateGame(Path directory, Properties ready, Properties resumed, int cycle) throws IOException {
        Properties passed = read(directory.resolve("probe-passed-" + cycle + ".properties"));
        require(Integer.toString(cycle).equals(passed.getProperty("generation"))
                && ready.getProperty("probe_identity").equals(passed.getProperty("probe_identity"))
                && "1".equals(passed.getProperty("calls")), "Current opaque world acceptance is absent");
        require(Integer.toString(cycle).equals(resumed.getProperty("generation")), "Opaque menu generation mismatch");
        require("1".equals(resumed.getProperty("calls")), "Opaque menu was initialized again");
        for (String key : List.of("probe_identity", "client_identity", "menu_identity", "renderer_identity",
                "render_target_identity", "window_handle")) {
            require(ready.getProperty(key).equals(resumed.getProperty(key)), "Opaque menu state changed: " + key);
        }
        require(Long.parseLong(resumed.getProperty("wall_millis")) > Long.parseLong(ready.getProperty("wall_millis")) + 1000,
                "Opaque wall clock did not advance across capture");
        require(Long.parseLong(resumed.getProperty("nano_time")) > Long.parseLong(ready.getProperty("nano_time")),
                "Opaque monotonic clock did not advance across capture");
        Properties modReady = read(directory.resolve("mod-ready.properties"));
        Properties modResumed = read(directory.resolve("mod-resumed-" + cycle + ".properties"));
        require(Integer.toString(cycle).equals(modResumed.getProperty("generation")) && "1".equals(modResumed.getProperty("calls")),
                "Opaque mod was initialized again");
        for (String key : List.of("identity", "thread_identity", "thread_id", "thread_object_identity")) {
            require(modReady.getProperty(key).equals(modResumed.getProperty(key)), "Opaque mod state changed: " + key);
        }
        require(Long.parseLong(modResumed.getProperty("counter")) > Long.parseLong(modResumed.getProperty("counter_before_release")),
                "Opaque mod background thread did not progress");
        require(Long.parseLong(modResumed.getProperty("counter_before_release")) >= Long.parseLong(modReady.getProperty("counter")),
                "Opaque mod counter reset across capture");
    }

    private static Properties read(Path path) throws IOException {
        LauncherFiles.checkPrivate(path, false);
        Properties result = new Properties();
        try (var input = Files.newInputStream(path)) { result.load(input); }
        return result;
    }

    private static void store(Path path, Properties properties) throws IOException {
        if (!Files.exists(path)) privateText(path, "");
        LauncherFiles.checkPrivate(path, false);
        try (var output = Files.newOutputStream(path)) { properties.store(output, "Opaque process smoke"); }
    }

    private static void privateText(Path path, String text) throws IOException {
        if (!Files.exists(path)) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        LauncherFiles.checkPrivate(path, false);
        Files.writeString(path, text, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
