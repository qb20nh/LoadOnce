import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Prepare and launch one FO LAN peer inside its own private hardware/audio session. */
public final class PrepareFabricLanPeer {
    static final String INSTANCE = "loadonce-fabulously-optimized-6.5.0";
    static final long COPY_LIMIT = 2L * 1024 * 1024 * 1024;
    private static final Object ACCOUNT_LIFETIME = new Object();
    private static boolean closed;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Use PrepareFabricLanPeer HOST_RUN_DIRECTORY LAN_PORT inside a second owned hardware/audio session");
        RunFabricPackIsolated.guardPeerEnvironment();
        Path session = RunFabricPackIsolated.peerSession(), peer = session.resolve("peer-prism");
        require(!Files.exists(peer, LinkOption.NOFOLLOW_LINKS), "Peer root must be new");
        Files.createDirectory(peer, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Thread shutdown = new Thread(() -> {
            try { cleanup(peer, session); }
            catch (IOException failed) { System.err.println("Private peer account cleanup failed"); }
        }, "peer-preparation-account-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            Path host = Path.of(args[0]);
            int port = Integer.parseInt(args[1]);
            prepare(peer, session, host, port);
            System.out.println("Private generated peer root: " + peer);
            RunFabricPackIsolated.main(new String[] {RunFabricPackIsolated.PRISM_ROOT.toString(), "--peer", peer.toString(),
                    "--host-run", host.toString(), "--port", Integer.toString(port)});
        } finally {
            cleanup(peer, session);
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException exiting) { }
        }
    }

    static void prepare(Path peer, Path session, Path host, int port) throws Exception {
        Path sourceRoot = RunFabricPackIsolated.PRISM_ROOT;
        Path source = sourceRoot.resolve("instances").resolve(INSTANCE);
        Properties hostState = RunFabricPackIsolated.peerHost(host, port);
        Properties hardware = RunFabricPackIsolated.peerProperties(session.resolve("hardware-display.properties"));
        String mods = RunFabricPackIsolated.peerMods(source.resolve("minecraft/mods"));
        verifyPack(source.resolve("mmc-pack.json"));
        String pack = RunFabricPackIsolated.peerHash(source.resolve("mmc-pack.json"));
        var copies = new ArrayList<Path>();
        for (String cache : List.of("assets", "libraries", "meta")) addTree(copies, sourceRoot.resolve(cache));
        copies.add(source.resolve("instance.cfg"));
        copies.add(source.resolve("mmc-pack.json"));
        for (String directory : List.of("mods", "config", "coremods", "resourcepacks", "shaderpacks")) {
            Path path = source.resolve("minecraft").resolve(directory);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) addTree(copies, path);
        }
        Path options = source.resolve("minecraft/options.txt");
        if (Files.exists(options, LinkOption.NOFOLLOW_LINKS)) copies.add(options);
        long bytes = 0;
        require(copies.size() <= 50000, "Peer copy exceeds 50000 files");
        for (Path file : copies) {
            noSymlinks(file);
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 64L * 1024 * 1024, "Invalid or oversized peer source file: " + file);
            bytes = Math.addExact(bytes, Files.size(file));
            require(bytes <= COPY_LIMIT, "Peer profile/cache copy exceeds 2 GiB");
        }
        require(Files.getFileStore(peer).getUsableSpace() >= bytes + 128L * 1024 * 1024, "Insufficient storage for independent peer files");
        for (Path file : copies) {
            Path target = peer.resolve(sourceRoot.relativize(file));
            privateParents(peer, target.getParent());
            Files.copy(file, target);
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        }
        Path instance = peer.resolve("instances").resolve(INSTANCE);
        require(mods.equals(RunFabricPackIsolated.peerMods(source.resolve("minecraft/mods")))
                && mods.equals(RunFabricPackIsolated.peerMods(instance.resolve("minecraft/mods")))
                && pack.equals(RunFabricPackIsolated.peerHash(source.resolve("mmc-pack.json")))
                && pack.equals(RunFabricPackIsolated.peerHash(instance.resolve("mmc-pack.json"))), "Selected FO pack changed during peer preparation");
        Path accountSource = sourceRoot.resolve("accounts.json");
        noSymlinks(accountSource);
        require(Files.isRegularFile(accountSource, LinkOption.NOFOLLOW_LINKS) && Files.size(accountSource) > 0
                && Files.size(accountSource) <= 1024 * 1024, "Genuine temporary private account cache is required");
        String name = "LOPeer" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String uuid = offlineUuid(name);
        String accountCopy = appendOffline(Files.readString(accountSource), name);
        publishAccounts(peer, accountCopy);
        var generated = new Properties();
        generated.setProperty("sourceRoot", sourceRoot.toString());
        generated.setProperty("peerRoot", peer.toString());
        generated.setProperty("session", session.toString());
        generated.setProperty("creatorPid", Long.toString(ProcessHandle.current().pid()));
        generated.setProperty("creatorStarted", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        for (String key : List.of("controllerPid", "controllerStarted")) generated.setProperty(key, hardware.getProperty(key));
        for (String key : List.of("helperPid", "helperStarted", "gamePid", "gameStarted", "displaySession")) generated.setProperty("host." + key, hostState.getProperty(key));
        generated.setProperty("hostRun", host.toString());
        generated.setProperty("port", Integer.toString(port));
        generated.setProperty("mods.sha256", mods);
        generated.setProperty("pack.sha256", pack);
        generated.setProperty("peerName", name);
        generated.setProperty("peerUuid", uuid);
        String module = hardware.getProperty("relativeInputModule", "");
        generated.setProperty("relativeInputModule", module);
        generated.setProperty("module.sha256", module.isEmpty() ? "" : RunFabricPackIsolated.peerHash(Path.of(module)));
        Path marker = peer.resolve("peer-generation.properties");
        Files.createFile(marker, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(marker)) { generated.store(output, "Owned FO LAN peer generation; no credential values"); }
    }

    static String appendOffline(String original, String name) throws IOException {
        require(name.matches("LOPeer[0-9a-f]{8}"), "Invalid generated peer name");
        try {
            JsonObject root = JsonParser.parseString(original).getAsJsonObject();
            require(root.get("formatVersion").getAsInt() == 3 && root.get("accounts").isJsonArray(), "Unsupported private account cache schema");
            JsonArray accounts = root.getAsJsonArray("accounts");
            boolean owningAccount = false;
            String uuid = offlineUuid(name);
            for (var value : accounts) {
                JsonObject account = value.getAsJsonObject();
                if (account.has("profile")) {
                    JsonObject profile = account.getAsJsonObject("profile");
                    require(!name.equals(profile.get("name").getAsString()) && !uuid.equals(profile.get("id").getAsString()), "Generated peer identity collision");
                }
                if ("MSA".equals(account.get("type").getAsString()) && account.has("entitlement")) {
                    JsonObject entitlement = account.getAsJsonObject("entitlement");
                    owningAccount |= entitlement.has("ownsMinecraft") && entitlement.get("ownsMinecraft").getAsBoolean();
                }
            }
            require(owningAccount, "Private cache has no genuine owning MSA account; Prism ownership rules remain required");
            var offline = new JsonObject();
            offline.addProperty("type", "Offline");
            var ygg = new JsonObject();
            ygg.addProperty("token", "0");
            ygg.addProperty("iat", Instant.now().getEpochSecond());
            var extra = new JsonObject();
            extra.addProperty("userName", name);
            extra.addProperty("clientToken", UUID.randomUUID().toString().replace("-", ""));
            ygg.add("extra", extra);
            offline.add("ygg", ygg);
            var profile = new JsonObject();
            profile.addProperty("id", uuid);
            profile.addProperty("name", name);
            offline.add("profile", profile);
            var entitlement = new JsonObject();
            entitlement.addProperty("ownsMinecraft", false);
            entitlement.addProperty("canPlayMinecraft", false);
            offline.add("entitlement", entitlement);
            accounts.add(offline);
            return root.toString();
        } catch (RuntimeException invalid) {
            throw new IOException("Private account cache schema rejected; values were not logged");
        }
    }

    static String offlineUuid(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""); }

    static void publishAccounts(Path peer, String accountCopy) throws IOException {
        synchronized (ACCOUNT_LIFETIME) {
            require(!closed, "Peer preparation is closed");
            Path accounts = peer.resolve("accounts.json");
            Files.createFile(accounts, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(accounts, accountCopy, StandardCharsets.UTF_8);
        }
    }

    static void cleanup(Path peer, Path session) throws IOException {
        synchronized (ACCOUNT_LIFETIME) {
            closed = true;
            RunFabricPackIsolated.closePeer(peer, session);
        }
    }

    static void verifyPack(Path path) throws IOException {
        require(Files.size(path) <= 1024 * 1024, "FO pack metadata is oversized");
        JsonObject pack;
        try { pack = JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new IOException("Invalid FO pack metadata"); }
        var expected = new java.util.HashMap<>(java.util.Map.of("org.lwjgl3", "3.3.3", "net.minecraft", "1.21.1",
                "net.fabricmc.intermediary", "1.21.1", "net.fabricmc.fabric-loader", "0.19.3"));
        try {
            for (var entry : pack.getAsJsonArray("components")) {
                JsonObject component = entry.getAsJsonObject();
                require(component.get("version").getAsString().equals(expected.remove(component.get("uid").getAsString())), "Unexpected FO loader component");
            }
        } catch (RuntimeException invalid) { throw new IOException("Invalid FO loader component schema"); }
        require(expected.isEmpty(), "FO loader components missing");
    }

    private static void addTree(List<Path> files, Path directory) throws IOException {
        noSymlinks(directory);
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "Peer cache/profile directory is missing: " + directory);
        try (var tree = Files.walk(directory)) {
            for (Path path : tree.toList()) {
                noSymlinks(path);
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) files.add(path);
                require(files.size() <= 50000, "Peer copy exceeds 50000 files");
            }
        }
    }

    private static void privateParents(Path root, Path directory) throws IOException {
        Path current = root;
        for (Path component : root.relativize(directory)) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            RunFabricPackIsolated.privatePeerObject(current, true, root);
        }
    }

    private static void noSymlinks(Path path) throws IOException {
        require(path.isAbsolute() && path.normalize().equals(path), "Peer paths must be absolute and normalized");
        Path current = path.getRoot();
        for (Path component : path) { current = current.resolve(component); require(!Files.isSymbolicLink(current), "Peer symlink rejected: " + current); }
    }

    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
}
