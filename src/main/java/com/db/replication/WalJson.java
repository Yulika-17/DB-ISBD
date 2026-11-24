package com.db.replication;

import com.db.config.DistanceMetric;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Компактный JSON для записей WAL и служебных сообщений репликации.
 * Схема фиксированная, парсер не претендует на универсальность — JDK без Jackson.
 */
public final class WalJson {

    private WalJson() {
    }

    public static String toJson(WalRecord record) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        field(sb, "lsn", record.lsn(), true);
        comma(sb);
        field(sb, "op", record.op().name());
        comma(sb);
        field(sb, "collection", record.collection());
        if (record.id() != null) {
            comma(sb);
            field(sb, "id", record.id());
        }
        if (record.vector() != null) {
            comma(sb);
            sb.append("\"vector\":[");
            double[] v = record.vector();
            for (int i = 0; i < v.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(v[i]);
            }
            sb.append(']');
        }
        if (record.data() != null) {
            comma(sb);
            field(sb, "data", Base64.getEncoder().encodeToString(record.data()));
        }
        if (record.dimension() != null) {
            comma(sb);
            field(sb, "dimension", record.dimension(), false);
        }
        if (record.metric() != null) {
            comma(sb);
            field(sb, "metric", record.metric().name());
        }
        if (record.maxVectors() != null) {
            comma(sb);
            field(sb, "maxVectors", record.maxVectors(), false);
        }
        sb.append('}');
        return sb.toString();
    }

    public static WalRecord parseRecord(String json) {
        long lsn = longField(json, "lsn", 0);
        WalRecord.Op op = WalRecord.Op.valueOf(stringField(json, "op"));
        String collection = stringField(json, "collection");
        String id = optionalString(json, "id");
        double[] vector = optionalVector(json);
        byte[] data = optionalBytes(json);
        Integer dimension = optionalInt(json, "dimension");
        DistanceMetric metric = optionalMetric(json);
        Integer maxVectors = optionalInt(json, "maxVectors");
        return new WalRecord(lsn, op, collection, id, vector, data, dimension, metric, maxVectors);
    }

    public static String hello(long lastAppliedLsn) {
        return "{\"type\":\"HELLO\",\"lastAppliedLsn\":" + lastAppliedLsn + "}";
    }

    public static boolean isHello(String json) {
        String type = optionalString(json, "type");
        return "HELLO".equals(type);
    }

    public static long helloLastApplied(String json) {
        return longField(json, "lastAppliedLsn", 0);
    }

    private static void field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static void field(StringBuilder sb, String name, long value, boolean first) {
        if (!first) {
            comma(sb);
        }
        sb.append('"').append(name).append("\":").append(value);
    }

    private static void comma(StringBuilder sb) {
        sb.append(',');
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stringField(String json, String name) {
        String v = optionalString(json, name);
        if (v == null) {
            throw new IllegalArgumentException("missing field " + name + " in " + json);
        }
        return v;
    }

    private static String optionalString(String json, String name) {
        String key = "\"" + name + "\":";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int p = skipWs(json, i + key.length());
        if (p >= json.length() || json.charAt(p) != '"') {
            return null;
        }
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int j = p + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (esc) {
                out.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static long longField(String json, String name, long defaultValue) {
        String key = "\"" + name + "\":";
        int i = json.indexOf(key);
        if (i < 0) {
            return defaultValue;
        }
        int p = skipWs(json, i + key.length());
        int end = p;
        if (end < json.length() && json.charAt(end) == '-') {
            end++;
        }
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == p) {
            return defaultValue;
        }
        return Long.parseLong(json.substring(p, end));
    }

    private static Integer optionalInt(String json, String name) {
        if (!json.contains("\"" + name + "\":")) {
            return null;
        }
        return (int) longField(json, name, 0);
    }

    private static DistanceMetric optionalMetric(String json) {
        String m = optionalString(json, "metric");
        return m == null ? null : DistanceMetric.valueOf(m.toUpperCase(Locale.ROOT));
    }

    private static byte[] optionalBytes(String json) {
        String b64 = optionalString(json, "data");
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        return Base64.getDecoder().decode(b64);
    }

    private static double[] optionalVector(String json) {
        String key = "\"vector\":";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int p = skipWs(json, i + key.length());
        if (p >= json.length() || json.charAt(p) != '[') {
            return null;
        }
        int end = json.indexOf(']', p);
        if (end < 0) {
            return null;
        }
        String body = json.substring(p + 1, end).trim();
        if (body.isEmpty()) {
            return new double[0];
        }
        String[] parts = body.split(",");
        List<Double> nums = new ArrayList<>(parts.length);
        for (String part : parts) {
            String t = part.trim();
            if (!t.isEmpty()) {
                nums.add(Double.parseDouble(t));
            }
        }
        double[] v = new double[nums.size()];
        for (int n = 0; n < nums.size(); n++) {
            v[n] = nums.get(n);
        }
        return v;
    }

    private static int skipWs(String json, int i) {
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }
}
