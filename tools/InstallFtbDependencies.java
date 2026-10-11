import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Install the four dependencies explicitly requested by this pack's bundled FTB notice. */
class InstallFtbDependencies {
    private static final Path ROOT = Path.of("/run/media/cash/Windows/LoadOnce-fabric-tests-2026-10-11/prism");
    private static final Set<String> HOSTS = Set.of("www.curseforge.com", "edge.forgecdn.net", "mediafilez.forgecdn.net");
    private record Mod(String file, long bytes, String sha512, int project, int release) { }
    private static final List<Mod> MODS = List.of(
            new Mod("ftb-quests-fabric-2001.4.18.jar", 1259696, "472e67958274cb325e6db0f24a3caf935c744489934fddd699e17f1d40b99701f046fd8f9b1608c7b3a353b2f0548b9f36b6d197df120f86511389dae83002c4", 438496, 7608803),
            new Mod("ftb-library-fabric-2001.2.12.jar", 769888, "d3f81173269cce2f66ab3b3409a8db2de4849b4f2e5e8581a0376489af8e1cdfbdeaa1c533a7dda61b684ca2bb654a82a83d868ebd1f2e4b86599197b6179368", 438495, 7296747),
            new Mod("ftb-teams-fabric-2001.3.2.jar", 248853, "5e1b19b5bc3575a050e3d4bc79c93f03b7ac096804b5b49ed75c4f3422d75b32d2a63ce5b9f4566d453e5e9724b56ea15306eb8c3cfa7b4ddebccb985a121e9b", 438497, 7499808),
            new Mod("ftb-xmod-compat-fabric-2.1.3.jar", 131086, "2e99ce5d32c4ada845001c39a71b4b018e1e9e096ddbd5c017c4f4bd89c716f2ce23970100569d2c63eb5f10e311c66bbefeb420e3c11b79556b34abb11b2766", 889915, 6402485));

    public static void main(String[] args) throws Exception {
        require(args.length == 1 && Path.of(args[0]).isAbsolute() && Path.of(args[0]).normalize().equals(ROOT),
                "Use InstallFtbDependencies.java " + ROOT + "; only this private Prism root is permitted");
        Path mods = ROOT.resolve("instances/loadonce-prominence-ii-4.1.3/minecraft/mods");
        rejectSymlinks(mods);
        require(Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS), "Installed pack mods directory is missing: " + mods);
        for (Mod mod : MODS) {
            Path target = mods.resolve(mod.file());
            rejectSymlinks(target);
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || verified(target, mod),
                    "Existing FTB dependency failed verification; refusing overwrite: " + target);
        }
        Path stage = Files.createTempDirectory(mods, ".loadonce-ftb-");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var deadlines = Executors.newSingleThreadScheduledExecutor();
        try {
            for (Mod mod : MODS) {
                if (Files.exists(mods.resolve(mod.file()))) continue;
                Path pending = stage.resolve(mod.file());
                URI uri = URI.create("https://www.curseforge.com/api/v1/mods/" + mod.project() + "/files/" + mod.release() + "/download");
                for (int redirects = 0; ; redirects++) {
                    require("https".equals(uri.getScheme()) && HOSTS.contains(uri.getHost()) && uri.getUserInfo() == null
                                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getFragment() == null,
                            "FTB download left approved public HTTPS hosts for " + mod.file());
                    var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) {
                        try (var body = response.body()) {
                            require(Set.of(301, 302, 303, 307, 308).contains(response.statusCode()) && redirects < 5,
                                    "FTB download HTTP " + response.statusCode() + " for " + mod.file());
                            uri = uri.resolve(response.headers().firstValue("Location").orElseThrow());
                            continue;
                        }
                    }
                    InputStream input = response.body();
                    Thread worker = Thread.currentThread();
                    var deadline = deadlines.schedule(() -> {
                        worker.interrupt();
                        try { input.close(); } catch (java.io.IOException ignored) { }
                    }, 3, TimeUnit.MINUTES);
                    try (input; var output = Files.newOutputStream(pending, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                        Files.setPosixFilePermissions(pending, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                        byte[] buffer = new byte[64 * 1024];
                        long bytes = 0;
                        for (int count; (count = input.read(buffer)) >= 0;) {
                            require(!worker.isInterrupted(), "FTB download interrupted: " + mod.file());
                            bytes += count;
                            require(bytes <= mod.bytes(), "FTB download exceeds pinned size: " + mod.file());
                            output.write(buffer, 0, count);
                        }
                    } finally { deadline.cancel(false); }
                    require(verified(pending, mod), "FTB size/SHA-512 mismatch: " + mod.file());
                    break;
                }
            }
            for (Mod mod : MODS) {
                Path pending = stage.resolve(mod.file()), target = mods.resolve(mod.file());
                if (Files.exists(pending)) {
                    rejectSymlinks(target);
                    require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "FTB destination appeared; refusing overwrite: " + target);
                    Files.move(pending, target);
                }
                require(verified(target, mod), "Installed FTB dependency failed verification: " + target);
                System.out.println("Verified required dependency: " + mod.file());
            }
        } finally {
            deadlines.shutdownNow();
            try (var files = Files.list(stage)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(stage);
        }
    }

    private static boolean verified(Path file, Mod mod) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != mod.bytes()) return false;
        var hash = MessageDigest.getInstance("SHA-512");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long bytes = 0;
            for (int count; (count = input.read(buffer)) >= 0;) {
                bytes += count;
                if (bytes > mod.bytes()) return false;
                hash.update(buffer, 0, count);
            }
            if (bytes != mod.bytes()) return false;
        }
        return HexFormat.of().formatHex(hash.digest()).equals(mod.sha512());
    }

    private static void rejectSymlinks(Path path) {
        Path current = path.toAbsolutePath().getRoot();
        for (Path part : path.toAbsolutePath()) {
            current = current.resolve(part);
            require(!Files.isSymbolicLink(current), "Refusing symbolic-link dependency path: " + current);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
