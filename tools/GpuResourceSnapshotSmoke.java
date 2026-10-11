import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** Java 25, Linux x86-64; resource-only gfxstream snapshot experiment, not application restoration. */
public final class GpuResourceSnapshotSmoke {
    static final String SOURCE = "07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe";
    static final int CTX = 7, BLOB = 41, IMAGE = 42, WIDTH = 64, HEIGHT = 64;
    static final int IMAGE_BYTES = WIDTH * HEIGHT * 4, BLOB_BYTES = 4096, LOG_LIMIT = 3 * 1024 * 1024;
    static final Set<PosixFilePermission> PRIVATE_DIR = PosixFilePermissions.fromString("rwx------");
    static final Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");
    static final List<String> SNAPSHOT_FILES = List.of("gfxstream_renderer.bin", "gfxstream_frontend.txtproto", "gfxstream_asg.bin");
    static final List<Process> CHILDREN = new ArrayList<>();
    static boolean closed;
    static final AtomicLong COMPLETED_FENCE = new AtomicLong();
    static final AtomicBoolean PROVIDER_ERROR = new AtomicBoolean();
    static final AtomicReference<String> DEVICE = new AtomicReference<>();
    static final AtomicBoolean SNAPSHOT_FEATURE = new AtomicBoolean();
    static final AtomicInteger DEBUG_BYTES = new AtomicInteger();

