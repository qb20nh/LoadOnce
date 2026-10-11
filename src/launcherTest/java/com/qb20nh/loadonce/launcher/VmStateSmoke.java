package com.qb20nh.loadonce.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Independent whole-guest snapshots, including its kernel and native resources. */
public final class VmStateSmoke {
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    static final String SNAPSHOT = "loadonce-initialized";
    private static final int RESTORE_CYCLES = 3;

    private VmStateSmoke() { }

    public static void main(String[] args) throws Exception {
        if ((args.length != 4 && args.length != 8) || !args[0].equals("--qemu-directory") || !args[2].equals("--work-directory")
                || (args.length == 8 && (!args[4].equals("--spec") || !args[6].equals("--xvfb")))) {
            throw new IllegalArgumentException("Use --qemu-directory EXTRACTED_ROOT --work-directory PRIVATE_DIRECTORY"
                    + " [--spec MINECRAFT_SPEC --xvfb LOCAL_XVFB]");
        }
        Path qemu = Path.of(args[1]).toRealPath();
        boolean minecraft = args.length == 8;
        for (String resource : List.of("usr/bin/qemu-img", "usr/bin/qemu-system-x86_64", "usr/share/qemu/bios-256k.bin")) {
            require(Files.isReadable(qemu.resolve(resource)), "Required QEMU resource is unavailable: " + qemu.resolve(resource));
        }
        require(Files.isReadable(Path.of("/dev/kvm")) && Files.isWritable(Path.of("/dev/kvm")), "KVM access is unavailable");
        VmGuestImage.Build image = minecraft ? VmGuestImage.create(Path.of(args[3]), Path.of(args[5]), Path.of(args[7]))
                : VmGuestImage.create(Path.of(args[3]));
        run(qemu, image, minecraft);
    }

    private static void run(Path qemu, VmGuestImage.Build image, boolean minecraft) throws Exception {
        Path directory = image.directory();
        require(Files.getFileStore(directory).getUsableSpace() >= (minecraft ? 3L * 1024 : 512) * 1024 * 1024,
                "VM snapshot lacks free space: " + directory);
        Path state = directory.resolve("state.qcow2");
        ChildProcess.Result created = ChildProcess.run(List.of(qemu.resolve("usr/bin/qemu-img").toString(),
                "create", "-f", "qcow2", state.toString(), "64M"), directory, environment(qemu),
                directory.resolve("create-state.log"), Duration.ofSeconds(30));
        require(created.exitCode() == 0, "qcow2 creation failed: " + directory.resolve("create-state.log"));
        if (minecraft) {
            try (Session cold = Session.start(qemu, image, state, "cold", false, true)) {
                Properties initialized = cold.serial.properties("VM_READY_BEGIN", "VM_READY_END", TIMEOUT);
                Thread.sleep(2100);
                cold.serial.send("CONTINUE 1 " + (System.currentTimeMillis() / 1000) + "\n");
                Properties result = cold.serial.properties("VM_RESULT_BEGIN", "VM_RESULT_END", TIMEOUT);
                validate(initialized, result, 1, true);
                cold.awaitExit();
                store(directory.resolve("cold-result.properties"), result);
                System.out.println("VM_STATE cold-world-passed workspace=" + directory);
            }
        }
        Properties ready;
        try (Session source = Session.start(qemu, image, state, "capture", false, minecraft)) {
            ready = source.serial.properties("VM_READY_BEGIN", "VM_READY_END", TIMEOUT);
            require("1".equals(ready.getProperty(minecraft ? "probe.calls" : "initialization_count")),
                    "Guest did not initialize exactly once");
            source.qmp.command("stop", null);
            source.qmp.command("human-monitor-command", "savevm " + SNAPSHOT);
            source.qmp.command("quit", null);
            source.awaitExit();
        }
        store(directory.resolve("ready.properties"), ready);
        String witnessKey = minecraft ? "probe.probe_identity" : "heap_witness";
        System.out.println("VM_STATE captured witness=" + ready.getProperty(witnessKey) + " workspace=" + directory);
        for (int cycle = 1; cycle <= RESTORE_CYCLES; cycle++) {
            Path copy = directory.resolve("restore-" + cycle + ".qcow2");
            cloneSnapshot(state, copy, RESTORE_CYCLES - cycle + 1, environment(qemu));
            Thread.sleep(2100);
            try (Session restored = Session.start(qemu, image, copy, "restore-" + cycle, true, minecraft)) {
                restored.qmp.command("cont", null);
                restored.serial.send("CONTINUE " + cycle + " " + (System.currentTimeMillis() / 1000) + "\n");
                Properties result = restored.serial.properties("VM_RESULT_BEGIN", "VM_RESULT_END", TIMEOUT);
                validate(ready, result, cycle, minecraft);
                store(directory.resolve("result-" + cycle + ".properties"), result);
                restored.awaitExit();
                System.out.println("VM_STATE restored-generation=" + cycle + " witness=" + result.getProperty(witnessKey));
            }
        }
        Properties completion = new Properties();
        completion.setProperty("status", "passed");
        completion.setProperty("target", minecraft ? "minecraft" : "cpu");
        completion.setProperty("restore_cycles", Integer.toString(RESTORE_CYCLES));
        store(directory.resolve("completion.properties"), completion);
        System.out.println("VM_STATE PASSED workspace=" + directory);
    }

