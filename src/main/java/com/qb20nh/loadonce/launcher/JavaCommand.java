package com.qb20nh.loadonce.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inspects a Java launch without rewriting its argument vector or retaining credentials. */
public final class JavaCommand {
    private static final int SCHEMA = 3;
    private static final int PROBE_OUTPUT_LIMIT = 1024 * 1024;
    private static final List<String> JVM_ENV = List.of(
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS");
    private static final List<String> NATIVE_ENV = List.of(
            "LD_LIBRARY_PATH", "LD_PRELOAD", "DYLD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES");
    private static final List<String> FINGERPRINT_ENV = List.of(
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS",
            "LD_LIBRARY_PATH", "LD_PRELOAD", "DYLD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "CLASSPATH");
    // Arbitrary application credential files and custom environment names remain unknowable.
    private static final Set<String> CREDENTIAL_ENV = Set.of(
            "TOKEN", "ACCESS_TOKEN", "AUTH_TOKEN", "REFRESH_TOKEN", "SESSION_TOKEN", "API_KEY",
            "PASSWORD", "CLIENT_SECRET", "GH_TOKEN", "GITHUB_TOKEN", "GITHUB_PAT", "GITLAB_TOKEN",
            "CI_JOB_TOKEN", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "AWS_SECURITY_TOKEN", "AWS_WEB_IDENTITY_TOKEN_FILE", "GOOGLE_APPLICATION_CREDENTIALS",
            "GOOGLE_OAUTH_ACCESS_TOKEN", "CLOUDSDK_AUTH_ACCESS_TOKEN", "AZURE_CLIENT_SECRET",
            "AZURE_CLIENT_CERTIFICATE_PASSWORD", "AZURE_FEDERATED_TOKEN_FILE", "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY", "MINECRAFT_ACCESS_TOKEN", "MC_ACCESS_TOKEN", "MICROSOFT_ACCESS_TOKEN",
            "MS_ACCESS_TOKEN", "XBOX_TOKEN", "XSTS_TOKEN", "DISCORD_TOKEN", "NPM_TOKEN", "SSH_AUTH_SOCK");
    private static final List<String> PROXY_ENV = List.of(
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy");
    private static final Pattern PROXY_CREDENTIAL = Pattern.compile("://[^/\\s@]+@", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SECRET = Pattern.compile(
            "(?:(?:access|refresh|auth|id).?token|jwt|authorization|session(?:.?token|.?id)?|"
                    + "password|passwd|secret|credential|api.?key|bearer|"
                    + "(?:^|[-_.])auth(?:entication)?(?:[-_.=:]|$)|"
                    + "(?:^|[-_.])token(?:[-_.=:]|$)|://[^/\\s:@]+:[^/\\s@]+@)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    private static final Pattern FLAGS = Pattern.compile("^\\s*\\w+\\s+(\\w+)\\s+=", Pattern.MULTILINE);
    private static final Pattern VERSION = Pattern.compile("(?:java|openjdk) version \"([^\"]+)\"");
    private static final Set<String> OPERAND_OPTIONS = Set.of(
            "--add-modules", "--limit-modules", "--add-exports", "--add-opens", "--add-reads",
            "--enable-native-access", "--source", "--finalization", "--describe-module", "-d");

    public record Probe(String version, boolean leyden, boolean cracFlags) { }

    public record Inputs(List<Path> paths, boolean nonemptyClasspathDirectory) {
        public Inputs { paths = List.copyOf(paths); }
    }

    private JavaCommand() { }

    /** Resolves through the supplied PATH, never a shell or the launcher's inherited environment. */
    public static Path resolveExecutable(String value, Map<String, String> env) throws IOException {
        try {
            Path candidate = Path.of(value);
            if (value.isEmpty()) throw new IOException("Java executable was not found");
            if (candidate.isAbsolute() || value.indexOf('/') >= 0 || value.indexOf(File.separatorChar) >= 0) {
                return executablePath(candidate);
            }
            String searchPath = env.getOrDefault("PATH", File.separatorChar == '/' ? "/bin:/usr/bin" : "");
            for (String directory : searchPath.split(Pattern.quote(File.pathSeparator), -1)) {
                Path found = Path.of(directory.isEmpty() ? "." : directory).resolve(value);
                if (Files.isRegularFile(found) && Files.isExecutable(found)) return found.toRealPath();
            }
            throw new IOException("Java executable was not found");
        } catch (InvalidPathException | SecurityException failure) {
            throw new IOException("Java executable was not found");
        }
    }

    private static Path executablePath(Path candidate) throws IOException {
        if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) {
            throw new IOException("Java executable was not found");
        }
        return candidate.toRealPath();
    }

    /** Captures only bounded capability output, with injected JVM/native options removed. */
    public static Probe probe(Path java, Map<String, String> env) throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("Java capability probe interrupted");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-XX:+PrintFlagsFinal", "-version");
        builder.environment().clear();
        builder.environment().putAll(env);
        JVM_ENV.forEach(builder.environment()::remove);
        NATIVE_ENV.forEach(builder.environment()::remove);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ProbeOutput capture = new ProbeOutput(process.getInputStream());
        Thread reader = new Thread(capture, "loadonce-java-probe");
        reader.setDaemon(true);
        reader.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IOException("Java capability probe timed out");
            reader.join(1000);
            if (reader.isAlive()) throw new IOException("Java capability probe output did not complete");
            if (capture.failure != null) throw new IOException("Cannot read Java capability probe output");
            if (capture.exceeded) throw new IOException("Java capability probe output exceeded its limit");
            String output = capture.bytes.toString(StandardCharsets.UTF_8);
            Set<String> flags = new HashSet<>();
            Matcher matches = FLAGS.matcher(output);
            while (matches.find()) flags.add(matches.group(1));
            Matcher version = VERSION.matcher(output);
            return new Probe(version.find() ? version.group(1) : "unknown",
                    process.exitValue() == 0 && flags.containsAll(Set.of("AOTCacheOutput", "AOTCache", "AOTMode")),
                    flags.containsAll(Set.of("CRaCCheckpointTo", "CRaCRestoreFrom")));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
        }
    }

    private static final class ProbeOutput implements Runnable {
        private final InputStream source;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile IOException failure;
        private volatile boolean exceeded;

        private ProbeOutput(InputStream source) { this.source = source; }

        @Override public void run() {
            try (source) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = source.read(buffer)) != -1) {
                    int available = PROBE_OUTPUT_LIMIT - bytes.size();
                    if (length > available) exceeded = true;
                    if (available > 0) bytes.write(buffer, 0, Math.min(available, length));
                }
            } catch (IOException error) {
                failure = error;
            }
        }
    }

    public static boolean sensitive(List<String> command, Map<String, String> env) {
        for (int index = 1; index < command.size(); index++) {
            if (SECRET.matcher(command.get(index)).find()) return true;
        }
        for (String key : JVM_ENV) {
            if (SECRET.matcher(env.getOrDefault(key, "")).find()) return true;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (!entry.getValue().isEmpty() && CREDENTIAL_ENV.contains(entry.getKey().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        for (String key : PROXY_ENV) {
            if (PROXY_CREDENTIAL.matcher(env.getOrDefault(key, "")).find()) return true;
        }
        return false;
    }

    /** Finds launch inputs, including recursive local JAR manifest dependencies. */
    public static Inputs launchInputs(List<String> command, Map<String, String> env) throws IOException {
        requireCommand(command);
        for (String key : JVM_ENV) {
            if (!env.getOrDefault(key, "").isEmpty()) {
                throw new IOException("Injected JVM options require ordinary startup");
            }
        }
        for (String key : NATIVE_ENV) {
            if (!env.getOrDefault(key, "").isEmpty()) {
                throw new IOException("Injected native libraries require ordinary startup");
            }
        }
        for (int index = 1; index < command.size(); index++) {
            if (command.get(index).startsWith("@")) {
                throw new IOException("Java argument files require ordinary startup in this prototype");
            }
        }
        List<Path> paths = new ArrayList<>();
        List<Path> classpathDirectories = new ArrayList<>();
        String classpath = null;
        boolean jarLaunch = false;
        boolean moduleLaunch = false;
        try {
            for (int index = 1; index < command.size(); index++) {
                String value = command.get(index);
                if (startsWithAny(value, "-XX:AOT", "-XX:SharedArchive", "-XX:ArchiveClassesAtExit", "-Xshare:")) {
                    throw new IOException("Existing JVM archive options require ordinary startup");
                }
                if (Set.of("-cp", "-classpath", "--class-path").contains(value)) {
                    classpath = operand(command, ++index, "classpath");
                } else if (startsWithAny(value, "--class-path=", "-Djava.class.path=")) {
                    classpath = afterEquals(value);
                } else if (Set.of("--module-path", "-p", "--upgrade-module-path").contains(value)) {
                    addPathList(operand(command, ++index, "module path"), paths, null);
                } else if (startsWithAny(value, "--module-path=", "--upgrade-module-path=",
                        "-Djava.library.path=", "-Dorg.lwjgl.librarypath=")) {
                    addPathList(afterEquals(value), paths, null);
                } else if (value.startsWith("-Dorg.lwjgl.system.SharedLibraryExtractPath=")) {
                    paths.add(Path.of(afterEquals(value)));
                } else if (value.equals("--patch-module")) {
                    String patch = operand(command, ++index, "patch module");
                    if (!patch.contains("=")) throw new IOException("Incomplete Java patch module option");
                    addPathList(afterEquals(patch), paths, null);
                } else if (value.startsWith("--patch-module=")) {
                    String patch = afterEquals(value);
                    if (!patch.contains("=")) throw new IOException("Incomplete Java patch module option");
                    addPathList(afterEquals(patch), paths, null);
                } else if (startsWithAny(value, "-javaagent:", "-agentpath:")) {
                    String agent = value.substring(value.indexOf(':') + 1);
                    paths.add(Path.of(agent.split("=", 2)[0]));
                } else if (value.startsWith("-agentlib:")) {
                    throw new IOException("Native agent library lookup requires ordinary startup");
                } else if (startsWithAny(value, "-XX:Flags=", "-XX:VMOptionsFile=")) {
                    throw new IOException("External JVM option files require ordinary startup");
                } else if (startsWithAny(value, "-Xbootclasspath/a:", "-Xbootclasspath/p:")) {
                    addPathList(value.substring(value.indexOf(':') + 1), paths, null);
                } else if (value.equals("-jar")) {
                    paths.add(Path.of(operand(command, ++index, "JAR")));
                    jarLaunch = true;
                    break;
                } else if (value.equals("-m") || value.equals("--module") || value.startsWith("--module=")) {
                    moduleLaunch = true;
                    break;
                } else if (OPERAND_OPTIONS.contains(value)) {
                    operand(command, ++index, "operand");
                } else if (value.equals("--")) {
                    break;
                } else if (!value.startsWith("-")) {
                    if (value.endsWith(".java")) throw new IOException("Java source launches require ordinary startup");
                    break;
                }
            }
            if (!jarLaunch && (!moduleLaunch || classpath != null)) {
                addPathList(classpath != null ? classpath : env.getOrDefault("CLASSPATH", "."),
                        paths, classpathDirectories);
            }
            TreeSet<Path> seen = new TreeSet<>();
            ArrayDeque<Path> pending = new ArrayDeque<>(paths);
            while (!pending.isEmpty()) {
                Path path = pending.removeLast().toAbsolutePath();
                if (!seen.add(path)) continue;
                List<Path> added = LauncherFiles.jarDependencies(path);
                pending.addAll(added);
                classpathDirectories.addAll(added);
            }
            boolean nonempty = false;
            for (Path directory : classpathDirectories) {
                if (Files.isDirectory(directory)) {
                    try (var children = Files.newDirectoryStream(directory)) {
                        if (children.iterator().hasNext()) { nonempty = true; break; }
                    }
                }
            }
            return new Inputs(new ArrayList<>(seen), nonempty);
        } catch (InvalidPathException | SecurityException failure) {
            throw new IOException("Cannot inspect Java launch inputs");
        }
    }

    private static void addPathList(String value, List<Path> paths, List<Path> classpathDirectories) throws IOException {
        for (String item : value.split(Pattern.quote(File.pathSeparator), -1)) {
            Path path = Path.of(item.isEmpty() ? "." : item);
            if (path.getFileName() != null && path.getFileName().toString().equals("*")) {
                Path parent = path.getParent() == null ? Path.of(".") : path.getParent();
                paths.add(parent);
                if (Files.isDirectory(parent)) {
                    try (var children = Files.newDirectoryStream(parent)) {
                        for (Path child : children) {
                            if (child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) paths.add(child);
                        }
                    }
                }
            } else {
                paths.add(path);
                if (classpathDirectories != null) classpathDirectories.add(path);
            }
        }
    }

    private static String operand(List<String> command, int index, String kind) throws IOException {
        if (index >= command.size()) throw new IOException("Incomplete Java " + kind + " option");
        return command.get(index);
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static String afterEquals(String value) { return value.substring(value.indexOf('=') + 1); }

    private static void requireCommand(List<String> command) throws IOException {
        if (command.isEmpty() || command.get(0).isEmpty()) throw new IOException("Java launch command is required");
    }

    public static String fingerprint(List<String> command, List<Path> inputs, Map<String, String> env) throws IOException {
        requireCommand(command);
        Path java = resolveExecutable(command.get(0), env);
        Path bin = java.getParent();
        if (bin == null || bin.getParent() == null) throw new IOException("Cannot identify Java runtime directory");
        Path home = bin.getParent();
        Inputs detected = launchInputs(command, env);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        // Length-delimited context is held only in memory; metadata receives this digest alone.
        digestString(digest, "loadonce-java-command");
        digestString(digest, Integer.toString(SCHEMA));
        digestString(digest, Integer.toString(command.size()));
        for (String argument : command) digestString(digest, argument);
        digestString(digest, Path.of("").toAbsolutePath().normalize().toString());
        digestString(digest, System.getProperty("os.name"));
        digestString(digest, System.getProperty("os.arch"));
        for (String key : FINGERPRINT_ENV) {
            digestString(digest, key);
            digestString(digest, env.get(key));
        }
        TreeSet<Path> paths = new TreeSet<>();
        paths.add(java);
        paths.add(home.resolve("release"));
        paths.add(home.resolve("lib"));
        paths.add(home.resolve("conf"));
        paths.addAll(detected.paths());
        for (Path input : inputs) paths.add(input.toAbsolutePath());
        for (Path path : paths) LauncherFiles.digestPath(digest, path);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void digestString(MessageDigest digest, String value) {
        if (value == null) {
            digest.update("-1:".getBytes(StandardCharsets.US_ASCII));
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
    }
}
