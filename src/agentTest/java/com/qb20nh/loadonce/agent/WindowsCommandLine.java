package com.qb20nh.loadonce.agent;

import java.util.ArrayList;
import java.util.List;

/** Feeds literal test arguments through Windows ProcessBuilder's legacy token handling. */
final class WindowsCommandLine {
    private WindowsCommandLine() { }

    static List<String> forProcessBuilder(List<String> command) {
        if (!System.getProperty("os.name").startsWith("Windows")) return command;
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
        return tokens;
    }
}
