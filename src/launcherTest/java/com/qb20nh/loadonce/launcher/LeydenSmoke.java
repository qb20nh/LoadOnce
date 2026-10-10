package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Real JDK 25 AOT mapping, argument, failure and same-child fallback checks. */
public final class LeydenSmoke {
    private static final String EXPECTED = "APP:a b|literal$HOME|quote\"value|literal*|literal;$()|--flag=value";

    private LeydenSmoke() { }

    public static void main(String[] arguments) throws Exception {
        String selectedHome = arguments.length == 0 ? System.getenv("JAVA_HOME") : arguments[0];
        if (selectedHome == null || selectedHome.isBlank()) {
            throw new IOException("JAVA_HOME or a JDK home argument is required for the real Leyden smoke");
        }
        Path javaHome = Path.of(selectedHome).toAbsolutePath();
        Path java = javaHome.resolve("bin/java");
        Map<String, String> environment = cleanEnvironment();
        if (!JavaCommand.probe(java, environment).leyden()) {
            throw new IOException("selected runtime has no JDK 25 AOT cache support");
        }
        Path root = LauncherFiles.tempDirectory(null, "loadonce-java-leyden-smoke-");
        try {
            Path source = root.resolve("Smoke.java");
            Files.writeString(source, """
                    import java.nio.file.*;
                    public class Smoke {
                        public static void main(String[] args) throws Exception {
                            Path counter = Path.of(args[0]);
                            int count = Files.exists(counter) ? Integer.parseInt(Files.readString(counter)) : 0;
                            Files.writeString(counter, Integer.toString(count + 1));
                            System.out.println("APP:" + String.join("|", java.util.Arrays.copyOfRange(args, 1, args.length)));
                            System.exit(Integer.parseInt(System.getenv().getOrDefault("SMOKE_EXIT", "0")));
                        }
                    }
                    """);
            ChildProcess.Result compiled = ChildProcess.run(
                    List.of(javaHome.resolve("bin/javac").toString(), source.toString()),
                    root, environment, root.resolve("compile.log"), Duration.ofSeconds(60));
            require(compiled.exitCode() == 0, compiled.output());
            Path jar = root.resolve("smoke.jar");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                output.putNextEntry(new JarEntry("Smoke.class"));
                output.write(Files.readAllBytes(root.resolve("Smoke.class")));
                output.closeEntry();
            }
            Path counter = root.resolve("counter");
            Path cache = root.resolve("cache");
            List<String> command = List.of(java.toString(), "-Xlog:aot", "-cp", jar.toString(), "Smoke",
                    counter.toString(), "a b", "literal$HOME", "quote\"value", "literal*", "literal;$()", "--flag=value");
            ChildProcess.Result cold = invoke("run", command, cache, environment, root.resolve("cold.log"));
            require(cold.exitCode() == 0 && cold.output().contains("ordinary startup"), cold.output());
            require(count(counter) == 1, "cold launch count changed");
            ChildProcess.Result training = invoke("train", command, cache, environment, root.resolve("train.log"));
            require(training.exitCode() == 0, training.output());
            require(count(counter) == 2, "training ran main more than once");
            ChildProcess.Result warm = invoke("run", command, cache, environment, root.resolve("warm.log"));
            require(warm.exitCode() == 0 && warm.output().contains("using validated")
                    && warm.output().contains("Opened AOT cache"), warm.output());
            require(count(counter) == 3, "warm launch count changed");
            ChildProcess.Result failed = invoke("run", command, cache, with(environment, "SMOKE_EXIT", "7"), root.resolve("failed.log"));
            require(failed.exitCode() == 7 && failed.output().contains("using validated"), failed.output());
            require(count(counter) == 4, "failed cached application was lost or replayed");

            String key = JavaCommand.fingerprint(command, List.of(), environment);
            Path archive = AotLauncher.validatedArchive(cache, key);
            byte[] previous = Files.readAllBytes(archive);
            ChildProcess.Result failedTraining = invoke("train", command, cache, with(environment, "SMOKE_EXIT", "13"),
                    root.resolve("failed-training.log"));
            require(failedTraining.exitCode() == 13, failedTraining.output());
            require(Arrays.equals(previous, Files.readAllBytes(AotLauncher.validatedArchive(cache, key))),
                    "unsuccessful training replaced the previous cache");
            require(count(counter) == 5, "failed training application was lost or replayed");

            // Outer integrity is valid; HotSpot must reject the inner format and
            // execute main in this same JVM, including its nonzero exit code.
            Files.writeString(archive, "not-a-hotspot-archive".repeat(50), StandardCharsets.UTF_8);
            Path manifest = cache.resolve(key).resolve("manifest.properties");
            Map<String, String> record = new HashMap<>(LauncherFiles.readManifest(manifest, AotLauncher.MANIFEST_FIELDS));
            record.put("sha256", LauncherFiles.fileHash(archive));
            record.put("bytes", Long.toString(Files.size(archive)));
            LauncherFiles.writeManifest(manifest, record);
            ChildProcess.Result rejected = invoke("run", command, cache, with(environment, "SMOKE_EXIT", "7"),
                    root.resolve("rejected.log"));
            require(rejected.exitCode() == 7 && rejected.output().contains("using validated")
                    && rejected.output().contains("bad magic number"), rejected.output());
            require(count(counter) == 6, "JVM archive rejection lost or replayed the application");
            Files.writeString(archive, "corrupt");
            ChildProcess.Result corrupt = invoke("run", command, cache, environment, root.resolve("corrupt.log"));
            require(corrupt.exitCode() == 0 && corrupt.output().contains("ordinary startup"), corrupt.output());
            require(count(counter) == 7, "integrity fallback lost or replayed the application");

            ChildProcess.Result credentials = invokeRaw("train", command, cache,
                    with(environment, "GH_TOKEN", "fixture-credential"), root.resolve("credentials.log"));
            require(credentials.exitCode() == 2 && credentials.output().contains("credential environments"), credentials.output());
            require(!credentials.output().contains("APP:") && count(counter) == 7, "credential training executed main");
            require(!credentials.output().contains("fixture-credential"), "credential value was logged");
            System.out.println("Real Java Leyden smoke passed: AOT mapping, exact arguments, training/run failures without replay, "
                    + "JVM archive rejection, corruption fallback, and credential-environment refusal.");
        } finally {
            LauncherFiles.deleteTree(root);
        }
    }

    private static ChildProcess.Result invoke(String action, List<String> command, Path cache,
                                               Map<String, String> environment, Path log) throws Exception {
        ChildProcess.Result result = invokeRaw(action, command, cache, environment, log);
        require(occurrences(result.output(), EXPECTED) == 1, result.output());
        return result;
    }

    private static ChildProcess.Result invokeRaw(String action, List<String> command, Path cache,
                                                  Map<String, String> environment, Path log) throws Exception {
        List<String> worker = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", AotLauncherTest.absoluteClasspath(), Invocation.class.getName(), action, cache.toString()));
        worker.addAll(command);
        return ChildProcess.run(worker, null, environment, log, Duration.ofSeconds(60));
    }

    /** A clean child hosts the launcher, exposing application IO to the outer driver. */
    public static final class Invocation {
        public static void main(String[] arguments) throws Exception {
            List<String> command = List.of(arguments).subList(2, arguments.length);
            try {
                int exit = arguments[0].equals("train")
                        ? AotLauncher.train(command, Path.of(arguments[1]), List.of(), false, System.getenv())
                        : AotLauncher.run(command, Path.of(arguments[1]), List.of(), false, System.getenv());
                System.exit(exit);
            } catch (IOException failure) {
                // Production entrypoint owns the same generic diagnostic policy.
                System.err.println("loadonce: " + failure.getMessage());
                System.exit(2);
            }
        }
    }

    private static Map<String, String> cleanEnvironment() {
        Map<String, String> result = new HashMap<>();
        for (String name : List.of("PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "SYSTEMROOT")) {
            if (System.getenv(name) != null) result.put(name, System.getenv(name));
        }
        return result;
    }
    private static Map<String, String> with(Map<String, String> environment, String name, String value) {
        Map<String, String> result = new HashMap<>(environment);
        result.put(name, value);
        return result;
    }
    private static int count(Path counter) throws IOException { return Integer.parseInt(Files.readString(counter)); }
    private static int occurrences(String output, String expected) {
        int count = 0;
        for (int index = output.indexOf(expected); index >= 0; index = output.indexOf(expected, index + expected.length())) count++;
        return count;
    }
    private static void require(boolean condition, String diagnostic) {
        if (!condition) throw new AssertionError(diagnostic);
    }
}
