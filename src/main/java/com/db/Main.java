package com.db;

import com.db.command.ResponseFormatter;
import com.db.command.TextCommandDispatcher;
import com.db.config.DbConfig;
import com.db.storage.InMemoryVectorDB;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Локальная консоль vecDB.
 *
 * <pre>
 * java -cp target/classes com.db.Main [--data ./data] [--name vecdb] [--save-interval 5] [--no-autosave]
 * </pre>
 *
 * Команды читаются из stdin построчно (можно передать файл: {@code ... com.db.Main < script.txt}).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        DbConfig config;
        try {
            config = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: Main [--data DIR] [--name NAME] [--save-interval SECONDS] [--no-autosave]");
            System.exit(2);
            return;
        }

        InMemoryVectorDB db = InMemoryVectorDB.open(config);
        Thread hook = new Thread(db::close, "vecdb-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        TextCommandDispatcher dispatcher = new TextCommandDispatcher(db);
        boolean interactive = System.console() != null;
        System.out.println("vecDB '" + config.name() + "' — data: " + config.dataPath().toAbsolutePath().normalize()
                + ", collections: " + db.listCollections().size()
                + (config.autoSave() ? ", autosave every " + config.saveInterval().toSeconds() + "s" : ", autosave off"));
        System.out.println("Type HELP for commands, EXIT to quit.");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            if (interactive) {
                System.out.print("vecdb> ");
                System.out.flush();
            }
            String line = in.readLine();
            if (line == null) {
                break;
            }
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!interactive) {
                System.out.println("vecdb> " + line);
            }
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.equals("EXIT") || upper.equals("QUIT")) {
                break;
            }
            List<String> tokens = Arrays.asList(line.split("\\s+"));
            try {
                System.out.println(ResponseFormatter.format(dispatcher.dispatch(tokens)));
            } catch (RuntimeException e) {
                System.out.println("ERR " + e.getMessage());
            }
        }

        Runtime.getRuntime().removeShutdownHook(hook);
        db.close();
        System.out.println("bye");
    }

    private static DbConfig parseArgs(String[] args) {
        DbConfig.Builder b = DbConfig.builder();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> b.dataDirectory(value(args, ++i, "--data"));
                case "--name" -> b.name(value(args, ++i, "--name"));
                case "--no-autosave" -> b.autoSave(false);
                case "--save-interval" -> {
                    String v = value(args, ++i, "--save-interval");
                    try {
                        b.saveInterval(Duration.ofSeconds(Long.parseLong(v)));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--save-interval expects seconds, got '" + v + "'");
                    }
                }
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        return b.build();
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[i];
    }
}
