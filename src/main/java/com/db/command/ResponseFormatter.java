package com.db.command;

import com.db.config.CollectionConfig;
import com.db.model.CollectionStats;
import com.db.model.DbStats;
import com.db.model.SearchResult;
import com.db.model.VectorEntry;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Человекочитаемое представление результатов {@link TextCommandDispatcher}. */
public final class ResponseFormatter {

    private ResponseFormatter() {
    }

    public static String format(Object result) {
        if (result == null) {
            return "(nil)";
        }
        if (result instanceof String s) {
            return s;
        }
        if (result instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (result instanceof VectorEntry e) {
            return entry(e);
        }
        if (result instanceof CollectionConfig c) {
            return config(c);
        }
        if (result instanceof CollectionStats s) {
            return collectionStats(s);
        }
        if (result instanceof DbStats s) {
            return dbStats(s);
        }
        if (result instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof SearchResult) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (Object o : list) {
                SearchResult r = (SearchResult) o;
                if (i > 1) sb.append('\n');
                sb.append(i++).append(") ")
                        .append(String.format(Locale.ROOT, "distance=%.6f  ", r.distance()))
                        .append(entry(r.entry()));
            }
            return sb.toString();
        }
        if (result instanceof Collection<?> c) {
            if (c.isEmpty()) {
                return "(empty)";
            }
            StringBuilder sb = new StringBuilder();
            for (Object o : c) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(o);
            }
            return sb.toString();
        }
        return String.valueOf(result);
    }

    private static String entry(VectorEntry e) {
        StringBuilder sb = new StringBuilder("id=").append(e.id()).append(" vector=[");
        double[] v = e.vector();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(v[i]);
        }
        sb.append(']');
        if (e.data() != null) {
            sb.append(" data=\"").append(new String(e.data(), StandardCharsets.UTF_8)).append('"');
        }
        return sb.toString();
    }

    private static String config(CollectionConfig c) {
        return c.name() + " (dimension=" + c.dimension() + ", metric=" + c.metric()
                + ", maxVectors=" + (c.maxVectors() == 0 ? "unlimited" : c.maxVectors()) + ")";
    }

    private static String collectionStats(CollectionStats s) {
        return String.format(Locale.ROOT,
                "%s: dimension=%d metric=%s vectors=%d puts=%d searches=%d hits=%d misses=%d hitRatio=%.2f evicted=%d",
                s.name(), s.dimension(), s.metric(), s.liveVectors(), s.totalPuts(), s.totalSearches(),
                s.hits(), s.misses(), s.hitRatio(), s.evictedVectors());
    }

    private static String dbStats(DbStats s) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "engine=%s collections=%d vectors=%d puts=%d searches=%d hitRatio=%.2f evicted=%d lastSave=%s (%d ms)",
                s.engine(), s.collections(), s.liveVectors(), s.totalPuts(), s.totalSearches(),
                s.hitRatio(), s.evictedVectors(),
                s.lastSaveAt() == null ? "never" : s.lastSaveAt(), s.lastSaveDurationMs()));
        for (CollectionStats c : s.perCollection().values()) {
            sb.append("\n  ").append(collectionStats(c));
        }
        return sb.toString();
    }
}