    static void cloneSnapshot(Path state, Path copy, int remainingCopies, Map<String, String> environment) throws Exception {
        Path directory = copy.getParent();
        require(!Files.exists(copy, LinkOption.NOFOLLOW_LINKS), "VM clone destination already exists: " + copy);
        Path log = directory.resolve(copy.getFileName() + "-clone.log");
        Files.createFile(copy, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            ChildProcess.Result reflink = ChildProcess.run(List.of("/usr/bin/cp", "--reflink=always", "--",
                    state.toString(), copy.toString()), directory, environment, log, TIMEOUT);
            if (reflink.exitCode() == 0) return;
            Files.delete(copy);
            Path allocationLog = directory.resolve(copy.getFileName() + "-allocation.log");
            ChildProcess.Result allocation = ChildProcess.run(List.of("/usr/bin/stat", "--format=%b %B", "--", state.toString()),
                    directory, environment, allocationLog, Duration.ofSeconds(30));
            require(allocation.exitCode() == 0, "Cannot measure VM snapshot allocation: " + state + "; inspect " + allocationLog);
            long allocated;
            long required;
            long logical = Files.size(state);
            try {
                String[] fields = allocation.output().trim().split("\\s+");
                allocated = Math.multiplyExact(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
                // Reserve full copies even when sparse copying is unavailable on the destination filesystem.
                required = Math.multiplyExact(Math.addExact(Math.max(logical, allocated), 1024 * 1024), remainingCopies);
            } catch (ArithmeticException | NumberFormatException | ArrayIndexOutOfBoundsException invalid) {
                throw new IOException("Invalid VM snapshot allocation: " + state + "; inspect " + allocationLog, invalid);
            }
            long available = Files.getFileStore(directory).getUsableSpace();
            require(available >= required, "VM snapshot fallback lacks space: source=" + state + " destination=" + copy
                    + " allocated_bytes=" + allocated + " logical_bytes=" + logical + " remaining_copies=" + remainingCopies
                    + " required_bytes=" + required + " available_bytes=" + available
                    + "; use storage with enough free space or reflink support; inspect " + log);
            Files.createFile(copy, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Path fallbackLog = directory.resolve(copy.getFileName() + "-copy.log");
            ChildProcess.Result copied = ChildProcess.run(List.of("/usr/bin/cp", "--reflink=never", "--sparse=always", "--",
                    state.toString(), copy.toString()), directory, environment, fallbackLog, TIMEOUT);
            require(copied.exitCode() == 0, "VM snapshot copy failed: source=" + state + " destination=" + copy
                    + "; inspect " + fallbackLog);
        } catch (Exception failure) {
            try { Files.deleteIfExists(copy); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static void validate(Properties ready, Properties result, int cycle, boolean minecraft) throws IOException {
        String generation = Integer.toString(cycle);
        if (!minecraft) {
            require("passed".equals(result.getProperty("fixture_validation"))
                    && generation.equals(result.getProperty("generation")), "Guest continuation did not pass");
            for (String key : List.of("heap_witness", "initialization_count", "object_identity", "parked_stack_witness")) {
                same(ready, result, key);
            }
            require(number(result, "observed_pause_millis") >= 1000, "Guest wall clock did not advance");
            return;
        }
        for (String prefix : List.of("probe.", "mod.", "passed.")) {
            require(generation.equals(result.getProperty(prefix + "generation")), "Minecraft generation is missing: " + prefix);
            require("1".equals(result.getProperty(prefix + "calls")), "Minecraft initialization repeated: " + prefix);
        }
        for (String key : List.of("probe_identity", "client_identity", "menu_identity", "renderer_identity",
                "window_identity", "render_target_identity", "window_handle")) {
            same(ready, result, "probe." + key);
        }
        for (String key : List.of("identity", "thread_identity", "thread_id", "thread_object_identity")) {
            same(ready, result, "mod." + key);
        }
        require(number(result, "mod.counter") > number(ready, "mod.counter"), "Restored mod thread did not progress");
        require(number(result, "probe.wall_millis") >= number(ready, "probe.wall_millis") + 1000,
                "Restored Minecraft wall clock did not advance");
        for (String key : List.of("input_verified", "reload_passed", "persisted_block")) {
            require("true".equals(result.getProperty("passed." + key)), "Minecraft acceptance failed: " + key);
        }
        for (String key : List.of("menu_frames", "world_frames", "reload_frames")) {
            require(number(result, "passed." + key) >= 3, "Minecraft rendered too few frames: " + key);
        }
    }

    private static void same(Properties ready, Properties result, String key) throws IOException {
        require(ready.getProperty(key) != null && ready.getProperty(key).equals(result.getProperty(key)),
                "VM restored state changed: " + key);
    }

    private static long number(Properties properties, String key) throws IOException {
        try { return Long.parseLong(properties.getProperty(key)); }
        catch (RuntimeException invalid) { throw new IOException("VM evidence needs a numeric " + key, invalid); }
    }

    static Map<String, String> environment(Path qemu) {
        return Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8",
                "LD_LIBRARY_PATH", qemu.resolve("usr/lib").toString(),
                "QEMU_MODULE_DIR", qemu.resolve("usr/lib/qemu").toString());
    }

    private static void store(Path file, Properties properties) throws IOException {
        Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(file)) { properties.store(output, "Whole guest state evidence"); }
    }

    static final class Session implements AutoCloseable {
        final Process process;
        final LinePipe serial;
        final LinePipe control;
        final Qmp qmp;
        final Path log;
        final long startedNanos;
        final Thread shutdown;

        private Session(Process process, LinePipe serial, LinePipe control, Qmp qmp, Path log, long startedNanos) {
            this.process = process; this.serial = serial; this.control = control; this.qmp = qmp; this.log = log;
            this.startedNanos = startedNanos;
            shutdown = new Thread(process::destroyForcibly, "LoadOnce VM shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
        }

        static Session start(Path qemu, VmGuestImage.Build image, Path state, String phase, boolean restore, boolean minecraft) throws Exception {
            return start(qemu, image, state, phase, restore, minecraft ? 3072 : 512, minecraft ? 2 : 1, false);
        }

        static Session startOpaque(Path qemu, VmGuestImage.Build image, Path state, String phase, boolean restore, int memory) throws Exception {
            require(memory == 3072 || memory == 5120, "Opaque VM memory must be 3072 or 5120 MiB");
            return start(qemu, image, state, phase, restore, memory, 2, true);
        }

        private static Session start(Path qemu, VmGuestImage.Build image, Path state, String phase, boolean restore,
                                     int memory, int cpus, boolean opaque) throws Exception {
            Path directory = image.directory();
            Path log = LauncherFiles.tempFile(directory, phase + "-", ".log");
            Path sockets = LauncherFiles.tempDirectory(null, "loadonce-vm-sockets-");
            Path monitor = sockets.resolve("qmp"), serial = sockets.resolve("serial"), control = sockets.resolve("control");
            List<String> command = new ArrayList<>(List.of(qemu.resolve("usr/bin/qemu-system-x86_64").toString(),
                    "-no-user-config", "-nodefaults", "-machine", "pc", "-accel", "kvm", "-cpu", "host",
                    "-m", Integer.toString(memory), "-smp", Integer.toString(cpus),
                    "-L", qemu.resolve("usr/share/qemu").toString(), "-bios", qemu.resolve("usr/share/qemu/bios-256k.bin").toString(),
                    "-display", "none", "-vga", "none", "-nic", "none", "-no-reboot",
                    "-kernel", image.kernel().toString(), "-initrd", image.initrd().toString(),
                    "-append", "console=ttyS0 quiet panic=1",
                    "-drive", "file=" + image.rootImage() + ",format=raw,readonly=on,if=virtio,id=root",
                    "-drive", "file=" + state + ",format=qcow2,if=virtio,id=state",
                    "-qmp", "unix:" + monitor + ",server=on,wait=off",
                    "-serial", "unix:" + serial + ",server=on,wait=off", "-rtc", "base=utc,clock=host"));
            if (opaque) command.addAll(List.of("-serial", "unix:" + control + ",server=on,wait=off"));
            if (restore) command.addAll(List.of("-S", "-loadvm", SNAPSHOT));
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
            builder.environment().clear(); builder.environment().putAll(environment(qemu));
            builder.redirectInput(Path.of("/dev/null").toFile());
            builder.redirectErrorStream(true); builder.redirectOutput(log.toFile());
            Process process;
            long startedNanos = System.nanoTime();
            try { process = builder.start(); }
            catch (IOException failure) {
                try { Files.deleteIfExists(sockets); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw new IOException("VM process could not start; inspect " + log, failure);
            }
            LinePipe serialPipe = null;
            LinePipe monitorPipe = null;
            LinePipe controlPipe = null;
            Qmp qmp = null;
            try {
                serialPipe = LinePipe.connect(serial, process, directory.resolve(phase + "-serial.log"));
                monitorPipe = LinePipe.connect(monitor, process, directory.resolve(phase + "-qmp.log"));
                if (opaque) controlPipe = LinePipe.connect(control, process, directory.resolve(phase + "-control.log"), true);
                qmp = new Qmp(monitorPipe);
                return new Session(process, serialPipe, controlPipe, qmp, log, startedNanos);
            } catch (Exception failure) {
                try { cleanup(process, serialPipe, monitorPipe, controlPipe, sockets); }
                catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw new IOException("VM startup failed; inspect " + log, failure);
            }
        }

        void awaitExit() throws Exception {
            require(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0, "QEMU exit failed: " + log);
        }

        @Override public void close() throws Exception {
            try { cleanup(process, serial, qmp.pipe, control, serial.socket.getParent()); }
            finally {
                if (!process.isAlive()) Runtime.getRuntime().removeShutdownHook(shutdown);
            }
        }

        private static void cleanup(Process process, LinePipe serial, LinePipe monitor, LinePipe control, Path sockets) throws Exception {
            Exception failure = null;
            process.destroyForcibly();
            try { require(process.waitFor(10, TimeUnit.SECONDS), "VM cleanup did not terminate its process"); }
            catch (Exception problem) { failure = problem; }
            for (LinePipe pipe : new LinePipe[] { serial, monitor, control }) {
                if (pipe == null) continue;
                try { pipe.close(); }
                catch (Exception problem) {
                    if (failure == null) failure = problem; else failure.addSuppressed(problem);
                }
            }
            for (Path file : List.of(sockets.resolve("serial"), sockets.resolve("qmp"), sockets.resolve("control"), sockets)) {
                try { Files.deleteIfExists(file); }
                catch (Exception problem) {
                    if (failure == null) failure = problem; else failure.addSuppressed(problem);
                }
            }
            if (failure != null) throw failure;
        }
    }

    static final class Qmp implements AutoCloseable {
        final LinePipe pipe;
        int request;
        Qmp(LinePipe pipe) throws Exception {
            this.pipe = pipe;
            require(pipe.line(Duration.ofSeconds(30)).contains("\"QMP\""), "QMP greeting is missing");
            command("qmp_capabilities", null);
        }
        void command(String execute, String human) throws Exception {
            int id = ++request;
            String args = human == null ? "" : ",\"arguments\":{\"command-line\":\"" + human + "\"}";
            pipe.send("{\"execute\":\"" + execute + "\",\"id\":" + id + args + "}\n");
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                String response = pipe.line(Duration.ofNanos(deadline - System.nanoTime()));
                if (!response.matches(".*\"id\"\\s*:\\s*" + id + "(?:[^0-9].*|$)")) continue;
                require(!response.contains("\"error\""), "QMP command failed: " + response);
                // HMP reports errors inside its successful QMP return string.
                if (human != null) require(response.matches(".*\"return\"\\s*:\\s*\"\".*"), "VM snapshot command failed: " + response);
                return;
            }
            throw new IOException("QMP command timed out: " + execute);
        }
        @Override public void close() throws IOException { pipe.close(); }
    }

    static final class LinePipe implements AutoCloseable {
        final SocketChannel channel;
        final Path socket;
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final java.io.BufferedWriter output;
        final Thread reader;
        private LinePipe(SocketChannel channel, Path socket, Path log, boolean control) throws IOException {
            this.channel = channel; this.socket = socket;
            Files.createFile(log, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            output = Files.newBufferedWriter(log);
            reader = new Thread(() -> {
                ByteBuffer bytes = ByteBuffer.allocate(4096);
                ByteArrayOutputStream pending = new ByteArrayOutputStream();
                try {
                    while (channel.read(bytes) >= 0) {
                        bytes.flip();
                        while (bytes.hasRemaining()) {
                            int value = bytes.get() & 255;
                            if (value == '\n') {
                                String line = pending.toString(StandardCharsets.UTF_8).stripTrailing();
                                pending.reset();
                                String logged = control && (line.startsWith("OPAQUE_SCREEN ") || line.startsWith("OPAQUE_LOG "))
                                        ? line.substring(0, line.indexOf(' ')) + " payload_characters=" + (line.length() - line.indexOf(' ') - 1) : line;
                                synchronized (output) { output.write(logged); output.newLine(); output.flush(); }
                                lines.put(line);
                            } else {
                                pending.write(value);
                                if (pending.size() > (control ? 8 * 1024 * 1024 : 65536)) throw new IOException("VM protocol line exceeds its bound");
                            }
                        }
                        bytes.clear();
                    }
                } catch (Exception failure) {
                    lines.offer("VM_FAILED protocol-reader " + failure.getClass().getSimpleName());
                } finally { lines.offer("\0EOF"); }
            }, "LoadOnce VM protocol reader");
            reader.setDaemon(true); reader.start();
        }
        static LinePipe connect(Path socket, Process process, Path log) throws Exception {
            return connect(socket, process, log, false);
        }
        static LinePipe connect(Path socket, Process process, Path log, boolean control) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (process.isAlive() && System.nanoTime() < deadline) {
                if (Files.exists(socket)) {
                    SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                    try { channel.connect(UnixDomainSocketAddress.of(socket)); return new LinePipe(channel, socket, log, control); }
                    catch (IOException retry) { channel.close(); }
                }
                Thread.sleep(20);
            }
            throw new IOException("VM socket unavailable: " + socket);
        }
        void send(String text) throws IOException {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
            while (bytes.hasRemaining()) channel.write(bytes);
        }
        String line(Duration timeout) throws Exception {
            String line = lines.poll(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            require(line != null && !line.equals("\0EOF") && !line.startsWith("VM_FAILED"), "VM protocol failed; inspect socket " + socket);
            return line;
        }
        Properties properties(String begin, String end, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean collecting = false;
            StringBuilder text = new StringBuilder();
            while (System.nanoTime() < deadline) {
                String line = line(Duration.ofNanos(deadline - System.nanoTime()));
                if (line.equals(begin)) { collecting = true; continue; }
                if (collecting && line.equals(end)) {
                    Properties result = new Properties();
                    result.load(new java.io.StringReader(text.toString()));
                    return result;
                }
                if (collecting) {
                    text.append(line).append('\n');
                    require(text.length() <= 8192, "VM evidence exceeds 8 KiB");
                }
            }
            throw new IOException("VM evidence timed out: " + begin);
        }
        @Override public void close() throws IOException {
            channel.close();
            try { reader.join(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            synchronized (output) { output.close(); }
        }
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
