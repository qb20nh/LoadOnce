package net.fabricmc.devlaunchinjector;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Test-only entry point; this class is never included in the production launcher. */
public final class Main {
    private static final String INITIALIZATION_MODE = initialize();

    private Main() {}

    private static String initialize() {
        try {
            Files.writeString(Path.of(System.getenv("LOADONCE_TEST_INITIALIZATIONS")), "initialized\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return System.getProperty("loadonce.prototype.boot", "missing");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static void main(String[] arguments) throws IOException {
        Files.writeString(Path.of(System.getenv("LOADONCE_TEST_INVOCATIONS")), "invoked\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (DataOutputStream output = new DataOutputStream(
                Files.newOutputStream(Path.of(System.getenv("LOADONCE_TEST_ARGUMENTS"))))) {
            output.writeLong(ProcessHandle.current().pid());
            output.writeUTF(INITIALIZATION_MODE);
            output.writeUTF(System.getProperty("loadonce.prototype.boot", "missing"));
            output.writeUTF(System.getProperty("loadonce.prototype.heapWitness", "missing"));
            output.writeUTF(System.getProperty("loadonce.prototype.verify", "missing"));
            output.writeUTF(System.getProperty("loadonce.prototype.world", "missing"));
            output.writeInt(arguments.length);
            for (String argument : arguments) output.writeUTF(argument);
        }
        if ("true".equals(System.getenv("LOADONCE_TEST_FAILURE"))) {
            throw new IOException("Application fixture failed after startup");
        }
    }
}
