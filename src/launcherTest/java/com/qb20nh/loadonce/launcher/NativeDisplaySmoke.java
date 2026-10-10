package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Opt-in native checks on a private software display or the caller's Linux desktop. */
public final class NativeDisplaySmoke {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
    private static final String CLEAN_PATH = "/usr/bin:/bin";
    private static final String CLEANUP = "NATIVE_PROBE cleanup=tracked-handles-zero native-release=ok";

    private NativeDisplaySmoke() { }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        if (options.help()) {
            usage();
            return;
        }
        int exit = execute(options);
        if (exit != 0) System.exit(exit);
    }

    private static int execute(Options options) throws Exception {
        return execute(options, System.getenv());
    }

    static int execute(Options options, Map<String, String> parent) throws Exception {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (options.desktop()) validateDesktop(parent, System.getProperty("os.name"));
        Path xvfb = options.desktop() ? null : executable(options.xvfb(), cwd);
        Path xauth = options.desktop() ? null : executable("xauth", cwd);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        List<String> command = options.command().isEmpty()
                ? probeCommand(java, options) : options.command();
        Session session = new Session();
        Thread shutdown = new Thread(session::closeOnShutdown, "loadonce-native-display-cleanup");
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
        } catch (IllegalStateException | SecurityException failure) {
            session.close();
            throw new IOException("Cannot register native display cleanup");
        }
        try {
            Map<String, String> environment = environment(session.directory, java, options, parent);
            if (options.desktop()) {
                System.out.println("NATIVE_SMOKE display=host-desktop software-gl=not-forced");
            } else {
                Path authority = LauncherFiles.tempFile(session.directory, "Xauthority-", "");
                environment.put("XAUTHORITY", authority.toString());
                byte[] random = new byte[16];
                new SecureRandom().nextBytes(random);
                String cookie = HexFormat.of().formatHex(random);
                Arrays.fill(random, (byte) 0);
                authorize(session, xauth, authority, ":0", cookie, environment, cwd);

                Path displayOutput = LauncherFiles.tempFile(session.directory, "display-", ".txt");
                Path displayError = LauncherFiles.tempFile(session.directory, "Xvfb-", ".log");
                session.startDisplay(List.of(xvfb.toString(), "-displayfd", "1", "-screen", "0", "640x480x24",
                        "-nolisten", "tcp", "-auth", authority.toString(), "-noreset"),
                        cwd, environment, displayOutput, displayError);
                String display = waitForDisplay(session.display, displayOutput, displayError);
                authorize(session, xauth, authority, display, cookie, environment, cwd);
                environment.put("DISPLAY", display);
                System.out.println("NATIVE_SMOKE display=" + display + " software-gl=true");
            }
            System.out.println("NATIVE_SMOKE audio=" + (options.systemAudio() ? "system-default" : "loopback")
                    + " physical-playback=not-measured");

            Path output = LauncherFiles.tempFile(session.directory, "command-", ".log");
            ChildProcess.Result result = session.run(command, cwd, environment, output, COMMAND_TIMEOUT);
            System.out.print(result.output());
            if (!options.command().isEmpty()) return result.exitCode();

            require(line(result.output(), CLEANUP), "native probe did not release all tracked handles");
            if (options.invalidShader()) {
                require(result.exitCode() != 0
                                && line(result.output(), "NATIVE_PROBE FAIL coordinator-state=STOPPED")
                                && line(result.output(), "NATIVE_PROBE failure-stop-verified=true")
                                && !result.output().contains("NATIVE_PROBE PASS"),
                        "invalid shader did not prove stopped restoration and native cleanup");
                System.out.println("NATIVE_SMOKE PASS expected-failure=invalid-shader");
            } else {
                require(result.exitCode() == 0 && line(result.output(), "NATIVE_PROBE PASS cycles=" + options.cycles())
                                && !result.output().contains("NATIVE_PROBE FAIL"),
                        "native reconstruction did not pass");
                System.out.println("NATIVE_SMOKE PASS cycles=" + options.cycles());
            }
            return 0;
        } finally {
            try {
                session.close();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException | SecurityException ignored) {
                    // The registered hook owns cleanup during JVM shutdown.
                }
            }
        }
    }

    private static void authorize(Session session, Path xauth, Path authority, String display, String cookie,
                                  Map<String, String> environment, Path cwd) throws Exception {
        Path log = LauncherFiles.tempFile(session.directory, "xauth-", ".log");
        ChildProcess.Result result = session.run(List.of(xauth.toString(), "-f", authority.toString(), "add",
                display, "MIT-MAGIC-COOKIE-1", cookie), cwd, environment, log, AUTH_TIMEOUT);
        require(result.exitCode() == 0, "xauth did not create the private display authorization");
        LauncherFiles.checkPrivate(authority, false);
    }

    private static String waitForDisplay(Future<ChildProcess.Result> server, Path output, Path error)
            throws Exception {
        long started = System.nanoTime();
        while (System.nanoTime() - started < Duration.ofSeconds(10).toNanos()) {
            if (server.isDone()) {
                result(server);
                throw new IOException("Xvfb exited before reporting a ready display: " + Files.readString(error));
            }
            long bytes = Files.size(output);
            require(bytes <= 32, "Xvfb returned an invalid display number");
            if (bytes > 0) {
                String number = Files.readString(output, StandardCharsets.US_ASCII);
                // Wait for the complete -displayfd record, including its newline.
                if (number.endsWith("\n")) {
                    number = number.strip();
                    require(number.matches("[0-9]{1,10}"), "Xvfb returned an invalid display number");
                    try {
                        require(Integer.parseInt(number) >= 0, "Xvfb returned an invalid display number");
                    } catch (NumberFormatException invalid) {
                        throw new IOException("Xvfb returned an invalid display number");
                    }
                    return ":" + number;
                }
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new IOException("Xvfb did not report a ready display within 10 seconds: " + Files.readString(error));
    }

    static Map<String, String> environment(Path root, Path java, Options options, Map<String, String> parent)
            throws IOException {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", CLEAN_PATH);
        environment.put("HOME", LauncherFiles.privateDirectory(root.resolve("home")).toString());
        environment.put("LANG", "C.UTF-8");
        environment.put("JAVA_HOME", java.getParent().getParent().toString());
        environment.put("TMPDIR", LauncherFiles.privateDirectory(root.resolve("tmp")).toString());
        if (options.desktop()) {
            for (String key : List.of("DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR")) {
                inherit(environment, parent, key);
            }
            if (environment.containsKey("DISPLAY") && !environment.containsKey("XAUTHORITY")) {
                inheritHomeFile(environment, parent, "XAUTHORITY", ".Xauthority");
            }
        } else {
            environment.put("XDG_RUNTIME_DIR", LauncherFiles.privateDirectory(root.resolve("xdg")).toString());
            environment.put("LIBGL_ALWAYS_SOFTWARE", "1");
        }
        environment.put("MESA_SHADER_CACHE_DIR", LauncherFiles.privateDirectory(root.resolve("mesa-cache")).toString());
        if (options.systemAudio()) {
            for (String key : List.of("PULSE_SERVER", "PULSE_COOKIE", "PIPEWIRE_REMOTE", "PIPEWIRE_RUNTIME_DIR")) {
                inherit(environment, parent, key);
            }
            if (!environment.containsKey("PULSE_COOKIE")) {
                inheritHomeFile(environment, parent, "PULSE_COOKIE", ".config/pulse/cookie");
            }
        } else {
            environment.put("ALSOFT_DRIVERS", "null");
        }
        return environment;
    }

    private static void inherit(Map<String, String> environment, Map<String, String> parent, String key) {
        String value = parent.get(key);
        if (value != null && !value.isBlank()) environment.put(key, value);
    }

    private static void inheritHomeFile(Map<String, String> environment, Map<String, String> parent,
                                        String key, String relative) {
        String home = parent.get("HOME");
        if (home != null && !home.isBlank()) {
            Path file = Path.of(home).resolve(relative);
            if (Files.isRegularFile(file) && Files.isReadable(file)) environment.put(key, file.toString());
        }
    }

    static void validateDesktop(Map<String, String> parent, String osName) throws IOException {
        require("Linux".equals(osName), "--desktop requires Linux; use the private Xvfb mode on this platform");
        String display = parent.getOrDefault("DISPLAY", "");
        String wayland = parent.getOrDefault("WAYLAND_DISPLAY", "");
        require(!display.isBlank() || !wayland.isBlank(),
                "--desktop requires DISPLAY or WAYLAND_DISPLAY from the current desktop session");
        String runtime = parent.getOrDefault("XDG_RUNTIME_DIR", "");
        require(wayland.isBlank() || !runtime.isBlank(),
                "--desktop with WAYLAND_DISPLAY requires XDG_RUNTIME_DIR from the current desktop session");
        if (!runtime.isBlank()) {
            require(Path.of(runtime).isAbsolute() && Files.isDirectory(Path.of(runtime))
                            && Files.isReadable(Path.of(runtime)),
                    "--desktop XDG_RUNTIME_DIR must name an existing readable absolute directory");
        }
    }

    static List<String> probeCommand(Path java, Options options) throws IOException {
        Path file = options.classpathFile().toAbsolutePath().normalize();
        require(Files.isRegularFile(file) && Files.size(file) <= 1024 * 1024, "native classpath file is missing or too large");
        String classpath = Files.readString(file).strip();
        require(!classpath.isEmpty() && classpath.indexOf('\n') < 0 && classpath.indexOf('\r') < 0
                        && classpath.indexOf('\0') < 0, "native classpath file must contain one nonempty classpath");
        List<String> command = new ArrayList<>(List.of(java.toString(), "--enable-native-access=ALL-UNNAMED",
                "-cp", classpath, "com.qb20nh.loadonce.prototype.NativeRestorationProbe",
                "--cycles=" + options.cycles()));
        if (!options.systemAudio()) command.add("--audio-loopback");
        if (options.reconfigure()) command.add("--reconfigure");
        if (options.invalidShader()) command.add("--invalid-shader-on-restore");
        return List.copyOf(command);
    }

    private static Path executable(String name, Path cwd) throws IOException {
        if (name.contains("/")) {
            Path path = cwd.resolve(name).normalize();
            require(Files.isRegularFile(path) && Files.isExecutable(path), "required native display executable is unavailable");
            return path.toRealPath();
        }
        for (String directory : CLEAN_PATH.split(":")) {
            Path path = Path.of(directory, name);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toRealPath();
        }
        throw new IOException("required native display executable is unavailable: " + name);
    }

    private static boolean line(String output, String expected) {
        return output.lines().anyMatch(expected::equals);
    }

    private static ChildProcess.Result result(Future<ChildProcess.Result> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IOException("native display worker failed", cause);
        }
    }

    private static void require(boolean condition, String diagnostic) throws IOException {
        if (!condition) throw new IOException(diagnostic);
    }

    private static void usage() {
        System.out.println("Usage: NativeDisplaySmoke [--xvfb PATH] [--classpath-file PATH] [--cycles 1..100]"
                + " [--desktop [--system-audio]] [--reconfigure] [--invalid-shader] [--command COMMAND ARGS...]");
        System.out.println("Default: private Xvfb, software GL, loopback audio. --desktop uses the current Linux desktop;"
                + " --system-audio opens its default audio device. Physical playback is not measured.");
    }

    record Options(String xvfb, Path classpathFile, int cycles, boolean desktop, boolean systemAudio, boolean reconfigure,
                           boolean invalidShader, List<String> command, boolean help) {
        static Options parse(String[] arguments) throws IOException {
            String xvfb = System.getenv("LOADONCE_XVFB");
            if (xvfb == null || xvfb.isBlank()) xvfb = "Xvfb";
            Path classpath = Path.of("build/native-probe-classpath.txt");
            int cycles = 10;
            boolean reconfigure = false;
            boolean invalidShader = false;
            boolean desktop = false;
            boolean systemAudio = false;
            boolean explicitXvfb = false;
            List<String> command = List.of();
            for (int index = 0; index < arguments.length; index++) {
                String argument = arguments[index];
                if (argument.equals("--help") || argument.equals("-h")) {
                    return new Options(xvfb, classpath, cycles, desktop, systemAudio, reconfigure, invalidShader, command, true);
                } else if (argument.equals("--command")) {
                    require(index + 1 < arguments.length, "--command requires an executable");
                    command = List.copyOf(Arrays.asList(arguments).subList(index + 1, arguments.length));
                    break;
                } else if (argument.equals("--reconfigure")) {
                    reconfigure = true;
                } else if (argument.equals("--invalid-shader")) {
                    invalidShader = true;
                } else if (argument.equals("--desktop")) {
                    desktop = true;
                } else if (argument.equals("--system-audio")) {
                    systemAudio = true;
                } else if (argument.equals("--xvfb") || argument.equals("--classpath-file") || argument.equals("--cycles")
                        || argument.startsWith("--xvfb=") || argument.startsWith("--classpath-file=")
                        || argument.startsWith("--cycles=")) {
                    int equals = argument.indexOf('=');
                    String key = equals < 0 ? argument : argument.substring(0, equals);
                    String value;
                    if (equals < 0) {
                        require(index + 1 < arguments.length, key + " requires a value");
                        value = arguments[++index];
                    } else {
                        value = argument.substring(equals + 1);
                    }
                    require(!value.isBlank(), key + " requires a value");
                    if (key.equals("--xvfb")) {
                        xvfb = value;
                        explicitXvfb = true;
                    }
                    else if (key.equals("--classpath-file")) classpath = Path.of(value);
                    else {
                        try {
                            cycles = Integer.parseInt(value);
                        } catch (NumberFormatException invalid) {
                            throw new IOException("cycles must be between 1 and 100");
                        }
                    }
                } else {
                    throw new IOException("unknown native display option");
                }
            }
            require(cycles >= 1 && cycles <= 100, "cycles must be between 1 and 100");
            require(command.isEmpty() || !invalidShader, "--invalid-shader requires the native probe");
            require(!systemAudio || desktop, "--system-audio requires --desktop to access the current audio server");
            require(!desktop || !explicitXvfb, "--desktop cannot be combined with --xvfb; it uses the current display");
            return new Options(xvfb, classpath, cycles, desktop, systemAudio, reconfigure, invalidShader, command, false);
        }
    }

    /** Cancellation waits for the shared supervisor to reap its direct children. */
    private static final class Session implements AutoCloseable {
        final Path directory = LauncherFiles.tempDirectory(null, "loadonce-native-smoke-");
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private Future<ChildProcess.Result> display;
        private Future<ChildProcess.Result> child;
        private boolean closed;

        Session() throws IOException { }

        synchronized void startDisplay(List<String> command, Path cwd, Map<String, String> environment,
                                       Path output, Path error) throws IOException {
            require(!closed, "native display session is closed");
            Map<String, String> copy = Map.copyOf(environment);
            display = workers.submit(() -> ChildProcess.run(command, cwd, copy, output, error, COMMAND_TIMEOUT.plusSeconds(20)));
        }

        ChildProcess.Result run(List<String> command, Path cwd, Map<String, String> environment,
                                Path log, Duration timeout) throws Exception {
            Future<ChildProcess.Result> running;
            synchronized (this) {
                require(!closed, "native display session is closed");
                Map<String, String> copy = Map.copyOf(environment);
                running = workers.submit(() -> ChildProcess.runIsolated(command, cwd, copy, log, timeout));
                child = running;
            }
            return result(running);
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            if (child != null) child.cancel(true);
            if (display != null) display.cancel(true);
            workers.shutdownNow();
            boolean interrupted = Thread.interrupted();
            long started = System.nanoTime();
            try {
                while (!workers.isTerminated()) {
                    long remaining = TimeUnit.SECONDS.toNanos(12) - (System.nanoTime() - started);
                    require(remaining > 0, "native display children did not finish cleanup");
                    try {
                        workers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException cancelled) {
                        interrupted = true;
                    }
                }
                LauncherFiles.deleteTree(directory);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        void closeOnShutdown() {
            try {
                close();
            } catch (IOException failure) {
                System.err.println("NATIVE_SMOKE cleanup failed: " + failure.getMessage());
            }
        }
    }
}
