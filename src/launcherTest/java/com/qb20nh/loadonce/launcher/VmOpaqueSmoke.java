package com.qb20nh.loadonce.launcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/** Opt-in black-box acceptance of an uninstrumented application in an isolated guest. */
public final class VmOpaqueSmoke {
    private static final Duration PHASE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final String ENDPOINT = "loadonce-opaque-vm";
    private final Path work;
    private final VmGuestImage.Build image;
    private String phase = "starting";
    private int screenshotNumber;
    private Properties timing;

    private VmOpaqueSmoke(Path work, VmGuestImage.Build image) { this.work = work; this.image = image; }

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equals("--send")) {
            send(Path.of(args[1]), List.of(args).subList(2, args.length));
            return;
        }
        require(args.length == 8 && args[0].equals("--qemu-directory") && args[2].equals("--work-directory")
                && args[4].equals("--spec") && args[6].equals("--xvfb"),
                "Use --qemu-directory ROOT --work-directory PRIVATE_WORK --spec SPEC --xvfb XVFB, or --send PRIVATE_WORK 'COMMAND'...");
        Path qemu = Path.of(args[1]).toRealPath();
        for (String resource : List.of("usr/bin/qemu-img", "usr/bin/qemu-system-x86_64", "usr/share/qemu/bios-256k.bin")) {
            require(Files.isReadable(qemu.resolve(resource)), "Required QEMU resource is unavailable: " + qemu.resolve(resource));
        }
        require(Files.isReadable(Path.of("/dev/kvm")) && Files.isWritable(Path.of("/dev/kvm")), "KVM access is unavailable");
        Path work = LauncherFiles.privateDirectory(Path.of(args[3]));
        Path ownership = work.resolve("controller.lock");
        if (!Files.exists(ownership, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(ownership, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        LauncherFiles.checkPrivate(ownership, false);
        try (var channel = FileChannel.open(ownership, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.tryLock()) {
            require(lock != null, "An opaque VM controller already owns " + work);
            start(qemu, work, args);
        }
    }

    private static void start(Path qemu, Path work, String[] args) throws Exception {
        Path endpoint = work.resolve("control.properties");
        if (Files.exists(endpoint, LinkOption.NOFOLLOW_LINKS)) {
            Properties previous = readProperties(endpoint, 8192);
            require(!liveController(previous), "An opaque VM controller already owns " + work);
        }
        require(!Files.exists(work.resolve("command-next.txt"), LinkOption.NOFOLLOW_LINKS),
                "Pending command from another run exists: " + work.resolve("command-next.txt"));
        Properties spec = readProperties(Path.of(args[5]), 1024 * 1024);
        int memory = switch (spec.getProperty("memory.mib", "")) {
            case "1536" -> 3072;
            case "2304" -> 5120;
            default -> throw new IOException("Opaque guest memory.mib must be 1536 or 2304");
        };
        long available = 0;
        for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
            if (line.startsWith("MemAvailable:")) available = Long.parseLong(line.split("\\s+")[1]) * 1024;
        }
        require(available >= (memory + 1024L) * 1024 * 1024,
                "Opaque VM lacks host memory: guest_mib=" + memory + " available_bytes=" + available + "; retain 1 GiB host headroom");
        long imageStartedNanos = System.nanoTime();
        VmGuestImage.Build image = VmGuestImage.createOpaque(work, Path.of(args[5]), Path.of(args[7]));
        long imageBuildNanos = System.nanoTime() - imageStartedNanos;
        Properties imageTiming = new Properties();
        imageTiming.setProperty("clock", "host-System.nanoTime");
        imageTiming.setProperty("measurement", "VmGuestImage.createOpaque-invocation-to-return");
        imageTiming.setProperty("elapsed.nanos", Long.toString(imageBuildNanos));
        imageTiming.setProperty("elapsed.millis", Long.toString(Duration.ofNanos(imageBuildNanos).toMillis()));
        store(image.directory().resolve("image-build-timing.properties"), imageTiming);
        System.out.println("VM_OPAQUE timing=image-build elapsed_millis=" + imageTiming.getProperty("elapsed.millis"));
        VmOpaqueSmoke controller = new VmOpaqueSmoke(work, image);
        controller.endpoint("running");
        try {
            controller.run(qemu, memory);
            controller.endpoint("passed");
        } catch (Exception failure) {
            try { controller.endpoint("failed"); }
            catch (Exception update) { failure.addSuppressed(update); }
            throw new IOException("Opaque VM " + controller.phase + " failed; retained evidence: " + image.directory(), failure);
        }
    }

    private void run(Path qemu, int memory) throws Exception {
        Path state = image.directory().resolve("state.qcow2");
        ChildProcess.Result created = ChildProcess.run(List.of(qemu.resolve("usr/bin/qemu-img").toString(),
                "create", "-f", "qcow2", state.toString(), "64M"), image.directory(), VmStateSmoke.environment(qemu),
                image.directory().resolve("create-state.log"), Duration.ofSeconds(30));
        require(created.exitCode() == 0, "qcow2 creation failed: " + image.directory().resolve("create-state.log"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rw-------"));
        Properties ready;
        phase = "cold";
        endpoint("running");
        try (var session = VmStateSmoke.Session.startOpaque(qemu, image, state, phase, false, memory)) {
            beginTiming();
            ready = properties(response(session.control, "OPAQUE_READY", PHASE_TIMEOUT));
            requireState(ready, true, false);
            recordTiming("control.ready", System.nanoTime() - session.startedNanos,
                    "QEMU-start-to-host-validated-OPAQUE_READY; application-menu-readiness-unverified");
            store(image.directory().resolve("cold-ready.properties"), ready);
            command(session, "SCREEN");
            System.out.println("VM_OPAQUE phase=cold endpoint=" + work + " workspace=" + image.directory());
            acceptCommands(session, "CAPTURE", "LOColdVerified");
            Properties frozen = properties(exchange(session, "FREEZE", "OPAQUE_FROZEN"));
            same(ready, frozen);
            requireState(frozen, true, true);
            store(image.directory().resolve("ready.properties"), frozen);
            correctClock(session);
            session.qmp.command("stop", null);
            session.qmp.command("human-monitor-command", "savevm " + VmStateSmoke.SNAPSHOT);
            session.qmp.command("quit", null);
            session.awaitExit();
        }
        String sourceHash = LauncherFiles.fileHash(state);
        for (int cycle = 1; cycle <= 3; cycle++) {
            phase = "warm-" + cycle;
            endpoint("running");
            require(sourceHash.equals(LauncherFiles.fileHash(state)), "Original VM snapshot changed: " + state);
            Path copy = image.directory().resolve("restore-" + cycle + ".qcow2");
            beginTiming();
            long cloneStartedNanos = System.nanoTime();
            VmStateSmoke.cloneSnapshot(state, copy, 1, VmStateSmoke.environment(qemu));
            long cloneNanos = System.nanoTime() - cloneStartedNanos;
            recordTiming("snapshot.clone", cloneNanos, "private-original-snapshot-clone-invocation-to-return; excluded-from-startup");
            try (var session = VmStateSmoke.Session.startOpaque(qemu, image, copy, phase, true, memory)) {
                session.qmp.command("cont", null);
                Properties before = properties(exchange(session, "STATE", "OPAQUE_STATE"));
                same(ready, before);
                requireState(before, true, true);
                recordTiming("control.frozen-ready", System.nanoTime() - session.startedNanos,
                        "QEMU-start-including-loadvm-to-host-validated-original-frozen-control-state");
                store(image.directory().resolve(phase + "-before.properties"), before);
                correctClock(session);
                recordTiming("clock.corrected", System.nanoTime() - session.startedNanos,
                        "QEMU-start-including-loadvm-to-host-validated-clock-correction-before-thaw");
                Properties thawed = properties(exchange(session, "THAW", "OPAQUE_THAWED"));
                same(ready, thawed);
                requireState(thawed, true, false);
                require(Math.abs(Long.parseLong(thawed.getProperty("clock.millis")) - System.currentTimeMillis()) < 10_000,
                        "Guest wall clock differs after application thaw");
                recordTiming("application.thawed", System.nanoTime() - session.startedNanos,
                        "QEMU-start-including-loadvm-to-host-validated-clock-corrected-thaw");
                store(image.directory().resolve(phase + "-thawed.properties"), thawed);
                command(session, "SCREEN");
                System.out.println("VM_OPAQUE phase=" + phase + " endpoint=" + work);
                acceptCommands(session, "NEXT", "LOWarmVerified" + cycle);
                Properties exited = properties(exchange(session, "STATE", "OPAQUE_STATE"));
                same(ready, exited);
                requireState(exited, false, false);
                Properties quit = properties(exchange(session, "QUIT", "OPAQUE_EXIT"));
                same(ready, quit);
                requireState(quit, false, false);
                session.awaitExit();
                applicationExit(session.serial);
                exited.setProperty("status", "passed");
                exited.setProperty("generation", Integer.toString(cycle));
                exited.setProperty("verification.marker", "LOWarmVerified" + cycle);
                store(image.directory().resolve("result-" + cycle + ".properties"), exited);
            }
            require(sourceHash.equals(LauncherFiles.fileHash(state)), "Original VM snapshot changed after restore: " + state);
            LauncherFiles.checkPrivate(copy, false);
            Files.delete(copy);
            System.out.println("VM_OPAQUE warm-passed generation=" + cycle);
        }
        Properties completion = new Properties();
        completion.setProperty("status", "passed");
        completion.setProperty("target", "opaque-application");
        completion.setProperty("restore_cycles", "3");
        completion.setProperty("snapshot_sha256", sourceHash);
        completion.setProperty("internal_object_identity", "unverified");
        completion.setProperty("exactly_once_mod_initialization", "unverified");
        store(image.directory().resolve("completion.properties"), completion);
        phase = "complete";
        System.out.println("VM_OPAQUE PASSED workspace=" + image.directory());
    }

    private void acceptCommands(VmStateSmoke.Session session, String finish, String marker) throws Exception {
        long deadline = System.nanoTime() + PHASE_TIMEOUT.toNanos();
        Path pending = work.resolve("command-next.txt");
        while (session.process.isAlive() && System.nanoTime() < deadline) {
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) { Thread.sleep(100); continue; }
            LauncherFiles.checkPrivate(pending, false);
            require(Files.size(pending) <= 16 * 4097L, "Private command batch exceeds its bound: " + pending);
            List<String> commands = Files.readAllLines(pending, StandardCharsets.UTF_8);
            validateCommands(commands);
            // Claim the complete atomic batch before handling it, so another batch may be queued.
            Files.delete(pending);
            for (int index = 0; index < commands.size(); index++) {
                String command = commands.get(index);
                if (command.equals("MENU_READY")) {
                    require(!timing.containsKey("menu.observed.nanos"), "MENU_READY was already recorded in phase " + phase);
                    long observedNanos = System.nanoTime() - session.startedNanos;
                    command(session, "SCREEN");
                    timing.setProperty("menu.status", "observed");
                    timing.setProperty("menu.screenshot", phase + "-screenshot-" + screenshotNumber + ".png");
                    recordTiming("menu.observed", observedNanos,
                            "manual-observation-upper-bound; QEMU-start-to-host-MENU_READY-receipt; includes-operator-and-polling-delay");
                    continue;
                }
                if (command.equals("CAPTURE") || command.equals("NEXT")) {
                    require(command.equals(finish) && index == commands.size() - 1,
                            "Use " + finish + " only as the final command in phase " + phase);
                    String log = command(session, "LOG");
                    require(successfulMarker(log, marker), "Missing successful server confirmation of " + marker + " in guest game log; inspect "
                            + image.directory().resolve("game-latest.log") + "; complete world save/reopen verification first");
                    command(session, "SCREEN");
                    Properties checked = properties(exchange(session, "STATE", "OPAQUE_STATE"));
                    requireState(checked, finish.equals("CAPTURE"), false);
                    checked.setProperty("verification.marker", marker);
                    store(image.directory().resolve(phase + "-acceptance.properties"), checked);
                    return;
                }
                command(session, command);
            }
            System.out.println("VM_OPAQUE phase=" + phase + " commands=" + commands.size() + " screenshot="
                    + image.directory().resolve("screenshot-latest.png"));
        }
        throw new IOException("Opaque VM " + phase + " exceeded 15 minutes or exited; inspect " + session.log);
    }

    private void beginTiming() {
        timing = new Properties();
        timing.setProperty("phase", phase);
        timing.setProperty("clock", "host-System.nanoTime");
        timing.setProperty("startup.origin", "QEMU-ProcessBuilder.start-invocation");
        timing.setProperty("menu.status", "not-observed");
    }

    private void recordTiming(String event, long elapsedNanos, String measurement) throws IOException {
        timing.setProperty(event + ".nanos", Long.toString(elapsedNanos));
        timing.setProperty(event + ".millis", Long.toString(Duration.ofNanos(elapsedNanos).toMillis()));
        timing.setProperty(event + ".measurement", measurement);
        store(image.directory().resolve(phase + "-timing.properties"), timing);
        System.out.println("VM_OPAQUE phase=" + phase + " timing=" + event + " elapsed_millis="
                + timing.getProperty(event + ".millis"));
    }

    static boolean successfulMarker(String log, String marker) {
        Pattern confirmation = Pattern.compile("\\[[0-9]{2}:[0-9]{2}:[0-9]{2}\\] \\[Server thread/INFO\\]: \\[LoadOnceTest\\] "
                + Pattern.quote(marker));
        return log.lines().anyMatch(line -> confirmation.matcher(line).matches());
    }

    private String command(VmStateSmoke.Session session, String command) throws Exception {
        if (command.equals("SCREEN") || command.equals("LOG")) {
            boolean screen = command.equals("SCREEN");
            String payload = exchange(session, command, screen ? "OPAQUE_SCREEN" : "OPAQUE_LOG");
            byte[] bytes = decoded(payload, screen ? 4 * 1024 * 1024 : 128 * 1024);
            if (screen) require(bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 'P'
                    && bytes[2] == 'N' && bytes[3] == 'G', "Guest screenshot is not a PNG");
            String name = screen ? "screenshot" : "game";
            String suffix = screen ? ".png" : ".log";
            write(image.directory().resolve(name + "-latest" + suffix), bytes);
            write(image.directory().resolve(screen ? phase + "-screenshot-" + (++screenshotNumber) + suffix
                    : phase + "-game" + suffix), bytes);
            return screen ? "" : new String(bytes, StandardCharsets.UTF_8);
        }
        if (command.equals("STATE")) {
            Properties state = properties(exchange(session, command, "OPAQUE_STATE"));
            store(image.directory().resolve(phase + "-state.properties"), state);
            System.out.println("VM_OPAQUE phase=" + phase + " app.alive=" + state.getProperty("app.alive")
                    + " app.frozen=" + state.getProperty("app.frozen"));
        } else {
            try { exchange(session, command, "OPAQUE_INPUT_OK"); }
            catch (IOException failure) {
                if (failure.getMessage() != null && failure.getMessage().startsWith("Guest control rejected command:")) {
                    try { command(session, "LOG"); }
                    catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
                }
                throw failure;
            }
        }
        return "";
    }

    private static String exchange(VmStateSmoke.Session session, String command, String prefix) throws Exception {
        session.control.send(command + "\n");
        return response(session.control, prefix, COMMAND_TIMEOUT);
    }

    private static void correctClock(VmStateSmoke.Session session) throws Exception {
        long clock = Long.parseLong(exchange(session, "CLOCK " + (System.currentTimeMillis() / 1000), "OPAQUE_CLOCK"));
        require(Math.abs(clock - System.currentTimeMillis()) < 10_000, "Guest wall clock correction failed while the application was stopped");
    }

    private static String response(VmStateSmoke.LinePipe pipe, String prefix, Duration timeout) throws Exception {
        String line = pipe.line(timeout);
        if (line.startsWith("OPAQUE_ERROR ")) throw new IOException("Guest control rejected command: "
                + new String(decoded(line.substring(13), 8192), StandardCharsets.UTF_8));
        require(line.equals(prefix) || line.startsWith(prefix + " "), "Unexpected guest response for " + prefix
                + "; inspect " + pipe.socket + " protocol log");
        return line.length() == prefix.length() ? "" : line.substring(prefix.length() + 1);
    }

    private static void applicationExit(VmStateSmoke.LinePipe pipe) throws Exception {
        long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String line = pipe.line(Duration.ofNanos(deadline - System.nanoTime()));
            if (line.startsWith("OPAQUE_APPLICATION_EXIT ")) {
                require(line.equals("OPAQUE_APPLICATION_EXIT code=0"), "Application did not exit normally: " + line);
                return;
            }
        }
        throw new IOException("Guest normal application exit evidence is absent");
    }

    private static void requireState(Properties state, boolean alive, boolean frozen) throws IOException {
        require(Boolean.toString(alive).equals(state.getProperty("app.alive"))
                && Boolean.toString(frozen).equals(state.getProperty("app.frozen")),
                "Unexpected application state: expected alive=" + alive + " frozen=" + frozen);
        for (String key : List.of("control.uuid", "app.pid", "app.start", "boot.uuid"))
            require(state.getProperty(key) != null && !state.getProperty(key).isBlank(), "Guest state field is missing: " + key);
    }

    private static void same(Properties original, Properties restored) throws IOException {
        for (String key : List.of("control.uuid", "app.pid", "app.start", "boot.uuid"))
            require(original.getProperty(key) != null && original.getProperty(key).equals(restored.getProperty(key)),
                    "Restored guest application continuity changed: " + key);
    }

    private void endpoint(String status) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("kind", ENDPOINT);
        properties.setProperty("controllerPid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("controllerStarted", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        properties.setProperty("workDirectory", work.toString());
        properties.setProperty("workspace", image.directory().toString());
        properties.setProperty("status", status);
        properties.setProperty("phase", phase);
        Path temp = LauncherFiles.tempFile(work, "control-", ".tmp");
        store(temp, properties);
        Files.move(temp, work.resolve("control.properties"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void send(Path requested, List<String> commands) throws Exception {
        require(requested.isAbsolute(), "Private command endpoint must be absolute");
        Path work = requested.normalize();
        LauncherFiles.checkPrivate(work, true);
        validateCommands(commands);
        try (var lock = LauncherFiles.lock(work, true)) {
            Properties endpoint = readProperties(work.resolve("control.properties"), 8192);
            require(ENDPOINT.equals(endpoint.getProperty("kind")) && "running".equals(endpoint.getProperty("status"))
                    && work.toString().equals(endpoint.getProperty("workDirectory")) && liveController(endpoint),
                    "Private opaque VM endpoint is absent, stale or stopped: " + work);
            Path workspace = Path.of(endpoint.getProperty("workspace", "")).normalize();
            require(workspace.isAbsolute() && work.equals(workspace.getParent()), "Opaque VM workspace escapes its private endpoint");
            LauncherFiles.checkPrivate(workspace, true);
            Path pending = work.resolve("command-next.txt");
            require(!Files.exists(pending, LinkOption.NOFOLLOW_LINKS), "A private command batch is already pending: " + pending);
            Path temp = LauncherFiles.tempFile(work, "command-", ".tmp");
            try {
                write(temp, (String.join("\n", commands) + "\n").getBytes(StandardCharsets.UTF_8));
                Files.move(temp, pending, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
        }
        System.out.println("VM_OPAQUE commands-published=" + commands.size() + " endpoint=" + work);
    }

    private static boolean liveController(Properties endpoint) {
        try {
            var handle = ProcessHandle.of(Long.parseLong(endpoint.getProperty("controllerPid", "-1")));
            return handle.isPresent() && handle.get().isAlive() && handle.get().info().startInstant().isPresent()
                    && handle.get().info().startInstant().get().toString().equals(endpoint.getProperty("controllerStarted"));
        } catch (RuntimeException invalid) { return false; }
    }

    static void validateCommands(List<String> commands) throws IOException {
        require(!commands.isEmpty() && commands.size() <= 16, "Send between 1 and 16 private commands");
        require(commands.stream().filter("MENU_READY"::equals).count() <= 1, "Send MENU_READY only once per phase");
        for (String command : commands) require(command.length() <= 4096
                && (List.of("STATE", "SCREEN", "LOG", "MENU_READY", "CAPTURE", "NEXT").contains(command)
                || command.matches("CLICK [0-9]{1,4} [0-9]{1,4}")
                || command.matches("KEY (ENTER|ESCAPE|TAB|BACKSPACE)")
                || command.matches("TEXT [A-Za-z0-9 /_.~:-]{1,160}")),
                "Invalid private VM command; use STATE/SCREEN/LOG, MENU_READY, CLICK X Y, KEY ENTER/ESCAPE/TAB/BACKSPACE, TEXT, CAPTURE or NEXT");
    }

    private static Properties readProperties(Path file, int maximum) throws IOException {
        LauncherFiles.checkPrivate(file, false);
        require(Files.size(file) <= maximum, "Private properties exceed their bound: " + file);
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(maximum + 1); }
        require(bytes.length <= maximum, "Private properties grew beyond their bound: " + file);
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties;
    }

    private static Properties properties(String payload) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(decoded(payload, 8192)));
        return properties;
    }

    private static byte[] decoded(String payload, int maximum) throws IOException {
        require(payload.length() <= (maximum + 2L) / 3 * 4, "Guest response exceeds its encoded bound");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(payload); }
        catch (IllegalArgumentException invalid) { throw new IOException("Guest response has invalid base64", invalid); }
        require(bytes.length <= maximum, "Guest response exceeds its decoded bound");
        return bytes;
    }

    private static void store(Path path, Properties properties) throws IOException {
        var bytes = new ByteArrayOutputStream();
        properties.store(bytes, "Opaque VM acceptance");
        write(path, bytes.toByteArray());
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        LauncherFiles.checkPrivate(path, false);
        Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
