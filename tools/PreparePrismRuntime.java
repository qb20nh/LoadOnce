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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Populate only the pinned private Prism runtime from public artifacts; never launch or read accounts. */
class PreparePrismRuntime {
    private static final Path ROOT = Path.of("/run/media/cash/Windows/LoadOnce-fabric-tests-2026-10-11/prism");
    private static final Path CACHE = Path.of("/home/cash/.local/share/PrismLauncher");
    private static final Set<String> HOSTS = Set.of("build.lwjgl.org", "libraries.minecraft.net", "piston-data.mojang.com",
            "piston-meta.mojang.com", "maven.fabricmc.net", "resources.download.minecraft.net", "meta.prismlauncher.org");
    private static final long MAX_ARTIFACT = 128L * 1024 * 1024;
    private static final long MAX_JSON = 2L * 1024 * 1024;
    private static final long GIB = 1024L * 1024 * 1024;

    private record Artifact(String path, URI uri, long size, String sha1) {}
    private record Profile(String key, String instance, String minecraft, String loader, int javaMajor, int memory) {}
    private static final Profile PROMINENCE = new Profile("prominence", "loadonce-prominence-ii-4.1.3", "1.20.1", "0.19.3", 17, 5120);
    private static final List<Profile> PROFILES = List.of(PROMINENCE,
            new Profile("fabulously", "loadonce-fabulously-optimized-6.5.0", "1.21.1", "0.19.3", 21, 1536),
            new Profile("spectral", "loadonce-spectral-creation-7.1.1", "1.20.1", "0.18.4", 17, 2304));

