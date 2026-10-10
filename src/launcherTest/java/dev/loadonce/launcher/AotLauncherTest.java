package dev.loadonce.launcher;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Executable launcher contracts; a local fake JVM counts every application start. */
public final class AotLauncherTest {
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        coldAndWarmLaunchExactlyOnce();
        exactEnvironmentSelectsJava();
        failedAndIncompleteTrainingPreservePreviousCache();
        sensitiveAndInjectedInputsNeverTrain();
        contentAndNativeDependenciesInvalidateCaches();
        manifestsAndDirectoryInputsAreConservative();
        corruptUnsafeAndBusyCachesChooseCold();
        nonPosixCachesChooseCold();
        publicationIsPrivateAndContainsDigests();
        System.out.println("Java launcher contract checks passed: " + checks);
    }

    private static void coldAndWarmLaunchExactlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            f.environment.put("FAKE_EXIT", "19");
            equal(19, f.run());
            equal(1, f.count());
            equal(f.command.subList(1, f.command.size()), f.arguments());
            f.environment.remove("FAKE_EXIT");
            equal(0, f.train());
            equal(2, f.count());
            f.command.addAll(List.of("two words", "literal$HOME", "literal*", "literal;$()", "unicode-π", "--option=value"));
            equal(0, f.train());
            f.environment.put("FAKE_EXIT", "23");
            equal(23, f.run());
            equal(4, f.count());
            List<String> received = f.arguments();
            equal("-XX:AOTMode=auto", received.get(0));
            check(received.get(1).startsWith("-XX:AOTCache="), "warm child receives one archive option");
            equal(f.command.subList(1, f.command.size()), received.subList(2, received.size()));
            f.environment.put("FAKE_UNSUPPORTED", "true");
            equal(23, f.run());
            equal(5, f.count());
            equal(f.command.subList(1, f.command.size()), f.arguments());
            expectIOException(f::train);
            equal(5, f.count());
        }
    }

    private static void failedAndIncompleteTrainingPreservePreviousCache() throws Exception {
        try (Fixture f = new Fixture()) {
            equal(0, f.train());
            Path archive = f.archive();
            byte[] previous = Files.readAllBytes(archive);
            f.environment.put("FAKE_EXIT", "17");
            equal(17, f.train());
            equal(2, f.count());
            equalBytes(previous, Files.readAllBytes(f.archive()));
            f.environment.remove("FAKE_EXIT");
            for (String kind : List.of("missing", "empty")) {
                f.environment.put("FAKE_OUTPUT", kind);
                expectIOException(f::train);
                equalBytes(previous, Files.readAllBytes(f.archive()));
            }
            f.environment.remove("FAKE_OUTPUT");
            f.environment.put("FAKE_TOUCH", f.resource.toString());
            expectIOException(f::train);
            equal(5, f.count());
            equalBytes(previous, Files.readAllBytes(archive));
            Files.writeString(f.resource, "resource-v1");
            f.environment.remove("FAKE_TOUCH");
            f.environment.put("FAKE_SYMLINK", f.resource.toString());
            expectIOException(f::train);
            equal(6, f.count());
            equal("resource-v1", Files.readString(f.resource));
            equalBytes(previous, Files.readAllBytes(f.archive()));
            try (var entries = Files.list(f.cache)) {
                check(entries.noneMatch(path -> path.getFileName().toString().startsWith(".train-")), "staging is cleaned");
            }
        }
    }

    private static void exactEnvironmentSelectsJava() throws Exception {
        try (Fixture f = new Fixture()) {
            Path actualJava = Path.of(System.getProperty("java.home"), "bin/java");
            Path selected = Files.createDirectory(f.base.resolve("selected-bin")).resolve("java");
            Files.createSymbolicLink(selected, actualJava);
            f.environment.put("PATH", selected.getParent().toString());
            Path executableArgv = f.base.resolve("executable-argv");
            f.environment.put("FAKE_EXECUTABLE_ARGV", executableArgv.toString());
            equal(actualJava.toRealPath(), JavaCommand.resolveExecutable("java", f.environment));
            List<String> child = List.of("java", "-cp", absoluteClasspath(), FakeJvm.class.getName(), "app argument");
            equal(0, ChildProcess.run(child, null, f.environment, null, Duration.ofSeconds(10)).exitCode());
            equal(1, f.count());
            equal(List.of("app argument"), f.arguments());
            equal(selected.toString(), Files.readString(executableArgv));
            f.environment.put("PATH", "selected-bin");
            equal(0, ChildProcess.run(child, f.base, f.environment, null, Duration.ofSeconds(10)).exitCode());
            equal(2, f.count());
            equal(selected.toString(), Files.readString(executableArgv));
        }
    }

    private static void sensitiveAndInjectedInputsNeverTrain() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String name : List.of("GH_TOKEN", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
                    "GOOGLE_APPLICATION_CREDENTIALS", "MINECRAFT_ACCESS_TOKEN", "gh_token")) {
                f.environment.put(name, "opaque-credential-value");
                int probes = f.probes();
                expectIOException(f::train);
                equal(probes, f.probes());
                int count = f.count();
                equal(0, f.run());
                equal(count + 1, f.count());
                equal(probes, f.probes());
                equal(f.command.subList(1, f.command.size()), f.arguments());
                f.environment.remove(name);
            }
            f.environment.put("https_proxy", "https://opaque-token@proxy.invalid");
            expectIOException(f::train);
            f.environment.remove("https_proxy");
            f.environment.put("CODEX_SESSION_ID", "ordinary-session");
            f.environment.put("HTTP_PROXY", "http://session-proxy.invalid:3128");
            f.environment.put("GH_TOKEN", "");
            equal(0, f.train());
            equal(0, f.run());
            check(f.arguments().get(0).equals("-XX:AOTMode=auto"), "ordinary session/proxy variables allow caching");
            for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS",
                    "LD_LIBRARY_PATH", "LD_PRELOAD", "DYLD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES")) {
                f.environment.put(name, ""); // empty injection variables are harmless
                JavaCommand.launchInputs(f.command, f.environment);
                f.environment.put(name, "injected-option");
                expectIOException(() -> JavaCommand.launchInputs(f.command, f.environment));
                expectIOException(f::train);
                // Actual cold launch intentionally retains injected environment;
                // fake-JVM runtime could reject it, so validate argument choice
                // through a separate harmless argument-file fallback instead.
                f.environment.remove(name);
            }
            List<String> sensitive = new ArrayList<>(f.command);
            sensitive.addAll(List.of("--accessToken", "private-value"));
            expectIOException(() -> AotLauncher.train(sensitive, f.cache, f.inputs, false, f.environment, f.executor));
            expectIOException(() -> AotLauncher.train(f.command, f.cache, f.inputs, true, f.environment, f.executor));
            List<String> argfile = new ArrayList<>(f.command);
            argfile.add("@application-argument.txt");
            expectIOException(() -> AotLauncher.train(argfile, f.cache, f.inputs, false, f.environment, f.executor));
            int count = f.count();
            equal(0, AotLauncher.run(argfile, f.cache, f.inputs, false, f.environment, f.executor));
            equal(count + 1, f.count());
            equal(argfile.subList(1, argfile.size()), f.arguments());
        }
    }

    private static void contentAndNativeDependenciesInvalidateCaches() throws Exception {
        try (Fixture f = new Fixture()) {
            equal(0, f.train());
            String before = JavaCommand.fingerprint(f.command, f.inputs, f.environment);
            FileTime modified = Files.getLastModifiedTime(f.resource);
            Files.writeString(f.resource, "resource-v2");
            Files.setLastModifiedTime(f.resource, modified);
            check(!before.equals(JavaCommand.fingerprint(f.command, f.inputs, f.environment)), "same-size/mtime content changes matter");
            equal(0, f.run());
            equal(f.command.subList(1, f.command.size()), f.arguments());
            Files.writeString(f.resource, "resource-v1");
            for (Path runtime : List.of(f.home.resolve("lib/server/libjvm.so"), f.home.resolve("conf/java.security"),
                    f.home.resolve("release"))) {
                equal(0, f.train());
                Files.writeString(runtime, "different-runtime");
                equal(0, f.run());
                equal(f.command.subList(1, f.command.size()), f.arguments());
            }
            Path nativeDirectory = Files.createDirectory(f.base.resolve("native"));
            Path nativeFile = nativeDirectory.resolve("libfixture.so");
            List<String> original = List.copyOf(f.command);
            for (String property : List.of("-Djava.library.path=", "-Dorg.lwjgl.librarypath=",
                    "-Dorg.lwjgl.system.SharedLibraryExtractPath=")) {
                Files.writeString(nativeFile, "native-v1");
                f.command.clear();
                f.command.add(original.get(0));
                f.command.add(property + nativeDirectory);
                f.command.addAll(original.subList(1, original.size()));
                equal(0, f.train());
                equal(0, f.run());
                check(f.arguments().get(0).equals("-XX:AOTMode=auto"), "stable native dependency uses cache");
                FileTime nativeModified = Files.getLastModifiedTime(nativeFile);
                Files.writeString(nativeFile, "native-v2");
                Files.setLastModifiedTime(nativeFile, nativeModified);
                equal(0, f.run());
                equal(f.command.subList(1, f.command.size()), f.arguments());
            }
            f.environment.put("FAKE_TOUCH", nativeFile.toString());
            expectIOException(f::train);
        }
    }

    private static void manifestsAndDirectoryInputsAreConservative() throws Exception {
        try (Fixture f = new Fixture()) {
            Path dependency = f.base.resolve("dependency.jar");
            Path transitive = f.base.resolve("transitive.jar");
            jar(transitive, "Manifest-Version: 1.0\n\n", "class-v1");
            jar(dependency, "Manifest-Version: 1.0\nClass-Path: transitive.jar\n\n", "class-v1");
            jar(f.jar, "Manifest-Version: 1.0\nClass-Path: depen\n dency.jar\n\n", "class-v1");
            check(JavaCommand.launchInputs(f.command, f.environment).paths().contains(transitive), "transitive continued manifests are inputs");
            String before = JavaCommand.fingerprint(f.command, List.of(), f.environment);
            jar(transitive, "Manifest-Version: 1.0\n\n", "class-v2");
            check(!before.equals(JavaCommand.fingerprint(f.command, List.of(), f.environment)), "transitive manifest content changes matter");
            jar(f.jar, "Manifest-Version: 1.0\nClass-Path: ./app.jar\n\n", "class-v1");
            equal(List.of(f.jar), JavaCommand.launchInputs(f.command, f.environment).paths());
            for (String manifest : List.of("Manifest-Version: 1.0\nClass-Path: first.jar\nClass-Path: second.jar\n\n",
                    "Manifest-Version: 1.0\nClass-Path: https://example.invalid/app.jar\n\n",
                    "Manifest-Version: 1.0\nClass-Path: http://[\n\n")) {
                jar(f.jar, manifest, "class-v1");
                int count = f.count();
                expectIOException(f::train);
                equal(count, f.count());
                equal(0, f.run());
                equal(f.command.subList(1, f.command.size()), f.arguments());
            }
            jar(f.jar, "Manifest-Version: 1.0\n\nName: Main.class\nClass-Path: https://irrelevant.invalid/a.jar\n\n", "class-v1");
            equal(List.of(f.jar), JavaCommand.launchInputs(f.command, f.environment).paths());
            Path classes = Files.createDirectory(f.base.resolve("classes"));
            Files.writeString(classes.resolve("resource.bin"), "resource");
            List<String> directoryCommand = List.of(f.java.toString(), "-cp", classes.toString(), "Main");
            check(JavaCommand.launchInputs(directoryCommand, f.environment).nonemptyClasspathDirectory(), "directory CP is detected before training");
            int count = f.count();
            expectIOException(() -> AotLauncher.train(directoryCommand, f.cache, List.of(), false, f.environment, f.executor));
            equal(count, f.count());
            List<String> wildcard = List.of(f.java.toString(), "-cp", classes.resolve("*").toString(), "Main");
            check(!JavaCommand.launchInputs(wildcard, f.environment).nonemptyClasspathDirectory(), "wildcard CP is not a class directory");
            before = JavaCommand.fingerprint(wildcard, List.of(), f.environment);
            jar(classes.resolve("new.jar"), "Manifest-Version: 1.0\n\n", "class-v1");
            check(!before.equals(JavaCommand.fingerprint(wildcard, List.of(), f.environment)), "wildcard additions matter");
            Files.createSymbolicLink(f.resource.getParent().resolve("cycle"), Path.of("cycle"));
            expectIOException(() -> JavaCommand.fingerprint(f.command, f.inputs, f.environment));
            equal(0, f.run());
            equal(f.command.subList(1, f.command.size()), f.arguments());
        }
    }

    private static void corruptUnsafeAndBusyCachesChooseCold() throws Exception {
        try (Fixture f = new Fixture()) {
            equal(0, f.train());
            Path archive = f.archive();
            byte[] original = Files.readAllBytes(archive);
            Files.writeString(archive, "corrupt");
            equal(0, f.run());
            equal(f.command.subList(1, f.command.size()), f.arguments());
            Files.write(archive, original);
            Path manifest = f.entry().resolve("manifest.properties");
            String originalManifest = Files.readString(manifest);
            for (String corrupted : List.of(originalManifest.replace("completed=true", "completed=false"),
                    originalManifest.replace("schema=3", "schema=2"), originalManifest + "unexpected=field\n",
                    originalManifest + "schema=3\n", "broken manifest")) {
                Files.writeString(manifest, corrupted);
                equal(0, f.run());
                equal(f.command.subList(1, f.command.size()), f.arguments());
            }
            Files.writeString(manifest, originalManifest);
            for (Path file : List.of(archive, manifest)) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
                equal(0, f.run());
                equal(f.command.subList(1, f.command.size()), f.arguments());
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
            Path outside = f.base.resolve("outside-archive");
            Files.move(archive, outside);
            Files.createSymbolicLink(archive, outside);
            equal(0, f.run());
            equal(f.command.subList(1, f.command.size()), f.arguments());
            Files.delete(archive);
            Files.createLink(archive, outside);
            equal(0, f.run());
            equal(f.command.subList(1, f.command.size()), f.arguments());
            Files.delete(archive);
            Files.move(outside, archive);
            try (LauncherFiles.CacheLock ignored = LauncherFiles.lock(f.cache, true)) {
                int count = f.count();
                equal(0, f.run());
                equal(count + 1, f.count());
                equal(f.command.subList(1, f.command.size()), f.arguments());
                expectIOException(f::train);
                equal(count + 1, f.count());
            }
            equal(0, f.run());
            check(f.arguments().get(0).equals("-XX:AOTMode=auto"), "cache remains usable after lock release");
        }
    }

    private static void publicationIsPrivateAndContainsDigests() throws Exception {
        try (Fixture f = new Fixture()) {
            f.command.add("opaque-private-launch-value");
            equal(0, f.train());
            String metadata = Files.readString(f.entry().resolve("manifest.properties"));
            check(!metadata.contains("opaque-private-launch-value") && !metadata.contains(f.java.toString())
                    && !metadata.contains(f.jar.toString()), "metadata contains no original args or paths");
            Path first = f.archive();
            equal(0, f.train());
            Path second = f.archive();
            check(!first.equals(second), "successful retraining atomically selects a new generation");
            check(!Files.exists(first), "superseded generation is reclaimed after commit");
            equal(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(second));
            equal(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(second.getParent()));
            equal(0, f.run());
            check(f.arguments().get(0).equals("-XX:AOTMode=auto"), "new complete generation is usable");
        }
    }

    private static void nonPosixCachesChooseCold() throws Exception {
        try (Fixture f = new Fixture();
             var archiveFilesystem = FileSystems.newFileSystem(
                     URI.create("jar:" + f.base.resolve("non-posix.zip").toUri()), Map.of("create", "true"))) {
            Path cache = archiveFilesystem.getPath("/cache");
            equal(0, AotLauncher.run(f.command, cache, f.inputs, false, f.environment, f.executor));
            equal(1, f.count());
            equal(f.command.subList(1, f.command.size()), f.arguments());
            expectIOException(() -> AotLauncher.train(f.command, cache, f.inputs, false, f.environment, f.executor));
            equal(1, f.count());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path base = Files.createTempDirectory("loadonce-java-contract-");
        final Path home = base.resolve("jdk");
        final Path java = home.resolve("bin/java");
        final Path jar = base.resolve("app.jar");
        final Path resource = base.resolve("declared/resource.bin");
        final Path cache = base.resolve("cache");
        final Path counter = base.resolve("counter");
        final Path probes = base.resolve("probes");
        final Path argumentFile = base.resolve("arguments");
        final List<Path> inputs = List.of(resource.getParent());
        final List<String> command = new ArrayList<>();
        final Map<String, String> environment = new HashMap<>();
        final AotLauncher.Executor executor = new AotLauncher.Executor() {
            @Override public JavaCommand.Probe probe(Path java, Map<String, String> environment)
                    throws IOException, InterruptedException {
                ChildProcess.Result result = ChildProcess.run(translate(List.of(java.toString(), "-XX:+PrintFlagsFinal", "-version")),
                        null, environment, base.resolve("probe.log"), Duration.ofSeconds(10));
                return new JavaCommand.Probe("25", result.exitCode() == 0 && result.output().contains("AOTCacheOutput")
                        && result.output().contains("AOTCache =") && result.output().contains("AOTMode"), false);
            }
            @Override public int execute(List<String> command, Map<String, String> environment)
                    throws IOException, InterruptedException {
                return ChildProcess.run(translate(command), null, environment, null, Duration.ofSeconds(10)).exitCode();
            }
        };

        Fixture() throws IOException {
            Files.createDirectories(java.getParent());
            Files.writeString(java, "fixture-runtime-binary");
            Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
            Files.writeString(home.resolve("release"), "JAVA_VERSION=25\n");
            Files.createDirectories(home.resolve("lib/server"));
            Files.writeString(home.resolve("lib/server/libjvm.so"), "runtime-library");
            Files.createDirectories(home.resolve("conf"));
            Files.writeString(home.resolve("conf/java.security"), "runtime-config");
            jar(jar, "Manifest-Version: 1.0\n\n", "class-v1");
            Files.createDirectories(resource.getParent());
            Files.writeString(resource, "resource-v1");
            command.addAll(List.of(java.toString(), "-cp", jar.toString(), "Main", "app argument"));
            environment.put("FAKE_COUNTER", counter.toString());
            environment.put("FAKE_PROBES", probes.toString());
            environment.put("FAKE_ARGUMENTS", argumentFile.toString());
            environment.put("LANG", "C.UTF-8");
        }

        private List<String> translate(List<String> original) {
            List<String> child = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp", absoluteClasspath(), FakeJvm.class.getName()));
            child.addAll(original.subList(1, original.size()));
            return child;
        }
        int train() throws Exception { return AotLauncher.train(command, cache, inputs, false, environment, executor); }
        int run() throws Exception { return AotLauncher.run(command, cache, inputs, false, environment, executor); }
        int count() throws IOException { return Files.exists(counter) ? Integer.parseInt(Files.readString(counter)) : 0; }
        int probes() throws IOException { return Files.exists(probes) ? Integer.parseInt(Files.readString(probes)) : 0; }
        Path entry() throws IOException { return cache.resolve(JavaCommand.fingerprint(command, inputs, environment)); }
        Path archive() throws IOException { return AotLauncher.validatedArchive(cache, entry().getFileName().toString()); }
        List<String> arguments() throws IOException {
            try (DataInputStream stream = new DataInputStream(Files.newInputStream(argumentFile))) {
                int size = stream.readInt();
                List<String> result = new ArrayList<>();
                for (int index = 0; index < size; index++) result.add(stream.readUTF());
                return result;
            }
        }
        @Override public void close() throws IOException { LauncherFiles.deleteTree(base); }
    }

    /** Real Java helper models application starts and the JVM's generated archive. */
    public static final class FakeJvm {
        public static void main(String[] arguments) throws Exception {
            if (List.of(arguments).equals(List.of("-XX:+PrintFlagsFinal", "-version"))) {
                increment(Path.of(System.getenv("FAKE_PROBES")));
                if (!"true".equals(System.getenv("FAKE_UNSUPPORTED"))) {
                    System.out.println("ccstr AOTCacheOutput = {product}\nccstr AOTCache = {product}\nccstr AOTMode = {product}");
                }
                System.out.println("openjdk version \"25\"");
                return;
            }
            increment(Path.of(System.getenv("FAKE_COUNTER")));
            String executableArgv = System.getenv("FAKE_EXECUTABLE_ARGV");
            if (executableArgv != null) {
                byte[] commandLine = Files.readAllBytes(Path.of("/proc/self/cmdline"));
                int end = 0;
                while (end < commandLine.length && commandLine[end] != 0) end++;
                Files.writeString(Path.of(executableArgv), new String(commandLine, 0, end, StandardCharsets.UTF_8));
            }
            try (DataOutputStream stream = new DataOutputStream(Files.newOutputStream(Path.of(System.getenv("FAKE_ARGUMENTS"))))) {
                stream.writeInt(arguments.length);
                for (String value : arguments) stream.writeUTF(value);
            }
            for (String value : arguments) {
                if (value.startsWith("-XX:AOTCacheOutput=")) {
                    Path output = Path.of(value.substring(value.indexOf('=') + 1));
                    String kind = System.getenv("FAKE_OUTPUT");
                    String link = System.getenv("FAKE_SYMLINK");
                    if (link != null) Files.createSymbolicLink(output, Path.of(link));
                    else if (!"missing".equals(kind)) Files.writeString(output, "empty".equals(kind) ? "" : "fake-aot-cache");
                }
            }
            String touched = System.getenv("FAKE_TOUCH");
            if (touched != null) Files.writeString(Path.of(touched), "mutated-input");
            System.exit(Integer.parseInt(System.getenv().getOrDefault("FAKE_EXIT", "0")));
        }

        private static void increment(Path path) throws IOException {
            int count = Files.exists(path) ? Integer.parseInt(Files.readString(path)) : 0;
            Files.writeString(path, Integer.toString(count + 1));
        }
    }

    static String absoluteClasspath() {
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(value -> Path.of(value).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static void jar(Path path, String manifest, String payload) throws IOException {
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(path))) {
            stream.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            stream.write(manifest.getBytes(StandardCharsets.UTF_8));
            stream.closeEntry();
            stream.putNextEntry(new ZipEntry("Main.class"));
            stream.write(payload.getBytes(StandardCharsets.UTF_8));
            stream.closeEntry();
        }
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static void equal(Object expected, Object actual) {
        check(java.util.Objects.equals(expected, actual), "expected " + expected + ", got " + actual);
    }
    private static void equalBytes(byte[] expected, byte[] actual) {
        check(java.util.Arrays.equals(expected, actual), "archive content changed unexpectedly");
    }
    private static void expectIOException(Checked action) throws Exception {
        checks++;
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("expected IOException");
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
