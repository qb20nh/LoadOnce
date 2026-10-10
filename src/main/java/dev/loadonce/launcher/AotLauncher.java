package dev.loadonce.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** A single-launch JDK 25 class/linking cache wrapper; never saves a process heap. */
public final class AotLauncher {
    public static final int SCHEMA = 3;
    static final Set<String> MANIFEST_FIELDS = Set.of(
            "schema", "completed", "fingerprint", "sha256", "bytes", "owner", "generation");
    // Contract tests use real Java helper children without creating executable
    // shell shims. Public calls always use the original JVM and child supervisor.
    interface Executor {
        JavaCommand.Probe probe(Path java, Map<String, String> environment) throws IOException, InterruptedException;
        int execute(List<String> command, Map<String, String> environment) throws IOException, InterruptedException;
    }
    private static final Executor SYSTEM = new Executor() {
        @Override public JavaCommand.Probe probe(Path java, Map<String, String> environment)
                throws IOException, InterruptedException {
            return JavaCommand.probe(java, environment);
        }
        @Override public int execute(List<String> command, Map<String, String> environment)
                throws IOException, InterruptedException {
            return ChildProcess.run(command, null, environment, null, null).exitCode();
        }
    };

    private AotLauncher() { }

    public static int train(List<String> command, Path cache, List<Path> inputs, boolean sensitive,
                            Map<String, String> environment) throws IOException, InterruptedException {
        return train(command, cache, inputs, sensitive, environment, SYSTEM, AotLauncher::message);
    }

    public static int train(List<String> command, Path cache, List<Path> inputs, boolean sensitive,
                            Map<String, String> environment, Consumer<String> diagnostics)
            throws IOException, InterruptedException {
        return train(command, cache, inputs, sensitive, environment, SYSTEM, diagnostics);
    }

    static int train(List<String> command, Path cache, List<Path> inputs, boolean sensitive,
                     Map<String, String> environment, Executor executor) throws IOException, InterruptedException {
        return train(command, cache, inputs, sensitive, environment, executor, AotLauncher::message);
    }

