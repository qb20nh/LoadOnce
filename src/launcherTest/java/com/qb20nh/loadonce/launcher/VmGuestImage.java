package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Local guest images; no CRaC API or application restoration callbacks. */
public final class VmGuestImage {
    private static final String KERNEL_VERSION = "6.18.55-1-cachyos-lts";
    private static final Path JAVA_HOME = Path.of("/usr/lib/jvm/java-25-openjdk");
    private static final Path BUSYBOX = Path.of("/usr/lib/initcpio/busybox");
    private static final Pattern LDD_PATH = Pattern.compile(
            "^\\s*(?:[^\\s]+\\s+=>\\s+)?(/[^\\s]+)\\s+\\(", Pattern.MULTILINE);
    private static final Pattern FOREIGN_NATIVE_PATH = Pattern.compile(
            "(?:^|[/_.-])(?:freebsd|openbsd|netbsd|dragonflybsd|sunos|solaris|android|musl)(?:$|[/_.-])",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> ENVIRONMENT = Map.of("PATH", "/usr/bin:/bin", "LANG", "C", "LC_ALL", "C");

    public record Build(Path directory, Path rootImage, Path initrd, Path kernel) { }

    private VmGuestImage() { }

    public static Build create(Path workRoot) throws Exception {
        return create(workRoot, null, null);
    }

    public static Build create(Path workRoot, Path spec, Path xvfb) throws Exception {
        return create(workRoot, spec, xvfb, JAVA_HOME, false);
    }

    public static Build createOpaque(Path workRoot, Path spec, Path xvfb) throws Exception {
        Properties properties = opaqueSpecification(spec);
        Path javaHome = opaquePath(properties, "java.home");
        requireFile(javaHome.resolve("bin/java"));
        return create(workRoot, spec, xvfb, javaHome, true);
    }

    private static Build create(Path workRoot, Path spec, Path xvfb, Path javaHome, boolean opaque) throws Exception {
        if ((spec == null) != (xvfb == null)) {
            throw new IOException("Minecraft guest requires both a launch specification and local Xvfb");
        }
        if (!"Linux".equals(System.getProperty("os.name"))) {
            throw new IOException("VM guest image construction requires the local Linux resources");
        }
        Path directory = LauncherFiles.tempDirectory(workRoot, "guest-image-");
        Stage root = new Stage(LauncherFiles.privateDirectory(directory.resolve("root")), directory, javaHome);
        Stage ramdisk = new Stage(LauncherFiles.privateDirectory(directory.resolve("ramdisk")), directory, javaHome);
        Path kernelSource = Path.of("/boot/vmlinuz-linux-cachyos-lts");
        Path module = Path.of("/usr/lib/modules", KERNEL_VERSION, "kernel/fs/squashfs/squashfs.ko.zst");
        requireFile(kernelSource);
        requireFile(module);
        root.copy(javaHome.resolve("bin"));
        root.copy(javaHome.resolve("lib"));
        root.copy(javaHome.resolve("conf"));
        root.copy(javaHome.resolve("release"));
        root.copy(Path.of("/usr/lib/locale/C.utf8"));
        root.file(BUSYBOX, root.guest(Path.of("/usr/bin/busybox")));
        ramdisk.file(BUSYBOX, ramdisk.guest(Path.of("/usr/bin/busybox")));

        Set<Path> elf = new LinkedHashSet<>();
        elf.add(javaHome.resolve("bin/java"));
        elf.add(BUSYBOX);
        for (String command : List.of("mount", "date", "stty")) {
            Path executable = Path.of("/usr/bin", command);
            root.copy(executable);
            elf.add(executable);
        }
        try (var paths = Files.walk(javaHome.resolve("lib"))) {
            paths.filter(path -> path.getFileName().toString().endsWith(".so") && Files.isRegularFile(path))
                    .forEach(elf::add);
        }
        root.dependencies(elf);
        Set<Path> bootElf = new LinkedHashSet<>(Set.of(BUSYBOX));
        for (String command : List.of("mount", "insmod", "switch_root")) {
            Path executable = Path.of("/usr/bin", command);
            ramdisk.copy(executable);
            bootElf.add(executable);
        }
        ramdisk.dependencies(bootElf);
        root.copyClasses(OpaqueStateFixture.class);
        String init = opaque ? opaque(root, spec, xvfb, javaHome) : spec == null ? guestInit() : minecraft(root, spec, xvfb);
        for (String name : List.of("dev", "proc", "sys", "tmp", "run", "prototype")) {
            LauncherFiles.privateDirectory(root.root.resolve(name));
        }
        for (String name : List.of("dev", "proc", "sys", "run", "newroot")) {
            LauncherFiles.privateDirectory(ramdisk.root.resolve(name));
        }
        write(root.root.resolve("init"), init, true);
        write(ramdisk.root.resolve("init"), ramdiskInit(), true);
        Path decompressedModule = ramdisk.root.resolve("squashfs.ko");
        ramdisk.sources.put(module.toRealPath(), LauncherFiles.fileHash(module.toRealPath()));
        run(directory, "module", List.of("/usr/bin/zstd", "-d", module.toString(), "-o", decompressedModule.toString()));
        Files.setPosixFilePermissions(decompressedModule, PosixFilePermissions.fromString("rw-------"));
        Path kernel = directory.resolve("kernel");
        root.sources.put(kernelSource.toRealPath(), LauncherFiles.fileHash(kernelSource));
        Files.copy(kernelSource, kernel);
        Files.setPosixFilePermissions(kernel, PosixFilePermissions.fromString("rw-------"));
        Path rootImage = directory.resolve("root.squashfs");
        run(directory, "squashfs", List.of("/usr/bin/mksquashfs", root.root.toString(), rootImage.toString(),
                "-noappend", "-all-root", "-comp", "zstd", "-processors", "1", "-mem", "256M", "-no-progress"));
        Files.setPosixFilePermissions(rootImage, PosixFilePermissions.fromString("rw-------"));
        Path archiveScript = directory.resolve("archive-initrd.sh");
        write(archiveScript, """
                #!/bin/bash
                set -euo pipefail
                umask 077
                cd "$1"
                /usr/bin/find . -print0 | /usr/bin/cpio --null -o -H newc -R 0:0 | /usr/bin/gzip -1 > "$2"
                """, true);
        Path initrd = directory.resolve("initrd.img");
        run(directory, "initrd", List.of("/bin/bash", archiveScript.toString(), ramdisk.root.toString(), initrd.toString()));
        root.checkUnchanged();
        ramdisk.checkUnchanged();
        LauncherFiles.writeManifest(directory.resolve("guest-build.properties"), Map.of(
                "kernel_version", KERNEL_VERSION, "kernel_sha256", LauncherFiles.fileHash(kernel),
                "root_sha256", LauncherFiles.fileHash(rootImage), "initrd_sha256", LauncherFiles.fileHash(initrd),
                "root_bytes", Long.toString(Files.size(rootImage)), "initrd_bytes", Long.toString(Files.size(initrd)),
                "kernel_bytes", Long.toString(Files.size(kernel)),
                "clock_policy", "guest-OS-wall-clock-correction-before-continuation"));
        return new Build(directory, rootImage, initrd, kernel);
    }

    private static String minecraft(Stage root, Path specification, Path xvfb) throws Exception {
        requireFile(specification);
        LauncherFiles.checkPrivate(specification, false);
        if (Files.size(specification) > 1024 * 1024) throw new IOException("Minecraft guest specification exceeds 1 MiB");
        root.sources.put(specification.toRealPath(), LauncherFiles.fileHash(specification));
        Properties spec = new Properties();
        try (var input = Files.newInputStream(specification)) {
            spec.load(input);
        }
        spec.setProperty("mode", "cold");
        spec.setProperty("verify", "true");
        Path game = Path.of(required(spec, "gameDirectory"));
        if (!game.isAbsolute()) throw new IOException("Minecraft guest game directory must be absolute: " + game);
        LauncherFiles.checkPrivate(game, true);
        Path marker = game.resolve(".loadonce-prototype");
        requireFile(marker);
        LauncherFiles.checkPrivate(marker, false);
        if (!Files.readString(marker).equals("LoadOnce isolated prototype\n")) {
            throw new IOException("Minecraft guest requires the isolated prototype marker: " + marker);
        }
        List<String> classpath = indexed(spec, "classpath");
        for (String entry : classpath) root.copy(Path.of(entry));
        List<String> jvm = indexed(spec, "jvm");
        String configArgument = jvm.stream().filter(argument -> argument.startsWith("-Dfabric.dli.config="))
                .findFirst().orElseThrow(() -> new IOException("Minecraft guest has no Fabric launch configuration"));
        Path config = Path.of(configArgument.substring("-Dfabric.dli.config=".length()));
        requireFile(config);
        root.copy(config);
        String configuration = Files.readString(config);
        for (String property : List.of("log4j.configurationFile", "fabric.gameJarPath", "fabric.gameJarPath.client")) {
            var match = Pattern.compile("(?m)^\\s*" + Pattern.quote(property) + "=(.+)$").matcher(configuration);
            if (!match.find()) throw new IOException("Missing Minecraft guest launch property: " + property);
            root.copy(Path.of(match.group(1).trim()));
        }
        var assetIndex = Pattern.compile("(?m)^\\s*--assetIndex\\s*\\R\\s*([^\\r\\n]+)").matcher(configuration);
        var assetsDirectory = Pattern.compile("(?m)^\\s*--assetsDir\\s*\\R\\s*([^\\r\\n]+)").matcher(configuration);
        if (!assetIndex.find() || !assetsDirectory.find()) throw new IOException("Missing Minecraft guest asset arguments");
        String indexName = assetIndex.group(1).trim();
        if (!indexName.matches("[A-Za-z0-9._-]+")) throw new IOException("Invalid Minecraft asset index: " + indexName);
        Path assets = Path.of(assetsDirectory.group(1).trim());
        Path index = assets.resolve("indexes").resolve(indexName + ".json");
        requireFile(index);
        root.copy(index);
        var hashes = Pattern.compile("\"hash\"\\s*:\\s*\"([a-f0-9]{40})\"").matcher(Files.readString(index));
        Set<String> objects = new LinkedHashSet<>();
        while (hashes.find()) objects.add(hashes.group(1));
        if (objects.isEmpty()) throw new IOException("Minecraft asset index contains no object hashes: " + index);
        for (String hash : objects) root.copy(assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash));

        Path template = LauncherFiles.privateDirectory(root.root.resolve("app/game-template"));
        root.sources.put(marker.toRealPath(), LauncherFiles.fileHash(marker));
        write(template.resolve(".loadonce-prototype"), Files.readString(marker), false);
        requireFile(game.resolve("options.txt"));
        root.sources.put(game.resolve("options.txt").toRealPath(), LauncherFiles.fileHash(game.resolve("options.txt")));
        String options = Files.readString(game.resolve("options.txt"))
                .replaceAll("(?m)^renderDistance:.*$", "renderDistance:2")
                .replaceAll("(?m)^simulationDistance:.*$", "simulationDistance:2")
                .replaceAll("(?m)^enableVsync:.*$", "enableVsync:false");
        write(template.resolve("options.txt"), options, false);
        Path mods = game.resolve("mods");
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(mods)) {
            throw new IOException("Minecraft fixture mods directory is unavailable: " + mods);
        }
        Path guestMods = LauncherFiles.privateDirectory(template.resolve("mods"));
        Set<Path> nativeArchives = new LinkedHashSet<>();
        for (String entry : classpath) {
            Path file = Path.of(entry);
            if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar")) nativeArchives.add(file);
        }
        try (var files = Files.newDirectoryStream(mods)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                    throw new IOException("Minecraft fixture mods must be regular files: " + file);
                }
                root.file(file, guestMods.resolve(file.getFileName()));
                if (file.getFileName().toString().endsWith(".jar")) nativeArchives.add(file);
            }
        }
        root.nativeDependencies(nativeArchives);
        LauncherFiles.privateDirectory(root.guest(game));
        Path launch = root.root.resolve("app/launch.properties");
        Files.createFile(launch, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var output = Files.newOutputStream(launch)) {
            spec.store(output, "Pinned local Minecraft VM launch");
        }
        graphics(root, xvfb);
        List<String> command = new ArrayList<>(List.of(JAVA_HOME.resolve("bin/java").toString(), "-Xshare:off",
                "-XX:+PerfDisableSharedMem", "-XX:+DisableAttachMechanism"));
        command.addAll(jvm);
        command.addAll(List.of("-Xms64m", "-Xmx1536m", "-Duser.home=/prototype/home", "-Djnidispatch.preserve=true",
                "-Djava.io.tmpdir=/prototype/tmp", "-Dloadonce.prototype.opaqueDirectory=/prototype",
                "-cp", String.join(":", classpath), MinecraftPrototypeBootstrap.class.getName(),
                "--cold", "--control-file", "/prototype/launch.properties"));
        return minecraftInit(game, command);
    }

    private static void graphics(Stage root, Path xvfb) throws Exception {
        requireFile(xvfb);
        root.file(xvfb, root.root.resolve("usr/bin/Xvfb"));
        Set<Path> graphics = new LinkedHashSet<>(Set.of(xvfb));
        Path keyboardCompiler = Path.of("/usr/bin/xkbcomp");
        root.copy(keyboardCompiler);
        graphics.add(keyboardCompiler);
        for (String library : List.of("libvulkan.so.1", "libvulkan_lvp.so", "libGL.so.1", "libGLX_mesa.so.0",
                "libEGL_mesa.so.0", "dri/swrast_dri.so", "libX11.so.6", "libXext.so.6", "libXcursor.so.1",
                "libXi.so.6", "libXrandr.so.2", "libXfixes.so.3", "libXrender.so.1", "libXss.so.1",
                "libxkbcommon.so.0", "libxkbcommon-x11.so.0", "libudev.so.1", "libfontconfig.so.1")) {
            Path resource = Path.of("/usr/lib", library);
            root.copy(resource);
            graphics.add(resource);
        }
        // Mesa's GL driver dlopens Gallium; ldd cannot discover that edge.
        try (var files = Files.newDirectoryStream(Path.of("/usr/lib"), "libgallium-*.so")) {
            for (Path file : files) {
                root.copy(file);
                graphics.add(file);
            }
        }
        root.dependencies(graphics);
        for (String resource : List.of("/usr/share/vulkan/icd.d/lvp_icd.json",
                "/usr/share/glvnd/egl_vendor.d/50_mesa.json", "/usr/share/X11/xkb", "/etc/fonts",
                "/usr/share/fonts/liberation")) root.copy(Path.of(resource));
        write(root.root.resolve("etc/passwd"), "root:x:0:0:root:/prototype/home:/bin/sh\n", false);
        write(root.root.resolve("etc/group"), "root:x:0:\n", false);
        write(root.root.resolve("etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n", false);
    }

    private static Properties opaqueSpecification(Path specification) throws IOException {
        requireFile(specification);
        LauncherFiles.checkPrivate(specification, false);
        if (Files.size(specification) > 1024 * 1024) throw new IOException("Opaque guest specification exceeds 1 MiB");
        Properties spec = new Properties();
        try (var input = Files.newInputStream(specification)) { spec.load(input); }
        Path game = opaquePath(spec, "game.directory");
        LauncherFiles.checkPrivate(game, true);
        if (!List.of("1536", "2304").contains(required(spec, "memory.mib"))) {
            throw new IOException("Opaque guest prototype requires memory.mib=1536 or 2304");
        }
        long bytes = 0;
        try (var files = Files.walk(game)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Opaque guest game contains a symlink: " + file);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    bytes += Files.size(file);
                    if (bytes > 512L * 1024 * 1024) {
                        throw new IOException("Opaque guest game exceeds 512 MiB tmpfs budget: " + game
                                + "; larger packs require disk-backed writable game storage");
                    }
                } else if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Opaque guest game entry is not a regular file or directory: " + file);
                }
            }
        }
        for (String key : List.of("assets.directory", "native.directory", "java.home")) {
            Path path = opaquePath(spec, key);
            if (!Files.isDirectory(path) || path.startsWith(game) || game.startsWith(path)) {
                throw new IOException("Opaque guest " + key + " must be a directory outside game.directory: " + path);
            }
        }
        String main = required(spec, "main.class");
        if (!main.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new IOException("Invalid opaque guest Java entrypoint: " + main);
        }
        List<String> classpath = indexed(spec, "classpath");
        if (classpath.isEmpty()) throw new IOException("Opaque guest classpath is empty");
        for (int index = 0; index < classpath.size(); index++) {
            String entry = classpath.get(index);
            Path path = opaqueStagingPath(entry, "classpath." + index);
            if (entry.contains(":")) throw new IOException("Invalid opaque guest classpath." + index + ": " + entry);
            requireFile(path);
        }
        opaqueArguments(spec);
        return spec;
    }

    private static Path opaquePath(Properties spec, String key) throws IOException {
        return opaqueStagingPath(required(spec, key), key);
    }

    static Path opaqueStagingPath(String value, String field) throws IOException {
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IOException("Opaque guest path must be absolute: " + field + "=" + path);
        path = path.normalize();
        boolean hidden = path.startsWith("/run") && !path.startsWith("/run/media");
        for (String mount : List.of("/tmp", "/prototype", "/dev", "/proc", "/sys")) hidden |= path.startsWith(mount);
        if (hidden) throw new IOException("Opaque guest staging input is hidden by a runtime mount: " + field + "=" + path
                + "; choose a path under /home or /run/media");
        return path;
    }

    private static List<String> opaqueArguments(Properties spec) throws IOException {
        int count;
        try { count = Integer.parseInt(required(spec, "arguments.count")); }
        catch (NumberFormatException failure) { throw new IOException("Invalid opaque guest arguments.count", failure); }
        if (count < 0 || count > 4096) throw new IOException("Opaque guest arguments.count exceeds 4096");
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String argument = spec.getProperty("arguments." + index);
            if (argument == null || argument.indexOf('\0') >= 0) throw new IOException("Invalid opaque guest argument " + index);
            arguments.add(argument);
        }
        return arguments;
    }

    private static String opaque(Stage root, Path specification, Path xvfb, Path javaHome) throws Exception {
        Properties spec = opaqueSpecification(specification);
        if (!javaHome.equals(opaquePath(spec, "java.home"))) throw new IOException("Opaque guest runtime changed during staging");
        root.sources.put(specification.toRealPath(), LauncherFiles.fileHash(specification));
        Path copiedSpec = root.evidence.resolve("opaque-spec.properties");
        Files.copy(specification, copiedSpec);
        Files.setPosixFilePermissions(copiedSpec, PosixFilePermissions.fromString("rw-------"));
        Path game = opaquePath(spec, "game.directory");
        root.copy(game);
        root.copy(opaquePath(spec, "assets.directory"));
        Path natives = opaquePath(spec, "native.directory");
        root.copy(natives);
        Set<Path> archives = new LinkedHashSet<>();
        List<String> classpath = indexed(spec, "classpath");
        for (String entry : classpath) {
            Path file = Path.of(entry);
            root.copy(file);
            if (entry.endsWith(".jar")) archives.add(file);
        }
        Set<Path> nativeFiles = new LinkedHashSet<>();
        try (var files = Files.walk(natives)) {
            files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().matches(".*\\.so(?:\\..*)?"))
                    .forEach(nativeFiles::add);
        }
        root.dependencies(nativeFiles);
        try (var files = Files.walk(game)) {
            files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar"))
                    .forEach(archives::add);
        }
        root.nativeDependencies(archives);
        graphics(root, xvfb);
        root.copyClasses(OpaqueGuestControl.class);
        write(root.root.resolve("app/.loadonce-opaque-guest"), "LoadOnce opaque guest\n", false);
        LauncherFiles.privateDirectory(root.root.resolve("app/game-template"));
        List<String> command = new ArrayList<>(List.of(javaHome.resolve("bin/java").toString(),
                "-Xms256m", "-Xmx" + required(spec, "memory.mib") + "m", "-Djava.library.path=" + natives,
                "-Duser.home=/prototype/home", "-Djava.io.tmpdir=/tmp", "-cp", String.join(":", classpath),
                required(spec, "main.class")));
        command.addAll(opaqueArguments(spec));
        return opaqueInit(game, javaHome, command);
    }

    private static String opaqueInit(Path game, Path javaHome, List<String> command) {
        return """
                #!/bin/sh
                export PATH=/bin:/usr/bin
                umask 077
                exec </dev/ttyS0 >/dev/ttyS0 2>&1
                stty -echo -ixon -ixoff
                finish() {
                    busybox poweroff -f
                    while :; do busybox sleep 1; done
                }
                failed() {
                    [ ! -f /tmp/loadonce-game.log ] || busybox cat /tmp/loadonce-game.log
                    [ ! -f /prototype/display.log ] || busybox cat /prototype/display.log
                    printf 'VM_FAILED step=%%s\\n' "$1"
                    finish
                }
                mount -n -t tmpfs -o mode=0700 tmpfs /prototype || failed work-mount
                mount -n -t tmpfs -o mode=1777,size=384m tmpfs /tmp || failed tmp-mount
                printf 'localhost\\n' >/proc/sys/kernel/hostname || failed guest-hostname
                game=%s
                mount -n --bind "$game" /app/game-template || failed game-template-bind
                mount -n -t tmpfs -o mode=0700,size=768m tmpfs "$game" || failed game-mount
                busybox cp -R /app/game-template/. "$game/" || failed game-template
                busybox mkdir -p /prototype/home /prototype/xdg /prototype/mesa-cache || failed work-directories
                export HOME=/prototype/home TMPDIR=/tmp XDG_RUNTIME_DIR=/prototype/xdg
                export MESA_SHADER_CACHE_DIR=/prototype/mesa-cache DISPLAY=:117
                export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe __GLX_VENDOR_LIBRARY_NAME=mesa
                export LIBGL_DRIVERS_PATH=/usr/lib/dri SDL_VIDEODRIVER=x11 ALSOFT_DRIVERS=null
                export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json
                export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json
                export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json
                export LANG=C.UTF-8
                /usr/bin/Xvfb :117 -screen 0 1280x720x24 -nolisten tcp -ac -noreset -fp built-ins \
                    -ardelay 60000 -arinterval 60000 \
                    >/prototype/display.log 2>&1 &
                display=$!
                attempts=0
                while [ ! -S /tmp/.X11-unix/X117 ]; do
                    kill -0 "$display" 2>/dev/null || failed display-before-ready
                    attempts=$((attempts + 1))
                    [ "$attempts" -lt 400 ] || failed display-timeout
                    busybox sleep 0.05
                done
                cd "$game" || failed game-directory
                %s </dev/null >/tmp/loadonce-game.log 2>&1 &
                app=$!
                stty -F /dev/ttyS1 -echo -ixon -ixoff || failed control-terminal
                %s/bin/java -Xms32m -Xmx128m -cp /app/classes \
                    com.qb20nh.loadonce.launcher.OpaqueGuestControl "$app" /tmp/loadonce-game.log \
                    </dev/ttyS1 >/dev/ttyS1 2>&1 || failed control-exit
                kill -0 "$app" 2>/dev/null && failed application-still-running
                wait "$app"
                appExit=$?
                printf 'OPAQUE_APPLICATION_EXIT code=%%s\\n' "$appExit"
                kill "$display" 2>/dev/null
                finish
                """.formatted(quote(game.toString()), command.stream().map(VmGuestImage::quote)
                .collect(java.util.stream.Collectors.joining(" ")), quote(javaHome.toString()));
    }

    private static String required(Properties properties, String field) throws IOException {
        String value = properties.getProperty(field);
        if (value == null || value.isBlank()) throw new IOException("Missing Minecraft guest specification field: " + field);
        return value;
    }

    private static List<String> indexed(Properties properties, String prefix) throws IOException {
        int count;
        try {
            count = Integer.parseInt(required(properties, prefix + ".count"));
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid Minecraft guest specification count: " + prefix, failure);
        }
        if (count < 0 || count > 1024) throw new IOException("Minecraft guest specification count out of range: " + prefix);
        List<String> values = new ArrayList<>();
        for (int index = 0; index < count; index++) values.add(required(properties, prefix + "." + index));
        return values;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String minecraftInit(Path game, List<String> command) {
        return """
                #!/bin/sh
                export PATH=/bin:/usr/bin
                umask 077
                exec </dev/ttyS0 >/dev/ttyS0 2>&1
                stty -echo -ixon -ixoff
                finish() {
                    busybox poweroff -f
                    while :; do busybox sleep 1; done
                }
                failed() {
                    if [ -f /prototype/application.log ]; then busybox cat /prototype/application.log; fi
                    if [ -f /prototype/display.log ]; then busybox cat /prototype/display.log; fi
                    printf 'VM_FAILED step=%%s\\n' "$1"
                    finish
                }
                emit() {
                    busybox sed '/^[#!]/d; /^$/d; s/^/'"$1"'./' "$2" || failed evidence-read
                }
                mount -n -t tmpfs -o mode=0700 tmpfs /prototype || failed work-mount
                mount -n -t tmpfs -o mode=1777 tmpfs /tmp || failed tmp-mount
                printf 'localhost\\n' >/proc/sys/kernel/hostname || failed guest-hostname
                game=%s
                mount -n -t tmpfs -o mode=0700 tmpfs "$game" || failed game-mount
                busybox cp -R /app/game-template/. "$game/" || failed game-template
                busybox mkdir -p /prototype/home /prototype/tmp /prototype/xdg /prototype/mesa-cache \
                    || failed work-directories
                busybox cp /app/launch.properties /prototype/launch.properties || failed launch-copy
                printf 'loadonce-opaque-v1\\n' >/prototype/.loadonce-opaque || failed opaque-marker
                export HOME=/prototype/home TMPDIR=/prototype/tmp XDG_RUNTIME_DIR=/prototype/xdg
                export MESA_SHADER_CACHE_DIR=/prototype/mesa-cache DISPLAY=:117
                export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe __GLX_VENDOR_LIBRARY_NAME=mesa
                export LIBGL_DRIVERS_PATH=/usr/lib/dri SDL_VIDEODRIVER=x11 ALSOFT_DRIVERS=null
                export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json
                export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json
                export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json
                export LANG=C.UTF-8
                /usr/bin/Xvfb :117 -screen 0 854x480x24 -nolisten tcp -ac -noreset -fp built-ins \
                    >/prototype/display.log 2>&1 &
                display=$!
                attempts=0
                while [ ! -S /tmp/.X11-unix/X117 ]; do
                    kill -0 "$display" 2>/dev/null || failed display-before-ready
                    attempts=$((attempts + 1))
                    [ "$attempts" -lt 400 ] || failed display-timeout
                    busybox sleep 0.05
                done
                : >/prototype/application.stdin
                cd "$game" || failed game-directory
                %s </prototype/application.stdin >/prototype/application.log 2>&1 &
                app=$!
                attempts=0
                while [ ! -f /prototype/probe-ready.properties ] || [ ! -f /prototype/mod-ready.properties ]; do
                    kill -0 "$app" 2>/dev/null || failed application-before-ready
                    kill -0 "$display" 2>/dev/null || failed display-before-menu
                    attempts=$((attempts + 1))
                    [ "$attempts" -lt 4000 ] || failed menu-timeout
                    busybox sleep 0.05
                done
                printf 'VM_READY_BEGIN\\n'
                emit probe /prototype/probe-ready.properties
                emit mod /prototype/mod-ready.properties
                printf 'VM_READY_END\\n'
                IFS=' ' read -r action generation epoch extra || failed continuation-read
                [ "$action" = CONTINUE ] && [ -z "$extra" ] || failed continuation-format
                case "$generation" in 1|2|3) ;; *) failed continuation-generation ;; esac
                case "$epoch" in ''|*[!0-9]*) failed continuation-epoch ;; esac
                for file in "probe-passed-$generation" "probe-resumed-$generation" "mod-resumed-$generation"; do
                    [ ! -e "/prototype/$file.properties" ] || failed stale-generation-evidence
                done
                # Correct the guest OS wall clock, then let the retained application observe its release file.
                date -s "@$epoch" >/prototype/clock-correction.log 2>&1 || failed clock-correction
                printf '%%s\\n' "$generation" >/prototype/release.tmp || failed release-write
                busybox mv /prototype/release.tmp /prototype/release || failed release-publish
                wait "$app" || failed application-exit
                for file in "probe-passed-$generation" "probe-resumed-$generation" "mod-resumed-$generation"; do
                    [ -f "/prototype/$file.properties" ] || failed generation-evidence-missing
                done
                printf 'VM_RESULT_BEGIN\\n'
                emit passed "/prototype/probe-passed-$generation.properties"
                emit probe "/prototype/probe-resumed-$generation.properties"
                emit mod "/prototype/mod-resumed-$generation.properties"
                printf 'VM_RESULT_END\\n'
                finish
                """.formatted(quote(game.toString()), command.stream().map(VmGuestImage::quote)
                .collect(java.util.stream.Collectors.joining(" ")));
    }

    private static String ramdiskInit() {
        return """
                #!/bin/sh
                export PATH=/bin:/usr/bin
                set -eu
                mount -n -t devtmpfs devtmpfs /dev
                mount -n -t proc proc /proc
                mount -n -t sysfs sysfs /sys
                mount -n -t tmpfs -o mode=0755 tmpfs /run
                insmod /squashfs.ko
                mount -n -t squashfs -o ro /dev/vda /newroot
                # Preserve staged removable-volume files beneath the writable runtime mount.
                if [ -d /newroot/run/media ]; then
                    busybox mkdir -p /run/media
                    mount -n --bind /newroot/run/media /run/media
                fi
                mount -n --move /dev /newroot/dev
                mount -n --move /proc /newroot/proc
                mount -n --move /sys /newroot/sys
                mount -n --move /run /newroot/run
                exec switch_root /newroot /init
                """;
    }

    private static String guestInit() {
        return """
                #!/bin/sh
                export PATH=/bin:/usr/bin
                umask 077
                exec </dev/ttyS0 >/dev/ttyS0 2>&1
                stty -echo -ixon -ixoff
                finish() {
                    busybox poweroff -f
                    while :; do busybox sleep 1; done
                }
                failed() {
                    if [ -f /prototype/fixture.log ]; then busybox cat /prototype/fixture.log; fi
                    printf 'VM_FAILED step=%%s\\n' "$1"
                    finish
                }
                mount -n -t tmpfs -o mode=0700 tmpfs /prototype || failed work-mount
                mount -n -t tmpfs -o mode=1777 tmpfs /tmp || failed tmp-mount
                : > /prototype/fixture.stdin
                %s/bin/java -Xms32m -Xmx32m -cp /app/classes \
                    com.qb20nh.loadonce.launcher.OpaqueStateFixture /prototype 300000 \
                    </prototype/fixture.stdin >/prototype/fixture.log 2>&1 &
                app=$!
                while [ ! -f /prototype/ready.properties ]; do
                    kill -0 "$app" 2>/dev/null || failed fixture-before-ready
                    busybox sleep 0.05
                done
                printf 'VM_READY_BEGIN\\n'
                busybox cat /prototype/ready.properties || failed readiness-read
                printf 'VM_READY_END\\n'
                IFS=' ' read -r action generation epoch extra || failed continuation-read
                [ "$action" = CONTINUE ] && [ -z "$extra" ] || failed continuation-format
                case "$generation" in 1|2|3) ;; *) failed continuation-generation ;; esac
                case "$epoch" in ''|*[!0-9]*) failed continuation-epoch ;; esac
                # Restore updates the guest OS clock; no application hook or initialization runs here.
                date -s "@$epoch" >/prototype/clock-correction.log 2>&1 || failed clock-correction
                printf 'generation=%%s\\nminimum_pause_millis=1000\\n' "$generation" >/prototype/continue.properties.tmp \
                    || failed continuation-write
                busybox mv /prototype/continue.properties.tmp /prototype/continue.properties || failed continuation-publish
                wait "$app" || failed fixture-exit
                [ -f /prototype/result.properties ] || failed fixture-result-missing
                printf 'VM_RESULT_BEGIN\\n'
                busybox cat /prototype/result.properties || failed fixture-result-read
                printf 'VM_RESULT_END\\n'
                finish
                """.formatted(JAVA_HOME);
    }

    private static void write(Path file, String text, boolean executable) throws IOException {
        LauncherFiles.privateDirectory(file.getParent());
        Files.createFile(file, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(executable ? "rwx------" : "rw-------")));
        Files.writeString(file, text);
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("Required local guest resource is missing or unreadable: " + file);
        }
    }

    private static ChildProcess.Result run(Path directory, String step, List<String> command) throws Exception {
        return run(directory, step, command, ENVIRONMENT);
    }

    private static ChildProcess.Result run(Path directory, String step, List<String> command,
                                          Map<String, String> environment) throws Exception {
        Path log = LauncherFiles.tempFile(directory, step + "-", ".log");
        ChildProcess.Result result = ChildProcess.run(command, directory, environment, log, Duration.ofSeconds(180));
        if (result.exitCode() != 0) {
            throw new IOException("VM guest image step " + step + " failed for " + command
                    + " with exit " + result.exitCode() + "; inspect " + log);
        }
        return result;
    }

    private static final class Stage {
        private final Path root;
        private final Path evidence;
        private final Path javaHome;
        private final Set<Path> copied = new LinkedHashSet<>();
        private final Map<Path, String> sources = new LinkedHashMap<>();

        private Stage(Path root, Path evidence, Path javaHome) throws IOException {
            this.root = root;
            this.evidence = evidence;
            this.javaHome = javaHome;
            LauncherFiles.privateDirectory(root.resolve("usr/bin"));
            LauncherFiles.privateDirectory(root.resolve("usr/lib"));
            Files.createSymbolicLink(root.resolve("bin"), Path.of("usr/bin"));
            Files.createSymbolicLink(root.resolve("sbin"), Path.of("usr/bin"));
            Files.createSymbolicLink(root.resolve("lib"), Path.of("usr/lib"));
            Files.createSymbolicLink(root.resolve("lib64"), Path.of("usr/lib"));
            Files.createSymbolicLink(root.resolve("usr/lib64"), Path.of("lib"));
            Files.createSymbolicLink(root.resolve("usr/bin/sh"), Path.of("busybox"));
        }

        private Path guest(Path absolute) throws IOException {
            if (!absolute.isAbsolute()) throw new IOException("Guest source path is not absolute: " + absolute);
            absolute = absolute.normalize();
            for (String alias : List.of("/lib", "/lib64", "/usr/lib64")) {
                Path prefix = Path.of(alias);
                if (absolute.startsWith(prefix)) absolute = Path.of("/usr/lib").resolve(prefix.relativize(absolute));
            }
            for (String alias : List.of("/bin", "/sbin", "/usr/sbin")) {
                Path prefix = Path.of(alias);
                if (absolute.startsWith(prefix)) absolute = Path.of("/usr/bin").resolve(prefix.relativize(absolute));
            }
            return root.resolve(absolute.toString().substring(1));
        }

        private void copy(Path source) throws IOException {
            source = source.toAbsolutePath().normalize();
            Path target = guest(source);
            if (!copied.add(target)) return;
            if (Files.isSymbolicLink(source)) {
                Path link = Files.readSymbolicLink(source);
                Path hostTarget = link.isAbsolute() ? link : source.getParent().resolve(link).normalize();
                LauncherFiles.privateDirectory(target.getParent());
                Path guestTarget = guest(hostTarget);
                Files.createSymbolicLink(target, target.getParent().relativize(guestTarget));
                copy(hostTarget);
            } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                LauncherFiles.privateDirectory(target);
                try (var entries = Files.newDirectoryStream(source)) {
                    for (Path entry : entries) copy(entry);
                }
            } else {
                file(source, target);
            }
        }

        private void file(Path source, Path target) throws IOException {
            requireFile(source);
            Path real = source.toRealPath();
            sources.putIfAbsent(real, LauncherFiles.fileHash(real));
            LauncherFiles.privateDirectory(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
            try {
                Files.createLink(target, real);
            } catch (IOException | UnsupportedOperationException unavailable) {
                Files.copy(real, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }

        private void dependencies(Set<Path> initial) throws Exception {
            dependencies(initial, Set.of());
        }

        private void dependencies(Set<Path> initial, Set<Path> analysisOnly) throws Exception {
            List<Path> pending = new ArrayList<>(initial);
            Set<Path> checked = new LinkedHashSet<>();
            Map<String, String> environment = new LinkedHashMap<>(ENVIRONMENT);
            // Standalone ldd must resolve libraries normally loaded by the Java launcher.
            Set<String> libraryDirectories = new LinkedHashSet<>();
            libraryDirectories.add(javaHome.resolve("lib").toString());
            libraryDirectories.add(javaHome.resolve("lib/server").toString());
            environment.put("LD_LIBRARY_PATH", String.join(":", libraryDirectories));
            for (int index = 0; index < pending.size(); index++) {
                Path binary = pending.get(index).toRealPath();
                if (!checked.add(binary)) continue;
                if (analysisOnly.contains(binary)) {
                    Set<String> siblingFirst = new LinkedHashSet<>();
                    siblingFirst.add(binary.getParent().toString());
                    siblingFirst.addAll(libraryDirectories);
                    environment.put("LD_LIBRARY_PATH", String.join(":", siblingFirst));
                } else {
                    environment.put("LD_LIBRARY_PATH", String.join(":", libraryDirectories));
                }
                String output = run(evidence, "ldd", List.of("/usr/bin/ldd", binary.toString()), environment).output();
                if (output.contains("=> not found")) {
                    throw new IOException("Missing local ELF dependency for " + binary + "; inspect ldd evidence in " + evidence);
                }
                var paths = LDD_PATH.matcher(output);
                while (paths.find()) {
                    Path dependency = Path.of(paths.group(1));
                    if (!analysisOnly.contains(dependency.toRealPath())) copy(dependency);
                    pending.add(dependency);
                }
            }
        }

        private void nativeDependencies(Set<Path> archives) throws Exception {
            Path directory = LauncherFiles.tempDirectory(evidence, "native-elf-");
            Set<Path> libraries = new LinkedHashSet<>();
            int archiveNumber = 0;
            long total = 0;
            for (Path archive : archives) {
                Path archiveDirectory = directory.resolve("archive-" + archiveNumber++);
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().matches(".*\\.so(?:\\.[^/]+)?$")) continue;
                        if (FOREIGN_NATIVE_PATH.matcher(entry.getName()).find()) continue;
                        try (var input = zip.getInputStream(entry)) {
                            byte[] header = input.readNBytes(20);
                            if (header.length != 20 || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L'
                                    || header[3] != 'F' || header[4] != 2 || header[5] != 1
                                    || (header[7] != 0 && header[7] != 3) || header[16] != 3 || header[17] != 0
                                    || header[18] != 62 || header[19] != 0) continue;
                            Path target = archiveDirectory.resolve(entry.getName()).normalize();
                            if (!target.startsWith(archiveDirectory)) {
                                throw new IOException("Native archive entry escapes evidence directory: " + archive + "!" + entry.getName());
                            }
                            if (entry.getSize() > 64L * 1024 * 1024) {
                                throw new IOException("Native ELF exceeds 64 MiB analysis limit: " + archive + "!" + entry.getName());
                            }
                            LauncherFiles.privateDirectory(target.getParent());
                            Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                            long bytes = header.length;
                            try (var output = Files.newOutputStream(target)) {
                                output.write(header);
                                byte[] buffer = new byte[8192];
                                for (int count; (count = input.read(buffer)) >= 0;) {
                                    bytes += count;
                                    total += count;
                                    if (bytes > 64L * 1024 * 1024 || total > 512L * 1024 * 1024) {
                                        throw new IOException("Native ELF analysis size limit exceeded: " + archive + "!" + entry.getName());
                                    }
                                    output.write(buffer, 0, count);
                                }
                            }
                            libraries.add(target.toRealPath());
                        }
                    }
                }
            }
            dependencies(libraries, libraries);
        }

        private void copyClasses(Class<?> type) throws IOException {
            String resource = type.getName().replace('.', '/') + ".class";
            Path target = root.resolve("app/classes").resolve(resource);
            LauncherFiles.privateDirectory(target.getParent());
            try (var input = type.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Fixture class resource is unavailable: " + resource);
                Files.copy(input, target);
            }
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            for (Class<?> nested : type.getDeclaredClasses()) copyClasses(nested);
        }

        private void checkUnchanged() throws IOException {
            for (var source : sources.entrySet()) {
                if (!source.getValue().equals(LauncherFiles.fileHash(source.getKey()))) {
                    throw new IOException("Local resource changed during guest image construction: " + source.getKey());
                }
            }
        }
    }
}
