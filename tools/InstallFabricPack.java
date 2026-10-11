import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Install only a pinned public pack into a new isolated Prism instance; never launch it. */
class InstallFabricPack {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long MAX_ENTRY = 512L * 1024 * 1024;
    private static final long MAX_OVERRIDES = GIB;

    private record Release(String archiveName, long archiveBytes, String archiveSha512, String instanceId,
            String displayName, String minecraftVersion, String fabricVersion, String lwjglVersion, int javaMajor,
            int minMemory, int maxMemory, int clientJars, long payloadBytes, String stagePrefix) {}

    private static final Release PROMINENCE = new Release("Prominence-II-4.1.3.mrpack", 423_424_621L,
            "0c01547c8614ddfff3ba99abe44b50b1dc5d499bb7e7498404e0cf013b6dbffc1f3ff968b016d97db75df7b7f8940ef9fc665c52d34f8049164c1f3f9b9e8b07",
            "loadonce-prominence-ii-4.1.3", "Prominence II 4.1.3 LoadOnce Test", "1.20.1", "0.19.3", "3.3.1", 17,
            1024, 5120, 443, 1_870_766_088L, ".prominence-install-");
    private static final Release FABULOUSLY_OPTIMIZED = new Release("Fabulously-Optimized-6.5.0.mrpack", 177_589L,
            "56d67f427207f561e21eaf53e98e4af1429327f3fa1215361e9bfad73e19fa905687c7692de2a1174d66e3bff4ffd7c7d3fbd8fe88de5d3fb50ba79f673e0f00",
            "loadonce-fabulously-optimized-6.5.0", "Fabulously Optimized 6.5.0 LoadOnce Test", "1.21.1", "0.19.3", "3.3.3", 21,
            256, 1536, 48, 44_322_932L, ".fabulously-install-");
    private static final Release SPECTRAL_CREATION = new Release("Spectral-Creation-7.1.1.mrpack", 113_141_122L,
            "89bd29caf5eecdeaf20a5d46cb054d0bfbed396fbd6856d83b008ab764b76c3e4912d7fa5c3bb63241ff5d7ce68c4d2dab6b1d468899a0828ffbdad227075e22",
            "loadonce-spectral-creation-7.1.1", "Spectral Creation 7.1.1 LoadOnce Test", "1.20.1", "0.18.4", "3.3.1", 17,
            256, 2304, 146, 367_007_501L, ".spectral-install-");
    private static final List<Release> RELEASES = List.of(PROMINENCE, FABULOUSLY_OPTIMIZED, SPECTRAL_CREATION);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Use InstallFabricPack.java /absolute/pinned-pack.mrpack /absolute/prism-root; archives: "
                    + RELEASES.stream().map(Release::archiveName).toList());
        }
        Path archive = Path.of(args[0]);
        Path root = Path.of(args[1]);
        if (!archive.isAbsolute() || !root.isAbsolute()) {
            throw new IllegalArgumentException("Use absolute archive and Prism root paths");
        }
        Release release = RELEASES.stream()
                .filter(candidate -> archive.getFileName().toString().equals(candidate.archiveName())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported pinned archive filename: " + archive.getFileName()));
        rejectSymlinks(archive);
        rejectSymlinks(root);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                || Files.size(archive) != release.archiveBytes()) {
            throw new IllegalStateException("Pinned archive size mismatch: " + archive);
        }
        var archiveDigest = MessageDigest.getInstance("SHA-512");
        try (var input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) archiveDigest.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(archiveDigest.digest()).equals(release.archiveSha512())) {
            throw new IllegalStateException("Pinned archive SHA-512 mismatch: " + archive);
        }
        System.out.println("Verified pinned archive: " + archive);
        String javaExecutable = "/usr/lib/jvm/java-" + release.javaMajor() + "-openjdk/bin/java";
        if (!Files.isExecutable(Path.of(javaExecutable))) {
            throw new IllegalStateException("Java " + release.javaMajor() + " executable missing: " + javaExecutable);
        }
        Files.createDirectories(root);
        rejectSymlinks(root);
        root = root.toRealPath();
        long free = Files.getFileStore(root).getUsableSpace();
        if (free < release.payloadBytes() + 3 * GIB) {
            throw new IllegalStateException("Insufficient storage at " + root + ": usable bytes=" + free
                    + "; requires pack payload " + release.payloadBytes() + " bytes plus 3 GiB reserve");
        }
        Path instances = root.resolve("instances");
        rejectSymlinks(instances);
        Files.createDirectories(instances);
        Path destination = instances.resolve(release.instanceId());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Refusing to overwrite existing Prism instance: " + destination);
        }
        Path stage = Files.createTempDirectory(instances, release.stagePrefix());
        boolean published = false;
        try (var zip = new ZipFile(archive.toFile())) {
            Path minecraft = stage.resolve("minecraft");
            Files.createDirectory(minecraft);
            var indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null || indexEntry.getSize() < 0 || indexEntry.getSize() > 1024 * 1024) {
                throw new IllegalStateException("Missing or oversized Modrinth index: " + archive);
            }
            JsonObject index;
            try (var input = zip.getInputStream(indexEntry)) {
                byte[] content = input.readNBytes(1024 * 1024 + 1);
                if (content.length > 1024 * 1024) throw new IllegalStateException("Modrinth index exceeds 1 MiB");
                index = JsonParser.parseString(new String(content, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            var dependencies = index.getAsJsonObject("dependencies");
            if (index.get("formatVersion").getAsInt() != 1
                    || !"minecraft".equals(index.get("game").getAsString())
                    || !release.minecraftVersion().equals(dependencies.get("minecraft").getAsString())
                    || !release.fabricVersion().equals(dependencies.get("fabric-loader").getAsString())) {
                throw new IllegalStateException("Pinned pack index has unexpected Minecraft/Fabric dependencies");
            }
            var files = new ArrayList<JsonObject>();
            var targets = new HashSet<Path>();
            long indexedBytes = 0;
            for (var item : index.getAsJsonArray("files")) {
                JsonObject file = item.getAsJsonObject();
                var env = file.getAsJsonObject("env");
                if (env != null && env.has("client") && "unsupported".equals(env.get("client").getAsString())) continue;
                Path target = contained(minecraft, file.get("path").getAsString());
                if (!targets.add(target)) throw new IllegalStateException("Duplicate index path: " + target);
                long size = file.get("fileSize").getAsLong();
                if (size <= 0 || size > MAX_ENTRY) throw new IllegalStateException("Invalid declared file size: " + target);
                indexedBytes = Math.addExact(indexedBytes, size);
                if (indexedBytes > 2 * GIB) throw new IllegalStateException("Indexed payload exceeds 2 GiB");
                JsonObject hashes = file.getAsJsonObject("hashes");
                if (hashes == null || (!hashes.has("sha512") && !hashes.has("sha1"))) {
                    throw new IllegalStateException("Missing SHA-512/SHA-1 hash for " + target);
                }
                for (String key : List.of("sha512", "sha1")) {
                    if (hashes.has(key) && !hashes.get(key).getAsString().matches(key.equals("sha512") ? "[0-9a-f]{128}" : "[0-9a-f]{40}")) {
                        throw new IllegalStateException("Invalid " + key + " hash for " + target);
                    }
                }
                if (file.getAsJsonArray("downloads").isEmpty()) throw new IllegalStateException("No download URL for " + target);
                for (var url : file.getAsJsonArray("downloads")) httpsUri(URI.create(url.getAsString()));
                files.add(file);
            }
            var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            var workers = Executors.newFixedThreadPool(4);
            var deadlines = Executors.newSingleThreadScheduledExecutor();
            try {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (var file : files) {
                    futures.add(workers.submit(() -> {
                        try { download(client, file, minecraft, deadlines); }
                        catch (Exception error) { throw new IllegalStateException("Failed pack file " + file.get("path").getAsString() + ": " + error.getMessage(), error); }
                    }));
                }
                int complete = 0;
                for (var future : futures) {
                    future.get();
                    complete++;
                    if (complete % 25 == 0 || complete == files.size()) {
                        System.out.println("Verified manifest files=" + complete + "/" + files.size());
                    }
                }
            } finally {
                workers.shutdownNow();
                while (!workers.awaitTermination(1, TimeUnit.SECONDS)) { /* Wait before deleting owned worker files. */ }
                deadlines.shutdownNow();
            }
            long overrideBytes = 0;
            for (String prefix : List.of("overrides/", "client-overrides/")) {
                var entries = zip.entries();
                var seen = new HashSet<Path>();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().startsWith(prefix) || entry.getName().equals(prefix)) continue;
                    Path target = contained(minecraft, entry.getName().substring(prefix.length()));
                    if (!seen.add(target)) throw new IllegalStateException("Duplicate ZIP override: " + entry.getName());
                    if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                    if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY) throw new IllegalStateException("Oversized ZIP override: " + entry.getName());
                    overrideBytes = Math.addExact(overrideBytes, entry.getSize());
                    if (overrideBytes > MAX_OVERRIDES) throw new IllegalStateException("ZIP overrides exceed 1 GiB");
                    Files.createDirectories(target.getParent());
                    rejectSymlinks(target);
                    try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(target)) {
                        long bytes = 0;
                        byte[] buffer = new byte[64 * 1024];
                        for (int count; (count = input.read(buffer)) >= 0;) {
                            bytes += count;
                            if (bytes > entry.getSize()) throw new IllegalStateException("ZIP override exceeds declared size: " + entry.getName());
                            output.write(buffer, 0, count);
                        }
                        if (bytes != entry.getSize()) throw new IllegalStateException("Truncated ZIP override: " + entry.getName());
                    }
                }
            }
            long jars;
            try (var paths = Files.walk(minecraft.resolve("mods"))) {
                jars = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().endsWith(".jar")).count();
            }
            if (jars != release.clientJars()) throw new IllegalStateException("Expected " + release.clientJars() + " client mod JARs, found " + jars);
            Files.writeString(stage.resolve("instance.cfg"), """
                    [General]
                    ConfigVersion=1.2
                    InstanceType=OneSix
                    name=%s
                    JavaPath=%s
                    OverrideJavaLocation=true
                    OverrideJavaArgs=true
                    JvmArgs=-XX:+UseG1GC
                    OverrideMemory=true
                    MinMemAlloc=%d
                    MaxMemAlloc=%d
                    OverrideWindow=true
                    MinecraftWinWidth=1280
                    MinecraftWinHeight=720
                    ManagedPack=false
                    """.formatted(release.displayName(), javaExecutable, release.minMemory(), release.maxMemory()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(stage.resolve("mmc-pack.json"), """
                    {"formatVersion":1,"components":[
                      {"uid":"org.lwjgl3","version":"%s"},
                      {"uid":"net.minecraft","version":"%s","important":true},
                      {"uid":"net.fabricmc.intermediary","version":"%s"},
                      {"uid":"net.fabricmc.fabric-loader","version":"%s","important":true}
                    ]}
                    """.formatted(release.lwjglVersion(), release.minecraftVersion(), release.minecraftVersion(), release.fabricVersion()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Instance appeared during installation: " + destination);
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            System.out.println("Installed Prism profile: " + destination + "; client mod JARs=" + jars);
            System.out.println("Local heap budget: " + release.maxMemory() + " MiB. Game was not launched; VM readiness is not established.");
        } finally {
            if (!published) {
                try (var paths = Files.walk(stage)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
                System.err.println("Installation failed; no instance was published: " + destination);
            }
        }
    }

    private static void download(HttpClient client, JsonObject file, Path minecraft,
            ScheduledExecutorService deadlines) throws Exception {
        Path target = contained(minecraft, file.get("path").getAsString());
        Files.createDirectories(target.getParent());
        rejectSymlinks(target);
        URI uri = URI.create(file.getAsJsonArray("downloads").get(0).getAsString());
        HttpResponse<InputStream> response = null;
        for (int redirects = 0; redirects <= 5; redirects++) {
            httpsUri(uri);
            response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "LoadOnce-local-modpack-validation/1.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) break;
            try (var body = response.body()) {
                if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode()) && redirects < 5) {
                    uri = uri.resolve(response.headers().firstValue("Location").orElseThrow(() -> new IllegalStateException("Redirect has no Location")));
                    continue;
                }
                throw new IllegalStateException("Download HTTP " + response.statusCode() + ": " + uri);
            }
        }
        JsonObject hashes = file.getAsJsonObject("hashes");
        var sha512 = MessageDigest.getInstance("SHA-512");
        var sha1 = MessageDigest.getInstance("SHA-1");
        long expected = file.get("fileSize").getAsLong(), bytes = 0;
        var input = response.body();
        URI source = uri;
        Thread worker = Thread.currentThread();
        var deadline = deadlines.schedule(() -> {
            System.err.println("Download body exceeded five-minute deadline: " + source);
            worker.interrupt();
            try { input.close(); }
            catch (java.io.IOException error) { System.err.println("Cannot close timed-out download: " + error.getMessage()); }
        }, 5, TimeUnit.MINUTES);
        try (input; var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Download interrupted");
                bytes += count;
                if (bytes > expected) throw new IllegalStateException("Download exceeds declared " + expected + " bytes: " + uri);
                sha512.update(buffer, 0, count);
                sha1.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        } finally {
            deadline.cancel(false);
        }
        if (bytes != expected || (hashes.has("sha512") && !hashes.get("sha512").getAsString().equals(HexFormat.of().formatHex(sha512.digest())))
                || (hashes.has("sha1") && !hashes.get("sha1").getAsString().equals(HexFormat.of().formatHex(sha1.digest())))) {
            throw new IllegalStateException("Size or SHA-512/SHA-1 mismatch: " + target + "; source=" + uri);
        }
    }

    private static void httpsUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Pinned manifest download must use HTTPS: " + uri);
        }
    }

    private static Path contained(Path base, String name) throws Exception {
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Unsafe pack path: " + name);
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..") || segment.equals(".")) throw new IllegalArgumentException("Unsafe pack path: " + name);
        }
        Path target = base.resolve(name).normalize();
        if (!target.startsWith(base) || target.equals(base)) throw new IllegalArgumentException("Pack path escapes instance: " + name);
        rejectSymlinks(target);
        return target;
    }

    private static void rejectSymlinks(Path path) throws Exception {
        Path current = path.toAbsolutePath().getRoot();
        for (Path part : path.toAbsolutePath()) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Refusing symbolic-link path: " + current);
        }
    }
}
