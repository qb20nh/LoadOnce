package com.qb20nh.loadonce.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Installs a private Java executable selector without changing the selected JDK. */
public final class RuntimeRedirectInstaller {
    private static final String CONFIG = "loadonce-runtime.conf";
    private static final String POLICY = "loadonce-policy.conf";
    private static final int MAX_INPUTS = 1024;
    private static final int MAX_POLICY_BYTES = 1024 * 1024;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");

    private RuntimeRedirectInstaller() {}

    /**
     * Startup policy contains only persistent configuration, never a launcher's arguments or environment.
     * Null bootstrap and cache paths select the application JDK and the private runtime's cache directory.
     */
    public record Options(Path bootstrapJavaHome, String aot, Path cache, List<Path> inputs, boolean verbose) {
        public Options {
            if (!"off".equals(aot) && !"auto".equals(aot) && !"train".equals(aot)) {
                throw new IllegalArgumentException("AOT mode must be off, auto, or train");
            }
            inputs = List.copyOf(inputs);
            if (inputs.size() > MAX_INPUTS) throw new IllegalArgumentException("Too many declared inputs");
        }
    }

    public static void main(String[] args) {
        try {
            Map<String, String> options = new HashMap<>();
            List<Path> inputs = new ArrayList<>();
            boolean verbose = false;
            Set<String> accepted = Set.of("--java-home", "--redirectors-dir", "--output-dir", "--agent-jar",
                    "--supervisor-jar", "--bootstrap-java-home", "--aot", "--cache");
            for (int index = 0; index < args.length; index++) {
                String option = args[index];
                if (option.equals("--verbose")) {
                    if (verbose) throw new IllegalArgumentException(usage());
                    verbose = true;
                } else if (option.equals("--input")) {
                    if (++index >= args.length) throw new IllegalArgumentException(usage());
                    inputs.add(Path.of(args[index]));
                } else {
                    if (!accepted.contains(option) || ++index >= args.length
                            || options.putIfAbsent(option, args[index]) != null) {
                        throw new IllegalArgumentException(usage());
                    }
                }
            }
            for (String required : List.of("--java-home", "--redirectors-dir", "--output-dir", "--agent-jar", "--supervisor-jar")) {
                if (!options.containsKey(required)) throw new IllegalArgumentException(usage());
            }
            Path home = Path.of(options.get("--java-home"));
            Path outputDir = Path.of(options.get("--output-dir"));
            Options policy = new Options(options.containsKey("--bootstrap-java-home")
                    ? Path.of(options.get("--bootstrap-java-home")) : home, options.getOrDefault("--aot", "auto"),
                    options.containsKey("--cache") ? Path.of(options.get("--cache")) : null, inputs, verbose);
            Path output = install(home, Path.of(options.get("--redirectors-dir")), outputDir,
                    Path.of(options.get("--agent-jar")), Path.of(options.get("--supervisor-jar")), policy);
            System.out.println("Installed LoadOnce runtime: " + output);
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("LoadOnce runtime setup failed: " + failure.getMessage());
            System.exit(1);
        }
    }

    public static Path install(Path javaHome, Path redirectorsDir, Path outputDir, Path agentJar, Path supervisorJar) throws IOException {
        return install(javaHome, redirectorsDir, outputDir, agentJar, supervisorJar,
                new Options(javaHome, "auto", null, List.of(), false));
    }

    public static Path install(Path javaHome, Path redirectorsDir, Path outputDir, Path agentJar,
            Path supervisorJar, Path bootstrapJavaHome) throws IOException {
        return install(javaHome, redirectorsDir, outputDir, agentJar, supervisorJar,
                new Options(bootstrapJavaHome, "auto", null, List.of(), false));
    }

