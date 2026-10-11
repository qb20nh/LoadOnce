import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Run only inside an owned, authenticated LoadOnce display session. */
class RunFabricPackIsolated {
    private static long launchNanos;
    private static boolean menuRecorded;
    private static final Path PRISM_ROOT = Path.of("/run/media/cash/Windows/LoadOnce-fabric-tests-2026-10-11/prism");
    private record Pack(String instance, int javaMajor, int minimum, int maximum) {
        String javaExecutable() { return "/usr/lib/jvm/java-" + javaMajor + "-openjdk/bin/java"; }
    }

    private static Pack pack(String key) {
        return switch (key) {
            case "prominence" -> new Pack("loadonce-prominence-ii-4.1.3", 17, 1024, 5120);
            case "fabulously" -> new Pack("loadonce-fabulously-optimized-6.5.0", 21, 256, 1536);
            case "spectral" -> new Pack("loadonce-spectral-creation-7.1.1", 17, 256, 2304);
            default -> throw new IllegalArgumentException("Unknown pack: " + key);
        };
    }

    public static void main(String[] args) throws Exception {
        boolean send = args.length >= 2 && args[1].equals("--send");
        if (args.length < 1 || (send ? args.length < 4 || args.length > 19
                : args.length > 4)) {
            throw new IllegalArgumentException("Use RunFabricPackIsolated.java PRIVATE_PRISM_ROOT [--pack prominence|fabulously|spectral] [--display-check], or PRIVATE_PRISM_ROOT --send RUN_DIRECTORY 'COMMAND'...");
        }
        require("Linux".equals(System.getProperty("os.name")), "Private display checks require Linux");
        Path root = Path.of(args[0]);
        require(root.isAbsolute() && root.normalize().equals(PRISM_ROOT), "Only the selected isolated Windows-volume Prism root is permitted");
        rejectSymlinks(root);
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "Private Prism root is missing: " + root);
        root = root.toRealPath();
        if (send) {
            sendInput(root, Path.of(args[2]), java.util.Arrays.asList(args).subList(3, args.length));
            return;
        }
        Pack pack = pack("prominence");
        boolean displayCheck = false, selected = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--display-check") && !displayCheck) displayCheck = true;
            else if (args[i].equals("--pack") && !selected && i + 1 < args.length) {
                pack = pack(args[++i]);
                selected = true;
            } else throw new IllegalArgumentException("Invalid isolated launch option: " + args[i]);
        }
        guardDisplay(displayCheck);
        Path instance = root.resolve("instances").resolve(pack.instance());
        Path config = instance.resolve("instance.cfg");
        rejectSymlinks(config);
        rejectSymlinks(instance.resolve("minecraft"));
        rejectSymlinks(instance.resolve(".minecraft"));
        require(Files.isDirectory(instance, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS), "Installed private pack profile is missing: " + instance);
        repairJavaConfig(config, pack);
        if (displayCheck) {
            Path output = ownedOutput(root);
            checkDisplay(output);
            System.out.println("PRIVATE_DISPLAY PASS input=text+mouse screenshot=" + output.resolve("display-check.png"));
            return;
        }
        Path accounts = root.resolve("accounts.json");
        rejectSymlinks(accounts);
        require(Files.isRegularFile(accounts, LinkOption.NOFOLLOW_LINKS) && Files.size(accounts) > 0,
                "Private Prism has no cached account state. Full offline launch requires a Minecraft-owning Microsoft account. "
                        + "Decide whether to sign in separately within the private display; credentials will not be copied from another launcher root.");
        System.out.println("Private account file exists; its contents were not read and license validity remains Prism's responsibility.");
        initializePrismConfig(root);
        runPrism(root, ownedOutput(root), pack);
    }

    private static void guardDisplay(boolean displayCheck) throws Exception {
        var env = System.getenv();
        for (String key : List.of("WAYLAND_DISPLAY", "WAYLAND_SOCKET", "DBUS_SESSION_BUS_ADDRESS", "PULSE_COOKIE")) {
            require(!env.containsKey(key), "Refusing inherited desktop/audio environment variable: " + key);
        }
        String display = env.getOrDefault("DISPLAY", "");
        require(display.matches(":[0-9]+"), "NativeDisplaySmoke private DISPLAY is required");
        Path authority = Path.of(env.getOrDefault("XAUTHORITY", ""));
        require(authority.isAbsolute(), "Private XAUTHORITY file is required");
        rejectSymlinks(authority);
        require(Files.isRegularFile(authority, LinkOption.NOFOLLOW_LINKS) && Files.size(authority) > 0,
                "Private display authorization is missing");
        Path session = authority.getParent();
        String name = session.getFileName().toString();
        require(name.startsWith("loadonce-native-smoke-") || name.startsWith("loadonce-native-display-"),
                "XAUTHORITY must belong to a NativeDisplaySmoke private session");
        require(authority.getFileName().toString().startsWith("Xauthority-"), "Unexpected private authorization filename");
        boolean displayMatches = false;
        try (var files = Files.list(session)) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("display-")
                    && path.getFileName().toString().endsWith(".txt")).toList()) {
                rejectSymlinks(file);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 32) {
                    String number = Files.readString(file, StandardCharsets.US_ASCII);
                    if (number.matches("[0-9]+\n") && display.equals(":" + number.strip())) displayMatches = true;
                }
            }
        }
        require(displayMatches, "DISPLAY does not match NativeDisplaySmoke's private Xvfb displayfd record");
        for (var permission : Files.getPosixFilePermissions(session)) {
            require(permission.name().startsWith("OWNER_"), "Native display session directory must be private");
        }
        for (var permission : Files.getPosixFilePermissions(authority)) {
            require(permission.name().startsWith("OWNER_"), "Native display authorization file must be private");
        }
        require(Files.getOwner(session).equals(Files.getOwner(authority)), "Display authorization owner differs from session owner");
        for (var entry : Map.of("HOME", "home", "XDG_RUNTIME_DIR", "xdg", "TMPDIR", "tmp").entrySet()) {
            Path path = Path.of(env.getOrDefault(entry.getKey(), ""));
            require(path.isAbsolute() && path.normalize().equals(session.resolve(entry.getValue())),
                    entry.getKey() + " must use NativeDisplaySmoke's private session path");
            rejectSymlinks(path);
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), "Private session directory missing: " + path);
        }
        Path hardware = session.resolve("hardware-display.properties");
        if (Files.exists(hardware, LinkOption.NOFOLLOW_LINKS)) {
            guardHardwareDisplay(hardware, session, display, authority);
            require(!env.containsKey("LIBGL_ALWAYS_SOFTWARE") && !env.containsKey("GALLIUM_DRIVER"),
                    "Hardware display must not force a software renderer");
        } else {
            require("1".equals(env.get("LIBGL_ALWAYS_SOFTWARE")), "Private display must use NativeDisplaySmoke software rendering");
        }
        guardAudio(session, Files.exists(hardware, LinkOption.NOFOLLOW_LINKS), !displayCheck);
    }

    private static void guardAudio(Path session, boolean hardware, boolean required) throws Exception {
        var expected = Map.of(
                "PIPEWIRE_RUNTIME_DIR", session.resolve("xdg").toString(),
                "PIPEWIRE_REMOTE", "loadonce-audio",
                "PIPEWIRE_CONFIG_DIR", session.resolve("audio/config").toString(),
                "PULSE_RUNTIME_PATH", session.resolve("xdg/pulse").toString(),
                "PULSE_SERVER", "unix:" + session.resolve("xdg/pulse/native"),
                "PULSE_CLIENTCONFIG", session.resolve("audio/config/pulse-client.conf").toString(),
                "PULSE_SINK", "loadonce_sink", "PULSE_SOURCE", "loadonce_sink.monitor",
                "ALSOFT_DRIVERS", "pulse");
        Path marker = session.resolve("audio-session.properties");
        boolean privateAudio = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
        for (var entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("PULSE_") || key.startsWith("PIPEWIRE_") || key.startsWith("ALSOFT_")
                    || key.startsWith("ALSA_") || key.startsWith("JACK_") || key.equals("SDL_AUDIODRIVER")) {
                require(privateAudio && expected.containsKey(key) && expected.get(key).equals(entry.getValue()),
                        "Refusing inherited or unexpected audio environment variable: " + key);
            }
        }
        require(privateAudio || !required, "Game launch requires the owned PrivateAudioSession wrapper");
        if (!privateAudio) return;
        require(hardware, "Private audio requires the validated hardware display session");
        for (var entry : expected.entrySet()) {
            require(entry.getValue().equals(System.getenv(entry.getKey())), "Private audio environment differs: " + entry.getKey());
        }
        rejectSymlinks(marker);
        require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.size(marker) <= 8192
                        && Files.getPosixFilePermissions(marker).stream().allMatch(p -> p.name().startsWith("OWNER_")),
                "Private audio marker is missing, oversized, or not private");
        var state = new Properties();
        try (var input = Files.newInputStream(marker)) { state.load(input); }
        require(session.toString().equals(state.getProperty("workDirectory"))
                        && expected.get("PULSE_SERVER").equals(state.getProperty("pulseServer"))
                        && expected.get("PIPEWIRE_REMOTE").equals(state.getProperty("pipewireRemote")),
                "Private audio marker does not match this session");
        long controller = Long.parseLong(state.getProperty("controllerPid", "-1"));
        boolean owned = false;
        for (var parent = ProcessHandle.current().parent(); parent.isPresent(); parent = parent.get().parent()) {
            if (parent.get().pid() == controller) { owned = true; break; }
        }
        require(owned, "Private audio controller must own this helper");
        for (String name : List.of("controller", "server")) {
            var process = ProcessHandle.of(Long.parseLong(state.getProperty(name + "Pid", "-1")))
                    .orElseThrow(() -> new IOException("Private audio process is absent: " + name));
            require(process.isAlive() && process.info().startInstant().orElseThrow().toString()
                            .equals(state.getProperty(name + "Started")), "Private audio process identity changed: " + name);
            if (name.equals("server")) {
                require(process.parent().map(p -> p.pid() == controller).orElse(false)
                                && process.info().command().orElse("").equals("/usr/bin/pipewire"),
                        "Private audio controller does not own the expected PipeWire server");
            }
        }
        for (Path path : List.of(session.resolve("audio"), session.resolve("audio/config"), session.resolve("xdg/pulse"),
                session.resolve("audio/config/pulse-client.conf"))) {
            rejectSymlinks(path);
            require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                            && Files.getOwner(path).equals(Files.getOwner(session))
                            && Files.getPosixFilePermissions(path).stream().allMatch(p -> p.name().startsWith("OWNER_")),
                    "Private audio path is missing or not private: " + path);
        }
    }

    private static void guardHardwareDisplay(Path marker, Path session, String display, Path authority) throws Exception {
        rejectSymlinks(marker);
        require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.size(marker) <= 8192,
                "Private hardware display marker is missing or oversized");
        require(Files.getPosixFilePermissions(marker).stream().allMatch(p -> p.name().startsWith("OWNER_")),
                "Private hardware display marker must be private");
        var state = new Properties();
        try (var input = Files.newInputStream(marker)) { state.load(input); }
        require(session.toString().equals(state.getProperty("workDirectory"))
                && display.equals(state.getProperty("display")) && authority.toString().equals(state.getProperty("authority")),
                "Hardware display marker does not match the private session");
        long controller = Long.parseLong(state.getProperty("controllerPid", "-1"));
        boolean owned = false;
        for (var parent = ProcessHandle.current().parent(); parent.isPresent(); parent = parent.get().parent()) {
            if (parent.get().pid() == controller) { owned = true; break; }
        }
        require(owned, "Hardware display controller must own this helper");
        List<String> westonArguments = new ArrayList<>(List.of("--backend=headless", "--renderer=gl", "--no-config", "--fake-seat",
                "--shell=kiosk-shell.so", "--socket=loadonce-private-wayland", "--width=1280", "--height=720",
                "--idle-time=0", "--log=" + session.resolve("weston.log")));
        if (state.containsKey("relativeInputModule")) {
            Path module = Path.of(state.getProperty("relativeInputModule"));
            require(module.isAbsolute() && module.normalize().equals(module) && module.toString().endsWith(".so")
                    && !module.toString().contains(","), "Expected an absolute normalized private .so module path without commas");
            rejectSymlinks(module);
            require(Files.isRegularFile(module, LinkOption.NOFOLLOW_LINKS) && Files.size(module) > 0
                    && Files.size(module) <= 4L * 1024 * 1024 && Files.getOwner(module).equals(Files.getOwner(session))
                    && Files.getPosixFilePermissions(module).stream().allMatch(p -> p.name().startsWith("OWNER_")),
                    "Private relative input module must be an owned nonempty .so up to 4 MiB with owner-only permissions");
            westonArguments.add("--modules=" + module);
        }
        for (String name : List.of("controller", "weston", "xwayland")) {
            var process = ProcessHandle.of(Long.parseLong(state.getProperty(name + "Pid", "-1")))
                    .orElseThrow(() -> new IOException("Private hardware process is absent: " + name));
            require(process.isAlive() && process.info().startInstant().orElseThrow().toString()
                    .equals(state.getProperty(name + "Started")), "Private hardware process identity changed: " + name);
            if (!name.equals("controller")) {
                require(process.parent().map(p -> p.pid() == controller).orElse(false),
                        "Hardware controller does not own " + name);
                require(process.info().command().orElse("").equals(name.equals("weston") ? "/usr/bin/weston" : "/usr/bin/Xwayland"),
                        "Unexpected private hardware executable: " + name);
                if (name.equals("weston")) require(java.util.Arrays.asList(process.info().arguments().orElse(new String[0]))
                        .equals(westonArguments), "Private Weston must use only the owned headless display arguments and selected input module");
            }
        }
        Path renderer = Path.of(state.getProperty("rendererLog", ""));
        require(renderer.isAbsolute() && renderer.getParent().equals(session), "Private renderer log is outside its session");
        rejectSymlinks(renderer);
        require(Files.isRegularFile(renderer, LinkOption.NOFOLLOW_LINKS) && Files.size(renderer) <= 32768,
                "Private renderer evidence is missing or oversized");
        String evidence = Files.readString(renderer);
        require(evidence.contains("direct rendering: Yes") && evidence.contains("NVIDIA GeForce RTX 3060 Ti")
                && !evidence.toLowerCase(java.util.Locale.ROOT).contains("llvmpipe"),
                "Private display does not prove direct RTX 3060 Ti rendering");
    }

    private static void repairJavaConfig(Path config, Pack pack) throws Exception {
        require(Files.isExecutable(Path.of(pack.javaExecutable())), "Required Java executable is missing: " + pack.javaExecutable());
        require(Files.size(config) <= 64 * 1024, "Private instance.cfg exceeds 64 KiB");
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("JavaPath", pack.javaExecutable());
        changes.put("OverrideJavaLocation", "true");
        changes.put("OverrideJavaArgs", "true");
        changes.put("JvmArgs", "-XX:+UseG1GC");
        changes.put("OverrideWindow", "true");
        changes.put("MinecraftWinWidth", "1280");
        changes.put("MinecraftWinHeight", "720");
        changes.put("OverrideMemory", "true");
        changes.put("MinMemAlloc", Integer.toString(pack.minimum()));
        changes.put("MaxMemAlloc", Integer.toString(pack.maximum()));
        List<String> result = new ArrayList<>();
        boolean general = false, found = false;
        for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (general) {
                    changes.forEach((key, value) -> result.add(key + "=" + value));
                    changes.clear();
                }
                general = trimmed.equals("[General]");
                if (general) {
                    require(!found, "Private instance.cfg has duplicate General sections");
                    found = true;
                }
            }
            int equals = line.indexOf('=');
            if (general && equals >= 0 && changes.containsKey(line.substring(0, equals).trim())) continue;
            result.add(line);
        }
        require(found, "Private instance.cfg has no General section");
        if (general) changes.forEach((key, value) -> result.add(key + "=" + value));
        Path pending = Files.createTempFile(config.getParent(), ".java-config-", ".tmp");
        try {
            Files.writeString(pending, String.join("\n", result) + "\n", StandardCharsets.UTF_8);
            Files.move(pending, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
        System.out.println("Private instance Java arguments repaired: " + config);
    }

    private static Path ownedOutput(Path root) throws Exception {
        Path parent = root.resolve("isolated-validation");
        rejectSymlinks(parent);
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "run-");
    }

    private record InputCommand(String type, int x, int y, String value) { }

    private static List<InputCommand> parseCommands(List<String> lines, int width, int height) throws IOException {
        require(lines.size() >= 1 && lines.size() <= 16, "Input batch must contain 1-16 commands");
        require(width > 0 && width <= 1280 && height > 0 && height <= 720, "Unexpected private display dimensions");
        var commands = new ArrayList<InputCommand>();
        for (String line : lines) {
            if (line.equals("menu_ready")) {
                require(lines.size() == 1, "Send menu_ready as its own batch after observing the menu");
                commands.add(new InputCommand("menu", 0, 0, ""));
            } else if (line.matches("click [0-9]{1,4} [0-9]{1,4}")) {
                String[] parts = line.split(" ");
                int x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]);
                require(x < width && y < height, "Input click is outside the private display");
                commands.add(new InputCommand("click", x, y, ""));
            } else if (line.matches("key (ENTER|ESCAPE|TAB|BACKSPACE|W|A|S|D|SPACE|E|F3)")) {
                commands.add(new InputCommand("key", 0, 0, line.substring(4)));
            } else if (line.matches("text [A-Za-z0-9 /_.~:-]{1,80}")) {
                commands.add(new InputCommand("text", 0, 0, line.substring(5)));
            } else {
                throw new IOException("Invalid input command; use click X Y, key ENTER/ESCAPE/TAB/BACKSPACE, or text with 1-80 ASCII letters/digits/spaces and /_.~:-");
            }
        }
        return commands;
    }

    private static void applyCommands(Robot robot, List<InputCommand> commands) {
        for (InputCommand command : commands) {
            switch (command.type()) {
                case "menu" -> { }
                case "click" -> {
                    robot.mouseMove(command.x(), command.y());
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                }
                case "key" -> {
                    int key = switch (command.value()) {
                        case "ENTER" -> KeyEvent.VK_ENTER;
                        case "ESCAPE" -> KeyEvent.VK_ESCAPE;
                        case "TAB" -> KeyEvent.VK_TAB;
                        case "BACKSPACE" -> KeyEvent.VK_BACK_SPACE;
                        case "W" -> KeyEvent.VK_W;
                        case "A" -> KeyEvent.VK_A;
                        case "S" -> KeyEvent.VK_S;
                        case "D" -> KeyEvent.VK_D;
                        case "SPACE" -> KeyEvent.VK_SPACE;
                        case "E" -> KeyEvent.VK_E;
                        case "F3" -> KeyEvent.VK_F3;
                        default -> throw new IllegalArgumentException("Unvalidated key");
                    };
                    robot.keyPress(key);
                    if (List.of("W", "A", "S", "D", "SPACE").contains(command.value())) robot.delay(500);
                    robot.keyRelease(key);
                }
                case "text" -> {
                    for (char character : command.value().toCharArray()) {
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
                }
                default -> throw new IllegalArgumentException("Unvalidated command");
            }
        }
        robot.waitForIdle();
    }

    private static void sendInput(Path root, Path requested, List<String> lines) throws Exception {
        Path parent = root.resolve("isolated-validation");
        require(requested.isAbsolute() && parent.equals(requested.normalize().getParent())
                        && requested.getFileName().toString().startsWith("run-"),
                "Input run directory must be inside this private Prism root's isolated-validation directory");
        rejectSymlinks(requested);
        require(Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS), "Private run directory is missing");
        Path run = requested.toRealPath();
        Path state = run.resolve("run.properties");
        rejectSymlinks(state);
        require(Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS) && Files.size(state) <= 4096, "Live run properties are missing or too large");
        var properties = new Properties();
        try (var input = Files.newInputStream(state)) {
            byte[] bytes = input.readNBytes(4097);
            require(bytes.length <= 4096, "Live run properties exceed 4096 bytes");
            properties.load(new java.io.ByteArrayInputStream(bytes));
        }
        require("running".equals(properties.getProperty("status")) && run.toString().equals(properties.getProperty("runDirectory")),
                "Private run is not accepting input");
        long pid = Long.parseLong(properties.getProperty("helperPid", "-1"));
        var handle = ProcessHandle.of(pid).orElseThrow(() -> new IOException("Private helper is no longer alive"));
        require(handle.isAlive() && handle.info().startInstant().isPresent()
                        && handle.info().startInstant().get().toString().equals(properties.getProperty("helperStarted")),
                "Private helper PID is stale or no longer alive");
        parseCommands(lines, Integer.parseInt(properties.getProperty("screenWidth", "0")),
                Integer.parseInt(properties.getProperty("screenHeight", "0")));
        Path pending = run.resolve("input-next.txt");
        Path lock = run.resolve(".input-send-lock");
        rejectSymlinks(pending);
        rejectSymlinks(lock);
        Files.createFile(lock);
        Path temporary = null;
        try {
            require(!Files.exists(pending, LinkOption.NOFOLLOW_LINKS), "Private run already has a pending input batch");
            temporary = Files.createTempFile(run, ".input-", ".tmp");
            Files.writeString(temporary, String.join("\n", lines) + "\n", StandardCharsets.US_ASCII);
            require(handle.isAlive(), "Private helper exited before input publication");
            require(!Files.exists(pending, LinkOption.NOFOLLOW_LINKS), "Private run already has a pending input batch");
            Files.move(temporary, pending, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("PRIVATE_INPUT queued commands=" + lines.size() + " run=" + run);
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
            Files.deleteIfExists(lock);
        }
    }

    private static void writeRunState(Path output, Rectangle screen, String status) throws Exception {
        var state = new Properties();
        var helper = ProcessHandle.current();
        state.setProperty("helperPid", Long.toString(helper.pid()));
        state.setProperty("helperStarted", helper.info().startInstant().orElseThrow().toString());
        state.setProperty("runDirectory", output.toString());
        state.setProperty("screenWidth", Integer.toString(screen.width));
        state.setProperty("screenHeight", Integer.toString(screen.height));
        state.setProperty("status", status);
        Path target = output.resolve("run.properties");
        rejectSymlinks(target);
        Path temporary = Files.createTempFile(output, ".run-state-", ".tmp");
        try {
            try (var stream = Files.newOutputStream(temporary)) { state.store(stream, "Private display input endpoint"); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void captureScreen(Robot capture, Rectangle screen, Path output) throws Exception {
        Path pending = Files.createTempFile(output, ".screen-", ".png");
        try {
            require(ImageIO.write(capture.createScreenCapture(screen), "png", pending.toFile()), "PNG writer is unavailable");
            Path destination = output.resolve("screen-latest.png");
            rejectSymlinks(destination);
            Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }

    private static boolean consumeInput(Robot robot, Rectangle screen, Path output) throws Exception {
        Path pending = output.resolve("input-next.txt");
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return false;
        rejectSymlinks(pending);
        require(Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS), "Private input endpoint must be a regular file");
        List<InputCommand> commands;
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(pending)) { bytes = input.readNBytes(4097); }
            require(bytes.length <= 4096, "Private input batch exceeds 4096 bytes");
            String content = new String(bytes, StandardCharsets.US_ASCII);
            require(content.endsWith("\n"), "Private input batch is incomplete");
            commands = parseCommands(List.of(content.substring(0, content.length() - 1).split("\n", -1)), screen.width, screen.height);
        } catch (IOException invalid) {
            Files.delete(pending);
            System.err.println("PRIVATE_INPUT rejected: " + invalid.getMessage());
            return false;
        }
        Files.delete(pending);
        if (commands.stream().anyMatch(c -> c.type().equals("menu"))) {
            require(launchNanos != 0 && !menuRecorded, "Menu timing requires a running game and is recorded only once");
            menuRecorded = true;
            var timing = new Properties();
            long elapsed = System.nanoTime() - launchNanos;
            timing.setProperty("phase", "cold");
            timing.setProperty("clock", "host-System.nanoTime");
            timing.setProperty("origin", "private-Prism-ProcessBuilder.start-invocation");
            timing.setProperty("menu.observed.nanos", Long.toString(elapsed));
            timing.setProperty("menu.observed.millis", Long.toString(Duration.ofNanos(elapsed).toMillis()));
            timing.setProperty("measurement", "manual-observation-upper-bound; includes-launcher-UI-and-operator-delay");
            try (var stream = Files.newOutputStream(output.resolve("boot-timing.properties"))) {
                timing.store(stream, "Private game startup timing");
            }
        }
        applyCommands(robot, commands);
        robot.delay(200);
        captureScreen(robot, screen, output);
        System.out.println("PRIVATE_INPUT batch-done commands=" + commands.size() + " run=" + output);
        return true;
    }

    private static void checkDisplay(Path output) throws Exception {
        JFrame[] frame = new JFrame[1];
        JPanel[] panel = new JPanel[1];
        JTextField[] text = new JTextField[1];
        JButton[] button = new JButton[1];
        var entered = new CountDownLatch(1);
        var clicked = new CountDownLatch(1);
        Color color = new Color(24, 158, 164);
        Thread deadline = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(45);
                System.err.println("Private GUI display check exceeded 45 seconds");
                System.exit(2);
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        }, "private-display-check-deadline");
        deadline.setDaemon(true);
        deadline.start();
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame[0] = new JFrame("LoadOnce private display check");
                panel[0] = new JPanel(null);
                panel[0].setBackground(color);
                text[0] = new JTextField();
                text[0].setBounds(35, 45, 320, 38);
                text[0].addActionListener(event -> entered.countDown());
                button[0] = new JButton("Private mouse check");
                button[0].setBounds(35, 110, 200, 40);
                button[0].addActionListener(event -> clicked.countDown());
                panel[0].add(text[0]);
                panel[0].add(button[0]);
                frame[0].setContentPane(panel[0]);
                frame[0].setSize(480, 280);
                frame[0].setLocation(30, 30);
                frame[0].setVisible(true);
            });
            Robot robot = new Robot();
            robot.setAutoDelay(50);
            robot.setAutoWaitForIdle(true);
            java.awt.Point[] points = new java.awt.Point[3];
            SwingUtilities.invokeAndWait(() -> {
                points[0] = text[0].getLocationOnScreen();
                points[1] = button[0].getLocationOnScreen();
                points[2] = panel[0].getLocationOnScreen();
            });
            var dimensions = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            applyCommands(robot, parseCommands(List.of("click " + (points[0].x + 20) + " " + (points[0].y + 15),
                    "text loadonce123 /setblock ~ ~ ~ diamond_block:1", "key ENTER"), dimensions.width, dimensions.height));
            require(entered.await(5, TimeUnit.SECONDS), "Private keyboard input did not reach the test frame");
            String[] value = new String[1];
            SwingUtilities.invokeAndWait(() -> value[0] = text[0].getText());
            require(value[0].equals("loadonce123 /setblock ~ ~ ~ diamond_block:1"),
                    "Private keyboard input differed from the test text: " + value[0]);
            applyCommands(robot, parseCommands(List.of("click " + (points[1].x + 70) + " " + (points[1].y + 20)),
                    dimensions.width, dimensions.height));
            require(clicked.await(5, TimeUnit.SECONDS), "Private mouse input did not reach the test button");
            robot.waitForIdle();
            Rectangle[] bounds = new Rectangle[1];
            SwingUtilities.invokeAndWait(() -> bounds[0] = frame[0].getBounds());
            var image = robot.createScreenCapture(bounds[0]);
            int pixel = image.getRGB(points[2].x + 20 - bounds[0].x, points[2].y + 210 - bounds[0].y);
            require((pixel & 0xffffff) == (color.getRGB() & 0xffffff), "Private screenshot color assertion failed");
            require(ImageIO.write(image, "png", output.resolve("display-check.png").toFile()), "PNG writer is unavailable");
            var properties = new Properties();
            properties.setProperty("mode", Files.exists(Path.of(System.getenv("XAUTHORITY")).getParent()
                    .resolve("hardware-display.properties")) ? "private-hardware" : "private-xvfb");
            properties.setProperty("keyboard", "passed");
            properties.setProperty("mouse", "passed");
            properties.setProperty("screenshotPixel", String.format("%06x", pixel & 0xffffff));
            properties.setProperty("prismStarted", "false");
            try (var stream = Files.newOutputStream(output.resolve("display-check.properties"))) {
                properties.store(stream, "LoadOnce private display validation");
            }
        } finally {
            try { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
            finally { deadline.interrupt(); }
        }
    }

    private static void runPrism(Path root, Path output, Pack pack) throws Exception {
        Path log = output.resolve("prism.log");
        Robot capture = new Robot();
        capture.setAutoDelay(50);
        capture.setAutoWaitForIdle(true);
        Rectangle screen = new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        Path screenshot = output.resolve("screen-latest.png");
        // Let Prism offer the cached name: overriding only its name retains the account UUID.
        var builder = new ProcessBuilder("/usr/bin/prismlauncher", "--dir", root.toString(),
                "--launch", pack.instance(), "--offline", "");
        builder.directory(root.toFile());
        builder.environment().put("QT_QPA_PLATFORM", "xcb");
        for (String key : List.of("XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME")) {
            builder.environment().put(key, Path.of(System.getenv("HOME")).resolve(key.toLowerCase(java.util.Locale.ROOT)).toString());
        }
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        launchNanos = System.nanoTime();
        Process process = builder.start();
        var owned = new LinkedHashMap<Long, ProcessHandle>();
        Thread shutdown = new Thread(() -> cleanup(process, owned), "private-prism-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            writeRunState(output, screen, "running");
            System.out.println("PRIVATE_INPUT endpoint=" + output + "; helper-pid=" + ProcessHandle.current().pid());
            boolean hardware = Files.exists(Path.of(System.getenv("XAUTHORITY")).getParent().resolve("hardware-display.properties"));
            long end = System.nanoTime() + Duration.ofMinutes(hardware ? 20 : 8).toNanos();
            long nextCapture = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            boolean captureReported = false;
            while (process.isAlive() && System.nanoTime() < end) {
                synchronized (owned) { process.descendants().forEach(handle -> owned.put(handle.pid(), handle)); }
                if (consumeInput(capture, screen, output)) {
                    nextCapture = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    if (!captureReported) {
                        System.out.println("Private display screenshot: " + screenshot);
                        captureReported = true;
                    }
                }
                if (System.nanoTime() >= nextCapture) {
                    captureScreen(capture, screen, output);
                    if (!captureReported) {
                        System.out.println("Private display screenshot: " + screenshot);
                        captureReported = true;
                    }
                    nextCapture = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                }
                process.waitFor(100, TimeUnit.MILLISECONDS);
            }
            require(!process.isAlive(), "Private Prism exceeded its " + (hardware ? "20" : "8") + " minute limit; see " + log);
            require(process.exitValue() == 0, "Private Prism failed with exit " + process.exitValue() + "; see " + log);
            System.out.println("Private Prism exited: " + log + "; game/VM success requires separate log inspection");
        } finally {
            cleanup(process, owned);
            writeRunState(output, screen, "stopped");
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { }
        }
    }

    private static void initializePrismConfig(Path root) throws Exception {
        Path config = root.resolve("prismlauncher.cfg");
        rejectSymlinks(config);
        if (Files.exists(config)) return;
        // Prism 11.0.3's createSetupWizard checks these settings before CLI launch.
        Files.writeString(config, """
                [General]
                Language=en_US
                ApplicationTheme=system
                IconTheme=pe_colored
                JavaPath=/usr/lib/jvm/java-17-openjdk/bin/java
                IgnoreJavaWizard=true
                AutomaticJavaDownload=false
                AutomaticJavaSwitch=false
                UserAskedAboutAutomaticJavaDownload=true
                PastebinURL=
                """, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private static void cleanup(Process process, Map<Long, ProcessHandle> owned) {
        synchronized (owned) {
            process.descendants().forEach(handle -> owned.put(handle.pid(), handle));
            for (var handle : owned.values()) if (handle.isAlive()) handle.destroy();
            process.destroy();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            for (var handle : owned.values()) if (handle.isAlive()) handle.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void rejectSymlinks(Path path) throws IOException {
        Path current = path.toAbsolutePath().getRoot();
        for (Path part : path.toAbsolutePath()) {
            current = current.resolve(part);
            require(!Files.isSymbolicLink(current), "Refusing symbolic-link path: " + current);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
