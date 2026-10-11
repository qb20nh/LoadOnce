import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

/** Synthetic profile and rejection checks; no account cache, display, game or network connection. */
public final class FabricLanPeerContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("loadonce-lan-peer-contract-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            String synthetic = "{\"formatVersion\":3,\"accounts\":[{\"type\":\"MSA\",\"active\":true,\"entitlement\":{\"ownsMinecraft\":true,\"canPlayMinecraft\":true},\"profile\":{\"id\":\"00000000000000000000000000000001\",\"name\":\"SyntheticOwner\"},\"msa\":{\"token\":\"synthetic-only\"}}]}";
            JsonObject original = JsonParser.parseString(synthetic).getAsJsonObject();
            JsonObject added = JsonParser.parseString(PrepareFabricLanPeer.appendOffline(synthetic, "LOPeer01234567")).getAsJsonObject();
            check(added.getAsJsonArray("accounts").get(0).equals(original.getAsJsonArray("accounts").get(0)), "Owning account was changed");
            JsonObject peer = added.getAsJsonArray("accounts").get(1).getAsJsonObject();
            check(peer.get("type").getAsString().equals("Offline") && !peer.has("msa"), "Peer must use an ordinary Offline account");
            check(!peer.getAsJsonObject("entitlement").get("ownsMinecraft").getAsBoolean(), "Peer must not fabricate ownership");
            check(peer.getAsJsonObject("profile").get("id").getAsString().equals(PrepareFabricLanPeer.offlineUuid("LOPeer01234567"))
                    && !PrepareFabricLanPeer.offlineUuid("LOPeer01234567").equals(PrepareFabricLanPeer.offlineUuid("LOPeer89abcdef")), "Peer UUID is not distinct and deterministic");
            reject(() -> PrepareFabricLanPeer.appendOffline("{\"formatVersion\":3,\"accounts\":[]}", "LOPeer01234567"));
            reject(() -> PrepareFabricLanPeer.appendOffline(synthetic.replace("\"ownsMinecraft\":true", "\"ownsMinecraft\":false"), "LOPeer01234567"));
            reject(() -> PrepareFabricLanPeer.appendOffline(synthetic, "bad-name"));
            reject(() -> PrepareFabricLanPeer.appendOffline("not-json", "LOPeer01234567"));
            reject(() -> PrepareFabricLanPeer.appendOffline(added.toString(), "LOPeer01234567"));
            Path a = Files.createDirectory(root.resolve("mods-a")), b = Files.createDirectory(root.resolve("mods-b"));
            for (int i = 0; i < 48; i++) {
                Files.write(a.resolve("mod-" + i + ".jar"), new byte[] {(byte) i});
                Files.write(b.resolve("mod-" + i + ".jar"), new byte[] {(byte) i});
            }
            check(RunFabricPackIsolated.peerMods(a).equals(RunFabricPackIsolated.peerMods(b)), "Equivalent mod bytes differ");
            Files.write(b.resolve("mod-0.jar"), new byte[] {99});
            check(!RunFabricPackIsolated.peerMods(a).equals(RunFabricPackIsolated.peerMods(b)), "Changed mod bytes accepted");
            Files.delete(b.resolve("mod-0.jar"));
            reject(() -> RunFabricPackIsolated.peerMods(b));
            Files.createSymbolicLink(b.resolve("mod-0.jar"), a.resolve("mod-0.jar"));
            reject(() -> RunFabricPackIsolated.peerMods(b));
            Files.delete(b.resolve("mod-0.jar"));
            Files.write(b.resolve("mod-0.jar"), new byte[] {0});
            Files.createDirectory(b.resolve("nested"));
            Files.write(b.resolve("nested/extra.jar"), new byte[] {1});
            reject(() -> RunFabricPackIsolated.peerMods(b));
            reject(() -> RunFabricPackIsolated.peerHost(root, 25445));
            reject(() -> RunFabricPackIsolated.peerHost(RunFabricPackIsolated.PRISM_ROOT.resolve("isolated-validation/run-1"), 0));
            reject(() -> RunFabricPackIsolated.peerNamespaces(ProcessHandle.current()));
            reject(() -> RunFabricPackIsolated.main(new String[] {root.toString(), "--pack", "fabulously"}));
            Path copy = Files.createDirectory(root.resolve("peer-prism"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            PrepareFabricLanPeer.publishAccounts(copy, "synthetic-only");
            check(Files.getPosixFilePermissions(copy.resolve("accounts.json")).equals(PosixFilePermissions.fromString("rw-------")), "Account copy was not private");
            Path pendingSave = Files.createFile(copy.resolve(".accounts.json.contract"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(pendingSave, "synthetic-only");
            PrepareFabricLanPeer.cleanup(copy, root);
            check(!Files.exists(copy.resolve("accounts.json")), "Account copy survives cleanup");
            check(!Files.exists(pendingSave), "Atomic account save survives cleanup");
            reject(() -> PrepareFabricLanPeer.publishAccounts(copy, "synthetic-only"));
            check(!Files.exists(copy.resolve("accounts.json")), "Shutdown admitted a late credential copy");
            try {
                RunFabricPackIsolated.startPeer(new ProcessBuilder("/nonexistent-loadonce-peer-contract"), new java.util.LinkedHashMap<>());
                throw new AssertionError("Shutdown admitted a late peer launch");
            } catch (java.io.IOException closed) {
                check("Peer launch is closed".equals(closed.getMessage()), "Late start reached ProcessBuilder after cleanup");
            }
            System.out.println("PASS: " + checks + " LAN peer synthetic identity, fingerprint, containment and cleanup checks");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }

    private interface Action { void run() throws Exception; }
    private static void reject(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected bounded peer guard rejection");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
