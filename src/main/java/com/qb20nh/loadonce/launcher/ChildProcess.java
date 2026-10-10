package com.qb20nh.loadonce.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Runs an argv directly, with an explicit environment and bounded log capture. */
public final class ChildProcess {
    private static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final long POLL_MILLIS = 25;
    private static final long STOP_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long DESCENDANT_STOP_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long SUPERVISED_SHUTDOWN_NANOS = TimeUnit.MILLISECONDS.toNanos(2500);

    private ChildProcess() {
    }

    public record Result(long pid, int exitCode, String output) {
    }

    /**
     * Uses exactly {@code environment}; caller environment variables are cleared.
     * A null log inherits standard IO and returns an empty output string. Otherwise
     * the log must be a private, owned, regular file in a private parent directory.
     */
    public static Result run(List<String> command, Path cwd,
                             Map<String, String> environment, Path log,
                             Duration timeout) throws IOException, InterruptedException {
        return execute(command, cwd, environment, log, null, timeout, true, false, false);
    }

    /**
     * Supervises an external application without adding output or replaying it.
     * Null cwd, log and timeout inherit the working directory and raw standard
     * handles without an application deadline. On JVM shutdown, a grace period
     * lets an already-broadcast OS signal run the application's shutdown hooks
     * before remaining children are stopped. Ordinary exit releases helpers.
     */
    public static Result runSupervised(List<String> command, Path cwd,
                                       Map<String, String> environment, Path log,
                                       Duration timeout) throws IOException, InterruptedException {
        return execute(command, cwd, environment, log, null, timeout, true, false, true);
    }

    /** Linux fixture execution with an isolated process group, including orphan cleanup. */
    public static Result runIsolated(List<String> command, Path cwd,
                                     Map<String, String> environment, Path log,
                                     Duration timeout) throws IOException, InterruptedException {
        return execute(command, cwd, environment, log, null, timeout, true, true, false);
    }

    /** Captures stdout and stderr separately while bounding their combined bytes. */
    public static Result run(List<String> command, Path cwd,
                             Map<String, String> environment, Path stdout, Path stderr,
                             Duration timeout) throws IOException, InterruptedException {
        if (stdout == null || stderr == null
                || stdout.toAbsolutePath().normalize().equals(stderr.toAbsolutePath().normalize())) {
            throw new IOException("Separate child output requires two distinct log files");
        }
        return execute(command, cwd, environment, stdout, stderr, timeout, false, false, false);
    }

    /** Separate-stream capture with the external supervisor's shutdown grace. */
    public static Result runSupervised(List<String> command, Path cwd,
                                       Map<String, String> environment, Path stdout, Path stderr,
                                       Duration timeout) throws IOException, InterruptedException {
        if (stdout == null || stderr == null
                || stdout.toAbsolutePath().normalize().equals(stderr.toAbsolutePath().normalize())) {
            throw new IOException("Separate child output requires two distinct log files");
        }
        return execute(command, cwd, environment, stdout, stderr, timeout, false, false, true);
    }

