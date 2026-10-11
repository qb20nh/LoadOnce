import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Properties;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Exercise the real integrated server's status and ping protocol after publishing a private world to LAN. */
class MinecraftNetworkCheck {
    private static final Path RUNS = Path.of("/run/media/cash/Windows/LoadOnce-fabric-tests-2026-10-11/prism/isolated-validation");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Use MinecraftNetworkCheck.java PRIVATE_RUN_DIRECTORY LAN_PORT");
        Path run = Path.of(args[0]).toAbsolutePath().normalize();
        require(run.getParent().equals(RUNS) && run.getFileName().toString().startsWith("run-"), "Expected private test run directory");
        for (Path p = run; p != null; p = p.getParent()) require(!Files.isSymbolicLink(p), "Symlink in private run path: " + p);
        Path marker = run.resolve("run.properties");
        require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker)
                && Files.size(marker) <= 8192, "Private run marker is missing or oversized");
        var state = new Properties();
        try (var input = Files.newInputStream(marker)) { state.load(input); }
        var helper = ProcessHandle.of(Long.parseLong(state.getProperty("helperPid", "-1")))
                .orElseThrow(() -> new IOException("Private game helper is absent"));
        require("running".equals(state.getProperty("status")) && run.toString().equals(state.getProperty("runDirectory"))
                && helper.isAlive() && helper.info().startInstant().orElseThrow().toString().equals(state.getProperty("helperStarted")),
                "Private game run is stale or stopped");
        int port = Integer.parseInt(args[1]);
        require(port >= 1024 && port <= 65535, "LAN port must be 1024-65535");
        var listener = ownedListener(helper, port);
        String listenerStarted = listener.info().startInstant().orElseThrow().toString();
        long started = System.nanoTime();
        String status;
        var deadline = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "private-network-deadline");
            thread.setDaemon(true);
            return thread;
        });
        try (var socket = new Socket()) {
            deadline.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 10, TimeUnit.SECONDS);
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            socket.setSoTimeout(5000);
            var output = new DataOutputStream(socket.getOutputStream());
            var input = new DataInputStream(socket.getInputStream());
            var handshake = new ByteArrayOutputStream();
            var body = new DataOutputStream(handshake);
            varInt(body, 0);
            varInt(body, -1); // Status requests are independent of the player's game protocol version.
            byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            varInt(body, host.length);
            body.write(host);
            body.writeShort(port);
            varInt(body, 1);
            packet(output, handshake.toByteArray());
            packet(output, new byte[] {0});
            var response = new DataInputStream(new ByteArrayInputStream(packet(input)));
            require(varInt(response) == 0, "Expected Minecraft status response packet");
            int length = varInt(response);
            require(length > 0 && length <= 1024 * 1024, "Invalid Minecraft status JSON length");
            byte[] json = response.readNBytes(length);
            require(json.length == length && response.available() == 0, "Truncated or trailing Minecraft status response");
            status = new String(json, StandardCharsets.UTF_8);
            require(status.startsWith("{") && status.endsWith("}") && status.contains("\"version\"")
                    && status.contains("\"players\"") && status.contains("\"description\""), "Missing Minecraft server status fields");
            long nonce = System.nanoTime();
            var ping = new ByteArrayOutputStream();
            var pingBody = new DataOutputStream(ping);
            varInt(pingBody, 1);
            pingBody.writeLong(nonce);
            packet(output, ping.toByteArray());
            var pong = new DataInputStream(new ByteArrayInputStream(packet(input)));
            require(varInt(pong) == 1 && pong.readLong() == nonce && pong.available() == 0, "Minecraft ping nonce mismatch");
        } finally { deadline.shutdownNow(); }
        require(listener.isAlive() && listener.info().startInstant().orElseThrow().toString().equals(listenerStarted)
                && ownedListener(helper, port).pid() == listener.pid(), "Private server listener changed during the probe");
        Path evidence = run.resolve("network-status-" + started + ".json");
        Files.writeString(evidence, status + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("MINECRAFT_NETWORK PASS status+ping loopback port=" + port + " privateListenerPid=" + listener.pid() + " elapsedMillis="
                + Duration.ofNanos(System.nanoTime() - started).toMillis() + " evidence=" + evidence);
        System.out.println("Scope: real integrated-server TCP status and ping; authenticated multiplayer remains unverified.");
    }

    private static ProcessHandle ownedListener(ProcessHandle helper, int port) throws IOException {
        var inodes = new HashSet<String>();
        for (String table : new String[] {"/proc/net/tcp", "/proc/net/tcp6"}) {
            for (String line : Files.readAllLines(Path.of(table))) {
                String[] fields = line.strip().split("\\s+");
                if (fields.length > 9 && fields[3].equals("0A")) {
                    String local = fields[1];
                    int separator = local.lastIndexOf(':');
                    String address = local.substring(0, separator);
                    boolean loopbackOrWildcard = address.equals("00000000") || address.equals("0100007F")
                            || address.equals("00000000000000000000000000000000")
                            || address.equals("0000000000000000FFFF00000100007F");
                    if (loopbackOrWildcard && Integer.parseInt(local.substring(separator + 1), 16) == port) inodes.add(fields[9]);
                }
            }
        }
        for (var process : helper.descendants().toList()) {
            if (!process.isAlive()) continue;
            var started = process.info().startInstant();
            if (started.isEmpty()) continue;
            try (var descriptors = Files.list(Path.of("/proc", Long.toString(process.pid()), "fd"))) {
                for (Path descriptor : descriptors.toList()) {
                    try {
                        String link = Files.readSymbolicLink(descriptor).toString();
                        if (link.startsWith("socket:[") && link.endsWith("]")
                                && inodes.contains(link.substring(8, link.length() - 1))
                                && process.isAlive() && process.info().startInstant().equals(started)) return process;
                    } catch (IOException vanishedDescriptor) { /* A game thread may close descriptors during inspection. */ }
                }
            } catch (IOException exitedProcess) { /* A launcher child may finish during inspection. */ }
        }
        throw new IOException("LAN port " + port + " has no listener owned by this private game's live process tree");
    }

    private static void packet(DataOutputStream output, byte[] body) throws IOException {
        varInt(output, body.length);
        output.write(body);
        output.flush();
    }

    private static byte[] packet(DataInputStream input) throws IOException {
        int length = varInt(input);
        require(length > 0 && length <= 1024 * 1024 + 16, "Invalid Minecraft packet length");
        byte[] body = input.readNBytes(length);
        require(body.length == length, "Truncated Minecraft packet");
        return body;
    }

    private static void varInt(DataOutputStream output, int value) throws IOException {
        do {
            int next = value & 127;
            value >>>= 7;
            output.writeByte(value == 0 ? next : next | 128);
        } while (value != 0);
    }

    private static int varInt(DataInputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.readUnsignedByte();
            if (shift == 28) require((next & 0xf0) == 0, "Minecraft VarInt exceeds 32 bits");
            value |= (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IOException("Minecraft VarInt exceeds five bytes");
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