    static int train(List<String> command, Path cache, List<Path> inputs, boolean sensitive,
                     Map<String, String> environment, Executor executor, Consumer<String> diagnostics)
            throws IOException, InterruptedException {
        Objects.requireNonNull(diagnostics);
        command = originalCommand(command);
        inputs = List.copyOf(inputs);
        environment = Map.copyOf(environment);
        if (sensitive || JavaCommand.sensitive(command, environment)) {
            throw new IOException("training with sensitive launch inputs or credential environments is disabled; use ordinary run");
        }
        if (JavaCommand.launchInputs(command, environment).nonemptyClasspathDirectory()) {
            throw new IOException("JDK AOT training requires JAR classpaths; nonempty directories are unsupported");
        }
        Path java = JavaCommand.resolveExecutable(command.get(0), environment);
        if (!executor.probe(java, environment).leyden()) {
            throw new IOException("this Java runtime does not support JDK 25 AOT cache output");
        }
        Path root = LauncherFiles.privateDirectory(cache);
        try (LauncherFiles.CacheLock ignored = LauncherFiles.lock(root, true)) {
            String key = JavaCommand.fingerprint(command, inputs, environment);
            Path stage = LauncherFiles.tempDirectory(root, ".train-");
            try {
                Path archive = stage.resolve("cache.aot");
                diagnostics.accept("training runs the original application once; cache promotion requires a successful exit");
                int exit = executor.execute(withOptions(command, "-XX:AOTCacheOutput=" + archive), environment);
                if (exit != 0) {
                    diagnostics.accept("training application exited unsuccessfully; no cache promoted and no replay attempted");
                    return exit;
                }
                // Java has no portable umask. The enclosing directory was private
                // throughout the JVM's execution; seal its output before publication.
                LauncherFiles.sealTree(stage);
                LauncherFiles.checkPrivate(archive, false);
                if (Files.size(archive) <= 0) {
                    throw new IOException("JVM did not create a complete AOT cache");
                }
                if (!key.equals(JavaCommand.fingerprint(command, inputs, environment))) {
                    throw new IOException("inputs changed during training; cache discarded");
                }
                // Retain only the finished archive, not transient training configs.
                try (var children = Files.list(stage)) {
                    for (Path child : children.toList()) {
                        if (!child.equals(archive)) LauncherFiles.deleteTree(child);
                    }
                }
                LauncherFiles.sync(archive);
                LauncherFiles.sync(stage);
                publish(root, key, stage, archive, diagnostics);
                diagnostics.accept("AOT cache promoted; it contains JVM class data, not a Minecraft process checkpoint");
                return 0;
            } finally {
                if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) LauncherFiles.deleteTree(stage);
            }
        }
    }

    public static int run(List<String> command, Path cache, List<Path> inputs, boolean sensitive,
                          Map<String, String> environment) throws IOException, InterruptedException {
        return run(command, cache, inputs, sensitive, environment, SYSTEM, AotLauncher::message);
    }

    public static int run(List<String> command, Path cache, List<Path> inputs, boolean sensitive,
                          Map<String, String> environment, Consumer<String> diagnostics)
            throws IOException, InterruptedException {
        return run(command, cache, inputs, sensitive, environment, SYSTEM, diagnostics);
    }

    static int run(List<String> command, Path cache, List<Path> inputs, boolean sensitive,
                   Map<String, String> environment, Executor executor) throws IOException, InterruptedException {
        return run(command, cache, inputs, sensitive, environment, executor, AotLauncher::message);
    }

    static int run(List<String> command, Path cache, List<Path> inputs, boolean sensitive,
                   Map<String, String> environment, Executor executor, Consumer<String> diagnostics)
            throws IOException, InterruptedException {
        Objects.requireNonNull(diagnostics);
        command = originalCommand(command);
        inputs = List.copyOf(inputs);
        environment = Map.copyOf(environment);
        if (sensitive || JavaCommand.sensitive(command, environment)) {
            diagnostics.accept("sensitive launch inputs: ordinary startup");
            return executor.execute(command, environment);
        }
        LauncherFiles.CacheLock lock = null;
        Path archive;
        try {
            JavaCommand.launchInputs(command, environment);
            Path java = JavaCommand.resolveExecutable(command.get(0), environment);
            if (!executor.probe(java, environment).leyden()) {
                throw new IOException("runtime has no JDK 25 AOT cache support");
            }
            Path root = LauncherFiles.privateDirectory(cache);
            lock = LauncherFiles.lock(root, false);
            archive = validatedArchive(root, JavaCommand.fingerprint(command, inputs, environment));
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            if (lock != null) lock.close();
            diagnostics.accept("compatible complete AOT cache unavailable: ordinary startup");
            return executor.execute(command, environment);
        }
        // The child call is outside the fallback catch. Application failures and
        // process-start failures must never cause a second application execution.
        try (LauncherFiles.CacheLock acquired = lock) {
            diagnostics.accept("using validated AOT cache; JVM may discard incompatible data during this single launch");
            return executor.execute(withOptions(command, "-XX:AOTMode=auto", "-XX:AOTCache=" + archive), environment);
        }
    }

    static Path validatedArchive(Path root, String key) throws IOException {
        if (!key.matches("[a-f0-9]{64}")) throw new IOException("invalid cache fingerprint");
        Path entry = root.resolve(key);
        LauncherFiles.checkPrivate(entry, true);
        Map<String, String> record = LauncherFiles.readManifest(entry.resolve("manifest.properties"), MANIFEST_FIELDS);
        String generation = record.get("generation");
        if (!generation.matches("generation-[a-f0-9]{32}")) throw new IOException("invalid cache generation");
        Path directory = entry.resolve(generation);
        LauncherFiles.checkPrivate(directory, true);
        Path archive = directory.resolve("cache.aot");
        LauncherFiles.checkPrivate(archive, false);
        long bytes;
        try {
            bytes = Long.parseLong(record.get("bytes"));
        } catch (NumberFormatException failure) {
            throw new IOException("invalid cache archive size");
        }
        if (!Integer.toString(SCHEMA).equals(record.get("schema"))
                || !"true".equals(record.get("completed")) || !key.equals(record.get("fingerprint"))
                || !LauncherFiles.ownerId().equals(record.get("owner")) || bytes <= 0
                || bytes != Files.size(archive) || !record.get("sha256").matches("[a-f0-9]{64}")
                || !record.get("sha256").equals(LauncherFiles.fileHash(archive))) {
            throw new IOException("cache is incomplete, incompatible, or corrupt");
        }
        return archive;
    }

    private static void publish(Path root, String key, Path stage, Path archive, Consumer<String> diagnostics) throws IOException {
        Path entry = LauncherFiles.privateDirectory(root.resolve(key));
        String generation = "generation-" + UUID.randomUUID().toString().replace("-", "");
        Path completed = entry.resolve(generation);
        Path manifest = entry.resolve("manifest.properties");
        byte[] previous = null;
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            LauncherFiles.checkPrivate(manifest, false);
            if (Files.size(manifest) > 4096) throw new IOException("invalid cache manifest");
            previous = Files.readAllBytes(manifest);
        }
        Map<String, String> record = new LinkedHashMap<>();
        record.put("schema", Integer.toString(SCHEMA));
        record.put("completed", "true");
        record.put("fingerprint", key);
        record.put("sha256", LauncherFiles.fileHash(archive));
        record.put("bytes", Long.toString(Files.size(archive)));
        record.put("owner", LauncherFiles.ownerId());
        record.put("generation", generation);
        Path pending = LauncherFiles.tempFile(entry, ".manifest-", ".properties");
        boolean replaced = false;
        boolean committed = false;
        try {
            LauncherFiles.writeManifest(pending, record);
            LauncherFiles.sync(pending);
            Files.move(stage, completed, StandardCopyOption.ATOMIC_MOVE);
            LauncherFiles.sync(entry);
            Files.move(pending, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
            LauncherFiles.sync(entry);
            LauncherFiles.sync(root);
            committed = true;
        } catch (IOException failure) {
            boolean rolledBack = !replaced;
            if (replaced) {
                try {
                    if (previous == null) {
                        Files.delete(manifest);
                    } else {
                        Path rollback = LauncherFiles.tempFile(entry, ".rollback-", ".properties");
                        try {
                            Files.write(rollback, previous);
                            LauncherFiles.sync(rollback);
                            Files.move(rollback, manifest, StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } finally {
                            Files.deleteIfExists(rollback);
                        }
                    }
                    LauncherFiles.sync(entry);
                    rolledBack = true;
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            // Never remove an archive that a surviving manifest may reference.
            if (rolledBack && Files.exists(completed, LinkOption.NOFOLLOW_LINKS)) {
                try { LauncherFiles.deleteTree(completed); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        } finally {
            Files.deleteIfExists(pending);
        }
        if (committed) {
            // Readers hold the shared cache lock through their entire launch.
            // Obsolete generations are merely reclaimable; cleanup failure must
            // not turn a successfully committed training run into a failed one.
            try (var children = Files.list(entry)) {
                for (Path child : children.toList()) {
                    if (!child.equals(completed) && child.getFileName().toString().matches("generation-[a-f0-9]{32}")) {
                        try { LauncherFiles.deleteTree(child); }
                        catch (IOException ignored) { diagnostics.accept("obsolete cache generation cleanup deferred"); }
                    }
                }
            } catch (IOException ignored) {
                diagnostics.accept("obsolete cache generation cleanup deferred");
            }
        }
    }

    private static List<String> originalCommand(List<String> command) throws IOException {
        if (command.isEmpty()) throw new IOException("an original Java command is required");
        return List.copyOf(command);
    }

    private static List<String> withOptions(List<String> command, String... options) {
        List<String> augmented = new ArrayList<>();
        augmented.add(command.get(0));
        augmented.addAll(List.of(options));
        augmented.addAll(command.subList(1, command.size()));
        return augmented;
    }

    private static void message(String text) {
        System.err.println("loadonce: " + text);
    }
}
