import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Own a headless hardware display for one bounded command; never attach to the desktop. */
public final class HardwareDisplaySession {
    private static final long MAX_LOG_BYTES = 16L * 1024 * 1024;
    private final Path directory;
    private final Path relativeInputModule;
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
    private final List<Path> logs = new ArrayList<>();
    private Process weston;
    private Process xwayland;
    private boolean closed;

    private HardwareDisplaySession(Path relativeInputModule) throws IOException {
        this.relativeInputModule = relativeInputModule;
        directory = Files.createTempDirectory("loadonce-native-display-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        for (String name : List.of("home", "tmp", "xdg", "cache")) {
            Files.createDirectory(directory.resolve(name),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("LANG", "C");
        environment.put("HOME", directory.resolve("home").toString());
        environment.put("TMPDIR", directory.resolve("tmp").toString());
        environment.put("XDG_RUNTIME_DIR", directory.resolve("xdg").toString());
        environment.put("XDG_CACHE_HOME", directory.resolve("cache").toString());
        environment.put("__EGL_VENDOR_LIBRARY_FILENAMES", "/usr/share/glvnd/egl_vendor.d/10_nvidia.json");
        environment.put("__GLX_VENDOR_LIBRARY_NAME", "nvidia");
        environment.put("__GL_SHADER_DISK_CACHE", "0");
        environment.put("MESA_SHADER_CACHE_DISABLE", "true");
    }

    public static void main(String[] args) throws Exception {
        int separator = 0;
        Path module = null;
        if (args.length >= 2 && args[0].equals("--relative-input-module")) {
            module = validateRelativeInputModule(Path.of(args[1]));
            separator = 2;
        }
        if (args.length < separator + 2 || !args[separator].equals("--")) {
            throw new IllegalArgumentException("Use HardwareDisplaySession.java [--relative-input-module ABSOLUTE.so] -- COMMAND [ARGUMENT ...]");
        }
        if (!System.getProperty("os.name").equals("Linux")) {
            throw new IllegalStateException("The private hardware display requires Linux");
        }
        HardwareDisplaySession session = new HardwareDisplaySession(module);
        Thread shutdown = new Thread(session::close, "private-hardware-display-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        int status;
        try {
            session.startDisplay();
            validateOwnedSession(session.directory);
            Path commandLog = session.log("command.log");
            Process command = session.start(Arrays.asList(args).subList(separator + 1, args.length),
                    session.environment, commandLog, commandLog);
            status = session.await(command, TimeUnit.MINUTES.toNanos(20), "command");
            System.out.println("HARDWARE_COMMAND exit=" + status + " log=" + commandLog);
        } catch (Exception failure) {
            System.err.println("HARDWARE_DISPLAY failed: " + failure.getMessage()
                    + "; evidence=" + session.directory);
            throw failure;
        } finally {
            session.close();
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
        if (status != 0) System.exit(status);
    }

    private void startDisplay() throws Exception {
        log("weston.log");
        Path westonOutput = log("weston-output.log");
        String socket = "loadonce-private-wayland";
        List<String> compositorCommand = new ArrayList<>(List.of("/usr/bin/weston"));
        compositorCommand.addAll(westonArguments(directory, relativeInputModule));
        weston = start(compositorCommand,
                environment, westonOutput, westonOutput);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(directory.resolve("xdg").resolve(socket), LinkOption.NOFOLLOW_LINKS)
                || relativeInputModule != null && !Files.exists(directory.resolve("xdg/loadonce-relative-input.sock"), LinkOption.NOFOLLOW_LINKS)) {
            check(deadline, "Weston private socket", false);
            Thread.sleep(50);
        }

        Path authority = privateFile("Xauthority-private");
        byte[] cookie = new byte[16];
        new SecureRandom().nextBytes(cookie);
        writeAuthority(authority, "0", cookie);
        Path displayFile = log("display-private.txt");
        Path xwaylandLog = log("xwayland.log");
        Map<String, String> waylandEnvironment = new LinkedHashMap<>(environment);
        waylandEnvironment.put("WAYLAND_DISPLAY", socket);
        xwayland = start(List.of("/usr/bin/Xwayland", "-geometry", "1280x720", "-glamor", "gl",
                "-auth", authority.toString(), "-displayfd", "1", "-nolisten", "tcp",
                "-ardelay", "60000", "-arinterval", "60000"),
                waylandEnvironment, displayFile, xwaylandLog);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String number;
        while (true) {
            check(deadline, "Xwayland display number", true);
            if (Files.size(displayFile) > 32) throw new IOException("Invalid display record: " + displayFile);
            number = Files.readString(displayFile, StandardCharsets.US_ASCII);
            if (number.matches("[0-9]+\\n")) break;
            Thread.sleep(50);
        }
        number = number.strip();
        writeAuthority(authority, number, cookie);
        Arrays.fill(cookie, (byte) 0);
        environment.put("DISPLAY", ":" + number);
        environment.put("XAUTHORITY", authority.toString());
        // Only Xwayland receives WAYLAND_DISPLAY; the command talks to the private X server.
        Path rendererLog = log("renderer.log");
        Process probe = start(List.of("/usr/bin/glxinfo", "-B"), environment, rendererLog, rendererLog);
        if (await(probe, TimeUnit.SECONDS.toNanos(30), "hardware renderer probe") != 0) {
            throw new IOException("glxinfo failed; inspect " + rendererLog);
        }
        String evidence = Files.readString(rendererLog);
        String renderer = evidence.lines().map(String::strip)
                .filter(line -> line.startsWith("OpenGL renderer string:")).findFirst().orElse("");
        boolean direct = evidence.lines().anyMatch(line -> line.strip().equals("direct rendering: Yes"));
        String lower = evidence.toLowerCase(java.util.Locale.ROOT);
        if (!direct || !renderer.matches("OpenGL renderer string: NVIDIA GeForce RTX 3060 Ti(?:/.*)?")
                || lower.contains("llvmpipe") || lower.contains("softpipe") || lower.contains("swrast")
                || lower.contains("software rasterizer")) {
            throw new IOException("Direct RTX 3060 Ti rendering was not verified; inspect " + rendererLog);
        }
        Properties marker = new Properties();
        identity(marker, "controller", ProcessHandle.current());
        identity(marker, "weston", weston.toHandle());
        identity(marker, "xwayland", xwayland.toHandle());
        marker.setProperty("workDirectory", directory.toString());
        marker.setProperty("display", environment.get("DISPLAY"));
        marker.setProperty("displayFile", displayFile.toString());
        marker.setProperty("authority", authority.toString());
        marker.setProperty("renderer", renderer);
        marker.setProperty("rendererLog", rendererLog.toString());
        if (relativeInputModule != null) marker.setProperty("relativeInputModule", relativeInputModule.toString());
        Path markerFile = privateFile("hardware-display.properties");
        try (var output = Files.newOutputStream(markerFile)) {
            marker.store(output, "Owned private headless hardware display; processes must remain live");
        }
        System.out.println("HARDWARE_DISPLAY session=" + directory + " display=" + environment.get("DISPLAY"));
        System.out.println(renderer + "; evidence=" + rendererLog);
    }

    private static void writeAuthority(Path file, String display, byte[] cookie) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeShort(65535); // FamilyWild, with a private per-session cookie.
            field(output, new byte[0]);
            field(output, display.getBytes(StandardCharsets.US_ASCII));
            field(output, "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII));
            field(output, cookie);
        }
    }

    private static void field(DataOutputStream output, byte[] value) throws IOException {
        output.writeShort(value.length);
        output.write(value);
    }

    private static void identity(Properties marker, String name, ProcessHandle process) throws IOException {
        marker.setProperty(name + "Pid", Long.toString(process.pid()));
        marker.setProperty(name + "Started", process.info().startInstant()
                .orElseThrow(() -> new IOException("Missing start identity for " + name)).toString());
    }

    /** Validate the private marker and its live PID/start identities before using this display. */
    public static void validateOwnedSession(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!root.getFileName().toString().startsWith("loadonce-native-display-")) {
            throw new IOException("Unexpected private display directory: " + root);
        }
        privatePath(root, root);
        if (!Files.getOwner(root).getName().equals(ProcessHandle.current().info().user().orElse(""))) {
            throw new IOException("Private display directory is not owned by the current user: " + root);
        }
        Path markerFile = root.resolve("hardware-display.properties");
        privatePath(root, markerFile);
        Properties marker = new Properties();
        try (var input = Files.newInputStream(markerFile)) { marker.load(input); }
        if (!root.toString().equals(marker.getProperty("workDirectory"))) {
            throw new IOException("Private display marker directory does not match " + root);
        }
        Path module = marker.containsKey("relativeInputModule")
                ? validateRelativeInputModule(Path.of(marker.getProperty("relativeInputModule"))) : null;
        for (String name : List.of("controller", "weston", "xwayland")) {
            try {
                ProcessHandle process = ProcessHandle.of(Long.parseLong(marker.getProperty(name + "Pid")))
                        .orElseThrow(() -> new IOException("Missing owned " + name + " process"));
                Instant started = Instant.parse(marker.getProperty(name + "Started"));
                if (!process.isAlive() || !process.info().startInstant().filter(started::equals).isPresent()
                        || !process.info().user().equals(ProcessHandle.current().info().user())) {
                    throw new IOException("Owned " + name + " process identity is no longer live");
                }
                if (!name.equals("controller")) {
                    long parent = Long.parseLong(marker.getProperty("controllerPid"));
                    if (process.parent().map(ProcessHandle::pid).orElse(-1L) != parent) {
                        throw new IOException("Display process is not a child of its controller: " + name);
                    }
                    String executable = name.equals("weston") ? "/usr/bin/weston" : "/usr/bin/Xwayland";
                    List<String> expected = name.equals("weston")
                            ? westonArguments(root, module)
                            : List.of("-geometry", "1280x720", "-glamor", "gl", "-auth",
                                    root.resolve("Xauthority-private").toString(), "-displayfd", "1", "-nolisten", "tcp",
                                    "-ardelay", "60000", "-arinterval", "60000");
                    if (!process.info().command().filter(executable::equals).isPresent()
                            || !process.info().arguments().map(Arrays::asList).filter(expected::equals).isPresent()) {
                        throw new IOException("Owned display process has unexpected executable or arguments: " + name);
                    }
                }
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new IOException("Invalid owned " + name + " process identity", invalid);
            }
        }
        for (String field : List.of("authority", "displayFile", "rendererLog")) {
            Path file = Path.of(marker.getProperty(field)).toAbsolutePath().normalize();
            if (!root.equals(file.getParent())) throw new IOException("Evidence outside private display: " + field);
            privatePath(root, file);
        }
        if (module != null) {
            Path inputSocket = root.resolve("xdg/loadonce-relative-input.sock");
            privatePath(root, inputSocket);
            if ((((Number) Files.getAttribute(inputSocket, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 0170000) != 0140000) {
                throw new IOException("Private relative input endpoint is not a Unix socket: " + inputSocket);
            }
        }
        Path displayFile = Path.of(marker.getProperty("displayFile"));
        if (Files.size(displayFile) > 32
                || !Files.readString(displayFile).matches("[0-9]+\\n")
                || !(":" + Files.readString(displayFile).strip()).equals(marker.getProperty("display"))) {
            throw new IOException("Private display number does not match its marker");
        }
    }

    private static List<String> westonArguments(Path root, Path module) {
        List<String> arguments = new ArrayList<>(List.of("--backend=headless", "--renderer=gl", "--no-config", "--fake-seat",
                "--shell=kiosk-shell.so", "--socket=loadonce-private-wayland", "--width=1280", "--height=720",
                "--idle-time=0", "--log=" + root.resolve("weston.log")));
        if (module != null) arguments.add("--modules=" + module);
        return arguments;
    }

    static Path validateRelativeInputModule(Path module) throws IOException {
        if (!module.isAbsolute() || !module.normalize().equals(module) || !module.toString().endsWith(".so")
                || module.toString().contains(",")) {
            throw new IOException("Relative input module must be an absolute normalized .so path without commas: " + module);
        }
        for (Path part = module; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Symlink in relative input module path: " + part);
        }
        if (!Files.isRegularFile(module, LinkOption.NOFOLLOW_LINKS) || Files.size(module) == 0
                || Files.size(module) > 4L * 1024 * 1024
                || !Files.getOwner(module).getName().equals(ProcessHandle.current().info().user().orElse(""))
                || Files.getPosixFilePermissions(module).stream().anyMatch(permission -> !permission.name().startsWith("OWNER_"))) {
            throw new IOException("Relative input module must be an owned nonempty regular .so up to 4 MiB with owner-only permissions; build in a private directory and chmod 600: " + module);
        }
        return module;
    }

    private static void privatePath(Path root, Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(file).equals(Files.getOwner(root))
                || Files.getPosixFilePermissions(file).stream().anyMatch(permission ->
                        permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))) {
            throw new IOException("Expected an owner-only private display path: " + file);
        }
    }

    private Path privateFile(String name) throws IOException {
        return Files.createFile(directory.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    private Path log(String name) throws IOException {
        Path file = privateFile(name);
        logs.add(file);
        return file;
    }

    private synchronized Process start(List<String> command, Map<String, String> env,
            Path stdout, Path stderr) throws IOException {
        if (closed) throw new IOException("Private hardware display is already closed");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        if (stdout.equals(stderr)) builder.redirectErrorStream(true);
        Process process = builder.start();
        owned.put(process.pid(), process.toHandle());
        return process;
    }

    private synchronized void sample() {
        for (ProcessHandle process : new ArrayList<>(owned.values())) {
            process.descendants().forEach(child -> owned.putIfAbsent(child.pid(), child));
        }
    }

    private void check(long deadline, String operation, boolean requireXwayland) throws IOException {
        sample();
        if (System.nanoTime() >= deadline) throw new IOException("Timed out waiting for " + operation);
        if (weston != null && !weston.isAlive()) throw new IOException("Weston exited; inspect " + directory);
        if (requireXwayland && xwayland != null && !xwayland.isAlive()) {
            throw new IOException("Xwayland exited; inspect " + directory);
        }
        for (Path file : logs) {
            if (Files.size(file) > MAX_LOG_BYTES) throw new IOException("Private log exceeded 16 MiB: " + file);
        }
    }

    private int await(Process process, long duration, String operation) throws Exception {
        long deadline = System.nanoTime() + duration;
        while (process.isAlive()) {
            check(deadline, operation, true);
            process.waitFor(50, TimeUnit.MILLISECONDS);
        }
        sample();
        check(deadline, operation, true);
        return process.exitValue();
    }

    private synchronized void close() {
        if (closed) return;
        closed = true;
        sample();
        List<ProcessHandle> processes = new ArrayList<>(owned.values());
        java.util.Collections.reverse(processes);
        for (ProcessHandle process : processes) if (process.isAlive()) process.destroy();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException interrupted) { break; }
        }
        for (ProcessHandle process : processes) if (process.isAlive()) process.destroyForcibly();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException interrupted) { break; }
        }
        if (processes.stream().anyMatch(ProcessHandle::isAlive)) {
            System.err.println("HARDWARE_DISPLAY cleanup incomplete; inspect owned process identities in " + directory);
        }
    }
}
