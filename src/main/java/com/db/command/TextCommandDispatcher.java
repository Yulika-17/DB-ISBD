package com.db.command;

import com.db.api.ICollection;
import com.db.api.ICommandDispatcher;
import com.db.api.IVectorDB;
import com.db.config.CollectionConfig;
import com.db.config.DistanceMetric;
import com.db.exception.IdNotFoundException;
import com.db.exception.StorageException;
import com.db.model.VectorEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Текстовый протокол поверх {@link IVectorDB}. Команда — список токенов, первый токен — имя команды
 * (регистр не важен). Имя коллекции передаётся явно в каждой команде, без состояния сессии,
 * поэтому тот же диспетчер подойдёт для REST на этапе 3.
 *
 * <p>Возвращает доменные объекты ({@link VectorEntry}, {@code List<SearchResult>}, {@code DbStats}, …);
 * превращение в текст — {@link ResponseFormatter}. Ошибки — исключения ({@code DbException},
 * {@code IllegalArgumentException} для неверного синтаксиса).
 */
public final class TextCommandDispatcher implements ICommandDispatcher {

    public static final String HELP = """
            Collections:
              CREATE_COLLECTION <name> <dimension> [COSINE|EUCLIDEAN|DOT_PRODUCT] [maxVectors]
              DROP_COLLECTION   <name>
              LIST_COLLECTIONS
              DESCRIBE          <name>
            Vectors:
              PUT    <collection> <id> <v1> ... <vN> [payload text...]
              GET    <collection> <id>
              DELETE <collection> <id>
              EXISTS <collection> <id>
              SIZE   <collection>
              CLEAR  <collection>
              SEARCH <collection> <v1> ... <vN> <k>
            Database:
              STATS  [collection]
              SAVE
              LOAD               (перечитать с диска, несохранённые изменения теряются)
              HELP""";

    private final IVectorDB db;

    public TextCommandDispatcher(IVectorDB db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    @Override
    public Object dispatch(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("empty command, type HELP");
        }
        String op = command.get(0).toUpperCase(Locale.ROOT);
        List<String> args = command.subList(1, command.size());
        return switch (op) {
            case "HELP" -> HELP;
            case "CREATE_COLLECTION" -> createCollection(args);
            case "DROP_COLLECTION" -> {
                arity(args, 1, "DROP_COLLECTION <name>");
                yield db.dropCollection(args.get(0));
            }
            case "LIST_COLLECTIONS" -> {
                arity(args, 0, "LIST_COLLECTIONS");
                yield db.listCollections();
            }
            case "DESCRIBE" -> {
                arity(args, 1, "DESCRIBE <name>");
                yield db.getCollection(args.get(0)).config();
            }
            case "PUT" -> put(args);
            case "GET" -> {
                arity(args, 2, "GET <collection> <id>");
                String id = args.get(1);
                yield collection(args).getById(id).orElseThrow(() -> new IdNotFoundException(id));
            }
            case "DELETE" -> {
                arity(args, 2, "DELETE <collection> <id>");
                yield collection(args).delete(args.get(1));
            }
            case "EXISTS" -> {
                arity(args, 2, "EXISTS <collection> <id>");
                yield collection(args).exists(args.get(1));
            }
            case "SIZE" -> {
                arity(args, 1, "SIZE <collection>");
                yield collection(args).size();
            }
            case "CLEAR" -> {
                arity(args, 1, "CLEAR <collection>");
                collection(args).clear();
                yield "OK";
            }
            case "SEARCH" -> search(args);
            case "STATS" -> {
                if (args.size() > 1) throw usage("STATS [collection]");
                yield args.isEmpty() ? db.stats() : collection(args).stats();
            }
            case "SAVE" -> {
                arity(args, 0, "SAVE");
                try {
                    db.save();
                } catch (IOException e) {
                    throw new StorageException("save failed: " + e.getMessage(), e);
                }
                yield "OK";
            }
            case "LOAD" -> {
                arity(args, 0, "LOAD");
                try {
                    db.load();
                } catch (IOException e) {
                    throw new StorageException("load failed: " + e.getMessage(), e);
                }
                yield "OK";
            }
            default -> throw new IllegalArgumentException("unknown command '" + command.get(0) + "', type HELP");
        };
    }

    private Object createCollection(List<String> args) {
        if (args.size() < 2 || args.size() > 4) {
            throw usage("CREATE_COLLECTION <name> <dimension> [metric] [maxVectors]");
        }
        CollectionConfig.Builder b = CollectionConfig.builder(args.get(0), parseInt(args.get(1), "dimension"));
        if (args.size() >= 3) {
            b.metric(parseMetric(args.get(2)));
        }
        if (args.size() == 4) {
            b.maxVectors(parseInt(args.get(3), "maxVectors"));
        }
        return db.createCollection(b.build()).config();
    }

    /** PUT coll id v1..vN [payload...]: размерность берётся из коллекции, остаток строки — payload. */
    private Object put(List<String> args) {
        if (args.size() < 2) {
            throw usage("PUT <collection> <id> <v1> ... <vN> [payload text...]");
        }
        ICollection c = collection(args);
        int dim = c.dimension();
        if (args.size() < 2 + dim) {
            throw new IllegalArgumentException("collection '" + c.name() + "' expects " + dim
                    + " vector components, got " + (args.size() - 2));
        }
        double[] vector = parseVector(args.subList(2, 2 + dim));
        List<String> rest = args.subList(2 + dim, args.size());
        byte[] payload = rest.isEmpty() ? null : String.join(" ", rest).getBytes(StandardCharsets.UTF_8);
        c.put(new VectorEntry(args.get(1), vector, payload));
        return "OK";
    }

    /** SEARCH coll v1..vN k */
    private Object search(List<String> args) {
        if (args.size() < 3) {
            throw usage("SEARCH <collection> <v1> ... <vN> <k>");
        }
        ICollection c = collection(args);
        int got = args.size() - 2;
        if (got != c.dimension()) {
            throw new IllegalArgumentException("collection '" + c.name() + "' expects " + c.dimension()
                    + " vector components and k, got " + got + " components");
        }
        double[] query = parseVector(args.subList(1, args.size() - 1));
        int k = parseInt(args.get(args.size() - 1), "k");
        return c.getNearest(query, k);
    }

    private ICollection collection(List<String> args) {
        return db.getCollection(args.get(0));
    }

    private static double[] parseVector(List<String> tokens) {
        double[] v = new double[tokens.size()];
        for (int i = 0; i < v.length; i++) {
            try {
                v[i] = Double.parseDouble(tokens.get(i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: '" + tokens.get(i) + "'");
            }
        }
        return v;
    }

    private static int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be an integer, got '" + s + "'");
        }
    }

    private static DistanceMetric parseMetric(String s) {
        try {
            return DistanceMetric.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown metric '" + s + "', expected COSINE, EUCLIDEAN or DOT_PRODUCT");
        }
    }

    private static void arity(List<String> args, int n, String usage) {
        if (args.size() != n) {
            throw usage(usage);
        }
    }

    private static IllegalArgumentException usage(String usage) {
        return new IllegalArgumentException("usage: " + usage);
    }
}
