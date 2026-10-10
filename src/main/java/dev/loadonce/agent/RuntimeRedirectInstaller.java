package dev.loadonce.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Installs a private Java executable selector without changing the selected JDK. */
public final class RuntimeRedirectInstaller {
    private static final String CONFIG = "loadonce-runtime.conf";
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");

    private RuntimeRedirectInstaller() {}

    public static void main(String[] args) {
        try {
            Map<String, Path> options = new HashMap<>();
            Set<String> accepted = Set.of("--java-home", "--redirectors-dir", "--output-dir", "--agent-jar");
            if (args.length != 8) throw new IllegalArgumentException(usage());
            for (int index = 0; index < args.length; index += 2) {
                if (!accepted.contains(args[index]) || options.putIfAbsent(args[index], Path.of(args[index + 1])) != null) {
                    throw new IllegalArgumentException(usage());
                }
            }
            Path output = install(options.get("--java-home"), options.get("--redirectors-dir"),
                    options.get("--output-dir"), options.get("--agent-jar"));
            System.out.println("Installed LoadOnce runtime: " + output);
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("LoadOnce runtime setup failed: " + failure.getMessage());
            System.exit(1);
        }
    }

    public static Path install(Path javaHome, Path redirectorsDir, Path outputDir, Path agentJar) throws IOException {
        String os = System.getProperty("os.name", "");
        boolean windows = os.startsWith("Windows");
        if (!windows && !os.equals("Linux")) throw new IOException("Only Linux and Windows redirectors are supported");
        Path home = javaHome.toRealPath();
        if (!Files.isDirectory(home)) throw new IOException("Java home must be a directory");
        Path backend = readableFile(home.resolve("bin").resolve(windows ? "java.exe" : "java"));
        if (!windows && !Files.isExecutable(backend)) throw new IOException("Java backend is not executable");
        if (Files.exists(home.resolve("bin").resolve(CONFIG), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(backend.getParent().resolve(CONFIG), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Java home is already a LoadOnce redirect runtime");
        }
        String architecture = nativeArchitecture(backend, windows);
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
        if (windows && !nativeArchitecture(readableFile(home.resolve("bin/javaw.exe")), true).equals(architecture)) {
            throw new IOException("Java console and GUI backends have different architectures");
        }
        Path sourceAgent = readableFile(agentJar);
        try (JarFile jar = new JarFile(sourceAgent.toFile())) {
            if (jar.getManifest() == null || !"dev.loadonce.agent.LoadOnceAgent".equals(
                    jar.getManifest().getMainAttributes().getValue("Premain-Class"))) {
                throw new IOException("Agent jar must declare the LoadOnce Premain-Class");
            }
        }
        Path requestedOutput = outputDir.toAbsolutePath().normalize();
        Path parent = requestedOutput.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Output parent must be an existing directory");
        parent = parent.toRealPath();
        Path output = parent.resolve(requestedOutput.getFileName());
        Path backendDirectory = backend.getParent();
        Path resolvedHome = backendDirectory.getFileName() != null && "bin".equalsIgnoreCase(backendDirectory.getFileName().toString())
                ? backendDirectory.getParent() : home;
        if (output.startsWith(home) || output.startsWith(resolvedHome)) {
            throw new IOException("Private runtime output must be outside the selected JDK");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Private runtime output must be a new directory");
        String backendValue = configValue(backend);
        String agentValue = configValue(output.resolve("loadonce-agent.jar"));
        if (agentValue.indexOf('=') >= 0) throw new IOException("Private runtime path cannot contain the Java agent option separator");
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
            Path config = stageBin.resolve(CONFIG);
            Files.writeString(config, "java=" + backendValue + "\nagent=" + agentValue + "\n", StandardCharsets.UTF_8,
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
            if (Files.exists(stage.resolve("release"))) Files.move(stage.resolve("release"), output.resolve("release"));
            // Publish the executables together only after their fixed agent/config paths are ready.
            Files.move(stageBin, output.resolve("bin"), StandardCopyOption.ATOMIC_MOVE);
            Files.delete(stage);
            return output;
        } catch (IOException | RuntimeException failure) {
            if (outputCreated) cleanup(output, failure);
            cleanup(stage, failure);
            throw failure;
        }
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
        return "Usage: RuntimeRedirectInstaller --java-home PATH --redirectors-dir PATH --output-dir PATH --agent-jar PATH";
    }
}