    public static void main(String[] args) throws Exception {
        require(args.length >= 1 && Path.of(args[0]).isAbsolute() && Path.of(args[0]).normalize().equals(ROOT),
                "Use PreparePrismRuntime.java " + ROOT + " [--pack prominence|fabulously|spectral] [--export-spec /absolute/spec.properties]");
        Profile profile = PROMINENCE;
        Path export = null;
        boolean packSelected = false;
        for (int i = 1; i < args.length; i += 2) {
            require(i + 1 < args.length, "Missing value for runtime option " + args[i]);
            if (args[i].equals("--pack")) {
                require(!packSelected, "Duplicate --pack option");
                String key = args[i + 1];
                profile = PROFILES.stream().filter(candidate -> candidate.key().equals(key)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unsupported pinned pack: " + key));
                packSelected = true;
            } else if (args[i].equals("--export-spec")) {
                require(export == null, "Duplicate --export-spec option");
                export = Path.of(args[i + 1]);
                require(export.isAbsolute(), "Runtime export path must be absolute");
                export = export.normalize();
                rejectSymlinks(export);
                require(!Files.exists(export, LinkOption.NOFOLLOW_LINKS), "Refusing to overwrite runtime export: " + export);
            } else {
                throw new IllegalArgumentException("Unsupported runtime option: " + args[i]);
            }
        }
        require(export == null || profile != PROMINENCE, "Opaque VM export supports only Fabulously Optimized and Spectral Creation");
        require(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"),
                "This pinned runtime preparation supports only Linux amd64");
        rejectSymlinks(ROOT);
        require(Files.isDirectory(ROOT, LinkOption.NOFOLLOW_LINKS), "Missing private Prism root: " + ROOT);
        JsonObject pack = readJson(contained(ROOT, "instances/" + profile.instance() + "/mmc-pack.json"));
        var components = pack.getAsJsonArray("components");
        require(components.size() == 4, "Instance must contain exactly the four pinned components");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        ScheduledExecutorService deadlines = Executors.newScheduledThreadPool(4);
        try {
        JsonObject minecraft = componentMetadata(client, deadlines, "net.minecraft", profile.minecraft());
        String lwjgl = null;
        for (var requirement : minecraft.getAsJsonArray("requires")) {
            JsonObject item = requirement.getAsJsonObject();
            if (item.get("uid").getAsString().equals("org.lwjgl3")) {
                require(lwjgl == null, "Duplicate Minecraft LWJGL requirement");
                lwjgl = item.get(item.has("equals") ? "equals" : "suggests").getAsString();
            }
        }
        require(lwjgl != null && lwjgl.matches("[0-9]+(?:\\.[0-9]+){2}"), "Missing/invalid Minecraft LWJGL requirement");
        boolean javaMatches = false;
        for (var major : minecraft.getAsJsonArray("compatibleJavaMajors")) if (major.getAsInt() == profile.javaMajor()) javaMatches = true;
        require(javaMatches, "Minecraft metadata does not support pinned Java " + profile.javaMajor());
        Map<String, String> expected = Map.of("org.lwjgl3", lwjgl, "net.minecraft", profile.minecraft(),
                "net.fabricmc.intermediary", profile.minecraft(), "net.fabricmc.fabric-loader", profile.loader());
        var seen = new java.util.HashSet<String>();
        var files = new LinkedHashMap<String, Artifact>();
        JsonObject fabric = null;
        for (var entry : components) {
            JsonObject component = entry.getAsJsonObject();
            String uid = component.get("uid").getAsString(), version = component.get("version").getAsString();
            require(version.equals(expected.get(uid)) && seen.add(uid), "Unexpected instance component: " + uid + ":" + version);
            JsonObject metadata = uid.equals("net.minecraft") ? minecraft : componentMetadata(client, deadlines, uid, version);
            for (var library : metadata.getAsJsonArray("libraries")) {
                JsonObject item = library.getAsJsonObject();
                if (linuxLibrary(item)) add(files, library(item));
            }
            if (uid.equals("net.minecraft")) {
                add(files, library(metadata.getAsJsonObject("mainJar")));
            }
            if (uid.equals("net.fabricmc.fabric-loader")) fabric = metadata;
        }
        require(fabric != null, "Missing pinned Fabric metadata");
        JsonObject index = minecraft.getAsJsonObject("assetIndex");
        String indexId = index.get("id").getAsString();
        require(indexId.matches("[0-9]{1,8}"), "Invalid Minecraft asset index identity");
        Artifact assetIndex = descriptor("assets/indexes/" + indexId + ".json", index);
        require(assetIndex.size() <= MAX_JSON, "Asset index exceeds 2 MiB");
        long estimate = index.get("totalSize").getAsLong();
        require(estimate > 0 && estimate < GIB, "Unexpected asset payload size: " + estimate);
        require(Files.getFileStore(ROOT).getUsableSpace() >= estimate + GIB,
                "Runtime preparation requires asset payload " + estimate + " bytes plus 1 GiB reserve at " + ROOT);
            for (Artifact artifact : files.values()) prepare(client, deadlines, artifact);
            prepare(client, deadlines, assetIndex);
            JsonObject objects = readJson(contained(ROOT, assetIndex.path())).getAsJsonObject("objects");
            var assets = new LinkedHashMap<String, Artifact>();
            long total = 0;
            for (var entry : objects.entrySet()) {
                JsonObject object = entry.getValue().getAsJsonObject();
                String hash = object.get("hash").getAsString();
                require(hash.matches("[0-9a-f]{40}"), "Invalid asset SHA-1 for " + entry.getKey());
                long size = object.get("size").getAsLong();
                require(size > 0 && size <= MAX_ARTIFACT, "Invalid asset size for " + entry.getKey());
                String path = hash.substring(0, 2) + "/" + hash;
                add(assets, new Artifact("assets/objects/" + path,
                        URI.create("https://resources.download.minecraft.net/" + path), size, hash));
                total = Math.addExact(total, size);
            }
            require(total <= 2 * GIB, "Pinned asset index exceeds the 2 GiB payload limit");
            require(Files.getFileStore(ROOT).getUsableSpace() >= total + GIB,
                    "Asset preparation requires " + total + " bytes plus 1 GiB reserve at " + ROOT);
            var workers = Executors.newFixedThreadPool(4);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (Artifact asset : assets.values()) {
                    futures.add(workers.submit(() -> {
                        try { prepare(client, deadlines, asset); }
                        catch (Exception error) { throw new IllegalStateException("Failed runtime file " + asset.path() + ": " + error.getMessage(), error); }
                    }));
                }
                int complete = 0;
                for (var future : futures) {
                    future.get();
                    complete++;
                    if (complete % 250 == 0 || complete == assets.size())
                        System.out.println("Verified runtime assets=" + complete + "/" + assets.size());
                }
            } finally {
                workers.shutdownNow();
                while (!workers.awaitTermination(1, TimeUnit.SECONDS)) { /* Do not leave downloads running. */ }
            }
            System.out.println("Prepared public runtime: " + files.size() + " libraries/client jars, " + assets.size()
                    + " assets; pack=" + profile.key() + "; private root=" + ROOT);
            if (export != null) exportSpecification(export, profile, minecraft, fabric, files, indexId);
        } finally {
            deadlines.shutdownNow();
        }
    }

