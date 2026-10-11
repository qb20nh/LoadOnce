import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Prototype sender: compositor dispatch evidence only, never game acceptance. */
class PrivateRelativeMotion {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Use PrivateRelativeMotion.java PRIVATE_DISPLAY_SESSION DX DY");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        double dx = Double.parseDouble(args[1]), dy = Double.parseDouble(args[2]);
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || Math.abs(dx) > 4096 || Math.abs(dy) > 4096)
            throw new IllegalArgumentException("Relative deltas must be finite and within -4096..4096");
        Properties marker = validate(root);
        Path socket = root.resolve("xdg/loadonce-relative-input.sock");
        privatePath(socket);
        if ((((Number) Files.getAttribute(socket, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 0170000) != 0140000)
            throw new IOException("Expected the private relative-input Unix socket");
        var timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "private-relative-input-deadline");
            thread.setDaemon(true);
            return thread;
        });
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            timer.schedule(() -> { try { channel.close(); } catch (IOException ignored) { } }, 3, TimeUnit.SECONDS);
            channel.connect(UnixDomainSocketAddress.of(socket));
            ByteBuffer request = StandardCharsets.US_ASCII.encode("MOVE " + dx + " " + dy + "\n");
            while (request.hasRemaining()) channel.write(request);
            ByteBuffer reply = ByteBuffer.allocate(32);
            while (reply.hasRemaining() && channel.read(reply) >= 0) { }
            reply.flip();
            if (!StandardCharsets.US_ASCII.decode(reply).toString().equals("DISPATCHED\n"))
                throw new IOException("Private compositor did not confirm relative-event dispatch");
        } finally { timer.shutdownNow(); }
        Properties after = validate(root);
        for (String field : List.of("controllerPid", "controllerStarted", "westonPid", "westonStarted", "xwaylandPid", "xwaylandStarted"))
            if (!marker.getProperty(field).equals(after.getProperty(field)))
                throw new IOException("Private compositor identity changed during motion request");
        System.out.println("PRIVATE_RELATIVE_MOTION dispatched=" + dx + "," + dy + " game.camera=unverified");
    }

    private static Properties validate(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("loadonce-native-display-"))
            throw new IOException("Expected an owned LoadOnce private display directory");
        privatePath(root);
        Path markerFile = root.resolve("hardware-display.properties");
        privatePath(markerFile);
        if (Files.size(markerFile) > 8192) throw new IOException("Oversized private display marker");
        Properties marker = new Properties();
        try (var input = Files.newInputStream(markerFile)) { marker.load(input); }
        if (!root.toString().equals(marker.getProperty("workDirectory")))
            throw new IOException("Private display marker directory mismatch");
        Path module = Path.of(marker.getProperty("relativeInputModule", ""));
        if (!module.isAbsolute() || !module.normalize().equals(module) || !module.toString().endsWith(".so")
                || module.toString().contains(","))
            throw new IOException("Private display marker must select an absolute normalized .so input module path without commas");
        privatePath(module);
        if (!Files.isRegularFile(module, LinkOption.NOFOLLOW_LINKS) || Files.size(module) == 0 || Files.size(module) > 4L * 1024 * 1024)
            throw new IOException("Private input module must be a nonempty regular .so up to 4 MiB: " + module);
        for (String name : List.of("controller", "weston", "xwayland")) {
            ProcessHandle process = ProcessHandle.of(Long.parseLong(marker.getProperty(name + "Pid", "-1")))
                    .orElseThrow(() -> new IOException("Private display process is absent: " + name));
            if (!process.isAlive() || !process.info().startInstant().orElseThrow().toString().equals(marker.getProperty(name + "Started"))
                    || !process.info().user().equals(ProcessHandle.current().info().user()))
                throw new IOException("Private display process identity changed: " + name);
            if (!name.equals("controller") && process.parent().map(ProcessHandle::pid).orElse(-1L)
                    != Long.parseLong(marker.getProperty("controllerPid")))
                throw new IOException("Private display process is outside its controller: " + name);
            if (name.equals("weston")) {
                List<String> arguments = Arrays.asList(process.info().arguments().orElseThrow());
                List<String> expected = List.of("--backend=headless", "--renderer=gl", "--no-config", "--fake-seat",
                        "--shell=kiosk-shell.so", "--socket=loadonce-private-wayland", "--width=1280", "--height=720",
                        "--idle-time=0", "--log=" + root.resolve("weston.log"), "--modules=" + module);
                if (!process.info().command().orElse("").equals("/usr/bin/weston") || !arguments.equals(expected))
                    throw new IOException("Expected the owned headless Weston with the private input module");
            }
        }
        return marker;
    }

    private static void privatePath(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Symlink in private input path: " + part);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path).getName().equals(ProcessHandle.current().info().user().orElse(""))
                || Files.getPosixFilePermissions(path).stream().anyMatch(permission -> !permission.name().startsWith("OWNER_")))
            throw new IOException("Expected an owner-only private input path: " + path);
    }
}