    public static Path install(Path javaHome, Path redirectorsDir, Path outputDir, Path agentJar,
            Path supervisorJar, Options options) throws IOException {
        String os = System.getProperty("os.name", "");
        boolean windows = os.startsWith("Windows");
        if (!windows && !os.equals("Linux")) throw new IOException("Only Linux and Windows redirectors are supported");
        Path home = javaHome.toRealPath();
        Path bootstrapHome = options.bootstrapJavaHome() == null ? home : options.bootstrapJavaHome().toRealPath();
        Path backend = backend(home, windows);
        Path bootstrapBackend = backend(bootstrapHome, windows);
        String architecture = nativeArchitecture(backend, windows);
        if (!nativeArchitecture(bootstrapBackend, windows).equals(architecture)) {
            throw new IOException("Bootstrap and application Java backends have different architectures");
        }
        Path binaries = redirectorsDir.toRealPath();
        if (!Files.isDirectory(binaries)) throw new IOException("Redirectors directory must be a directory");
        String[] names = windows ? new String[] {"java.exe", "javaw.exe"} : new String[] {"java"};
        for (String name : names) {
            Path nativeBinary = readableFile(binaries.resolve(name));
            if (!nativeArchitecture(nativeBinary, windows).equals(architecture)) {
                throw new IOException("Redirector architecture differs from the Java backend: " + name);
            }
            if (!windows && !Files.isExecutable(nativeBinary)) throw new IOException("Redirector is not executable: " + name);
        }
        Path sourceAgent = readableFile(agentJar);
        try (JarFile jar = new JarFile(sourceAgent.toFile())) {
            if (jar.getManifest() == null || !"com.qb20nh.loadonce.agent.LoadOnceAgent".equals(
                    jar.getManifest().getMainAttributes().getValue("Premain-Class"))) {
                throw new IOException("Agent jar must declare the LoadOnce Premain-Class");
            }
        }
        Path sourceSupervisor = readableFile(supervisorJar);
        try (JarFile jar = new JarFile(sourceSupervisor.toFile())) {
            var entry = jar.getJarEntry("com/qb20nh/loadonce/supervisor/SupervisorMain.class");
            if (entry == null || entry.isDirectory()) throw new IOException("Supervisor jar must contain the LoadOnce supervisor entrypoint");
            try (var input = jar.getInputStream(entry)) {
                byte[] magic = input.readNBytes(4);
                if (magic.length != 4 || ByteBuffer.wrap(magic).getInt() != 0xcafebabe) {
                    throw new IOException("Supervisor jar contains an invalid supervisor class");
                }
            }
        }
        Path requestedOutput = outputDir.toAbsolutePath().normalize();
        Path parent = requestedOutput.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Output parent must be an existing directory");
        parent = parent.toRealPath();
        Path output = parent.resolve(requestedOutput.getFileName());
        if (output.startsWith(home) || output.startsWith(resolvedHome(backend, home))
                || output.startsWith(bootstrapHome) || output.startsWith(resolvedHome(bootstrapBackend, bootstrapHome))) {
            throw new IOException("Private runtime output must be outside both selected JDKs");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Private runtime output must be a new directory");
        String backendValue = configValue(backend);
        String bootstrapValue = configValue(bootstrapBackend);
        String agentValue = configValue(output.resolve("loadonce-agent.jar"));
        String supervisorValue = configValue(output.resolve("loadonce-supervisor.jar"));
        String policyValue = configValue(output.resolve(POLICY));
        if (agentValue.indexOf('=') >= 0) throw new IOException("Private runtime path cannot contain the Java agent option separator");
        Path cache = options.cache() == null ? output.resolve("cache") : options.cache().toAbsolutePath().normalize();
        StringBuilder policy = new StringBuilder("aot=").append(options.aot()).append("\ncache=")
                .append(configValue(cache)).append("\ninput.count=").append(options.inputs().size()).append('\n');
        for (int index = 0; index < options.inputs().size(); index++) {
            policy.append("input.").append(index).append('=').append(configValue(options.inputs().get(index).toAbsolutePath().normalize())).append('\n');
        }
        policy.append("verbose=").append(options.verbose()).append('\n');
        byte[] policyBytes = policy.toString().getBytes(StandardCharsets.UTF_8);
        if (policyBytes.length > MAX_POLICY_BYTES) throw new IOException("Runtime policy is too large");
        if (windows) {
            String nativeEncoding = System.getProperty("native.encoding");
            Charset nativeCharset;
            try {
                if (nativeEncoding == null) throw new IllegalArgumentException();
                nativeCharset = Charset.forName(nativeEncoding);
            } catch (IllegalArgumentException unavailable) {
                throw new IOException("Windows native.encoding is unavailable; cannot validate the supervisor launch paths");
            }
            for (String value : List.of(bootstrapValue, agentValue, supervisorValue, policyValue)) {
                if (!nativeCharset.newEncoder().canEncode(value)
                        || !nativeCharset.decode(nativeCharset.encode(value)).toString().equals(value)) {
                    throw new IOException("Supervisor launch path cannot be preserved by Windows native.encoding ("
                            + nativeEncoding + "); choose a path using supported characters");
                }
            }
        }
        boolean posix = Files.getFileStore(parent).supportsFileAttributeView("posix");
        FileAttribute<?>[] permissions = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY)} : new FileAttribute<?>[0];
        Path stage = Files.createTempDirectory(parent, ".loadonce-runtime-", permissions);
        boolean outputCreated = false;
        try {
            Path stageBin = Files.createDirectory(stage.resolve("bin"), permissions);
            for (String name : names) {
                Path binary = Files.copy(binaries.resolve(name), stageBin.resolve(name));
                if (posix) Files.setPosixFilePermissions(binary, PRIVATE_DIRECTORY);
            }
            Path installedAgent = Files.copy(sourceAgent, stage.resolve("loadonce-agent.jar"));
            if (posix) Files.setPosixFilePermissions(installedAgent, PRIVATE_FILE);
            Path installedSupervisor = Files.copy(sourceSupervisor, stage.resolve("loadonce-supervisor.jar"));
            if (posix) Files.setPosixFilePermissions(installedSupervisor, PRIVATE_FILE);
            Path installedPolicy = stage.resolve(POLICY);
            Files.write(installedPolicy, policyBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (posix) Files.setPosixFilePermissions(installedPolicy, PRIVATE_FILE);
            Path config = stageBin.resolve(CONFIG);
            Files.writeString(config, "java=" + backendValue + "\nbootstrap-java=" + bootstrapValue
                    + "\nsupervisor=" + supervisorValue + "\nagent=" + agentValue + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (posix) Files.setPosixFilePermissions(config, PRIVATE_FILE);
            Path release = home.resolve("release");
            if (Files.isRegularFile(release)) {
                Path copiedRelease = Files.copy(release, stage.resolve("release"));
                if (posix) Files.setPosixFilePermissions(copiedRelease, PRIVATE_FILE);
            }
            // Reserve the new output exclusively. Atomic directory rename can overwrite a raced empty target on Unix.
            Files.createDirectory(output, permissions);
            outputCreated = true;
            Files.move(installedAgent, output.resolve("loadonce-agent.jar"));
            Files.move(installedSupervisor, output.resolve("loadonce-supervisor.jar"));
            Files.move(installedPolicy, output.resolve(POLICY));
            if (Files.exists(stage.resolve("release"))) Files.move(stage.resolve("release"), output.resolve("release"));
            // Publish the executables together only after the supervisor, policy, and agent paths are ready.
            Files.move(stageBin, output.resolve("bin"), StandardCopyOption.ATOMIC_MOVE);
            Files.delete(stage);
            return output;
        } catch (IOException | RuntimeException failure) {
            if (outputCreated) cleanup(output, failure);
            cleanup(stage, failure);
            throw failure;
        }
    }

    private static Path backend(Path home, boolean windows) throws IOException {
        if (!Files.isDirectory(home)) throw new IOException("Java home must be a directory");
        Path backend = readableFile(home.resolve("bin").resolve(windows ? "java.exe" : "java"));
        if (!windows && !Files.isExecutable(backend)) throw new IOException("Java backend is not executable");
        rejectRedirector(home.resolve("bin"));
        rejectRedirector(backend.getParent());
        if (windows) {
            // The native GUI redirector selects the sibling of the canonical configured console executable.
            Path gui = readableFile(backend.getParent().resolve("javaw.exe"));
            rejectRedirector(gui.getParent());
            if (!nativeArchitecture(gui, true).equals(nativeArchitecture(backend, true))) {
                throw new IOException("Java console and GUI backends have different architectures");
            }
        }
        return backend;
    }

    private static void rejectRedirector(Path directory) throws IOException {
        if (Files.exists(directory.resolve(CONFIG), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Java home is already a LoadOnce redirect runtime");
        }
    }

    private static Path resolvedHome(Path backend, Path home) {
        Path bin = backend.getParent();
        return bin.getFileName() != null && "bin".equalsIgnoreCase(bin.getFileName().toString()) ? bin.getParent() : home;
    }

    private static Path readableFile(Path file) throws IOException {
        Path real = file.toRealPath();
        if (!Files.isRegularFile(real) || !Files.isReadable(real)) throw new IOException("Expected a readable regular file: " + file);
        return real;
    }

    private static String configValue(Path path) throws IOException {
        String value = path.toAbsolutePath().toString();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character == 127) throw new IOException("Runtime configuration paths cannot contain control characters");
        }
        // UTF-8 encoders replace malformed UTF-16. Reject it before writing a different filesystem path.
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IOException("Runtime paths must encode as UTF-8");
        return value;
    }

