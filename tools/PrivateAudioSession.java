import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Private Pulse-compatible audio capture inside an owned hardware display session. */
class PrivateAudioSession {
    private static final String REMOTE = "loadonce-audio";
    private static final String SINK = "loadonce_sink";
    private static final long LIMIT = 16L * 1024 * 1024;
    private final List<Process> processes = new ArrayList<>();
    private final Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
    private Map<String, String> environment;
    private Path session;
    private Path directory;
    private boolean closed;
    private final Properties evidence = new Properties();
    private record Recording(Process recorder, Thread thread, AtomicReference<IOException> failure, Path pcm, int generation) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("--"))
            throw new IllegalArgumentException("Use PrivateAudioSession.java -- COMMAND [ARG...]");
        PrivateAudioSession audio = new PrivateAudioSession();
        Thread cleanup = new Thread(audio::close, "private-audio-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        int exit;
        try {
            exit = audio.run(Arrays.asList(args).subList(1, args.length));
        } finally {
            audio.close();
            Runtime.getRuntime().removeShutdownHook(cleanup);
        }
        System.exit(exit);
    }

    private int run(List<String> command) throws Exception {
        require("Linux".equals(System.getProperty("os.name")), "Private audio requires Linux");
        Map<String, String> inherited = System.getenv();
        Path authority = Path.of(inherited.getOrDefault("XAUTHORITY", "")).toAbsolutePath().normalize();
        session = authority.getParent();
        checkPrivate(session, true);
        checkPrivate(authority, false);
        for (var entry : Map.of("HOME", "home", "XDG_RUNTIME_DIR", "xdg", "TMPDIR", "tmp").entrySet()) {
            Path path = Path.of(inherited.getOrDefault(entry.getKey(), ""));
            require(path.isAbsolute() && path.normalize().equals(session.resolve(entry.getValue())),
                    entry.getKey() + " must remain inside the private display session");
            checkPrivate(path, true);
        }
        checkPrivate(session.resolve("hardware-display.properties"), false);
        directory = Files.createDirectory(session.resolve("audio"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path configDirectory = privateDirectory(directory.resolve("config"));
        Path pulseDirectory = privateDirectory(session.resolve("xdg/pulse"));
        Path socket = pulseDirectory.resolve("native");
        String server = "unix:" + socket;
        Path config = write(configDirectory.resolve("private.conf"), configuration(socket));
        write(configDirectory.resolve("client.conf"), """
                context.spa-libs = { audio.convert.* = audioconvert/libspa-audioconvert support.* = support/libspa-support }
                context.modules = [
                  { name = libpipewire-module-protocol-native }
                  { name = libpipewire-module-client-node }
                  { name = libpipewire-module-adapter }
                  { name = libpipewire-module-metadata }
                ]
                """);
        Path client = write(configDirectory.resolve("pulse-client.conf"),
                "autospawn = no\ndefault-server = " + server + "\n");
        environment = new LinkedHashMap<>(inherited);
        environment.keySet().removeIf(key -> key.startsWith("PULSE_") || key.startsWith("PIPEWIRE_")
                || key.startsWith("ALSOFT_") || key.startsWith("DBUS_") || key.startsWith("ALSA_")
                || key.startsWith("JACK_") || key.equals("SDL_AUDIODRIVER")
                || key.equals("WAYLAND_DISPLAY") || key.equals("WAYLAND_SOCKET"));
        environment.put("PIPEWIRE_RUNTIME_DIR", session.resolve("xdg").toString());
        environment.put("PIPEWIRE_REMOTE", REMOTE);
        environment.put("PIPEWIRE_CONFIG_DIR", configDirectory.toString());
        environment.put("PULSE_RUNTIME_PATH", pulseDirectory.toString());
        environment.put("PULSE_SERVER", server);
        environment.put("PULSE_CLIENTCONFIG", client.toString());
        environment.put("PULSE_SINK", SINK);
        environment.put("PULSE_SOURCE", SINK + ".monitor");
        environment.put("ALSOFT_DRIVERS", "pulse");
        Process pipewire = start(List.of("/usr/bin/pipewire", "-c", config.toString()), "server.log");
        long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            require(pipewire.isAlive(), "Private PipeWire exited; see " + directory.resolve("server.log"));
            try {
                verifyEndpoints(server);
                break;
            } catch (IOException waiting) {
                if (System.nanoTime() >= readyDeadline) throw waiting;
                Thread.sleep(100);
            }
        }
        Properties marker = new Properties();
        marker.setProperty("controllerPid", Long.toString(ProcessHandle.current().pid()));
        marker.setProperty("controllerStarted", started(ProcessHandle.current()));
        marker.setProperty("serverPid", Long.toString(pipewire.pid()));
        marker.setProperty("serverStarted", started(pipewire.toHandle()));
        marker.setProperty("workDirectory", session.toString());
        marker.setProperty("pulseServer", server);
        marker.setProperty("pipewireRemote", REMOTE);
        store(session.resolve("audio-session.properties"), marker);
        Recording recording = startRecording(server, 1);
        Process application = start(command, "application.log");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
        boolean routed = false;
        try {
            while (application.isAlive()) {
                rememberDescendants();
                require(pipewire.isAlive(), "Private audio server died");
                if (nextRecordingRequested()) {
                    require(recording.generation() < 3, "Private audio permits at most three recordings");
                    finishRecording(recording, routed);
                    recording = startRecording(server, recording.generation() + 1);
                    routed = false;
                }
                if (recording.failure().get() != null) throw recording.failure().get();
                require(recording.recorder().isAlive() || Files.size(recording.pcm()) == LIMIT,
                        "Private PCM recorder exited before its size limit; see "
                                + directory.resolve("recorder-" + recording.generation() + ".log"));
                routed |= route(recording);
                require(System.nanoTime() < deadline, "Private audio command exceeded 20 minutes");
                Thread.sleep(1000);
            }
            return application.exitValue();
        } finally {
            finishRecording(recording, routed);
            evidence.setProperty("routing.status", routed ? "linked" : "no-stereo-stream-observed");
            evidence.setProperty("command.exit", application.isAlive() ? "unfinished" : Integer.toString(application.exitValue()));
            evidence.setProperty("game.sound.validation", "unverified-requires-known-game-sound-action");
            store(directory.resolve("audio-evidence.properties"), evidence);
            System.out.println("PRIVATE_AUDIO_EVIDENCE " + directory.resolve("audio-evidence.properties"));
        }
    }

    private void verifyEndpoints(String server) throws IOException, InterruptedException {
        String sinks = output(List.of("/usr/bin/pactl", "--server=" + server, "list", "short", "sinks"));
        String sources = output(List.of("/usr/bin/pactl", "--server=" + server, "list", "short", "sources"));
        require(singleName(sinks, SINK) && singleName(sources, SINK + ".monitor"),
                "Private audio must expose exactly its own sink and monitor");
        String info = output(List.of("/usr/bin/pactl", "--server=" + server, "info"));
        String defaults = info.lines().filter(line -> line.startsWith("Default Sink: ") || line.startsWith("Default Source: "))
                .map(line -> line.substring(0, Math.min(line.length(), 128))).collect(java.util.stream.Collectors.joining("; "));
        require(info.lines().anyMatch(line -> line.equals("Default Sink: " + SINK))
                && info.lines().anyMatch(line -> line.equals("Default Source: " + SINK + ".monitor")),
                "Private audio default devices must select its own sink and monitor; observed: "
                        + (defaults.isEmpty() ? "(missing)" : defaults));
        evidence.setProperty("sinks", sinks.strip());
        evidence.setProperty("sources", sources.strip());
        evidence.setProperty("default.sink", SINK);
        evidence.setProperty("default.source", SINK + ".monitor");
    }

    private static boolean singleName(String text, String name) {
        List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() != 1) return false;
        String[] fields = lines.get(0).split("\\s+");
        return fields.length >= 2 && fields[1].equals(name);
    }

    private boolean route(Recording recording) throws IOException, InterruptedException {
        int generation = recording.generation();
        List<String> outputs = ports("-o");
        List<String> inputs = ports("-i");
        String game = stereoNode(outputs, "output_", SINK);
        String recorder = stereoNode(inputs, "input_", SINK);
        if (game == null && recorder == null) return false;
        String links = output(List.of("/usr/bin/pw-link", "-r", REMOTE, "-l"));
        boolean captureLinked = recorder != null;
        if (recorder != null) for (String channel : List.of("FL", "FR")) {
            String monitorPort = SINK + ":monitor_" + channel;
            String capturePort = recorder + ":input_" + channel;
            require(outputs.contains(monitorPort), "Private sink monitor stereo ports missing");
            if (!isLinked(links, monitorPort, capturePort)) {
                try {
                    output(List.of("/usr/bin/pw-link", "-r", REMOTE, "-w", monitorPort, capturePort));
                } catch (IOException endedCapture) {
                    if (Files.size(recording.pcm()) != LIMIT || recording.failure().get() != null
                            || ports("-i").contains(capturePort)) throw endedCapture;
                    captureLinked = false;
                }
            }
            if (captureLinked) evidence.setProperty("recording." + generation + ".monitor.route." + channel,
                    monitorPort + " -> " + capturePort);
        }
        if (game == null) return false;
        for (String channel : List.of("FL", "FR")) {
            String gamePort = game + ":output_" + channel;
            String sinkPort = SINK + ":playback_" + channel;
            String monitorPort = SINK + ":monitor_" + channel;
            require(inputs.contains(sinkPort), "Private sink playback stereo ports missing");
            try {
                if (!isLinked(links, gamePort, sinkPort))
                    output(List.of("/usr/bin/pw-link", "-r", REMOTE, "-w", gamePort, sinkPort));
            } catch (IOException transientStream) {
                if (!ports("-o").contains(gamePort)) return false;
                throw transientStream;
            }
            String route = gamePort + " -> " + sinkPort
                    + (captureLinked ? "; " + monitorPort + " -> " + recorder + ":input_" + channel : "; recording-finished");
            evidence.setProperty("route." + channel, route);
            evidence.setProperty("recording." + generation + ".route." + channel, route);
        }
        return captureLinked;
    }

    private static boolean isLinked(String listing, String output, String input) {
        boolean current = false;
        for (String line : listing.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("|-> ")) {
                if (current && value.substring(4).equals(input)) return true;
            } else if (!value.startsWith("|")) current = value.equals(output);
        }
        return false;
    }

    private List<String> ports(String flag) throws IOException, InterruptedException {
        return output(List.of("/usr/bin/pw-link", "-r", REMOTE, flag)).lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String stereoNode(List<String> ports, String prefix, String excluded) throws IOException {
        Set<String> candidates = new java.util.LinkedHashSet<>();
        for (String port : ports) {
            String suffix = ":" + prefix + "FL";
            if (!port.endsWith(suffix)) continue;
            String node = port.substring(0, port.length() - suffix.length());
            if (!node.equals(excluded) && ports.contains(node + ":" + prefix + "FR")) candidates.add(node);
        }
        require(candidates.size() <= 1, "Ambiguous private stereo audio ports: " + candidates);
        return candidates.isEmpty() ? null : candidates.iterator().next();
    }

    private boolean nextRecordingRequested() throws IOException {
        Path request = directory.resolve("capture-next.txt");
        if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) return false;
        checkPrivate(request, false);
        require(Files.size(request) <= 7 && Files.readString(request).equals("record\n"),
                "Private audio recording request must contain exactly record followed by newline");
        Files.delete(request);
        return true;
    }

    private Recording startRecording(String server, int generation) throws IOException {
        Path pcm = write(directory.resolve("capture-" + generation + ".pcm"), "");
        Process recorder = startCapture(server, generation);
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread capture = new Thread(() -> capture(recorder, pcm, failure), "private-pcm-capture-" + generation);
        capture.start();
        evidence.setProperty("capture.latest.path", pcm.toString());
        evidence.setProperty("capture.recordings", Integer.toString(generation));
        evidence.setProperty("recording." + generation + ".pcm.path", pcm.toString());
        evidence.remove("route.FL");
        evidence.remove("route.FR");
        System.out.println("PRIVATE_AUDIO_CAPTURE " + generation + " " + pcm);
        return new Recording(recorder, capture, failure, pcm, generation);
    }

    private void finishRecording(Recording recording, boolean routed) throws IOException, InterruptedException {
        stop(recording.recorder());
        recording.thread().join(5000);
        require(!recording.thread().isAlive(), "Private audio capture did not stop");
        evidence.setProperty("recording." + recording.generation() + ".routing.status",
                routed ? "linked" : "no-stereo-stream-observed");
        if (recording.failure().get() != null)
            evidence.setProperty("recording." + recording.generation() + ".capture.error", recording.failure().get().toString());
        analysePcm(recording);
    }

    private synchronized Process startCapture(String server, int generation) throws IOException {
        require(!closed, "Private audio session is closed");
        ProcessBuilder builder = builder(List.of("/usr/bin/parec", "--server=" + server,
                "--device=" + SINK + ".monitor", "--client-name=LoadOnceRecorder", "--stream-name=LoadOnceCapture",
                "--raw", "--format=s16le", "--rate=48000", "--channels=2"));
        builder.redirectError(directory.resolve("recorder-" + generation + ".log").toFile());
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    private void capture(Process recorder, Path pcm, AtomicReference<IOException> failure) {
        try (InputStream input = recorder.getInputStream(); var file = Files.newOutputStream(pcm, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            while (total < LIMIT) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, LIMIT - total));
                if (count < 0) break;
                file.write(buffer, 0, count);
                total += count;
            }
        } catch (IOException error) {
            failure.set(error);
        } finally {
            recorder.destroy();
        }
    }

    private void analysePcm(Recording recording) throws IOException {
        Path pcm = recording.pcm();
        long bytes = Files.size(pcm), samples = 0, nonzero = 0, squares = 0;
        int peak = 0, low = -1;
        try (InputStream input = Files.newInputStream(pcm)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) for (int i = 0; i < count; i++) {
                int value = buffer[i] & 255;
                if (low < 0) low = value;
                else {
                    int sample = (short) (low | value << 8);
                    low = -1;
                    samples++;
                    if (sample != 0) nonzero++;
                    peak = Math.max(peak, Math.abs(sample));
                    squares += (long) sample * sample;
                }
            }
        }
        evidence.setProperty("pcm.bytes", Long.toString(bytes));
        evidence.setProperty("pcm.limit.bytes", Long.toString(LIMIT));
        evidence.setProperty("pcm.limit.reached", Boolean.toString(bytes == LIMIT));
        evidence.setProperty("pcm.samples", Long.toString(samples));
        evidence.setProperty("pcm.nonzero.samples", Long.toString(nonzero));
        evidence.setProperty("pcm.peak", Integer.toString(peak));
        evidence.setProperty("pcm.rms", Double.toString(samples == 0 ? 0 : Math.sqrt((double) squares / samples)));
        for (String key : List.of("bytes", "limit.bytes", "limit.reached", "samples", "nonzero.samples", "peak", "rms"))
            evidence.setProperty("recording." + recording.generation() + ".pcm." + key, evidence.getProperty("pcm." + key));
    }

    private ProcessBuilder builder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(session.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder;
    }

    private synchronized Process start(List<String> command, String log) throws IOException {
        require(!closed, "Private audio session is closed");
        Process process = builder(command).redirectErrorStream(true)
                .redirectOutput(directory.resolve(log).toFile()).start();
        processes.add(process);
        return process;
    }

    private synchronized String output(List<String> command) throws IOException, InterruptedException {
        require(!closed, "Private audio session is closed");
        Path log = Files.createTempFile(directory, "query-", ".log");
        Process process = builder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        processes.add(process);
        try {
            require(process.waitFor(3, TimeUnit.SECONDS), "Private audio query timed out: " + command.get(0));
            require(Files.size(log) <= 65536, "Private audio query output exceeds bound");
            String text = Files.readString(log);
            require(process.exitValue() == 0, "Private audio query failed: " + command + ": " + text.strip());
            return text;
        } finally {
            stop(process);
            processes.remove(process);
            Files.deleteIfExists(log);
        }
    }

    private synchronized void rememberDescendants() {
        for (Process process : processes) process.descendants().forEach(handle -> descendants.put(handle.pid(), handle));
    }

    private synchronized void close() {
        if (closed) return;
        closed = true;
        rememberDescendants();
        for (ProcessHandle handle : descendants.values()) if (handle.isAlive()) handle.destroy();
        for (int i = processes.size() - 1; i >= 0; i--) stop(processes.get(i));
        for (ProcessHandle handle : descendants.values()) if (handle.isAlive()) handle.destroyForcibly();
    }

    private static void stop(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String started(ProcessHandle handle) throws IOException {
        return handle.info().startInstant().map(Instant::toString)
                .orElseThrow(() -> new IOException("Process start identity unavailable: " + handle.pid()));
    }

    private static Path privateDirectory(Path path) throws IOException {
        return Files.createDirectory(path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static Path write(Path path, String contents) throws IOException {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, contents, StandardCharsets.UTF_8);
        return path;
    }

    private static void store(Path path, Properties values) throws IOException {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(path)) { values.store(output, "Private audio evidence"); }
    }

    private static void checkPrivate(Path path, boolean directory) throws IOException {
        require(path != null && path.isAbsolute() && path.normalize().equals(path), "Private path must be absolute and normalized");
        for (Path part = path; part != null; part = part.getParent())
            require(!Files.isSymbolicLink(part), "Private path contains a symbolic link: " + part);
        require(directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Private path missing: " + path);
        require(Files.getOwner(path).getName().equals(System.getProperty("user.name")), "Private path owner differs: " + path);
        require(Files.getPosixFilePermissions(path).stream().allMatch(permission -> permission.name().startsWith("OWNER_")),
                "Private path has shared permissions: " + path);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static String configuration(Path socket) {
        return """
                context.properties = { core.daemon = true core.name = loadonce-audio default.clock.rate = 48000 default.clock.quantum = 1024 }
                context.spa-libs = { audio.convert.* = audioconvert/libspa-audioconvert audio.adapt = audioconvert/libspa-audioconvert support.* = support/libspa-support }
                context.modules = [
                  { name = libpipewire-module-protocol-native }
                  { name = libpipewire-module-metadata }
                  { name = libpipewire-module-spa-node-factory }
                  { name = libpipewire-module-client-node }
                  { name = libpipewire-module-adapter }
                  { name = libpipewire-module-link-factory }
                  { name = libpipewire-module-access args = { access.socket = { loadonce-audio = unrestricted } } }
                  { name = libpipewire-module-protocol-pulse }
                ]
                pulse.properties = { server.address = [ "unix:%s" ] server.dbus-name = "" }
                stream.properties = { adapter.auto-port-config = { mode = dsp } }
                context.objects = [
                  { factory = metadata args = { metadata.name = default metadata.values = [
                    { key = default.audio.sink type = "Spa:String:JSON" value = "{ \\"name\\": \\"loadonce_sink\\" }" }
                    { key = default.audio.source type = "Spa:String:JSON" value = "{ \\"name\\": \\"loadonce_sink\\" }" }
                  ] } }
                  { factory = spa-node-factory args = { factory.name = support.node.driver node.name = Dummy-Driver node.group = pipewire.dummy priority.driver = 20000 } }
                  { factory = adapter args = { factory.name = support.null-audio-sink node.name = loadonce_sink node.description = LoadOnce-private-audio media.class = Audio/Sink audio.rate = 48000 audio.position = [ FL FR ] monitor.channel-volumes = true monitor.passthrough = true adapter.auto-port-config = { mode = dsp monitor = true control = false position = preserve } } }
                ]
                """.formatted(socket);
    }
}
