package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Cache policy for an externally launched Java command, without consuming its input streams. */
public final class ExternalSupervisor {
    private static final Set<String> INFORMATION_OPTIONS = Set.of(
            "-version", "--version", "-showversion", "--show-version", "-help", "--help", "-h", "-?", "-X",
            "--help-extra", "--list-modules", "--describe-module", "-d", "--validate-modules", "-fullversion",
            "--dry-run", "-XshowSettings", "-XX:+PrintFlagsFinal", "-XX:+PrintFlagsInitial",
            "-XX:+PrintFlagsRanges");
    private static final Set<String> OPERAND_OPTIONS = Set.of(
            "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
            "--add-modules", "--limit-modules", "--add-exports", "--add-opens", "--add-reads",
            "--patch-module", "--enable-native-access", "--source", "--finalization");

    private ExternalSupervisor() { }

    /**
     * {@code auto} uses a validated existing cache; it never trains automatically.
     * The caller restores the original environment before entering this API and
     * leaves its working directory and standard streams inherited from the host.
     */
    public static int execute(Path backend, Path agent, Path cache, String mode,
                              List<String> originalArguments, List<Path> declaredInputs,
                              Map<String, String> exactEnvironment, boolean verbose)
            throws IOException, InterruptedException {
        if (!Set.of("off", "auto", "train").contains(mode)) throw new IllegalArgumentException("invalid AOT mode");
        List<String> arguments = List.copyOf(originalArguments);
        List<Path> inputs = List.copyOf(declaredInputs);
        Map<String, String> environment = Map.copyOf(exactEnvironment);
        Path configuredAgent = agent.toAbsolutePath();
        List<String> command = new ArrayList<>();
        command.add(backend.toAbsolutePath().toString());
        if (!containsConfiguredAgent(arguments, configuredAgent)) command.add("-javaagent:" + configuredAgent);
        command.addAll(arguments);
        Consumer<String> diagnostics = verbose
                ? text -> System.err.println("loadonce: " + text) : text -> { };
        SupervisedExecutor executor = new SupervisedExecutor();

        // Probes, checker/launcher entrypoints and credential-bearing commands
        // cannot acquire or publish a cache, even in an explicit training profile.
        if (mode.equals("off") || probeOrOpaque(arguments)
                || JavaCommand.sensitive(command, environment)) {
            return executor.execute(command, environment);
        }
        if (mode.equals("auto")) {
            if (!Files.exists(cache)) {
                diagnostics.accept("AOT cache absent: ordinary startup");
                return executor.execute(command, environment);
            }
            return AotLauncher.run(command, cache, inputs, false, environment, executor, diagnostics);
        }
        try {
            return AotLauncher.train(command, cache, inputs, false, environment, executor, diagnostics);
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            if (executor.attempted) throw failure;
            diagnostics.accept("AOT training unavailable before launch: ordinary startup");
            return executor.execute(command, environment);
        }
    }

    private static final class SupervisedExecutor implements AotLauncher.Executor {
        private boolean attempted;

        @Override public JavaCommand.Probe probe(Path java, Map<String, String> environment)
                throws IOException, InterruptedException {
            return JavaCommand.probe(java, environment);
        }

        @Override public int execute(List<String> command, Map<String, String> environment)
                throws IOException, InterruptedException {
            // Set before process creation: even an ambiguous failed start cannot
            // authorize a replay. Publication failures after main are also final.
            attempted = true;
            return ChildProcess.runSupervised(command, null, environment, null, null).exitCode();
        }
    }

    private static boolean containsConfiguredAgent(List<String> arguments, Path configured) throws IOException {
        Path expected = null;
        for (int index = 0; index < arguments.size(); index++) {
            String value = arguments.get(index);
            if (value.startsWith("-javaagent:")) {
                String path = value.substring("-javaagent:".length()).split("=", 2)[0];
                try {
                    Path candidate = Path.of(path).toAbsolutePath();
                    if (candidate.equals(configured)) return true;
                    if (Files.exists(candidate) && Files.exists(configured)) {
                        if (expected == null) expected = configured.toRealPath();
                        if (candidate.toRealPath().equals(expected)) return true;
                    }
                } catch (InvalidPathException ignored) {
                    // Preserve invalid user options for the backend's own error.
                }
            } else if (OPERAND_OPTIONS.contains(value)) {
                index++;
            } else if (value.equals("-jar") || value.equals("-m") || value.equals("--module")
                    || value.startsWith("--module=") || !value.startsWith("-")) {
                break;
            }
        }
        return false;
    }

    private static boolean probeOrOpaque(List<String> arguments) {
        if (arguments.isEmpty()) return true;
        for (int index = 0; index < arguments.size(); index++) {
            String value = arguments.get(index);
            if (value.startsWith("@") || INFORMATION_OPTIONS.contains(value)
                    || value.startsWith("-XshowSettings:") || value.startsWith("--describe-module=")) return true;
            if (value.equals("-jar")) {
                if (++index == arguments.size()) return true;
                return checkerJar(arguments.get(index));
            }
            if (value.equals("-m") || value.equals("--module")) {
                return ++index == arguments.size() || checkerOrOpaqueMain(arguments.get(index));
            }
            if (value.startsWith("--module=")) return checkerOrOpaqueMain(value.substring("--module=".length()));
            if (OPERAND_OPTIONS.contains(value)) {
                index++;
            } else if (!value.startsWith("-")) {
                return checkerOrOpaqueMain(value);
            }
        }
        return true;
    }

    private static boolean checkerJar(String value) {
        try {
            Path path = Path.of(value);
            String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains("checker") || name.contains("javacheck") || name.contains("javainfo")) return true;
            try (ZipFile jar = new ZipFile(path.toFile())) {
                var entry = jar.getEntry("META-INF/MANIFEST.MF");
                if (entry == null) return false;
                try (var input = jar.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(65537);
                    if (bytes.length > 65536) return true;
                    String main = new Manifest(new java.io.ByteArrayInputStream(bytes)).getMainAttributes()
                            .getValue(Attributes.Name.MAIN_CLASS);
                    return main != null && checkerOrOpaqueMain(main);
                }
            }
        } catch (IOException | InvalidPathException | SecurityException failure) {
            // A malformed or unavailable JAR is still launched once, unchanged.
            return true;
        }
    }

    private static boolean checkerOrOpaqueMain(String value) {
        int slash = value.lastIndexOf('/');
        String name = (slash < 0 ? value : value.substring(slash + 1)).toLowerCase(Locale.ROOT);
        return name.equals("javainfo") || name.equals("javacheck") || name.equals("javachecker")
                || name.startsWith("org.multimc.") || name.startsWith("org.prismlauncher.")
                || name.endsWith(".javainfo") || name.endsWith(".javacheck") || name.endsWith(".javachecker");
    }
}
