package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Local POSIX files used by the optional launchers. Failures permit cold startup. */
public final class LauncherFiles {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final FileAttribute<Set<PosixFilePermission>> DIRECTORY_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(DIRECTORY_MODE);
    private static final FileAttribute<Set<PosixFilePermission>> FILE_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(FILE_MODE);
    private static final int MANIFEST_LIMIT = 4096;
    private static final int JAR_MANIFEST_LIMIT = 1024 * 1024;

    private LauncherFiles() {}

    public static String ownerId() throws IOException {
        try {
            Class<?> system = Class.forName("com.sun.security.auth.module.UnixSystem");
            Object instance = system.getConstructor().newInstance();
            Object uid = system.getMethod("getUid").invoke(instance);
            if (!(uid instanceof Long value) || value < 0) throw new IOException("POSIX ownership is unavailable");
            return Long.toString(value);
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            throw new IOException("POSIX ownership is unavailable");
        }
    }

    public static Path privateDirectory(Path directory) throws IOException {
        noLinkedAncestors(directory);
        Path absolute = directory.toAbsolutePath().normalize();
        Path component = absolute.getRoot();
        for (Path name : absolute) {
            component = component.resolve(name);
            try {
                BasicFileAttributes attributes = Files.readAttributes(component, BasicFileAttributes.class, NOFOLLOW);
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                    throw new IOException("private paths require directories without symbolic links");
                }
            } catch (NoSuchFileException missing) {
                try {
                    Files.createDirectory(component, DIRECTORY_ATTRIBUTE);
                } catch (FileAlreadyExistsException raced) {
                    // Recheck a concurrently created component instead of following it.
                } catch (UnsupportedOperationException unsupported) {
                    throw new IOException("POSIX privacy is unavailable");
                }
                BasicFileAttributes attributes = Files.readAttributes(component, BasicFileAttributes.class, NOFOLLOW);
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                    throw new IOException("private paths require directories without symbolic links");
                }
            }
        }
        checkPrivate(absolute, true);
        return absolute;
    }

    public static Path tempDirectory(Path parent, String prefix) throws IOException {
        Path directory;
        try {
            directory = parent == null
                    ? Files.createTempDirectory(prefix, DIRECTORY_ATTRIBUTE)
                    : Files.createTempDirectory(privateDirectory(parent), prefix, DIRECTORY_ATTRIBUTE);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("POSIX privacy is unavailable");
        }
        try {
            checkPrivate(directory, true);
            return directory.toAbsolutePath().normalize();
        } catch (IOException invalid) {
            Files.deleteIfExists(directory);
            throw invalid;
        }
    }

    public static Path tempFile(Path directory, String prefix, String suffix) throws IOException {
        Path file;
        try {
            file = Files.createTempFile(privateDirectory(directory), prefix, suffix, FILE_ATTRIBUTE);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("POSIX privacy is unavailable");
        }
        checkPrivate(file, false);
        return file;
    }

    public static void checkPrivate(Path path, boolean directory) throws IOException {
        noLinkedAncestors(path);
        Node node = ownedNode(path, directory);
        for (PosixFilePermission permission : node.permissions()) {
            if (!permission.name().startsWith("OWNER_")) {
                throw new IOException("cache paths must be private and owned by this user");
            }
        }
    }

    /** Validate all generated objects before changing any permissions. */
    public static void sealTree(Path root) throws IOException {
        checkPrivate(root, true);
        List<Node> nodes = new ArrayList<>();
        collectOwned(root, nodes);
        for (Node node : nodes) {
            Node current = ownedNode(node.path(), node.directory());
            if (!sameIdentity(node.attributes(), current.attributes())) {
                throw new IOException("generated files changed during validation");
            }
        }
        for (Node node : nodes) {
            Set<PosixFilePermission> mode = new HashSet<>(node.directory() ? DIRECTORY_MODE : FILE_MODE);
            if (!node.directory() && node.permissions().contains(PosixFilePermission.OWNER_EXECUTE)) {
                mode.add(PosixFilePermission.OWNER_EXECUTE);
            }
            try {
                Files.setPosixFilePermissions(node.path(), mode);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("POSIX privacy is unavailable");
            }
            checkPrivate(node.path(), node.directory());
        }
    }

    public static CacheLock lock(Path root, boolean exclusive) throws IOException {
        Path file = privateDirectory(root).resolve(".lock");
        if (Files.exists(file, NOFOLLOW)) checkPrivate(file, false);
        FileChannel channel;
        try {
            channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, NOFOLLOW), FILE_ATTRIBUTE);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("POSIX cache locking is unavailable");
        }
        try {
            checkPrivate(file, false);
            FileLock held;
            try {
                held = channel.tryLock(0, Long.MAX_VALUE, !exclusive);
            } catch (OverlappingFileLockException busy) {
                throw new IOException("cache is in use");
            }
            if (held == null) throw new IOException("cache is in use");
            return new CacheLock(channel, held);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    public static final class CacheLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private CacheLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override public void close() throws IOException {
            try {
                if (lock.isValid()) lock.release();
            } finally {
                channel.close();
            }
        }
    }

    public static void digestPath(MessageDigest digest, Path path) throws IOException {
        digestPath(digest, path.toAbsolutePath(), new HashSet<>());
    }

    private static void digestPath(MessageDigest digest, Path path, Set<Path> visiting) throws IOException {
        update(digest, path.toString() + "\0");
        BasicFileAttributes lexical;
        try {
            lexical = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        } catch (NoSuchFileException absent) {
            update(digest, "missing\0");
            return;
        }
        Path real;
        try {
            real = path.toRealPath();
        } catch (IOException invalid) {
            throw new IOException("input contains an unresolved or cyclic symbolic link");
        }
        if (visiting.contains(real)) throw new IOException("input contains a symbolic link cycle");
        if (lexical.isSymbolicLink()) update(digest, "link\0" + Files.readSymbolicLink(path) + "\0");
        BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class, NOFOLLOW);
        if (attributes.isRegularFile()) {
            update(digest, "file\0" + attributes.size() + "\0");
            hashBytes(digest, real);
            if (!unchanged(attributes, Files.readAttributes(real, BasicFileAttributes.class, NOFOLLOW))) {
                throw new IOException("launch input changed while fingerprinting");
            }
        } else if (attributes.isDirectory()) {
            update(digest, "directory\0");
            visiting.add(real);
            try {
                for (Path child : children(path)) digestPath(digest, child, visiting);
            } finally {
                visiting.remove(real);
            }
            if (!unchanged(attributes, Files.readAttributes(real, BasicFileAttributes.class, NOFOLLOW))) {
                throw new IOException("launch input changed while fingerprinting");
            }
        } else {
            throw new IOException("launch inputs must be regular files or directories");
        }
        update(digest, "\0end\0");
    }

    public static String fileHash(Path file) throws IOException {
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW);
        if (!before.isRegularFile()) throw new IOException("artifact must be a regular file");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable");
        }
        hashBytes(digest, file);
        if (!unchanged(before, Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW))) {
            throw new IOException("artifact changed while hashing");
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    public static List<Path> jarDependencies(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) return List.of();
        byte[] bytes;
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            ZipEntry manifest = null;
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    if (manifest != null) throw new IOException("duplicate JAR manifests require ordinary startup");
                    manifest = entry;
                }
            }
            if (manifest == null) return List.of();
            if (manifest.getSize() > JAR_MANIFEST_LIMIT) throw new IOException("JAR manifest is too large");
            try (InputStream input = archive.getInputStream(manifest)) {
                bytes = boundedBytes(input, JAR_MANIFEST_LIMIT, "JAR manifest is too large");
            }
        } catch (ZipException notJar) {
            return List.of();
        }
        String text = utf8(bytes).replace("\r\n", "\n").replace('\r', '\n');
        Map<String, String> attributes = new LinkedHashMap<>();
        String name = null;
        StringBuilder value = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith(" ")) {
                if (name == null) throw new IOException("invalid JAR manifest continuation");
                value.append(line.substring(1));
                continue;
            }
            if (name != null) {
                if (attributes.putIfAbsent(name, value.toString()) != null) {
                    throw new IOException("duplicate JAR manifest attributes require ordinary startup");
                }
                name = null;
            }
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
                throw new IOException("invalid JAR manifest attribute");
            }
            name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            value = new StringBuilder(line.substring(colon + 1).stripLeading());
        }
        if (name != null && attributes.putIfAbsent(name, value.toString()) != null) {
            throw new IOException("duplicate JAR manifest attributes require ordinary startup");
        }
        String classpath = attributes.get("class-path");
        if (classpath == null || classpath.isBlank()) return List.of();
        URI base = jar.toAbsolutePath().normalize().toUri();
        List<Path> dependencies = new ArrayList<>();
        for (String entry : classpath.trim().split("\\s+")) {
            try {
                URI resolved = base.resolve(new URI(entry));
                String authority = resolved.getRawAuthority();
                if (!"file".equalsIgnoreCase(resolved.getScheme())
                        || (authority != null && !authority.isEmpty() && !authority.equalsIgnoreCase("localhost"))
                        || resolved.getRawQuery() != null || resolved.getRawFragment() != null) {
                    throw new IOException("remote JAR manifest classpaths require ordinary startup");
                }
                dependencies.add(Path.of(resolved.getPath()).toAbsolutePath().normalize());
            } catch (URISyntaxException | IllegalArgumentException invalid) {
                throw new IOException("invalid JAR manifest classpath URL");
            }
        }
        return List.copyOf(dependencies);
    }

    public static Map<String, String> readManifest(Path file, Set<String> exactFields) throws IOException {
        checkPrivate(file, false);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, NOFOLLOW)) {
            bytes = boundedBytes(input, MANIFEST_LIMIT, "cache manifest is too large");
        }
        String text = utf8(bytes);
        Map<String, String> fields = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index == lines.length - 1 && line.isEmpty()) break;
            int equals = line.indexOf('=');
            if (equals < 1) throw new IOException("invalid cache manifest");
            String key = line.substring(0, equals);
            String value = line.substring(equals + 1);
            validateField(key, value);
            if (fields.putIfAbsent(key, value) != null) throw new IOException("duplicate cache manifest field");
        }
        if (!fields.keySet().equals(exactFields)) throw new IOException("invalid cache manifest schema");
        return Map.copyOf(fields);
    }

    public static void writeManifest(Path file, Map<String, String> fields) throws IOException {
        if (fields == null) throw new IOException("invalid cache manifest fields");
        for (var field : fields.entrySet()) validateField(field.getKey(), field.getValue());
        privateDirectory(file.toAbsolutePath().normalize().getParent());
        StringBuilder text = new StringBuilder();
        for (var field : new TreeMap<>(fields).entrySet()) {
            validateField(field.getKey(), field.getValue());
            text.append(field.getKey()).append('=').append(field.getValue()).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MANIFEST_LIMIT) throw new IOException("cache manifest is too large");
        if (Files.exists(file, NOFOLLOW)) checkPrivate(file, false);
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, NOFOLLOW), FILE_ATTRIBUTE)) {
            ByteBuffer data = ByteBuffer.wrap(bytes);
            while (data.hasRemaining()) channel.write(data);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("POSIX privacy is unavailable");
        }
        checkPrivate(file, false);
    }

    public static void sync(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        checkPrivate(path, attributes.isDirectory());
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW)) {
            channel.force(true);
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW)) return;
        noLinkedAncestors(root.getParent());
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW);
        if (attributes.isDirectory()) {
            for (Path child : children(root)) deleteTree(child);
        }
        Files.delete(root);
    }

    private static void collectOwned(Path path, List<Node> nodes) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        Node node = ownedNode(path, attributes.isDirectory());
        nodes.add(node);
        if (node.directory()) {
            for (Path child : children(path)) collectOwned(child, nodes);
        }
    }

    private static Node ownedNode(Path path, boolean directory) throws IOException {
        try {
            Map<String, Object> unix = Files.readAttributes(path, "unix:uid,nlink,mode", NOFOLLOW);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
            if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) {
                throw new IOException("cache paths require regular files and directories");
            }
            if (!ownerId().equals(unix.get("uid").toString())
                    || (!directory && ((Number) unix.get("nlink")).longValue() != 1)
                    || (((Number) unix.get("mode")).intValue() & 07000) != 0) {
                throw new IOException("cache objects must be owned and have no hard links or special modes");
            }
            return new Node(path, directory, attributes, Files.getPosixFilePermissions(path, NOFOLLOW));
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            throw new IOException("POSIX privacy is unavailable");
        }
    }

    private record Node(Path path, boolean directory, BasicFileAttributes attributes,
                        Set<PosixFilePermission> permissions) {}

    private static void noLinkedAncestors(Path path) throws IOException {
        if (path == null) return;
        for (Path component = path.toAbsolutePath(); component != null; component = component.getParent()) {
            if (Files.isSymbolicLink(component)) throw new IOException("private paths cannot contain symbolic links");
        }
    }

    private static List<Path> children(Path directory) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) result.add(child);
        }
        result.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return result;
    }

    private static boolean sameIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return first.isDirectory() == second.isDirectory() && first.isRegularFile() == second.isRegularFile()
                && java.util.Objects.equals(first.fileKey(), second.fileKey());
    }

    private static boolean unchanged(BasicFileAttributes first, BasicFileAttributes second) {
        return sameIdentity(first, second) && first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime());
    }

    private static void hashBytes(MessageDigest digest, Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, NOFOLLOW)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
    }

    private static byte[] boundedBytes(InputStream input, int limit, String reason) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException(reason);
        return bytes;
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("metadata must be valid UTF-8");
        }
    }

    private static void validateField(String key, String value) throws IOException {
        if (key == null || value == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || !value.equals(value.strip()) || value.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new IOException("invalid cache manifest field");
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
