package io.loadonce.runtime.demo;

import io.loadonce.runtime.CheckpointCoordinator;
import io.loadonce.runtime.CheckpointResult;
import io.loadonce.runtime.CheckpointSafety;
import io.loadonce.runtime.Readiness;
import io.loadonce.runtime.ResourceAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A deterministic initialization workload for launcher/CDS smoke tests, not a Minecraft benchmark. */
public final class RuntimeDemo {
    private static final Map<String, byte[]> PREPARED_RESOURCES = new TreeMap<>();

    private RuntimeDemo() {}

    public static void main(String[] arguments) throws Exception {
        List<String> options = Arrays.asList(arguments);
        if (options.stream().anyMatch(option -> !List.of("--server", "--client", "--checkpoint",
                "--fixture-safe", "--capabilities").contains(option))) {
            throw new IllegalArgumentException("Options: --server | --client, --checkpoint --fixture-safe, --capabilities");
        }
        if (options.contains("--server") && options.contains("--client")) {
            throw new IllegalArgumentException("Choose one readiness milestone");
        }
        CheckpointCoordinator coordinator = new CheckpointCoordinator(() -> options.contains("--fixture-safe")
                ? CheckpointSafety.reusableInitializationOnly() : CheckpointSafety.denied());
        if (options.contains("--capabilities")) {
            System.out.println(coordinator.supportMessage());
            if (!options.contains("--checkpoint")) return;
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int index = 0; index < 2_048; index++) {
            String key = "fixture/resource/" + index;
            PREPARED_RESOURCES.put(key, digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        }
        String before = digestOfHeap(digest);
        List<String> lifecycle = new ArrayList<>();
        coordinator.register(new ResourceAdapter() {
            public String name() { return "pure Java fixture heap"; }
            public boolean supportsReusableCheckpoint() { return true; }
            public void beforeCheckpoint() { lifecycle.add("prepared"); }
            public void afterRestore() { lifecycle.add("restored"); }
        });

        if (options.contains("--checkpoint")) {
            if (!options.contains("--fixture-safe")) {
                System.err.println("Fixture checkpoint vetoed: pass --fixture-safe only for this pure Java demo.");
                System.exit(2);
            }
            System.out.println("checkpoint fixture prepared resources=" + PREPARED_RESOURCES.size()
                    + " sha256=" + before);
            CheckpointResult result = coordinator.checkpoint();
            System.out.println(result.status() + ": " + result.detail());
            if (result.status() != CheckpointResult.Status.RESTORED) System.exit(3);
            if (!before.equals(digestOfHeap(digest)) || !lifecycle.equals(List.of("prepared", "restored"))) {
                throw new IllegalStateException("Restored initialized heap or resource lifecycle differs");
            }
            System.out.println("RESTORED fixture heap verified resources=" + PREPARED_RESOURCES.size()
                    + " sha256=" + before);
        }
        if (options.contains("--client")) Readiness.markClientMenuReady();
        else Readiness.markServerReady();
        System.out.println("fixture initialized resources=" + PREPARED_RESOURCES.size() + " sha256=" + before);
    }

    private static String digestOfHeap(MessageDigest digest) {
        PREPARED_RESOURCES.forEach((key, bytes) -> {
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
        });
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
