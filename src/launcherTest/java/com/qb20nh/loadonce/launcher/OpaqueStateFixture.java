package com.qb20nh.loadonce.launcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Ordinary JVM state witnesses; only an external harness can prove a process image restore. */
public final class OpaqueStateFixture {
    private static final AtomicInteger INITIALIZATIONS = new AtomicInteger();
    private static final byte[] PREFIX = "already-consumed|".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REMAINDER = "opaque-open-file-remainder".getBytes(StandardCharsets.UTF_8);

    private OpaqueStateFixture() { }

    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 1 || arguments.length == 2,
                "usage: OpaqueStateFixture <private-work-directory> [timeout-millis]");
        Path directory = Path.of(arguments[0]).toAbsolutePath().normalize();
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS),
                "fixture work directory must already exist and must not be a symbolic link: " + directory);
        require(Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------")),
                "fixture work directory must have mode 0700: " + directory);
        long timeout = arguments.length == 2 ? Long.parseLong(arguments[1]) : 120_000;
        require(timeout > 0, "timeout-millis must be positive");
        State state = new State();
        write(directory.resolve("initialized.properties"), properties(
                "heap_witness", state.witness, "initialization_count", INITIALIZATIONS.get()));
        Path resource = directory.resolve("opaque-resource.bin");
        Files.createFile(resource, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(resource, PREFIX);
        Files.write(resource, REMAINDER, StandardOpenOption.APPEND);

        try (FileChannel channel = FileChannel.open(resource, StandardOpenOption.READ)) {
            channel.position(PREFIX.length);
            state.start();
            try {
                require(state.parked.await(5, TimeUnit.SECONDS), "parked worker did not initialize");
                long warmupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (state.progress.get() < 3 || state.parkedThread.getState() != Thread.State.WAITING) {
                    require(System.nanoTime() < warmupDeadline, "background/parked workers did not become ready");
                    state.checkThreads();
                    Thread.sleep(10);
                }
                long readyProgress = state.progress.get();
                int identity = System.identityHashCode(state);
                long readyMillis = System.currentTimeMillis();
                write(directory.resolve("ready.properties"), properties(
                        "source_pid", ProcessHandle.current().pid(), "heap_witness", state.witness,
                        "initialization_count", INITIALIZATIONS.get(), "object_identity", identity,
                        "background_progress", readyProgress, "parked_stack_witness", state.stackWitness,
                        "resource_offset", channel.position(), "ready_millis", readyMillis));
                System.out.println("OPAQUE_STATE ready heap-witness=" + state.witness);

                Path signal = directory.resolve("continue.properties");
                long previousMillis = readyMillis;
                long maximumGapMillis = 0;
                while (true) {
                    long now = System.currentTimeMillis();
                    maximumGapMillis = Math.max(maximumGapMillis, now - previousMillis);
                    previousMillis = now;
                    require(now - readyMillis < timeout, "timed out waiting for " + signal);
                    state.checkThreads();
                    if (Files.isRegularFile(signal, LinkOption.NOFOLLOW_LINKS)) break;
                    Thread.sleep(10);
                }
                Properties continuation = new Properties();
                try (var reader = Files.newBufferedReader(signal, StandardCharsets.UTF_8)) {
                    continuation.load(reader);
                }
                String generation = continuation.getProperty("generation");
                require(generation != null && !generation.isBlank(), "continue.properties needs a generation token");
                long minimumPause = Long.parseLong(continuation.getProperty("minimum_pause_millis", "0"));
                require(minimumPause >= 0, "minimum_pause_millis must be nonnegative");
                require(maximumGapMillis >= minimumPause,
                        "observed polling gap " + maximumGapMillis + "ms is below requested " + minimumPause + "ms");
                require(INITIALIZATIONS.get() == 1 && System.identityHashCode(state) == identity,
                        "initialized heap object was replaced");
                require(state.self == state && state.values.equals(List.of(state.witness, "initialized-mod-state")),
                        "opaque heap graph changed");
                require(channel.isOpen() && channel.position() == PREFIX.length, "open file offset changed");
                ByteBuffer remainder = ByteBuffer.allocate(REMAINDER.length + 1);
                while (channel.read(remainder) > 0) { }
                require(remainder.position() == REMAINDER.length, "open file remainder length changed");
                remainder.flip();
                require(StandardCharsets.UTF_8.decode(remainder).toString()
                                .equals(new String(REMAINDER, StandardCharsets.UTF_8)),
                        "open file remainder content changed");

                long resumedProgress = state.progress.get();
                require(resumedProgress >= readyProgress, "background counter lost initialized progress");
                state.release = true;
                LockSupport.unpark(state.parkedThread);
                state.parkedThread.join(5_000);
                require(!state.parkedThread.isAlive() && state.returnedState == state
                                && state.stackWitness.equals(state.returnedStackWitness),
                        "parked thread did not continue with its captured stack locals");
                long progressDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (state.progress.get() <= resumedProgress) {
                    require(System.nanoTime() < progressDeadline, "background thread did not progress after continuation");
                    state.checkThreads();
                    Thread.sleep(10);
                }
                state.checkThreads();
                write(directory.resolve("result.properties"), properties(
                        "fixture_validation", "passed", "generation", generation,
                        "heap_witness", state.witness, "initialization_count", INITIALIZATIONS.get(),
                        "object_identity", System.identityHashCode(state), "ready_progress", readyProgress,
                        "continued_progress", state.progress.get(), "parked_stack_witness", state.returnedStackWitness,
                        "resource_offset_before", PREFIX.length, "resource_offset_after", channel.position(),
                        "observed_pause_millis", maximumGapMillis,
                        "elapsed_since_ready_millis", System.currentTimeMillis() - readyMillis));
                System.out.println("OPAQUE_STATE fixture-state-validated generation=" + generation
                        + " heap-witness=" + state.witness);
            } finally {
                state.stop = true;
                state.release = true;
                LockSupport.unpark(state.parkedThread);
                state.parkedThread.join(5_000);
                state.backgroundThread.join(5_000);
            }
        }
    }

    private static Properties properties(Object... entries) {
        Properties properties = new Properties();
        for (int index = 0; index < entries.length; index += 2) {
            properties.setProperty(entries[index].toString(), entries[index + 1].toString());
        }
        return properties;
    }

    private static void write(Path file, Properties properties) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createFile(temporary,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "Opaque state fixture evidence");
        }
        Files.move(temporary, file);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class State {
        private final String witness = UUID.randomUUID().toString();
        private final State self = this;
        private final List<String> values = new ArrayList<>(List.of(witness, "initialized-mod-state"));
        private final AtomicLong progress = new AtomicLong();
        private final CountDownLatch parked = new CountDownLatch(1);
        private final AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        private final Thread backgroundThread;
        private final Thread parkedThread;
        private volatile String stackWitness;
        private volatile String returnedStackWitness;
        private volatile State returnedState;
        private volatile boolean release;
        private volatile boolean stop;

        private State() {
            INITIALIZATIONS.incrementAndGet();
            backgroundThread = new Thread(() -> {
                try {
                    while (!stop) {
                        progress.incrementAndGet();
                        Thread.sleep(10);
                    }
                } catch (Throwable failure) {
                    threadFailure.set(failure);
                }
            }, "opaque-background-worker");
            parkedThread = new Thread(() -> {
                State localState = this;
                String localWitness = UUID.randomUUID().toString();
                stackWitness = localWitness;
                parked.countDown();
                while (!release) LockSupport.park();
                returnedState = localState;
                returnedStackWitness = localWitness;
            }, "opaque-parked-worker");
        }

        private void start() {
            backgroundThread.start();
            parkedThread.start();
        }

        private void checkThreads() {
            require(threadFailure.get() == null, "background worker failed: " + threadFailure.get());
            require(backgroundThread.isAlive(), "background worker unexpectedly stopped");
        }
    }
}
