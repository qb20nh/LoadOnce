package io.loadonce.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

/** Explicit lifecycle milestones: loading a mod is not application readiness. */
public final class Readiness {
    private static long startNanos = processStartNanos();
    private static String runId = UUID.randomUUID().toString();
    private static Path reportPath;
    private static boolean marked;

    static {
        try {
            beginRun(startNanos, false);
        } catch (RuntimeException failure) {
            unavailable(failure);
        }
    }

    private Readiness() {}

    static synchronized void beginRun(long start, boolean restored) {
        String previousId = runId;
        startNanos = start;
        marked = false;
        String suppliedId = System.getProperty("loadonce.runId");
        runId = suppliedId == null || suppliedId.isBlank()
                || (restored && suppliedId.equals(previousId))
                ? UUID.randomUUID().toString() : suppliedId;
        reportPath = null;
        String suppliedPath = System.getProperty("loadonce.report");
        reportPath = suppliedPath == null || suppliedPath.isBlank()
                ? null : Path.of(suppliedPath).toAbsolutePath();
    }

    public static void markClientMenuReady() {
        mark("client_menu_ready");
    }

    public static void markServerReady() {
        mark("server_ready");
    }

    static void beginRestoredRun() {
        try {
            long restoredStart = System.nanoTime();
            System.setProperty("loadonce.agentStartNanos", Long.toString(restoredStart));
            beginRun(restoredStart, true);
            // Classes first loaded by another loader after restoration need the fresh run identity too.
            System.setProperty("loadonce.runId", runId);
        } catch (RuntimeException failure) {
            unavailable(failure);
        }
    }

    private static long processStartNanos() {
        long now = System.nanoTime();
        try {
            String value = System.getProperty("loadonce.agentStartNanos");
            if (value != null) {
                long suppliedStart = Long.parseLong(value);
                // nanoTime has an arbitrary origin and may be negative; only elapsed time is meaningful.
                if (now - suppliedStart >= 0) return suppliedStart;
            }
        } catch (RuntimeException ignored) {
            // A malformed or inaccessible optional property must not affect application readiness.
        }
        return now;
    }

    private static synchronized void mark(String milestone) {
        if (marked) {
            return;
        }
        marked = true;
        if (reportPath == null) {
            return;
        }
        Path temporary = null;
        try {
            long elapsed = Math.max(0, System.nanoTime() - startNanos);
            String json = "{\"schemaVersion\":1,\"runId\":\"" + escape(runId)
                    + "\",\"pid\":" + ProcessHandle.current().pid()
                    + ",\"milestone\":\"" + milestone + "\",\"elapsedNanos\":" + elapsed
                    + ",\"observedAt\":\"" + Instant.now() + "\"}\n";
            Files.createDirectories(reportPath.getParent());
            temporary = Files.createTempFile(reportPath.getParent(), ".loadonce-ready-", ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            // A non-atomic replacement can expose a partial report to a launcher. Fail closed instead.
            Files.move(temporary, reportPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | LinkageError failure) {
            unavailable(failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException ignored) {
                    // Reporting must never prevent Minecraft from becoming ready.
                }
            }
        }
    }

    private static void unavailable(Throwable failure) {
        System.err.println("LoadOnce: readiness report unavailable ("
                + failure.getClass().getSimpleName() + ").");
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.toString();
    }
}
