package com.qb20nh.loadonce.supervisor;

import com.qb20nh.loadonce.launcher.ExternalSupervisor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Private bootstrap entrypoint for the native java/javaw executables. */
public final class SupervisorMain {
    private static final int INPUT_MAGIC = 0x4c4f5331;
    private static final int COMPLETION_MAGIC = 0x4c4f4431;
    private static final int MAX_ENVELOPE = 8 * 1024 * 1024;
    private static final int MAX_ITEMS = 65536;

    private SupervisorMain() { }

    public static void main(String[] arguments) {
        int exit = 125;
        try {
            Map<String, String> options = options(arguments);
            FileDescriptor incoming = descriptor(options.get("--input-descriptor"));
            FileDescriptor completion = descriptor(options.get("--completion-descriptor"));
            Envelope envelope;
            try (var input = new DataInputStream(new FileInputStream(incoming))) {
                envelope = readEnvelope(input);
            }
            Policy policy = readPolicy(Path.of(options.get("--policy")));
            exit = ExternalSupervisor.execute(envelope.backend(), envelope.agent(), policy.cache(), policy.aot(),
                    envelope.arguments(), policy.inputs(), envelope.environment(), policy.verbose());
            // A process exit alone cannot release native ownership: a crashed
            // controller may still have a live game or helper. This packet is
            // written only after the application has completed normally.
            try (var output = new DataOutputStream(new FileOutputStream(completion))) {
                output.writeInt(COMPLETION_MAGIC);
                output.writeInt(exit);
                output.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exit = 130;
            System.err.println("loadonce: supervisor interrupted");
        } catch (IOException | ReflectiveOperationException | RuntimeException failure) {
            // Launch arguments and the original environment can contain account
            // credentials. Never interpolate exception messages or their data.
            System.err.println("loadonce: supervisor startup failed");
        }
        System.exit(exit);
    }

    private static Map<String, String> options(String[] arguments) throws IOException {
        Set<String> keys = Set.of("--input-descriptor", "--completion-descriptor", "--policy");
        if (arguments.length != 6) throw new IOException("invalid bootstrap arguments");
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (!keys.contains(arguments[index]) || result.putIfAbsent(arguments[index], arguments[index + 1]) != null) {
                throw new IOException("invalid bootstrap arguments");
            }
        }
        if (!result.keySet().equals(keys)) throw new IOException("missing bootstrap argument");
        return result;
    }

    private static FileDescriptor descriptor(String value) throws ReflectiveOperationException, IOException {
        long number;
        try {
            number = Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IOException("invalid pipe descriptor");
        }
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        if (number < 3 || (!windows && number > Integer.MAX_VALUE)) throw new IOException("invalid pipe descriptor");
        FileDescriptor result = new FileDescriptor();
        Field field = FileDescriptor.class.getDeclaredField(windows ? "handle" : "fd");
        field.setAccessible(true);
        if (windows) field.setLong(result, number);
        else field.setInt(result, (int) number);
        return result;
    }

    record Envelope(Path backend, Path agent, List<String> arguments, Map<String, String> environment) { }

    static Envelope readEnvelope(DataInputStream input) throws IOException {
        Wire wire = new Wire(input);
        if (wire.integer() != INPUT_MAGIC) throw new IOException("invalid bootstrap protocol");
        Path backend = Path.of(wire.string());
        Path agent = Path.of(wire.string());
        if (!backend.isAbsolute() || !agent.isAbsolute()) throw new IOException("relative bootstrap path");
        int argumentCount = wire.count();
        List<String> arguments = new ArrayList<>(argumentCount);
        for (int index = 0; index < argumentCount; index++) arguments.add(wire.string());
        int environmentCount = wire.count();
        Map<String, String> environment = new LinkedHashMap<>();
        for (int index = 0; index < environmentCount; index++) {
            String key = wire.string();
            String value = wire.string();
            if (key.isEmpty() || key.indexOf('=', 1) >= 0 || environment.putIfAbsent(key, value) != null) {
                throw new IOException("invalid environment entry");
            }
        }
        if (input.read() != -1) throw new IOException("trailing bootstrap data");
        return new Envelope(backend, agent, List.copyOf(arguments), Map.copyOf(environment));
    }

    private static final class Wire {
        private final DataInputStream input;
        private int remaining = MAX_ENVELOPE;

        private Wire(DataInputStream input) { this.input = input; }

        private void consume(int amount) throws IOException {
            if (amount < 0 || amount > remaining) throw new IOException("bootstrap envelope too large");
            remaining -= amount;
        }

        private int integer() throws IOException {
            consume(4);
            return input.readInt();
        }

        private int count() throws IOException {
            int count = integer();
            if (count < 0 || count > MAX_ITEMS) throw new IOException("bootstrap item count too large");
            return count;
        }

        private String string() throws IOException {
            int size = integer();
            consume(size);
            byte[] bytes = input.readNBytes(size);
            if (bytes.length != size) throw new IOException("truncated bootstrap string");
            String value = utf8(bytes);
            if (value.indexOf('\0') >= 0) throw new IOException("invalid bootstrap string");
            return value;
        }
    }

    record Policy(String aot, Path cache, List<Path> inputs, boolean verbose) { }

    static Policy readPolicy(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(1024 * 1024 + 1);
        }
        if (bytes.length > 1024 * 1024) throw new IOException("policy too large");
        Map<String, String> fields = new HashMap<>();
        for (String line : utf8(bytes).split("\n", -1)) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator < 1 || line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0
                    || fields.putIfAbsent(line.substring(0, separator), line.substring(separator + 1)) != null) {
                throw new IOException("invalid policy entry");
            }
        }
        String aot = fields.remove("aot");
        if (aot == null || !Set.of("off", "auto", "train").contains(aot)) throw new IOException("invalid AOT policy");
        Path cache = absolute(fields.remove("cache"));
        int count;
        try {
            count = Integer.parseInt(fields.remove("input.count"));
        } catch (NumberFormatException invalid) {
            throw new IOException("invalid policy inputs");
        }
        if (count < 0 || count > 1024) throw new IOException("too many policy inputs");
        List<Path> inputs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) inputs.add(absolute(fields.remove("input." + index)));
        String verbose = fields.remove("verbose");
        if (verbose != null && !verbose.equals("true") && !verbose.equals("false")) throw new IOException("invalid diagnostics policy");
        if (!fields.isEmpty()) throw new IOException("unknown policy entries");
        return new Policy(aot, cache, List.copyOf(inputs), Boolean.parseBoolean(verbose));
    }

    private static Path absolute(String value) throws IOException {
        if (value == null || value.isEmpty()) throw new IOException("missing policy path");
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < 32 || value.charAt(index) == 127) throw new IOException("invalid policy path");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IOException("relative policy path");
        return path;
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