    private static JsonObject componentMetadata(HttpClient client, ScheduledExecutorService deadlines, String uid, String version) throws Exception {
        require(Set.of("org.lwjgl3", "net.minecraft", "net.fabricmc.intermediary", "net.fabricmc.fabric-loader").contains(uid)
                && version.matches("[0-9]+(?:\\.[0-9]+){2}"), "Unsupported component metadata identity");
        Path target = contained(ROOT, "meta/" + uid + "/" + version + ".json");
        JsonObject metadata;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            metadata = readJson(target);
        } else {
            URI uri = URI.create("https://meta.prismlauncher.org/v1/" + uid + "/" + version + ".json");
            byte[] content = receive(client, deadlines, uri, null, MAX_JSON, -1);
            metadata = JsonParser.parseString(new String(content, StandardCharsets.UTF_8)).getAsJsonObject();
            require(uid.equals(metadata.get("uid").getAsString()) && version.equals(metadata.get("version").getAsString()),
                    "Official component metadata identity mismatch: " + uid + ":" + version);
            directories(target.getParent());
            Path stage = Files.createTempFile(target.getParent(), ".metadata-", ".part");
            privateFile(stage);
            try {
                Files.write(stage, content, StandardOpenOption.TRUNCATE_EXISTING);
                rejectSymlinks(target);
                require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Component metadata appeared; refusing overwrite: " + target);
                Files.move(stage, target);
            } finally {
                Files.deleteIfExists(stage);
            }
        }
        require(uid.equals(metadata.get("uid").getAsString()) && version.equals(metadata.get("version").getAsString()),
                "Resolved metadata identity mismatch: " + uid + ":" + version);
        return metadata;
    }

    private static void exportSpecification(Path target, Profile profile, JsonObject minecraft, JsonObject fabric,
            Map<String, Artifact> files, String indexId) throws Exception {
        Path game = contained(ROOT, "instances/" + profile.instance() + "/minecraft");
        Path natives = persistentNatives(profile, files);
        Path assets = contained(ROOT, "assets");
        Path javaHome = Path.of("/usr/lib/jvm/java-" + profile.javaMajor() + "-openjdk");
        require(Files.isDirectory(game, LinkOption.NOFOLLOW_LINKS), "Missing export game directory: " + game);
        rejectSymlinks(javaHome);
        require(Files.isExecutable(javaHome.resolve("bin/java")), "Missing pinned export Java runtime: " + javaHome);
        String main = fabric.get("mainClass").getAsString();
        require(main.equals("net.fabricmc.loader.impl.launch.knot.KnotClient"), "Unexpected Fabric client entrypoint: " + main);
        Properties spec = new Properties();
        spec.setProperty("java.home", javaHome.toString());
        spec.setProperty("game.directory", game.toString());
        spec.setProperty("assets.directory", assets.toString());
        spec.setProperty("native.directory", natives.toString());
        spec.setProperty("memory.mib", Integer.toString(profile.memory()));
        spec.setProperty("main.class", main);
        require(!files.isEmpty() && files.size() <= 1024, "Invalid export classpath count");
        spec.setProperty("classpath.count", Integer.toString(files.size()));
        int classIndex = 0;
        for (Artifact artifact : files.values()) {
            Path path = contained(ROOT, artifact.path());
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toString().endsWith(".jar"), "Missing export classpath JAR: " + path);
            spec.setProperty("classpath." + classIndex++, path.toString());
        }
        Map<String, String> values = Map.of("auth_player_name", "LoadOnceTest", "version_name", profile.minecraft(),
                "game_directory", game.toString(), "assets_root", assets.toString(), "assets_index_name", indexId,
                "auth_uuid", UUID.nameUUIDFromBytes("OfflinePlayer:LoadOnceTest".getBytes(StandardCharsets.UTF_8)).toString(),
                "auth_access_token", "0", "user_type", "legacy", "version_type", "release");
        var arguments = new ArrayList<String>();
        String template = minecraft.get("minecraftArguments").getAsString();
        require(template.length() <= 8192, "Minecraft argument template exceeds export limit");
        for (String argument : template.trim().split("\\s+")) {
            for (var value : values.entrySet()) argument = argument.replace("${" + value.getKey() + "}", value.getValue());
            require(!argument.contains("${") && argument.indexOf('\0') < 0, "Unsupported Minecraft export argument placeholder/value");
            arguments.add(argument);
        }
        arguments.addAll(List.of("--width", "1280", "--height", "720"));
        require(arguments.size() <= 4096, "Minecraft export exceeds 4096 arguments");
        spec.setProperty("arguments.count", Integer.toString(arguments.size()));
        for (int i = 0; i < arguments.size(); i++) spec.setProperty("arguments." + i, arguments.get(i));
        require(Files.getFileStore(game).supportsFileAttributeView("posix"), "Opaque VM game directory requires POSIX privacy: " + game);
        Files.setPosixFilePermissions(game, PosixFilePermissions.fromString("rwx------"));
        rejectSymlinks(target);
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Refusing to overwrite runtime export: " + target);
        directories(target.getParent());
        Path stage = Files.createTempFile(target.getParent(), ".offline-spec-", ".part");
        privateFile(stage);
        try {
            try (var output = Files.newOutputStream(stage, StandardOpenOption.TRUNCATE_EXISTING)) { spec.store(output, "LoadOnce offline opaque VM specification"); }
            rejectSymlinks(target);
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Runtime export appeared; refusing overwrite: " + target);
            Files.move(stage, target);
            System.out.println("Exported offline VM specification: " + target);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static Path persistentNatives(Profile profile, Map<String, Artifact> files) throws Exception {
        Path directory = contained(ROOT, "opaque-natives/" + profile.instance());
        directories(directory);
        require(Files.getFileStore(directory).supportsFileAttributeView("posix"), "Opaque native bundle requires POSIX privacy: " + directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        var expected = new LinkedHashMap<String, Artifact>();
        int entryCount = 0;
        long total = 0;
        for (Artifact artifact : files.values()) {
            boolean lwjgl = artifact.path().matches("libraries/org/lwjgl/[^/]+-natives-linux/.*\\.jar");
            boolean netty = artifact.path().matches("libraries/io/netty/[^/]+/[^/]+/[^/]+-linux-x86_64\\.jar");
            if (!lwjgl && !netty) continue;
            Path jar = contained(ROOT, artifact.path());
            require(artifact.sha1() != null && verified(jar, artifact), "Native source JAR failed size/SHA-1 verification: " + jar);
            try (var zip = new ZipFile(jar.toFile())) {
                require(zip.size() <= 1024, "Native source JAR exceeds 1024 ZIP entries: " + jar);
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().matches(".*\\.so(?:\\.[0-9]+)*")) continue;
                    contained(directory, entry.getName());
                    String name = Path.of(entry.getName()).getFileName().toString();
                    require(name.matches("[A-Za-z0-9_.+\\-]+"), "Unsafe native library filename in " + jar);
                    require(entry.getSize() > 0 && entry.getSize() <= 16L * 1024 * 1024, "Invalid native entry size: " + jar + "!" + entry.getName());
                    entryCount++;
                    total = Math.addExact(total, entry.getSize());
                    require(entryCount <= 64 && total <= 64L * 1024 * 1024, "Native bundle exceeds 64 files/64 MiB: " + directory);
                    Path destination = contained(directory, name);
                    Path stage = Files.createTempFile(directory, ".native-", ".part");
                    privateFile(stage);
                    try {
                        var digest = MessageDigest.getInstance("SHA-1");
                        long bytes = 0;
                        try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(stage, StandardOpenOption.TRUNCATE_EXISTING)) {
                            byte[] buffer = new byte[64 * 1024];
                            for (int count; (count = input.read(buffer)) >= 0;) {
                                bytes += count;
                                require(bytes <= entry.getSize(), "Native entry exceeds declared size: " + jar + "!" + entry.getName());
                                digest.update(buffer, 0, count);
                                output.write(buffer, 0, count);
                            }
                        }
                        require(bytes == entry.getSize(), "Truncated native entry: " + jar + "!" + entry.getName());
                        try (var input = Files.newInputStream(stage)) {
                            byte[] header = input.readNBytes(20);
                            require(header.length == 20 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                                    && header[4] == 2 && header[5] == 1 && header[18] == 62 && header[19] == 0,
                                    "Native entry is not Linux amd64 ELF: " + jar + "!" + entry.getName());
                        }
                        Artifact nativeFile = new Artifact("opaque-natives/" + profile.instance() + "/" + name,
                                artifact.uri(), bytes, HexFormat.of().formatHex(digest.digest()));
                        Artifact previous = expected.putIfAbsent(name, nativeFile);
                        require(previous == null || (previous.size() == nativeFile.size() && previous.sha1().equals(nativeFile.sha1())),
                                "Conflicting native library basename: " + name);
                        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                            require(verified(destination, nativeFile), "Existing native library differs; refusing overwrite: " + destination);
                        } else {
                            require(verified(stage, nativeFile), "Extracted native library failed verification: " + name);
                            rejectSymlinks(destination);
                            require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), "Native destination appeared; refusing overwrite: " + destination);
                            Files.move(stage, destination);
                        }
                    } finally {
                        Files.deleteIfExists(stage);
                    }
                }
            }
        }
        require(expected.containsKey("liblwjgl.so") && expected.containsKey("libglfw.so"), "Verified LWJGL/GLFW natives are missing: " + directory);
        try (var entries = Files.walk(directory)) {
            List<Path> paths = entries.limit(66).toList();
            require(paths.size() <= 65, "Unexpected extra entries in native bundle: " + directory);
            for (Path entry : paths) {
                rejectSymlinks(entry);
                if (entry.equals(directory)) continue;
                require(entry.getParent().equals(directory) && expected.containsKey(entry.getFileName().toString())
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS), "Unexpected native bundle entry: " + entry);
            }
        }
        System.out.println("Verified persistent native bundle: " + directory + "; files=" + expected.size());
        return directory;
    }

    private static boolean linuxLibrary(JsonObject library) {
        require(!library.has("natives") && !library.has("extract"), "Unexpected legacy native library metadata");
        if (!library.has("rules")) return true;
        boolean allowed = false;
        for (var entry : library.getAsJsonArray("rules")) {
            JsonObject rule = entry.getAsJsonObject();
            require(rule.keySet().stream().allMatch(k -> Set.of("action", "os").contains(k)), "Unsupported library rule");
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                require(os.keySet().equals(Set.of("name")), "Unsupported library OS rule");
                matches = os.get("name").getAsString().equals("linux");
            }
            String action = rule.get("action").getAsString();
            require(Set.of("allow", "disallow").contains(action), "Unsupported library rule action");
            if (matches) allowed = action.equals("allow");
        }
        return allowed;
    }

    private static Artifact library(JsonObject library) {
        String[] coords = library.get("name").getAsString().split(":", -1);
        require(coords.length == 3 || coords.length == 4, "Unsupported pinned library coordinates");
        for (String part : coords) require(part.matches("[A-Za-z0-9_.+\\-]+") && !part.equals(".") && !part.equals(".."),
                "Unsafe library coordinate");
        String path = coords[0].replace('.', '/') + "/" + coords[1] + "/" + coords[2] + "/" + coords[1]
                + "-" + coords[2] + (coords.length == 4 ? "-" + coords[3] : "") + ".jar";
        if (library.has("downloads")) return descriptor("libraries/" + path, library.getAsJsonObject("downloads").getAsJsonObject("artifact"));
        String base = library.get("url").getAsString();
        require(base.equals("https://maven.fabricmc.net") || base.equals("https://maven.fabricmc.net/"),
                "Unhashed libraries must use the pinned public Fabric Maven repository");
        return new Artifact("libraries/" + path, URI.create("https://maven.fabricmc.net/" + path), -1, null);
    }

    private static Artifact descriptor(String path, JsonObject value) {
        String hash = value.get("sha1").getAsString();
        long size = value.get("size").getAsLong();
        require(hash.matches("[0-9a-f]{40}") && size > 0 && size <= MAX_ARTIFACT, "Invalid artifact hash or size: " + path);
        URI uri = URI.create(value.get("url").getAsString());
        httpsUri(uri);
        return new Artifact(path, uri, size, hash);
    }

    private static void add(Map<String, Artifact> files, Artifact artifact) {
        Artifact previous = files.putIfAbsent(artifact.path(), artifact);
        require(previous == null || previous.equals(artifact), "Conflicting runtime artifact: " + artifact.path());
    }

    private static void prepare(HttpClient client, ScheduledExecutorService deadlines, Artifact artifact) throws Exception {
        if (artifact.sha1() == null) {
            URI sidecar = URI.create(artifact.uri().toString() + ".sha1");
            String hash = new String(receive(client, deadlines, sidecar, null, 1024, -1), StandardCharsets.US_ASCII).trim();
            require(hash.matches("[0-9a-fA-F]{40}(\\s+\\*?[^\\r\\n]+)?"), "Invalid public Maven SHA-1 sidecar: " + sidecar);
            artifact = new Artifact(artifact.path(), artifact.uri(), -1, hash.substring(0, 40).toLowerCase(java.util.Locale.ROOT));
        }
        Path target = contained(ROOT, artifact.path());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(verified(target, artifact), "Existing runtime file failed size/SHA-1 verification; refusing overwrite: " + target);
            return;
        }
        directories(target.getParent());
        Path stage = Files.createTempFile(target.getParent(), ".runtime-", ".part");
        privateFile(stage);
        try {
            Path cached = contained(CACHE, artifact.path());
            if (Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS) && verified(cached, artifact)) {
                try (var input = Files.newInputStream(cached); var output = Files.newOutputStream(stage, StandardOpenOption.TRUNCATE_EXISTING)) {
                    long bytes = 0;
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = input.read(buffer)) >= 0;) {
                        bytes += count;
                        require(bytes <= (artifact.size() < 0 ? MAX_ARTIFACT : artifact.size()), "Cached runtime file grew while copying: " + cached);
                        output.write(buffer, 0, count);
                    }
                }
            } else {
                receive(client, deadlines, artifact.uri(), stage, artifact.size() < 0 ? MAX_ARTIFACT : artifact.size(), artifact.size());
            }
            require(verified(stage, artifact), "Downloaded/copied runtime file failed size/SHA-1 verification: " + artifact.path());
            rejectSymlinks(target);
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Runtime destination appeared; refusing overwrite: " + target);
            Files.move(stage, target);
            if (artifact.path().startsWith("libraries/")) System.out.println("Verified runtime library: " + artifact.path());
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static byte[] receive(HttpClient client, ScheduledExecutorService deadlines, URI source,
            Path destination, long maxBytes, long expected) throws Exception {
        URI uri = source;
        for (int redirects = 0; redirects <= 5; redirects++) {
            httpsUri(uri);
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90))
                    .header("User-Agent", "LoadOnce-private-runtime-preparation/1.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (var body = response.body()) {
                    require(Set.of(301, 302, 303, 307, 308).contains(response.statusCode()) && redirects < 5,
                            "Runtime download HTTP " + response.statusCode() + ": " + uri);
                    uri = uri.resolve(response.headers().firstValue("Location").orElseThrow());
                    continue;
                }
            }
            var input = response.body();
            Thread worker = Thread.currentThread();
            var deadline = deadlines.schedule(() -> {
                worker.interrupt();
                try { input.close(); } catch (java.io.IOException ignored) { }
            }, 5, TimeUnit.MINUTES);
            var memory = destination == null ? new java.io.ByteArrayOutputStream() : null;
            try (input; var output = destination == null ? memory : Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING)) {
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                require(declared < 0 || (declared <= maxBytes && (expected < 0 || declared == expected)),
                        "Runtime Content-Length mismatch: " + uri);
                long bytes = 0;
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (worker.isInterrupted()) throw new InterruptedException("Runtime download interrupted: " + source);
                    bytes += count;
                    require(bytes <= maxBytes, "Runtime download exceeds byte limit: " + source);
                    output.write(buffer, 0, count);
                }
                require(bytes > 0 && (expected < 0 || bytes == expected) && (declared < 0 || bytes == declared),
                        "Runtime download size mismatch: " + source);
                return memory == null ? null : memory.toByteArray();
            } finally {
                deadline.cancel(false);
            }
        }
        throw new IllegalStateException("Too many runtime download redirects: " + source);
    }

    private static boolean verified(Path path, Artifact artifact) throws Exception {
        rejectSymlinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        long size = Files.size(path);
        if (size <= 0 || size > MAX_ARTIFACT || (artifact.size() >= 0 && size != artifact.size())) return false;
        var digest = MessageDigest.getInstance("SHA-1");
        try (var input = Files.newInputStream(path)) {
            long bytes = 0;
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                bytes += count;
                if (bytes > size) return false;
                digest.update(buffer, 0, count);
            }
            if (bytes != size) return false;
        }
        return artifact.sha1().equals(HexFormat.of().formatHex(digest.digest()));
    }

    private static JsonObject readJson(Path path) throws Exception {
        rejectSymlinks(path);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= MAX_JSON, "Missing/oversized runtime metadata: " + path);
        try (var input = Files.newInputStream(path)) {
            byte[] content = input.readNBytes((int) MAX_JSON + 1);
            require(content.length <= MAX_JSON, "Runtime metadata exceeds 2 MiB: " + path);
            return JsonParser.parseString(new String(content, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void httpsUri(URI uri) {
        require("https".equalsIgnoreCase(uri.getScheme()) && HOSTS.contains(uri.getHost()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getQuery() == null && uri.getFragment() == null,
                "Runtime artifact URL must use an approved public HTTPS host: " + uri);
    }

    private static Path contained(Path base, String name) throws Exception {
        require(!name.isEmpty() && !name.contains("\\") && !name.contains("\0") && !name.startsWith("/"), "Unsafe runtime path");
        for (String part : name.split("/", -1)) require(!part.isEmpty() && !part.equals(".") && !part.equals(".."), "Unsafe runtime path");
        Path target = base.resolve(name).normalize();
        require(target.startsWith(base) && !target.equals(base), "Runtime path escapes cache");
        rejectSymlinks(target);
        return target;
    }

    private static void directories(Path path) throws Exception {
        rejectSymlinks(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            directories(path.getParent());
            try { Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
            catch (UnsupportedOperationException unsupported) { Files.createDirectory(path); }
            catch (java.nio.file.FileAlreadyExistsException raced) { /* Other asset workers may share this directory. */ }
        }
        rejectSymlinks(path);
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), "Runtime parent is not a directory: " + path);
    }

    private static void privateFile(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private static void rejectSymlinks(Path path) throws Exception {
        Path current = path.toAbsolutePath().getRoot();
        for (Path part : path.toAbsolutePath()) {
            current = current.resolve(part);
            require(!Files.isSymbolicLink(current), "Refusing symbolic-link runtime/cache path: " + current);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