    private static String nativeArchitecture(Path file, boolean windows) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            ByteBuffer header = read(channel, 64);
            if (windows) {
                if (header.get(0) != 'M' || header.get(1) != 'Z') throw new IOException("Expected a Windows executable: " + file);
                long peOffset = Integer.toUnsignedLong(header.order(ByteOrder.LITTLE_ENDIAN).getInt(60));
                if (peOffset > channel.size() - 6) throw new IOException("Invalid Windows executable header: " + file);
                channel.position(peOffset);
                ByteBuffer pe = read(channel, 6).order(ByteOrder.LITTLE_ENDIAN);
                if (pe.getInt(0) != 0x00004550) throw new IOException("Invalid Windows executable signature: " + file);
                return "pe:" + Short.toUnsignedInt(pe.getShort(4));
            }
            if (header.get(0) != 0x7f || header.get(1) != 'E' || header.get(2) != 'L' || header.get(3) != 'F'
                    || (header.get(4) != 1 && header.get(4) != 2) || (header.get(5) != 1 && header.get(5) != 2)) {
                throw new IOException("Expected a Linux ELF executable: " + file);
            }
            header.order(header.get(5) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            return "elf:" + header.get(4) + ":" + header.get(5) + ":" + Short.toUnsignedInt(header.getShort(18));
        }
    }

    private static ByteBuffer read(SeekableByteChannel channel, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("Truncated native executable header");
        }
        return buffer;
    }

    private static void cleanup(Path directory, Throwable cause) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            cause.addSuppressed(failure);
        }
    }

    private static String usage() {
        return "Usage: RuntimeRedirectInstaller --java-home PATH --redirectors-dir PATH --output-dir PATH --agent-jar PATH --supervisor-jar PATH"
                + " [--bootstrap-java-home PATH] [--aot off|auto|train] [--cache PATH] [--input PATH] [--verbose]";
    }
}
