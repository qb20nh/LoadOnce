package com.qb20nh.loadonce.launcher;

import com.qb20nh.loadonce.runtime.CheckpointCoordinator;
import com.qb20nh.loadonce.runtime.CheckpointGate;
import com.qb20nh.loadonce.runtime.CheckpointResource;
import com.qb20nh.loadonce.runtime.CracBridge;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/** Restores the JVM before any application or mod entrypoint executes. */
public final class MinecraftPrototypeBootstrap {
    private static Object gameCheckpointVeto;

    private MinecraftPrototypeBootstrap() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3 || !arguments[1].equals("--control-file")
                || !(arguments[0].equals("--checkpoint") || arguments[0].equals("--cold"))) {
            throw new IllegalArgumentException("Expected --checkpoint|--cold --control-file PRIVATE_PATH");
        }
        boolean checkpoint = arguments[0].equals("--checkpoint");
        Path control = Path.of(arguments[2]).toAbsolutePath().normalize();
        String runtimeClasspath = System.getProperty("java.class.path");
        PreparedRuntime prepared = new PreparedRuntime();
        CracBridge.CallbackResult callbacks = new CracBridge.CallbackResult(0, 0);
        if (checkpoint) {
            // Do not read the launch request yet: current application arguments,
            // including any future session inputs, must not enter the image.
            CheckpointCoordinator coordinator = new CheckpointCoordinator(() ->
                    CheckpointGate.Decision.allow("prototype bootstrap; application and mods have not executed"));
            coordinator.register(prepared);
            CracBridge bridge = CracBridge.install(coordinator);
            System.out.println("LOADONCE_PROTOTYPE_CAPTURE heap-witness=" + prepared.witness
                    + " namespace-pid=" + kernelPid());
            callbacks = bridge.checkpointRestore();
            if (coordinator.state() != CheckpointCoordinator.State.READY
                    || callbacks.preparationCallbacks() != 1 || callbacks.restorationCallbacks() != 1
                    || prepared.preparations != 1 || prepared.restorations != 1 || prepared.detached) {
                throw new IOException("Prototype bootstrap did not complete exactly one restoration");
            }
        }
        installGameCheckpointVeto();
        boolean classpathReset = !runtimeClasspath.equals(System.getProperty("java.class.path"));
        // CRaC applies the restore command's default classpath property even
        // though the restored system class loader retains the source URLs.
        // Fabric discovers its fresh application classpath through this property.
        System.setProperty("java.class.path", runtimeClasspath);
        System.out.println("LOADONCE_PROTOTYPE_CLASSPATH restored-property=" + classpathReset);
        Properties request = readRequest(control);
        String mode = checkpoint ? "warm" : "cold";
        if (!mode.equals(request.getProperty("mode"))) {
            throw new IOException("Launch request mode does not match the actual bootstrap path");
        }
        Path game = Path.of(required(request, "gameDirectory")).toRealPath();
        LauncherFiles.checkPrivate(game, true);
        Path marker = game.resolve(".loadonce-prototype");
        LauncherFiles.checkPrivate(marker, false);
        if (!Files.isRegularFile(marker) || !Files.readString(marker).equals("LoadOnce isolated prototype\n")) {
            throw new IOException("Prototype game directory is missing its private profile marker: " + marker);
        }
        boolean verify = Boolean.parseBoolean(request.getProperty("verify", "false"));
        System.setProperty("loadonce.prototype.verify", Boolean.toString(verify));
        System.setProperty("loadonce.prototype.world", "LoadOncePrototype");
        System.setProperty("loadonce.prototype.boot", mode);
        System.setProperty("loadonce.prototype.heapWitness", prepared.witness.toString());
        System.setProperty("loadonce.prototype.launchId", UUID.randomUUID().toString());
        System.out.println("LOADONCE_PROTOTYPE_BOOT mode=" + mode + " heap-witness=" + prepared.witness
                + " namespace-pid=" + kernelPid() + " callbacks=" + callbacks.preparationCallbacks()
                + "/" + callbacks.restorationCallbacks() + " application-initialization=fresh");
        String main = required(request, "main");
        if (!main.equals("net.fabricmc.devlaunchinjector.Main")) {
            throw new IOException("Prototype launch request must use the configured Fabric development launcher");
        }
        String[] applicationArguments = arguments(request);
        // The Fabric loader, all mods and every native game resource start only
        // after the successful restore. There is no application replay path.
        try {
            Class.forName(main, true, ClassLoader.getSystemClassLoader()).getMethod("main", String[].class)
                    .invoke(null, (Object) applicationArguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    private static Properties readRequest(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 1024 * 1024) {
            throw new IOException("Launch request must be an owner-only regular file of at most 1 MiB: " + path);
        }
        LauncherFiles.checkPrivate(path, false);
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) { properties.load(input); }
        return properties;
    }

    private static String required(Properties request, String key) throws IOException {
        String value = request.getProperty(key);
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IOException("Launch request is missing a valid " + key);
        }
        return value;
    }

    private static String[] arguments(Properties request) throws IOException {
        final int count;
        try { count = Integer.parseInt(required(request, "args.count")); }
        catch (NumberFormatException failure) { throw new IOException("Invalid launch argument count", failure); }
        if (count < 0 || count > 4096) throw new IOException("Launch argument count must be between 0 and 4096");
        String[] arguments = new String[count];
        for (int index = 0; index < count; index++) {
            String value = request.getProperty("args." + index);
            if (value == null || value.indexOf('\0') >= 0) throw new IOException("Invalid launch argument " + index);
            arguments[index] = value;
        }
        return arguments;
    }

    private static long kernelPid() throws IOException {
        String stat = Files.readString(Path.of("/proc/self/stat"));
        return Long.parseLong(stat.substring(0, stat.indexOf(' ')));
    }

    private static void installGameCheckpointVeto() throws Exception {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        final Class<?> core;
        try { core = Class.forName("jdk.crac.Core", false, loader); }
        catch (ClassNotFoundException stockJdk) { return; }
        Class<?> resource = Class.forName("jdk.crac.Resource", true, loader);
        Class<?> context = Class.forName("jdk.crac.Context", true, loader);
        Object global = core.getMethod("getGlobalContext").invoke(null);
        gameCheckpointVeto = Proxy.newProxyInstance(resource.getClassLoader(), new Class<?>[] {resource},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "LoadOnce prototype game checkpoint veto";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new IllegalStateException("Unknown checkpoint resource operation");
                        };
                    }
                    if (method.getName().equals("beforeCheckpoint")) {
                        throw new IOException("Game checkpoint denied: only the pre-application prototype boundary is supported");
                    }
                    if (method.getName().equals("afterRestore")) return null;
                    throw new IllegalStateException("Unknown checkpoint resource operation");
                });
        context.getMethod("register", resource).invoke(global, gameCheckpointVeto);
    }

    private static final class PreparedRuntime implements CheckpointResource {
        private final UUID witness = UUID.randomUUID();
        private int preparations;
        private int restorations;
        private boolean detached;

        @Override public String id() { return "pre-application-jvm"; }
        @Override public void checkpoint() { detached = true; preparations++; }
        @Override public void restore() {
            if (!detached) throw new IllegalStateException("Bootstrap was not prepared");
            detached = false;
            restorations++;
        }
        @Override public void stopSafely(Throwable cause) { detached = true; }
    }
}