    public static void main(String[] args) throws Throwable {
        require(Runtime.version().feature() >= 25 && System.getProperty("os.name").equals("Linux")
                && System.getProperty("os.arch").equals("amd64")
                && ValueLayout.ADDRESS.byteSize() == 8 && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN,
                "Requires Java 25 and the pinned Linux x86-64 ABI");
        if (args.length == 6 && args[0].equals("--child")) {
            child(args);
            return;
        }
        require(args.length == 2, "Usage: java --enable-native-access=ALL-UNNAMED GpuResourceSnapshotSmoke.java ABSOLUTE_BACKEND.so NEW_PRIVATE_OUTPUT_DIRECTORY");
        Path library = library(Path.of(args[0]));
        Path root = absolute(Path.of(args[1]));
        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS), "Output directory must be new: " + root);
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PRIVATE_DIR));
        privateDirectory(root);
        Properties controller = new Properties();
        controller.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        controller.setProperty("start", start(ProcessHandle.current()).toString());
        controller.setProperty("token", UUID.randomUUID().toString());
        controller.setProperty("library.sha256", hash(library));
        save(root.resolve("controller.properties"), controller);
        Runtime.getRuntime().addShutdownHook(new Thread(GpuResourceSnapshotSmoke::cleanup, "owned-gpu-resource-cleanup"));
        Properties result = new Properties();
        result.setProperty("acceptance.scope", "gpu-resource-only; no OpenGL context, CPU process, Minecraft, modpack, display, audio or network restoration claim");
        result.setProperty("provider.source", SOURCE);
        result.setProperty("measurement.scope", "resourceFixture only; not Minecraft cold/warm timing");
        result.setProperty("library", library.toString());
        result.setProperty("library.sha256", controller.getProperty("library.sha256"));
        result.setProperty("status", "FAIL");
        try {
            run(library, root, "capture", controller.getProperty("token"));
            Map<String, String> original = snapshots(root.resolve("snapshot"));
            Properties capture = load(root.resolve("capture.properties"));
            Set<String> pids = new HashSet<>();
            pids.add(capture.getProperty("pid"));
            for (int i = 1; i <= 3; i++) {
                String phase = "restore-" + i;
                require(original.equals(snapshots(root.resolve("snapshot"))), "Original snapshot changed before " + phase);
                run(library, root, phase, controller.getProperty("token"));
                Properties restored = load(root.resolve(phase + ".properties"));
                require(pids.add(restored.getProperty("pid")), "Restores must use distinct fresh JVM processes");
                for (String key : List.of("image.before.sha256", "blob.before.sha256", "provider.device")) {
                    require(Objects.equals(capture.getProperty(key), restored.getProperty(key)), phase + " mismatch: " + key);
                }
                require(original.equals(snapshots(root.resolve("snapshot"))), "Restore modified original snapshot: " + phase);
            }
            result.setProperty("status", "PASS_RESOURCE_ONLY");
            result.setProperty("provider.device", capture.getProperty("provider.device"));
            result.setProperty("fresh.restores", "3");
            original.forEach((name, sha) -> result.setProperty("snapshot." + name + ".sha256", sha));
            System.out.println("PASS_RESOURCE_ONLY: " + capture.getProperty("provider.device"));
        } finally {
            cleanup();
            save(root.resolve("result.properties"), result);
            System.out.println("Evidence: " + root.resolve("result.properties"));
        }
    }

    static void run(Path library, Path root, String phase, String token) throws Exception {
        Path work = root.resolve(phase);
        Files.createDirectory(work, PosixFilePermissions.asFileAttribute(PRIVATE_DIR));
        for (String dir : List.of("home", "tmp", "runtime")) {
            Files.createDirectory(work.resolve(dir), PosixFilePermissions.asFileAttribute(PRIVATE_DIR));
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(List.of("-Xmx128m", "--enable-native-access=ALL-UNNAMED"));
        Optional<String> source = Arrays.stream(ProcessHandle.current().info().arguments().orElse(new String[0]))
                .filter(s -> s.endsWith("GpuResourceSnapshotSmoke.java")).findFirst();
        if (source.isPresent()) command.add(absolute(Path.of(source.get())).toString());
        else command.addAll(List.of("-cp", absoluteClassPath(), GpuResourceSnapshotSmoke.class.getName()));
        command.addAll(List.of("--child", library.toString(), root.toString(), phase, token, SOURCE));
        ProcessBuilder pb = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", "/usr/bin:/bin");
        env.put("LANG", "C");
        env.put("HOME", work.resolve("home").toString());
        env.put("TMPDIR", work.resolve("tmp").toString());
        env.put("XDG_RUNTIME_DIR", work.resolve("runtime").toString());
        env.put("XDG_CACHE_HOME", work.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", work.resolve("home").toString());
        env.put("VK_DRIVER_FILES", "/usr/share/vulkan/icd.d/nvidia_icd.json");
        env.put("ANDROID_GFXSTREAM_CAPTURE_VK_SNAPSHOT", "1");
        env.put("LD_LIBRARY_PATH", library.getParent().toString());
        Path log = root.resolve(phase + ".log");
        OutputStream output = Files.newOutputStream(log, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(log, PRIVATE_FILE);
        long begun = System.nanoTime();
        Process process;
        try {
            synchronized (CHILDREN) {
                require(!closed, "GPU resource coordinator is closed");
                process = pb.start();
                CHILDREN.add(process);
            }
        } catch (IOException | RuntimeException failure) {
            output.close();
            throw failure;
        }
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream(); OutputStream out = output) {
                byte[] buffer = new byte[4096];
                int kept = 0, count;
                while ((count = in.read(buffer)) != -1) {
                    int n = Math.min(count, LOG_LIMIT - kept);
                    if (n > 0) { out.write(buffer, 0, n); kept += n; }
                }
            } catch (IOException ignored) { }
        }, "bounded-" + phase + "-log");
        drain.setDaemon(true);
        drain.start();
        try {
            require(process.waitFor(120, TimeUnit.SECONDS), phase + " exceeded 120 seconds; retained log: " + log);
            drain.join(2000);
            require(process.exitValue() == 0, phase + " exited " + process.exitValue() + "; retained log: " + log);
            Properties evidence = load(root.resolve(phase + ".properties"));
            require(evidence.getProperty("status", "").equals("PASS_RESOURCE_PHASE"), "Missing phase acceptance: " + phase);
            require(evidence.getProperty("pid", "").equals(Long.toString(process.pid())), "Phase PID mismatch");
            evidence.setProperty("parent.elapsed.nanos", Long.toString(System.nanoTime() - begun));
            save(root.resolve(phase + ".properties"), evidence);
            System.out.println("resourceFixture " + phase + ": " + evidence.getProperty("elapsed.nanos") + " ns; " + root.resolve(phase + ".properties"));
        } finally {
            stop(process);
            synchronized (CHILDREN) { CHILDREN.remove(process); }
        }
    }

    static void child(String[] args) throws Throwable {
        Path library = library(Path.of(args[1])), root = absolute(Path.of(args[2]));
        privateDirectory(root);
        require(args[3].matches("capture|restore-[123]") && args[5].equals(SOURCE), "Invalid child phase or provider ABI pin");
        Properties controller = load(root.resolve("controller.properties"));
        ProcessHandle parent = ProcessHandle.current().parent().orElseThrow();
        require(controller.getProperty("token", "").equals(args[4]) && parent.isAlive()
                && controller.getProperty("pid", "").equals(Long.toString(parent.pid()))
                && controller.getProperty("start", "").equals(start(parent).toString()), "Child is not owned by the live coordinator");
        require(controller.getProperty("library.sha256", "").equals(hash(library)), "Provider library changed");
        boolean capture = args[3].equals("capture");
        Path snapshot = root.resolve("snapshot");
        if (capture) Files.createDirectory(snapshot, PosixFilePermissions.asFileAttribute(PRIVATE_DIR));
        else snapshots(snapshot);
        Properties evidence = new Properties();
        evidence.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        evidence.setProperty("start", start(ProcessHandle.current()).toString());
        evidence.setProperty("provider.source", SOURCE);
        evidence.setProperty("renderer.flags", "32 (USE_VK only; no GLES)");
        evidence.setProperty("resource.image", "42: RGBA8_UNORM(67), 64x64x1");
        evidence.setProperty("resource.blob", "41: HOST3D(2), MAPPABLE(1), blob_id=0, 4096 bytes; host-memory witness");
        evidence.setProperty("readback.before.upload", Boolean.toString(!capture));
        long begun = System.nanoTime();
        try (Arena arena = Arena.ofShared()) {
            Api api = new Api(library, arena);
            MemorySegment fenceCallback = Linker.nativeLinker().upcallStub(MethodHandles.lookup().findStatic(
                    GpuResourceSnapshotSmoke.class, "fenceCallback", MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), arena);
            MemorySegment debugCallback = Linker.nativeLinker().upcallStub(MethodHandles.lookup().findStatic(
                    GpuResourceSnapshotSmoke.class, "debugCallback", MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), arena);
            MemorySegment features = arena.allocateFrom("VulkanSnapshots:enabled");
            MemorySegment params = arena.allocate(6 * 16, 8);
            long[][] values = {{1, 0}, {2, 32}, {3, fenceCallback.address()}, {7, debugCallback.address()}, {11, features.address()}, {4, WIDTH}};
            for (int i = 0; i < values.length; i++) {
                params.set(ValueLayout.JAVA_LONG, i * 16L, values[i][0]);
                params.set(ValueLayout.JAVA_LONG, i * 16L + 8, values[i][1]);
            }
            long initStart = System.nanoTime();
            api.ok("stream_renderer_init", params, 6L);
            evidence.setProperty("resourceFixture.init.nanos", Long.toString(System.nanoTime() - initStart));
            try {
                checkProvider();
                evidence.setProperty("provider.device", DEVICE.get());
                evidence.setProperty("provider.device.evidence", "stream_renderer_debug_callback_ex; vk_common_operations.cpp selected physical-device diagnostic");
                MemorySegment pixels = arena.allocate(IMAGE_BYTES, 8), iov = iov(arena, pixels);
                if (capture) {
                    api.ok("stream_renderer_context_create", CTX, 19, arena.allocateFrom("loadonce-resource-7"), 3);
                    MemorySegment blobArgs = arena.allocate(24, 8);
                    blobArgs.set(ValueLayout.JAVA_INT, 0, 2); blobArgs.set(ValueLayout.JAVA_INT, 4, 1);
                    blobArgs.set(ValueLayout.JAVA_LONG, 8, 0); blobArgs.set(ValueLayout.JAVA_LONG, 16, BLOB_BYTES);
                    api.ok("stream_renderer_create_blob", CTX, BLOB, blobArgs, MemorySegment.NULL, 0, MemorySegment.NULL);
                    MemorySegment create = arena.allocate(44, 4);
                    int[] fields = {IMAGE, 2, 67, 2, WIDTH, HEIGHT, 1, 1, 0, 0, 0};
                    for (int i = 0; i < fields.length; i++) create.set(ValueLayout.JAVA_INT, i * 4L, fields[i]);
                    api.ok("stream_renderer_resource_create", create, iov, 1);
                    api.call("stream_renderer_ctx_attach_resource", CTX, IMAGE);
                    api.call("stream_renderer_ctx_attach_resource", CTX, BLOB);
                    blob(api, arena, true, 1);
                    pixels.copyFrom(MemorySegment.ofArray(pattern(IMAGE_BYTES, 1)));
                    transfer(api, arena, true, iov);
                } else {
                    long restoreStart = System.nanoTime();
                    api.ok("stream_renderer_restore", arena.allocateFrom(snapshot.toString()));
                    evidence.setProperty("resourceFixture.restore.nanos", Long.toString(System.nanoTime() - restoreStart));
                    checkProvider();
                    // Transient CPU backing is not serialized; reattach zeroed memory without uploading it.
                    api.ok("stream_renderer_resource_attach_iov", IMAGE, iov, 1);
                }
                pixels.fill((byte) 0);
                transfer(api, arena, false, iov);
                require(Arrays.equals(pixels.toArray(ValueLayout.JAVA_BYTE), pattern(IMAGE_BYTES, 1)), "GPU image contents lost before any restore upload");
                evidence.setProperty("image.before.sha256", hash(pixels.toArray(ValueLayout.JAVA_BYTE)));
                evidence.setProperty("blob.before.sha256", blob(api, arena, false, 1));
                fence(api, arena, 101);
                if (capture) {
                    long snapshotStart = System.nanoTime();
                    api.ok("stream_renderer_snapshot", arena.allocateFrom(snapshot.toString()));
                    evidence.setProperty("resourceFixture.snapshot.nanos", Long.toString(System.nanoTime() - snapshotStart));
                    snapshots(snapshot);
                    for (String name : SNAPSHOT_FILES) Files.setPosixFilePermissions(snapshot.resolve(name), PRIVATE_FILE);
                } else {
                    pixels.copyFrom(MemorySegment.ofArray(pattern(IMAGE_BYTES, 2)));
                    transfer(api, arena, true, iov);
                    pixels.fill((byte) 0);
                    transfer(api, arena, false, iov);
                    require(Arrays.equals(pixels.toArray(ValueLayout.JAVA_BYTE), pattern(IMAGE_BYTES, 2)), "Restored GPU image unusable after upload");
                    evidence.setProperty("image.after.sha256", hash(pixels.toArray(ValueLayout.JAVA_BYTE)));
                    blob(api, arena, true, 2);
                    evidence.setProperty("blob.after.sha256", blob(api, arena, false, 2));
                    fence(api, arena, 102);
                }
                checkProvider();
            } finally {
                api.call("stream_renderer_teardown");
            }
            require(!PROVIDER_ERROR.get(), "Provider reported an error; see phase log");
        }
        evidence.setProperty("elapsed.nanos", Long.toString(System.nanoTime() - begun));
        evidence.setProperty("status", "PASS_RESOURCE_PHASE");
        save(root.resolve(args[3] + ".properties"), evidence);
    }

    static MemorySegment iov(Arena arena, MemorySegment pixels) {
        MemorySegment iov = arena.allocate(16, 8);
        iov.set(ValueLayout.ADDRESS, 0, pixels); iov.set(ValueLayout.JAVA_LONG, 8, pixels.byteSize());
        return iov;
    }

    static void transfer(Api api, Arena arena, boolean write, MemorySegment iov) throws Throwable {
        MemorySegment box = arena.allocate(24, 4);
        box.set(ValueLayout.JAVA_INT, 12, WIDTH); box.set(ValueLayout.JAVA_INT, 16, HEIGHT); box.set(ValueLayout.JAVA_INT, 20, 1);
        api.ok(write ? "stream_renderer_transfer_write_iov" : "stream_renderer_transfer_read_iov",
                IMAGE, CTX, 0, WIDTH * 4, IMAGE_BYTES, box, 0L, iov, 1);
    }

    static String blob(Api api, Arena arena, boolean write, int seed) throws Throwable {
        MemorySegment pointer = arena.allocate(ValueLayout.ADDRESS), size = arena.allocate(ValueLayout.JAVA_LONG);
        api.ok("stream_renderer_resource_map", BLOB, pointer, size);
        try {
            require(size.get(ValueLayout.JAVA_LONG, 0) == BLOB_BYTES && pointer.get(ValueLayout.ADDRESS, 0).address() != 0, "Invalid blob mapping");
            MemorySegment bytes = pointer.get(ValueLayout.ADDRESS, 0).reinterpret(BLOB_BYTES);
            if (write) bytes.copyFrom(MemorySegment.ofArray(pattern(BLOB_BYTES, seed)));
            byte[] actual = bytes.toArray(ValueLayout.JAVA_BYTE);
            require(Arrays.equals(actual, pattern(BLOB_BYTES, seed)), "Restored blob contents differ");
            return hash(actual);
        } finally { api.ok("stream_renderer_resource_unmap", BLOB); }
    }

    static void fence(Api api, Arena arena, long id) throws Throwable {
        MemorySegment fence = arena.allocate(24, 8);
        fence.set(ValueLayout.JAVA_INT, 0, 1); fence.set(ValueLayout.JAVA_LONG, 8, id);
        api.ok("stream_renderer_create_fence", fence);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (COMPLETED_FENCE.get() < id && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        require(COMPLETED_FENCE.get() >= id, "Provider fence did not complete in 3 seconds");
    }

    static void fenceCallback(MemorySegment user, MemorySegment pointer) {
        try { COMPLETED_FENCE.accumulateAndGet(pointer.reinterpret(24).get(ValueLayout.JAVA_LONG, 8), Math::max); }
        catch (Throwable failure) { PROVIDER_ERROR.set(true); }
    }

    static void debugCallback(MemorySegment user, MemorySegment pointer) {
        try {
            MemorySegment info = pointer.reinterpret(40);
            String file = cString(info.get(ValueLayout.ADDRESS, 8));
            String message = cString(info.get(ValueLayout.ADDRESS, 32));
            int type = info.get(ValueLayout.JAVA_INT, 0);
            if (type == 1) PROVIDER_ERROR.set(true);
            if (type == 3 && (file.equals("vk_common_operations.cpp") || file.endsWith("/vk_common_operations.cpp"))
                    && message.startsWith("Selecting Vulkan device: ")) {
                if (message.matches("Selecting Vulkan device: NVIDIA GeForce RTX 3060 Ti, Version: [0-9]+\\.[0-9]+\\.[0-9]+")) DEVICE.set(message);
                else PROVIDER_ERROR.set(true);
            }
            if (type == 3 && (file.equals("virtio_gpu_gfxstream_renderer.cpp") || file.endsWith("/virtio_gpu_gfxstream_renderer.cpp"))
                    && message.strip().matches("VulkanSnapshots: enabled \\(.*\\)")) SNAPSHOT_FEATURE.set(true);
            if (DEBUG_BYTES.addAndGet(message.length()) <= 65536) System.out.println("provider[" + type + "] " + file + ":" + info.get(ValueLayout.JAVA_INT, 16) + " " + message);
        } catch (Throwable failure) { PROVIDER_ERROR.set(true); }
    }

    static String cString(MemorySegment pointer) {
        if (pointer.address() == 0) return "";
        MemorySegment bytes = pointer.reinterpret(4096);
        int length = 0;
        while (length < 4096 && bytes.get(ValueLayout.JAVA_BYTE, length) != 0) length++;
        require(length < 4096, "Unbounded provider diagnostic");
        return new String(bytes.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_8);
    }

    static void checkProvider() {
        require(!PROVIDER_ERROR.get(), "Provider reported an error; see phase log");
        require(DEVICE.get() != null, "Provider did not identify the selected NVIDIA RTX 3060 Ti through its own diagnostic ABI");
        require(SNAPSHOT_FEATURE.get(), "Provider did not confirm VulkanSnapshots enabled");
    }

    static final class Api {
        final Map<String, MethodHandle> methods = new HashMap<>();
        Api(Path library, Arena arena) {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library, arena);
            var i = ValueLayout.JAVA_INT; var l = ValueLayout.JAVA_LONG; var p = ValueLayout.ADDRESS;
            bind(symbols, "stream_renderer_init", i, p, l);
            bind(symbols, "stream_renderer_teardown", null);
            bind(symbols, "stream_renderer_context_create", i, i, i, p, i);
            bind(symbols, "stream_renderer_create_blob", i, i, i, p, p, i, p);
            bind(symbols, "stream_renderer_resource_create", i, p, p, i);
            bind(symbols, "stream_renderer_ctx_attach_resource", null, i, i);
            bind(symbols, "stream_renderer_resource_attach_iov", i, i, p, i);
            bind(symbols, "stream_renderer_resource_map", i, i, p, p);
            bind(symbols, "stream_renderer_resource_unmap", i, i);
            bind(symbols, "stream_renderer_transfer_write_iov", i, i, i, i, i, i, p, l, p, i);
            bind(symbols, "stream_renderer_transfer_read_iov", i, i, i, i, i, i, p, l, p, i);
            bind(symbols, "stream_renderer_create_fence", i, p);
            bind(symbols, "stream_renderer_snapshot", i, p);
            bind(symbols, "stream_renderer_restore", i, p);
        }
        void bind(SymbolLookup symbols, String name, MemoryLayout result, MemoryLayout... parameters) {
            MemorySegment symbol = symbols.find(name).orElseThrow(() -> new IllegalArgumentException("Provider API missing: " + name));
            methods.put(name, Linker.nativeLinker().downcallHandle(symbol,
                    result == null ? FunctionDescriptor.ofVoid(parameters) : FunctionDescriptor.of(result, parameters)));
        }
        Object call(String name, Object... arguments) throws Throwable { return methods.get(name).invokeWithArguments(arguments); }
        void ok(String name, Object... arguments) throws Throwable {
            int result = (int) call(name, arguments);
            require(result == 0, name + " failed: " + result + " (provider must support the pinned snapshot flags and API)");
        }
    }

    static byte[] pattern(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) ((i * 37 + (i / 256) * 13 + seed * 71) ^ (i >>> 3));
        return bytes;
    }

    static Path absolute(Path path) throws IOException {
        require(path.isAbsolute(), "Path must be absolute: " + path);
        Path normalized = path.normalize();
        require(path.equals(normalized), "Path must be normalized: " + path);
        Path current = normalized.getRoot();
        for (Path component : normalized) {
            current = current.resolve(component);
            require(!Files.isSymbolicLink(current), "Symlink path rejected: " + current);
        }
        return normalized;
    }

    static Path library(Path path) throws IOException {
        path = absolute(path);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toString().endsWith(".so") && Files.size(path) > 0,
                "Provider must be an existing nonempty .so file: " + path);
        require(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name")), "Provider must be owned by the caller: " + path);
        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        require(!mode.contains(PosixFilePermission.GROUP_WRITE) && !mode.contains(PosixFilePermission.OTHERS_WRITE), "Provider must not be writable by other users: " + path);
        return path;
    }

    static void privateDirectory(Path path) throws IOException {
        absolute(path);
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name"))
                && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_DIR), "Requires caller-owned 0700 directory: " + path);
    }

    static Map<String, String> snapshots(Path directory) throws Exception {
        privateDirectory(directory);
        Map<String, String> hashes = new TreeMap<>();
        try (var files = Files.list(directory)) {
            List<Path> entries = files.toList();
            require(entries.size() == SNAPSHOT_FILES.size(), "Snapshot must contain exactly the three pinned provider files");
            for (String name : SNAPSHOT_FILES) {
                Path file = directory.resolve(name);
                require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)
                        && Files.size(file) > 0 && Files.size(file) <= 64L * 1024 * 1024, "Missing, empty or oversized snapshot: " + file);
                hashes.put(name, hash(file));
            }
        }
        return hashes;
    }

    static String absoluteClassPath() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(s -> Path.of(s).toAbsolutePath().normalize().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }
    static Instant start(ProcessHandle process) { return process.info().startInstant().orElseThrow(); }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) { byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static Properties load(Path path) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path) <= 16384, "Invalid properties file: " + path);
        Properties props = new Properties(); try (InputStream in = Files.newInputStream(path)) { props.load(in); } return props;
    }
    static void save(Path path, Properties props) throws IOException {
        require(!Files.isSymbolicLink(path), "Symlink evidence rejected: " + path);
        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) { props.store(out, "Resource-only gfxstream evidence"); }
        Files.setPosixFilePermissions(path, PRIVATE_FILE);
    }
    static void cleanup() {
        synchronized (CHILDREN) {
            closed = true;
            for (Process process : CHILDREN) stop(process);
        }
    }
    static void stop(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (ProcessHandle child : descendants.reversed()) child.destroy();
        process.destroy();
        try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        for (ProcessHandle child : descendants.reversed()) if (child.isAlive()) child.destroyForcibly();
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
