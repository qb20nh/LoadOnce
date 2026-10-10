package dev.loadonce.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Java/javaw entry point. JVM startup flags are selected before the application runs. */
public final class LoadOnce {
    private LoadOnce() {}

    public static void main(String[] args) {
        int code;
        try {
            code = execute(args, System.getenv());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("loadonce: interrupted; child stopped without replay");
            code = 130;
        } catch (IOException | IllegalArgumentException failure) {
            // File-system errors and invalid argument text can themselves contain credentials.
            System.err.println("loadonce: command failed; inspect private logs; no automatic replay");
            code = 2;
        }
        if (code != 0) System.exit(code);
    }

    public static int execute(String[] args, Map<String, String> environment) throws IOException, InterruptedException {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            return 0;
        }
        String action = args[0];
        if (!List.of("run", "train", "doctor", "fixture-capture", "fixture-run").contains(action)) {
            throw new IllegalArgumentException("unknown launcher action");
        }
        Path cache = null;
        Path java = null;
        Path classpathFile = null;
        Path criu = null;
        List<Path> inputs = new ArrayList<>();
        List<String> command = new ArrayList<>();
        boolean sensitive = false;
        boolean nativeFixture = false;
        boolean reconfigure = false;
        String engine = "warp";
        Duration timeout = Duration.ofSeconds(60);
        for (int i = 1; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--")) {
                command.addAll(List.of(args).subList(i + 1, args.length));
                break;
            }
            if (List.of("--sensitive", "--native", "--reconfigure").contains(option)) {
                switch (option) {
                    case "--sensitive" -> sensitive = true;
                    case "--native" -> nativeFixture = true;
                    case "--reconfigure" -> reconfigure = true;
                    default -> throw new AssertionError();
                }
                continue;
            }
            if (!List.of("--cache-dir", "--input", "--java", "--classpath-file", "--engine", "--timeout", "--criu").contains(option)
                    || ++i >= args.length) throw new IllegalArgumentException("invalid launcher option");
            String value = args[i];
            switch (option) {
                case "--cache-dir" -> cache = Path.of(value);
                case "--input" -> inputs.add(Path.of(value));
                case "--java" -> java = Path.of(value);
                case "--classpath-file" -> classpathFile = Path.of(value);
                case "--engine" -> engine = value;
                case "--timeout" -> {
                    long seconds = Long.parseLong(value);
                    if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("timeout must be 1..60 seconds");
                    timeout = Duration.ofSeconds(seconds);
                }
                case "--criu" -> criu = Path.of(value);
                default -> throw new AssertionError();
            }
        }
        if (action.equals("doctor")) {
            if (cache != null || classpathFile != null || !command.isEmpty() || !inputs.isEmpty()
                    || sensitive || nativeFixture || reconfigure || !engine.equals("warp")) {
                throw new IllegalArgumentException("invalid doctor options");
            }
            doctor(java == null ? Path.of(System.getProperty("java.home"), "bin", executableName("java")) : java,
                    criu, environment);
            return 0;
        }
        if (cache == null) throw new IllegalArgumentException("cache directory required");
        if (action.equals("run") || action.equals("train")) {
            if (command.isEmpty() || java != null || classpathFile != null || criu != null || nativeFixture || reconfigure
                    || !engine.equals("warp") || !timeout.equals(Duration.ofSeconds(60))) {
                throw new IllegalArgumentException("use -- followed by the original Java command");
            }
            return action.equals("train") ? AotLauncher.train(command, cache, inputs, sensitive, environment)
                    : AotLauncher.run(command, cache, inputs, sensitive, environment);
        }
        if (java == null || classpathFile == null || !command.isEmpty() || criu != null || sensitive) {
            throw new IllegalArgumentException("fixture requires Java and classpath file");
        }
        if (Files.size(classpathFile) > 1024 * 1024) throw new IOException("classpath file too large");
        CracFixture.Fixture fixture = new CracFixture.Fixture(JavaCommand.resolveExecutable(java.toString(), environment), Files.readString(classpathFile).strip(),
                nativeFixture, engine, inputs, reconfigure ? List.of("--reconfigure") : List.of());
        CracFixture.Outcome result = action.equals("fixture-capture") ? CracFixture.capture(fixture, cache, timeout, environment)
                : CracFixture.run(fixture, cache, timeout, environment);
        System.out.println("CRAC_FIXTURE mode=" + result.mode() + " exit=" + result.exitCode()
                + " pid=" + result.pid() + " log=" + result.log() + "; isolated fixture only; no replay after child startup");
        return result.exitCode();
    }

    private static void doctor(Path selectedJava, Path selectedCriu, Map<String, String> environment)
            throws IOException, InterruptedException {
        Path java = JavaCommand.resolveExecutable(selectedJava.toString(), environment);
        var probe = JavaCommand.probe(java, environment);
        System.out.println("java.version=" + probe.version());
        System.out.println("leyden=" + probe.leyden());
        System.out.println("crac.flags=" + probe.cracFlags());
        Map<String, String> clean = new HashMap<>(environment);
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS")) clean.remove(key);
        Path directory = LauncherFiles.tempDirectory(null, "loadonce-doctor-");
        try {
            Path javap = java.getParent().resolve(executableName("javap"));
            List<String> cores = new ArrayList<>();
            if (Files.isRegularFile(javap)) {
                for (String name : List.of("jdk.crac.Core", "org.crac.Core")) {
                    var result = ChildProcess.run(List.of(javap.toString(), name), directory, clean,
                            LauncherFiles.tempFile(directory, "core-", ".log"), Duration.ofSeconds(20));
                    if (result.exitCode() == 0) cores.add(name);
                }
            }
            System.out.println("crac.core=" + (cores.isEmpty() ? "unavailable" : String.join(",", cores)));
            Path criu = selectedCriu;
            if (criu == null) {
                try { criu = JavaCommand.resolveExecutable("criu", environment); }
                catch (IOException unavailable) { /* Explicitly reported below. */ }
            }
            if (criu == null) {
                System.out.println("criu=unavailable");
            } else {
                var result = ChildProcess.run(List.of(criu.toString(), "check"), directory, clean,
                        LauncherFiles.tempFile(directory, "criu-", ".log"), Duration.ofSeconds(20));
                System.out.println("criu=" + (result.exitCode() == 0 ? "kernel-check-passed" : "kernel-or-permission-blocked"));
                System.out.println("criu.exit=" + result.exitCode());
            }
            System.out.println("Checkpoint capabilities do not establish safe Minecraft or native restoration.");
        } finally {
            LauncherFiles.deleteTree(directory);
        }
    }

    private static String executableName(String name) {
        return System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name;
    }

    private static void usage() {
        System.out.println("""
                LoadOnce Java launcher
                  java -jar loadonce-launcher.jar doctor [--java PATH] [--criu PATH]
                  java -jar loadonce-launcher.jar train|run --cache-dir DIR [--input PATH] [--sensitive] -- JAVA ORIGINAL_ARGS...
                  java -jar loadonce-launcher.jar fixture-capture|fixture-run --java PATH --cache-dir DIR --classpath-file FILE
                    [--native] [--reconfigure] [--input PATH] [--engine warp|criuengine] [--timeout 1..60]
                Fixture images contain only the isolated CPU/native probes. Failed child launches are never replayed.
                javaw can start this same JAR; application arguments and environment remain under the host launcher's control.
                """);
    }
}
