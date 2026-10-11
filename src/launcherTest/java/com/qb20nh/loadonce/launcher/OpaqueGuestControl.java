package com.qb20nh.loadonce.launcher;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** External guest UI control. Does not load or inspect application classes. */
public final class OpaqueGuestControl {
    private final ProcessHandle application;
    private final Path log;
    private final Robot robot;
    private final Rectangle screen;
    private final String identity = UUID.randomUUID().toString();
    private final String start;
    private final String boot;
    private final List<Long> frozen = new ArrayList<>();
    private boolean exitDiagnosticsCollected;

    private OpaqueGuestControl(long pid, Path log) throws Exception {
        require("Linux".equals(System.getProperty("os.name")) && ":117".equals(System.getenv("DISPLAY"))
                && "1".equals(System.getenv("LIBGL_ALWAYS_SOFTWARE"))
                && Files.readString(Path.of("/app/.loadonce-opaque-guest")).equals("LoadOnce opaque guest\n"),
                "Opaque control requires the isolated LoadOnce guest display");
        require(Files.exists(Path.of("/dev/ttyS1")) && log.equals(Path.of("/tmp/loadonce-game.log")),
                "Opaque control requires its guest serial port and private game log");
        application = ProcessHandle.of(pid).orElseThrow(() -> new IOException("Application PID is absent"));
        require(application.isAlive(), "Application exited before control startup");
        this.log = log;
        start = startTicks(pid);
        boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip();
        robot = new Robot();
        robot.setAutoDelay(50);
        robot.setAutoWaitForIdle(true);
        screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        require(screen.width == 1280 && screen.height == 720, "Unexpected opaque guest display dimensions");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Use OpaqueGuestControl APPLICATION_PID GAME_LOG");
        var control = new OpaqueGuestControl(Long.parseLong(args[0]), Path.of(args[1]));
        System.out.println("OPAQUE_READY " + control.state());
        for (String line; (line = readLine()) != null;) {
            try {
                if (control.command(line)) return;
            } catch (Exception failure) {
                System.out.println("OPAQUE_ERROR " + encode(failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            }
        }
        throw new IOException("Opaque control input closed before normal application exit");
    }

    private boolean command(String line) throws Exception {
        if (line.equals("STATE")) {
            System.out.println("OPAQUE_STATE " + state());
        } else if (line.equals("SCREEN")) {
            var bytes = new ByteArrayOutputStream();
            require(ImageIO.write(robot.createScreenCapture(screen), "png", bytes), "PNG writer unavailable");
            require(bytes.size() <= 4 * 1024 * 1024, "Private screenshot exceeds 4 MiB");
            System.out.println("OPAQUE_SCREEN " + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } else if (line.equals("LOG")) {
            byte[] diagnosticFailure = new byte[0];
            if (!application.isAlive()) {
                try { collectExitDiagnostics(); }
                catch (IOException failure) {
                    String message = String.valueOf(failure.getMessage());
                    diagnosticFailure = ("\nLOADONCE_EXIT_DIAGNOSTICS unavailable: "
                            + message.substring(0, Math.min(256, message.length())) + "\n").getBytes(StandardCharsets.UTF_8);
                }
            }
            byte[] bytes;
            try (var input = Files.newInputStream(log)) {
                long size = Files.size(log);
                int limit = 128 * 1024 - diagnosticFailure.length;
                if (size > limit) input.skipNBytes(size - limit);
                var output = new ByteArrayOutputStream();
                output.write(input.readNBytes(limit));
                output.write(diagnosticFailure);
                bytes = output.toByteArray();
            }
            System.out.println("OPAQUE_LOG " + Base64.getEncoder().encodeToString(bytes));
        } else if (line.startsWith("CLOCK ")) {
            long seconds = Long.parseLong(line.substring(6));
            require(seconds >= 1_700_000_000L && seconds < 4_000_000_000L, "Guest clock is outside the supported range");
            require(!frozen.isEmpty(), "Stop the application before correcting the guest wall clock");
            run(List.of("/usr/bin/date", "-u", "-s", "@" + seconds));
            System.out.println("OPAQUE_CLOCK " + System.currentTimeMillis());
        } else if (line.equals("FREEZE")) {
            require(application.isAlive() && frozen.isEmpty(), "Application is absent or already stopped");
            run(List.of("/bin/busybox", "kill", "-STOP", Long.toString(application.pid())));
            frozen.add(application.pid());
            awaitStopped(application.pid());
            for (var child : application.descendants().toList()) {
                if (child.isAlive()) {
                    run(List.of("/bin/busybox", "kill", "-STOP", Long.toString(child.pid())));
                    frozen.add(child.pid());
                    awaitStopped(child.pid());
                }
            }
            System.out.println("OPAQUE_FROZEN " + state());
        } else if (line.equals("THAW")) {
            require(!frozen.isEmpty(), "Application is not stopped");
            for (int index = frozen.size() - 1; index >= 0; index--) {
                long pid = frozen.get(index);
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                    run(List.of("/bin/busybox", "kill", "-CONT", Long.toString(pid)));
            }
            frozen.clear();
            System.out.println("OPAQUE_THAWED " + state());
        } else if (line.equals("QUIT")) {
            require(!application.isAlive(), "Exit Minecraft through its private UI before shutting down the guest");
            System.out.println("OPAQUE_EXIT " + state());
            return true;
        } else {
            require(application.isAlive() && frozen.isEmpty(), "Application is absent or stopped");
            input(line);
            robot.delay(200);
            System.out.println("OPAQUE_INPUT_OK");
        }
        return false;
    }

    private void input(String line) throws Exception {
        if (line.matches("CLICK [0-9]{1,4} [0-9]{1,4}")) {
            String[] parts = line.split(" ");
            int x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]);
            require(x < screen.width && y < screen.height, "Click is outside the private display");
            robot.mouseMove(x, y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } else if (line.matches("KEY (ENTER|ESCAPE|TAB|BACKSPACE)")) {
            int key = switch (line.substring(4)) {
                case "ENTER" -> KeyEvent.VK_ENTER;
                case "ESCAPE" -> KeyEvent.VK_ESCAPE;
                case "TAB" -> KeyEvent.VK_TAB;
                case "BACKSPACE" -> KeyEvent.VK_BACK_SPACE;
                default -> throw new IOException("Unsupported key");
            };
            robot.keyPress(key);
            robot.keyRelease(key);
        } else if (line.matches("TEXT [A-Za-z0-9 /_.~:-]{1,160}")) {
            for (char character : line.substring(5).toCharArray()) {
                boolean shift = character >= 'A' && character <= 'Z' || "_~:".indexOf(character) >= 0;
                int key = switch (character) {
                    case '_' -> KeyEvent.VK_MINUS;
                    case '~' -> KeyEvent.VK_BACK_QUOTE;
                    case ':' -> KeyEvent.VK_SEMICOLON;
                    default -> KeyEvent.getExtendedKeyCodeForChar(character);
                };
                if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
                robot.keyPress(key);
                robot.keyRelease(key);
                if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
            }
        } else throw new IOException("Use CLICK X Y, KEY ENTER/ESCAPE/TAB/BACKSPACE, or bounded ASCII TEXT");
        robot.waitForIdle();
    }

    private String state() throws Exception {
        var properties = new Properties();
        properties.setProperty("control.uuid", identity);
        properties.setProperty("app.pid", Long.toString(application.pid()));
        properties.setProperty("app.start", start);
        properties.setProperty("app.alive", Boolean.toString(application.isAlive()));
        properties.setProperty("app.frozen", Boolean.toString(!frozen.isEmpty()));
        properties.setProperty("boot.uuid", boot);
        properties.setProperty("clock.millis", Long.toString(System.currentTimeMillis()));
        properties.setProperty("log.bytes", Long.toString(Files.exists(log) ? Files.size(log) : 0));
        if (application.isAlive()) require(start.equals(startTicks(application.pid())), "Application PID was reused");
        if (!frozen.isEmpty()) require(stopped(application.pid()), "Application threads are not stopped");
        var output = new ByteArrayOutputStream();
        properties.store(output, "Opaque application continuity");
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static String startTicks(long pid) throws IOException {
        String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
        return stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19];
    }

    private void collectExitDiagnostics() throws IOException {
        if (exitDiagnosticsCollected) return;
        exitDiagnosticsCollected = true;
        var evidence = new StringBuilder("\nLOADONCE_EXIT_DIAGNOSTICS\n");
        for (Path file : List.of(Path.of("/proc/meminfo"), Path.of("/proc/" + application.pid() + "/stat"))) {
            if (Files.isRegularFile(file)) evidence.append(file).append('\n').append(Files.readString(file)).append('\n');
        }
        Path kernel = Path.of("/prototype/exit-kernel.log");
        try {
            var process = new ProcessBuilder("/bin/busybox", "sh", "-c",
                    "/bin/busybox dmesg | /bin/busybox tail -c 32768")
                    .redirectErrorStream(true).redirectOutput(kernel.toFile()).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            evidence.append("kernel log\n").append(tail(kernel, 32768)).append('\n');
        } catch (Exception failure) {
            evidence.append("kernel diagnostics unavailable: ").append(failure.getClass().getSimpleName()).append('\n');
        }
        Path game = Path.of(System.getProperty("user.dir"));
        int reports = 0;
        for (Path directory : List.of(game, game.resolve("crash-reports"))) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            String pattern = directory.equals(game) ? "hs_err_pid*.log" : "*.txt";
            try (var files = Files.newDirectoryStream(directory, pattern)) {
                for (Path file : files) {
                    if (reports == 4) break;
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    try (var input = Files.newInputStream(file)) {
                        evidence.append(file.getFileName()).append('\n')
                                .append(new String(input.readNBytes(16384), StandardCharsets.UTF_8)).append('\n');
                    }
                    reports++;
                }
            }
        }
        Files.writeString(log, evidence, StandardOpenOption.APPEND);
    }

    private static String tail(Path file, int limit) throws IOException {
        try (var input = Files.newInputStream(file)) {
            long size = Files.size(file);
            if (size > limit) input.skipNBytes(size - limit);
            return new String(input.readNBytes(limit), StandardCharsets.UTF_8);
        }
    }

    private static boolean stopped(long pid) throws IOException {
        try (var tasks = Files.newDirectoryStream(Path.of("/proc/" + pid + "/task"))) {
            boolean present = false;
            for (Path task : tasks) {
                present = true;
                String stat = Files.readString(task.resolve("stat"));
                String state = stat.substring(stat.lastIndexOf(')') + 2).split(" ")[0];
                if (!state.equals("T") && !state.equals("t")) return false;
            }
            return present;
        }
    }

    private static void awaitStopped(long pid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (stopped(pid)) return;
            Thread.sleep(10);
        }
        throw new IOException("Application process did not stop: " + pid);
    }

    private static void run(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Guest command timed out: " + String.join(" ", command));
        }
        require(process.exitValue() == 0, "Guest command failed: " + String.join(" ", command)
                + "; exit=" + process.exitValue());
    }

    private static String readLine() throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int value; (value = System.in.read()) >= 0;) {
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8);
            require(bytes.size() < 4096, "Opaque control input exceeds 4096 bytes");
            bytes.write(value);
        }
        return null;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