    private static Result execute(List<String> command, Path cwd,
                                  Map<String, String> environment, Path stdout, Path stderr,
                                  Duration timeout, boolean merged, boolean isolated,
                                  boolean supervised) throws IOException, InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Child execution interrupted");
        }
        ProcessBuilder builder = builder(command, cwd, environment);
        Path groupKill = null;
        if (isolated) {
            Path setsid = trustedControl("setsid");
            groupKill = trustedControl("kill");
            if (!Files.isRegularFile(Path.of("/proc/self/stat"))) {
                throw new IOException("Linux process-group verification is unavailable");
            }
            List<String> grouped = new ArrayList<>(List.of(setsid.toString(), "--"));
            grouped.addAll(builder.command());
            builder.command(grouped);
        }
        long budget = timeoutBudget(timeout);
        Path output = stdout == null ? null : prepareLog(stdout);
        Path errors = stderr == null ? null : prepareLog(stderr);
        List<Path> logs = new ArrayList<>();
        if (output != null) logs.add(output);
        if (errors != null) logs.add(errors);
        if (output == null) {
            builder.inheritIO();
        } else {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectErrorStream(merged);
            builder.redirectOutput(output.toFile());
            if (errors != null) builder.redirectError(errors.toFile());
        }

        ProcessTree tree = new ProcessTree(groupKill, supervised);
        Thread shutdown = new Thread(tree::stopOnShutdown, "loadonce-child-cleanup");
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
        } catch (IllegalStateException | SecurityException failure) {
            throw new IOException("Cannot register child cleanup");
        }

        try {
            Process child = tree.start(builder);
            long started = System.nanoTime();
            while (true) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Child execution interrupted");
                }
                tree.sample();
                checkOutputBound(logs);
                if (!child.isAlive()) {
                    break;
                }
                long remaining = budget == Long.MAX_VALUE
                        ? Long.MAX_VALUE : budget - (System.nanoTime() - started);
                if (remaining <= 0) {
                    throw new IOException("Child process timed out");
                }
                child.waitFor(Math.min(TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS), remaining),
                        TimeUnit.NANOSECONDS);
            }
            int exitCode = child.waitFor();
            if (isolated) tree.stop();
            String captured = readOutputs(logs);
            tree.release();
            return new Result(child.pid(), exitCode, captured);
        } catch (InterruptedException failure) {
            stopAfterFailure(tree);
            throw failure;
        } catch (IOException | RuntimeException | Error failure) {
            stopAfterFailure(tree);
            throw failure;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException | SecurityException ignored) {
                // Shutdown owns cleanup once hooks have begun running.
            }
        }
    }

    private static Path trustedControl(String name) throws IOException {
        try {
            Path selected = Path.of("/usr/bin", name);
            Path executable = selected.toRealPath();
            String caller = LauncherFiles.ownerId();
            for (Path start : List.of(selected, executable)) {
                for (Path node = start; node != null; node = node.getParent()) {
                    Object uid = Files.getAttribute(node, "unix:uid", LinkOption.NOFOLLOW_LINKS);
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(node, LinkOption.NOFOLLOW_LINKS);
                    boolean callerOwned = uid instanceof Number owner && Long.toString(owner.longValue()).equals(caller);
                    // Managed containers can map immutable system files to a
                    // foreign UID rather than zero. Verify their immutability to
                    // this caller, including every ancestor, instead of assuming
                    // a particular namespace mapping for root.
                    if (!(uid instanceof Number) || (!caller.equals("0") && (callerOwned || Files.isWritable(node)))
                            || (!Files.isSymbolicLink(node) && (permissions.contains(PosixFilePermission.GROUP_WRITE)
                            || permissions.contains(PosixFilePermission.OTHERS_WRITE)))) {
                        throw new IOException("Trusted process-group controls are unavailable");
                    }
                }
            }
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw new IOException("Trusted process-group controls are unavailable");
            }
            // Keep the utility alias for systems where setsid/kill are applets.
            return selected;
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            throw new IOException("Trusted process-group controls are unavailable");
        }
    }

    private static ProcessBuilder builder(List<String> command, Path cwd,
                                          Map<String, String> environment) throws IOException {
        try {
            if (command == null || command.isEmpty() || environment == null) {
                throw new IllegalArgumentException();
            }
            List<String> argv = new ArrayList<>(List.copyOf(command));
            if (argv.get(0).isEmpty() || argv.stream().anyMatch(value -> value.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException();
            }
            Map<String, String> exactEnvironment = Map.copyOf(environment);
            // ProcessBuilder searches the parent JVM's PATH before applying the
            // child's environment. Resolve explicitly so probes and launches use
            // the same selected executable, including a custom clean PATH.
            argv.set(0, resolveExecutable(argv.get(0), cwd, exactEnvironment).toString());
            ProcessBuilder builder = new ProcessBuilder(WindowsCommandLine.forProcessBuilder(argv));
            builder.environment().clear();
            builder.environment().putAll(exactEnvironment);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            return builder;
        } catch (IllegalArgumentException | NullPointerException | SecurityException failure) {
            throw new IOException("Invalid child process configuration");
        }
    }

    private static Path resolveExecutable(String name, Path cwd, Map<String, String> environment) throws IOException {
        try {
            Path workingDirectory = cwd == null ? Path.of("").toAbsolutePath() : cwd.toAbsolutePath();
            Path supplied = Path.of(name);
            if (supplied.isAbsolute() || name.indexOf('/') >= 0 || name.indexOf(File.separatorChar) >= 0) {
                Path candidate = supplied.isAbsolute() ? supplied : workingDirectory.resolve(supplied);
                Path executable = executableCandidate(candidate);
                if (executable != null) return executable;
            } else {
                String path = environmentPath(environment);
                for (String component : path.split(Pattern.quote(File.pathSeparator), -1)) {
                    Path directory = Path.of(component.isEmpty() ? "." : component);
                    if (!directory.isAbsolute()) directory = workingDirectory.resolve(directory);
                    Path candidate = directory.resolve(name);
                    Path executable = executableCandidate(candidate);
                    if (executable != null) return executable;
                }
            }
        } catch (InvalidPathException | SecurityException failure) {
            throw new IOException("Cannot resolve child executable");
        }
        throw new IOException("Cannot resolve child executable");
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static String environmentPath(Map<String, String> environment) {
        if (environment.containsKey("PATH")) return environment.get("PATH");
        if (windows()) {
            for (var entry : environment.entrySet()) {
                if (entry.getKey().equalsIgnoreCase("PATH")) return entry.getValue();
            }
        }
        return windows() ? "" : "/bin:/usr/bin";
    }

    private static Path executableCandidate(Path candidate) {
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath();
        if (windows()) {
            // Native java/javaw names may omit .exe. Batch scripts still require
            // an explicit interpreter; this helper never inserts a shell.
            Path exe = candidate.resolveSibling(candidate.getFileName() + ".exe");
            if (Files.isRegularFile(exe) && Files.isExecutable(exe)) return exe.toAbsolutePath();
        }
        return null;
    }

    private static long timeoutBudget(Duration timeout) throws IOException {
        if (timeout == null) {
            return Long.MAX_VALUE;
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IOException("Child timeout must be positive");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Path prepareLog(Path log) throws IOException {
        Path path = log.toAbsolutePath().normalize();
        if (path.getParent() == null || path.getFileName() == null) {
            throw new IOException("Invalid child log path");
        }
        LauncherFiles.privateDirectory(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            LauncherFiles.checkPrivate(path, false);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            LauncherFiles.checkPrivate(path, false);
            channel.truncate(0);
        }
        return path;
    }

    private static void checkOutputBound(List<Path> paths) throws IOException {
        long total = 0;
        for (Path path : paths) {
            long size = Files.size(path);
            if (size > MAX_OUTPUT_BYTES - total) throw new IOException("Child output exceeded capture limit");
            total += size;
        }
    }

    private static String readOutputs(List<Path> paths) throws IOException {
        StringBuilder combined = new StringBuilder();
        int bytes = 0;
        for (Path path : paths) {
            Captured output = readOutput(path, MAX_OUTPUT_BYTES - bytes);
            bytes += output.bytes();
            combined.append(output.text());
        }
        return combined.toString();
    }

    private record Captured(int bytes, String text) { }

    private static Captured readOutput(Path path, int limit) throws IOException {
        LauncherFiles.checkPrivate(path, false);
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() > limit) {
                throw new IOException("Child output exceeded capture limit");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (channel.read(buffer) != -1) {
                int length = buffer.position();
                if (output.size() > limit - length) {
                    throw new IOException("Child output exceeded capture limit");
                }
                output.write(buffer.array(), 0, length);
                buffer.clear();
            }
            return new Captured(output.size(), output.toString(StandardCharsets.UTF_8));
        }
    }

    private static void stopAfterFailure(ProcessTree tree) {
        try {
            tree.stop();
        } catch (IOException ignored) {
            // Preserve the failure that initiated cleanup.
        }
    }

    /** Remember children before helpers can exit or be reparented by an engine. */
    private static final class ProcessTree {
        private final Map<Long, ProcessHandle> observed = new LinkedHashMap<>();
        private final Set<Long> isolatedGroups = new LinkedHashSet<>();
        private final Path groupKill;
        private final boolean supervised;
        private Process root;
        private boolean closing;
        private boolean groupConfirmed;

        private ProcessTree(Path groupKill, boolean supervised) {
            this.groupKill = groupKill;
            this.supervised = supervised;
        }

        synchronized Process start(ProcessBuilder builder) throws IOException {
            if (closing) {
                throw new IOException("Child launch interrupted by shutdown");
            }
            try {
                root = builder.start();
            } catch (IOException | SecurityException failure) {
                throw new IOException("Cannot start child process");
            }
            observed.put(root.pid(), root.toHandle());
            sample();
            return root;
        }

        synchronized void sample() {
            if (groupKill != null && root != null && !groupConfirmed) {
                groupConfirmed = kernelGroup(root.pid(), root.pid());
                if (groupConfirmed) isolatedGroups.add(root.pid());
            }
            for (ProcessHandle process : new ArrayList<>(observed.values())) {
                if (process.isAlive()) {
                    try (Stream<ProcessHandle> descendants = process.descendants()) {
                        descendants.forEach(child -> observed.put(child.pid(), child));
                    }
                }
            }
            if (groupKill != null) {
                // A nested Java fixture driver may create another isolated
                // engine session. Remember its verified group while its leader
                // is still a descendant, so abrupt outer cancellation also
                // reaches helpers orphaned inside that session.
                for (ProcessHandle process : observed.values()) {
                    if (process.isAlive() && kernelGroup(process.pid(), process.pid())) {
                        isolatedGroups.add(process.pid());
                    }
                }
            }
        }

        private static boolean kernelGroup(long process, long group) {
            if (group <= 1) return false;
            try {
                String stat = Files.readString(Path.of("/proc", Long.toString(process), "stat"));
                int nameEnd = stat.lastIndexOf(')');
                if (nameEnd < 0) return false;
                String[] fields = stat.substring(nameEnd + 1).trim().split("\\s+");
                // After comm: state, ppid, pgrp, session. setsid must retain its PID.
                return fields.length >= 4 && Long.parseLong(fields[2]) == group
                        && Long.parseLong(fields[3]) == group;
            } catch (IOException | NumberFormatException | SecurityException missing) {
                return false;
            }
        }

        private boolean groupPresent(long group) {
            try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
                return processes.anyMatch(process -> kernelGroup(process.pid(), group));
            }
        }

        private void stopGroups(Process child) throws IOException, InterruptedException {
            if (groupKill == null || child == null) return;
            long group = child.pid();
            List<Long> groups;
            synchronized (this) {
                if (!groupConfirmed) {
                    // A very short-lived setsid root can leave an orphan before
                    // the first sample. Verify that group's actual kernel state.
                    groupConfirmed = kernelGroup(group, group) || groupPresent(group);
                }
                if (groupConfirmed) isolatedGroups.add(group);
                groups = new ArrayList<>(isolatedGroups);
            }
            for (int index = groups.size() - 1; index >= 0; index--) signalGroup(groups.get(index));
        }

        private void signalGroup(long group) throws IOException, InterruptedException {
            ProcessBuilder control = new ProcessBuilder(groupKill.toString(), "-KILL", "--", "-" + group);
            control.environment().clear();
            control.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "LANG", "C"));
            control.redirectErrorStream(true);
            control.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process signal = control.start();
            try {
                signal.getOutputStream().close();
                if (!signal.waitFor(1, TimeUnit.SECONDS)) throw new IOException("Process-group control timed out");
                if (signal.exitValue() != 0 && groupPresent(group)) {
                    throw new IOException("Unable to stop isolated process group");
                }
            } finally {
                if (signal.isAlive()) signal.destroyForcibly();
            }
        }

        private synchronized List<ProcessHandle> snapshot() {
            closing = true;
            sample();
            return new ArrayList<>(observed.values());
        }

        synchronized void release() {
            closing = true;
            observed.clear();
            isolatedGroups.clear();
            root = null;
        }

        void stop() throws IOException {
            boolean interrupted = Thread.interrupted();
            long started = System.nanoTime();
            long reapedAt = 0;
            try {
                while (true) {
                    List<ProcessHandle> processes = snapshot();
                    Process child;
                    synchronized (this) {
                        child = root;
                    }
                    IOException groupFailure = null;
                    try {
                        stopGroups(child);
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    } catch (IOException failure) {
                        groupFailure = failure;
                    }
                    boolean descendantsAlive = false;
                    for (int index = processes.size() - 1; index >= 0; index--) {
                        ProcessHandle process = processes.get(index);
                        if (process.isAlive()) {
                            descendantsAlive |= child == null || process.pid() != child.pid();
                            process.destroyForcibly();
                        }
                    }
                    if (child == null) {
                        return;
                    }
                    try {
                        // waitFor reaps our direct child even after isAlive is false.
                        if (child.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                            if (reapedAt == 0) {
                                reapedAt = System.nanoTime();
                            }
                            // Grandchildren cannot be reaped here. ProcessHandle can
                            // also report adopted Linux zombies as alive, so their
                            // disappearance is best effort after the direct child is reaped.
                            if (!descendantsAlive || System.nanoTime() - reapedAt >= DESCENDANT_STOP_NANOS) {
                                if (groupFailure != null) throw groupFailure;
                                return;
                            }
                            TimeUnit.MILLISECONDS.sleep(POLL_MILLIS);
                        }
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                    if (System.nanoTime() - started >= STOP_NANOS) {
                        if (reapedAt != 0) {
                            return;
                        }
                        throw new IOException("Unable to stop child process");
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void stopOnShutdown() {
            if (supervised) {
                boolean interrupted = Thread.interrupted();
                long started = System.nanoTime();
                Process child;
                synchronized (this) {
                    closing = true;
                    child = root;
                }
                while (child != null && child.isAlive()
                        && System.nanoTime() - started < SUPERVISED_SHUTDOWN_NANOS) {
                    sample();
                    try {
                        child.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException cancelled) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            stopAfterFailure(this);
        }
    }
}
