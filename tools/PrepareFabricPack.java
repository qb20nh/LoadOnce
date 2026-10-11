import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Download the pinned public pack only after its isolated storage is available. */
class PrepareFabricPack {
    private static final long GIB = 1024L * 1024 * 1024;
    private record Release(String name, String archive, URI source, long bytes, String sha512,
            int javaMajor, long setupGiB) {}

    private static Release release(String key) {
        return switch (key) {
            case "prominence" -> new Release("Prominence II 4.1.3", "Prominence-II-4.1.3.mrpack",
                    URI.create("https://cdn.modrinth.com/data/EGs3lC8D/versions/sXMHqA8W/Prominence%E2%84%A2%20II%20Hasturian%20Era%20%20v4.1.3.mrpack"),
                    423_424_621L, "0c01547c8614ddfff3ba99abe44b50b1dc5d499bb7e7498404e0cf013b6dbffc1f3ff968b016d97db75df7b7f8940ef9fc665c52d34f8049164c1f3f9b9e8b07", 17, 15);
            case "fabulously" -> new Release("Fabulously Optimized 6.5.0", "Fabulously-Optimized-6.5.0.mrpack",
                    URI.create("https://cdn.modrinth.com/data/1KVo5zza/versions/N276l2ON/Fabulously.Optimized-v6.5.0.mrpack"),
                    177_589L, "56d67f427207f561e21eaf53e98e4af1429327f3fa1215361e9bfad73e19fa905687c7692de2a1174d66e3bff4ffd7c7d3fbd8fe88de5d3fb50ba79f673e0f00", 21, 1);
            case "spectral" -> new Release("Spectral Creation 7.1.1", "Spectral-Creation-7.1.1.mrpack",
                    URI.create("https://cdn.modrinth.com/data/eqbP6Ycz/versions/iJhfeFZL/Spectral%20Creation%201.20.1-7.1.1.mrpack"),
                    113_141_122L, "89bd29caf5eecdeaf20a5d46cb054d0bfbed396fbd6856d83b008ab764b76c3e4912d7fa5c3bb63241ff5d7ce68c4d2dab6b1d468899a0828ffbdad227075e22", 17, 2);
            default -> throw new IllegalArgumentException("Unknown pack: " + key + "; use prominence, fabulously, or spectral");
        };
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 4) {
            throw new IllegalArgumentException("Use PrepareFabricPack.java /absolute/storage [--pack prominence|fabulously|spectral] [--check]");
        }
        String key = "prominence";
        boolean check = false, selected = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--check") && !check) check = true;
            else if (args[i].equals("--pack") && !selected && i + 1 < args.length) {
                key = args[++i];
                selected = true;
            } else throw new IllegalArgumentException("Invalid pack option: " + args[i]);
        }
        Release release = release(key);
        Path requested = Path.of(args[0]);
        if (!requested.isAbsolute() || !Files.isDirectory(requested)) {
            throw new IllegalArgumentException("Storage must be an existing absolute directory: " + requested);
        }
        Path directory = requested.toRealPath();
        long free = Files.getFileStore(directory).getUsableSpace();
        Path java = Path.of("/usr/lib/jvm/java-" + release.javaMajor() + "-openjdk/bin/java");
        System.out.println("Pack: " + release.name());
        System.out.println("Storage: " + directory + "; usable bytes=" + free + "; setup minimum bytes=" + release.setupGiB() * GIB);
        System.out.println("Java: " + java);
        if (free < release.setupGiB() * GIB) {
            throw new IllegalStateException("Pack setup stopped before download: " + directory
                    + " needs at least " + release.setupGiB() + " GiB free; VM snapshots require a separate storage preflight.");
        }
        if (!Files.isExecutable(java)) {
            throw new IllegalStateException("Required Java executable is missing: " + java);
        }
        if (check) return;
        Path output = directory.resolve(release.archive());
        if (Files.exists(output)) throw new IllegalStateException("Pack archive already exists; refusing to overwrite " + output);
        Path pending = Files.createTempFile(directory, ".pack-download-", ".part",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        boolean published = false;
        try {
            var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            var response = client.send(HttpRequest.newBuilder(release.source()).timeout(Duration.ofMinutes(15))
                    .header("User-Agent", "LoadOnce-local-modpack-validation/1.0").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            var digest = MessageDigest.getInstance("SHA-512");
            long bytes = 0, reported = 0;
            var deadlines = Executors.newSingleThreadScheduledExecutor();
            Thread downloadThread = Thread.currentThread();
            var deadline = deadlines.schedule(() -> {
                downloadThread.interrupt();
                try { response.body().close(); }
                catch (java.io.IOException ignored) { }
            }, 15, TimeUnit.MINUTES);
            try (InputStream input = response.body(); var target = Files.newOutputStream(pending)) {
                if (response.statusCode() != 200) throw new IllegalStateException("Pack download HTTP " + response.statusCode() + ": " + release.source());
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    bytes += count;
                    if (bytes > release.bytes()) throw new IllegalStateException("Pack archive exceeded its pinned byte size: " + release.source());
                    digest.update(buffer, 0, count);
                    target.write(buffer, 0, count);
                    if (bytes - reported >= 64 * 1024 * 1024) {
                        reported = bytes;
                        System.out.println("Downloaded bytes=" + bytes + "/" + release.bytes());
                    }
                }
            } finally {
                deadline.cancel(false);
                deadlines.shutdownNow();
            }
            if (bytes != release.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(release.sha512())) {
                throw new IllegalStateException("Pack size or SHA-512 mismatch; no archive was published: " + release.source());
            }
            try (var zip = new ZipFile(pending.toFile())) {
                if (zip.getEntry("modrinth.index.json") == null) throw new IllegalStateException("Pinned pack has no Modrinth index");
            }
            Files.move(pending, output, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            System.out.println("Verified pack archive: " + output);
            System.out.println("Import into a separate Prism root: prismlauncher --dir " + quoted(directory.resolve("prism"))
                    + " --import " + quoted(output));
        } finally {
            if (!published) Files.deleteIfExists(pending);
        }
    }

    private static String quoted(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }
}
