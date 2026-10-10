package com.qb20nh.loadonce.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Host environment contracts use a helper JVM and never open a display or audio device. */
public final class NativeDisplaySmokeTest {
    private static int checks;
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    private NativeDisplaySmokeTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child();
            return;
        }
        environmentAndProbeModes();
        if ("Linux".equals(System.getProperty("os.name"))) desktopCommandIsIsolated();
        invalidConfigurations();
        System.out.println("Native display smoke checks passed: " + checks);
    }

    private static void environmentAndProbeModes() throws Exception {
        Path root = LauncherFiles.tempDirectory(null, "loadonce-native-mode-test-");
        try {
            Path runtime = Files.createDirectory(root.resolve("host-runtime"));
            Path home = Files.createDirectory(root.resolve("host-home"));
            Files.writeString(home.resolve(".Xauthority"), "fixture-authorization");
            Files.createDirectories(home.resolve(".config/pulse"));
            Files.writeString(home.resolve(".config/pulse/cookie"), "fixture-cookie");
            Map<String, String> parent = Map.of("HOME", home.toString(), "DISPLAY", ":fixture",
                    "WAYLAND_DISPLAY", "wayland-fixture", "XDG_RUNTIME_DIR", runtime.toString(),
                    "PULSE_SERVER", "unix:fixture-audio", "ALSOFT_DRIVERS", "null",
                    "LIBGL_ALWAYS_SOFTWARE", "1", "JAVA_TOOL_OPTIONS", "-Dfixture.injected=true");
            Path classpath = root.resolve("classpath.txt");
            Files.writeString(classpath, "fixture-classpath");
            NativeDisplaySmoke.Options isolated = NativeDisplaySmoke.Options.parse(
                    new String[] {"--classpath-file", classpath.toString()});
            Map<String, String> clean = NativeDisplaySmoke.environment(root.resolve("isolated"), JAVA, isolated, parent);
            check("1".equals(clean.get("LIBGL_ALWAYS_SOFTWARE")) && "null".equals(clean.get("ALSOFT_DRIVERS")),
                    "default mode keeps software GL and null driver");
            check(!clean.containsKey("DISPLAY") && !clean.containsKey("WAYLAND_DISPLAY")
                            && !clean.containsKey("XAUTHORITY") && !clean.containsKey("PULSE_SERVER"),
                    "private display mode excludes desktop and audio endpoints");
            check(!runtime.toString().equals(clean.get("XDG_RUNTIME_DIR")), "private mode isolates runtime directory");
            check(NativeDisplaySmoke.probeCommand(JAVA, isolated).contains("--audio-loopback"),
                    "private probe validates loopback PCM");

            NativeDisplaySmoke.Options desktop = NativeDisplaySmoke.Options.parse(
                    new String[] {"--desktop", "--classpath-file", classpath.toString()});
            Map<String, String> display = NativeDisplaySmoke.environment(root.resolve("desktop"), JAVA, desktop, parent);
            check(":fixture".equals(display.get("DISPLAY")) && "wayland-fixture".equals(display.get("WAYLAND_DISPLAY"))
                            && runtime.toString().equals(display.get("XDG_RUNTIME_DIR")),
                    "desktop mode forwards explicitly selected display endpoints");
            check(home.resolve(".Xauthority").toString().equals(display.get("XAUTHORITY")),
                    "isolated HOME can still use the caller's existing Xauthority file");
            check(!display.containsKey("LIBGL_ALWAYS_SOFTWARE") && !display.containsKey("JAVA_TOOL_OPTIONS"),
                    "desktop mode excludes software override and JVM injection");
            check("null".equals(display.get("ALSOFT_DRIVERS")) && !display.containsKey("PULSE_SERVER")
                            && NativeDisplaySmoke.probeCommand(JAVA, desktop).contains("--audio-loopback"),
                    "desktop display alone retains loopback audio");

            NativeDisplaySmoke.Options audio = NativeDisplaySmoke.Options.parse(
                    new String[] {"--desktop", "--system-audio", "--classpath-file", classpath.toString(), "--reconfigure"});
            Map<String, String> system = NativeDisplaySmoke.environment(root.resolve("system"), JAVA, audio, parent);
            check("unix:fixture-audio".equals(system.get("PULSE_SERVER"))
                            && home.resolve(".config/pulse/cookie").toString().equals(system.get("PULSE_COOKIE"))
                            && !system.containsKey("ALSOFT_DRIVERS"),
                    "system audio can reach the current audio server without inheriting a forced driver");
            check(!home.toString().equals(system.get("HOME")) && Files.isDirectory(Path.of(system.get("HOME")))
                            && Path.of(system.get("TMPDIR")).startsWith(root.resolve("system")),
                    "host mode retains private HOME and temporary files");
            List<String> command = NativeDisplaySmoke.probeCommand(JAVA, audio);
            check(!command.contains("--audio-loopback") && command.contains("--reconfigure")
                            && command.contains("--cycles=10"),
                    "system probe uses default audio and preserves reconstruction checks");
        } finally {
            LauncherFiles.deleteTree(root);
        }
    }

    private static void desktopCommandIsIsolated() throws Exception {
        List<String> args = new ArrayList<>(List.of("--desktop", "--system-audio", "--command",
                JAVA.toString(), "-cp", absoluteClasspath(), NativeDisplaySmokeTest.class.getName(), "child"));
        Map<String, String> parent = Map.of("DISPLAY", ":fixture", "PULSE_SERVER", "unix:fixture-audio",
                "XAUTHORITY", "/fixture/authority", "JAVA_TOOL_OPTIONS", "-XX:DefinitelyNotAJvmOption",
                "_JAVA_OPTIONS", "-XX:DefinitelyNotAJvmOption", "JDK_JAVA_OPTIONS", "-XX:DefinitelyNotAJvmOption",
                "LD_PRELOAD", "/fixture/injection.so", "ALSOFT_DRIVERS", "null", "LIBGL_ALWAYS_SOFTWARE", "1");
        check(NativeDisplaySmoke.execute(NativeDisplaySmoke.Options.parse(args.toArray(String[]::new)), parent) == 0,
                "--command executes on the desktop without Xvfb and without inherited JVM or native injections");
    }

    private static void child() {
        check(":fixture".equals(System.getenv("DISPLAY")), "child receives selected display");
        check("unix:fixture-audio".equals(System.getenv("PULSE_SERVER")), "child receives selected audio endpoint");
        check("/fixture/authority".equals(System.getenv("XAUTHORITY")), "child receives authority reference");
        Set<String> allowed = Set.of("PATH", "HOME", "LANG", "JAVA_HOME", "TMPDIR", "MESA_SHADER_CACHE_DIR",
                "DISPLAY", "PULSE_SERVER", "XAUTHORITY");
        check(allowed.equals(System.getenv().keySet()), "child receives only the allowlisted environment");
        check(System.getProperty("fixture.injected") == null, "child has no inherited JVM properties");
        System.out.println("NATIVE_SMOKE_COMMAND_FIXTURE PASS");
    }

    private static void invalidConfigurations() throws Exception {
        fails("--system-audio requires --desktop", () -> NativeDisplaySmoke.Options.parse(new String[] {"--system-audio"}));
        fails("--desktop cannot be combined with --xvfb", () -> NativeDisplaySmoke.Options.parse(
                new String[] {"--desktop", "--xvfb=/unavailable/Xvfb"}));
        fails("--desktop requires Linux", () -> NativeDisplaySmoke.validateDesktop(Map.of("DISPLAY", ":0"), "Windows"));
        fails("--desktop requires DISPLAY or WAYLAND_DISPLAY", () -> NativeDisplaySmoke.validateDesktop(Map.of(), "Linux"));
        fails("WAYLAND_DISPLAY requires XDG_RUNTIME_DIR", () -> NativeDisplaySmoke.validateDesktop(
                Map.of("WAYLAND_DISPLAY", "wayland-0"), "Linux"));
        fails("XDG_RUNTIME_DIR must name an existing readable absolute directory", () -> NativeDisplaySmoke.validateDesktop(
                Map.of("DISPLAY", ":0", "XDG_RUNTIME_DIR", "relative-runtime"), "Linux"));
    }

    private static String absoluteClasspath() {
        List<String> parts = new ArrayList<>();
        for (String part : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator), -1)) {
            parts.add(Path.of(part.isEmpty() ? "." : part).toAbsolutePath().normalize().toString());
        }
        return String.join(File.pathSeparator, parts);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static void fails(String diagnostic, Checked action) throws Exception {
        checks++;
        try {
            action.run();
        } catch (IOException failure) {
            if (failure.getMessage().contains(diagnostic)) return;
            throw new AssertionError("expected diagnostic: " + diagnostic, failure);
        }
        throw new AssertionError("expected diagnostic: " + diagnostic);
    }
}
