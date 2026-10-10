package com.qb20nh.loadonce.launcher;

import java.util.ArrayList;
import java.util.List;

/** Preserves literal native-executable arguments through Windows ProcessBuilder. */
final class WindowsCommandLine {
    private WindowsCommandLine() { }

    static List<String> forProcessBuilder(List<String> command) {
        if (!System.getProperty("os.name").startsWith("Windows")) return command;
        // ProcessImpl's strict mode treats a whole quoted argument as prequoted
        // input. Its legacy mode appends these explicitly CRT-encoded tokens.
        if ("false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands"))) {
            throw new IllegalArgumentException("Literal Windows arguments require legacy ProcessBuilder token handling");
        }
        List<String> tokens = new ArrayList<>(command.size());
        tokens.add(command.get(0));
        for (String argument : command.subList(1, command.size())) {
            StringBuilder token = new StringBuilder("\"");
            int backslashes = 0;
            for (int index = 0; index < argument.length(); index++) {
                char character = argument.charAt(index);
                if (character == '\\') {
                    backslashes++;
                    continue;
                }
                int escapes = character == '"' ? 2 * backslashes + 1 : backslashes;
                token.append("\\".repeat(escapes)).append(character);
                backslashes = 0;
            }
            token.append("\\".repeat(2 * backslashes)).append('"');
            tokens.add(token.toString());
        }
        return List.copyOf(tokens);
    }
}
