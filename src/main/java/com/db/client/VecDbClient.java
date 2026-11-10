package com.db.client;

import com.db.config.DistanceMetric;
import com.db.config.NodeRole;
import com.db.model.VectorEntry;
import com.db.replication.ReplicationLog;
import com.db.replication.ReplicationStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * HTTP-клиент REST API. JDK {@link HttpClient}, без Spring.
 * Записи идут на URL ведущего; чтение с токеном может ходить на ведомого ({@code waitLsn}).
 */
public final class VecDbClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI base;

    public VecDbClient(String baseUrl) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    }

    public void waitUntilUp(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/api/v1/health")).GET().build());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + base, e);
            }
        }
        throw new IllegalStateException("server at " + base + " did not become ready", last);
    }

    public LsnToken createCollection(String name, int dimension, DistanceMetric metric) {
        String body = "{\"name\":\"" + name + "\",\"dimension\":" + dimension
                + ",\"metric\":\"" + metric.name() + "\"}";
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/api/v1/collections"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        require(r, 201);
        return tokenFrom(r, true);
    }

    public LsnToken put(String collection, String id, double[] vector, byte[] data) {
        StringBuilder json = new StringBuilder("{\"vector\":[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(vector[i]);
        }
        json.append(']');
        if (data != null) {
            json.append(",\"data\":\"").append(Base64.getEncoder().encodeToString(data)).append('"');
        }
        json.append('}');
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/api/v1/collections/" + collection + "/vectors/" + id))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build());
        require(r, 200);
        LsnToken token = tokenFrom(r, true);
        ReplicationLog.client("write ok lsn=" + token.lsn());
        return token;
    }

    public Optional<VectorEntry> get(String collection, String id) {
        return get(collection, id, null);
    }

    /**
     * Чтение. Если передан токен, сервер ждёт {@code lastAppliedLsn >= token} (на ведомом это RYW).
     */
    public Optional<VectorEntry> get(String collection, String id, LsnToken token) {
        String path = "/api/v1/collections/" + collection + "/vectors/" + id;
        if (token != null) {
            path += "?waitLsn=" + token.lsn();
        }
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri(path)).GET().build());
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        require(r, 200);
        return Optional.of(parseEntry(r.body()));
    }

    public ReplicationStatus status() {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/api/v1/replication/status")).GET().build());
        require(r, 200);
        String body = r.body();
        NodeRole role = NodeRole.valueOf(stringField(body, "role"));
        long lastLsn = longField(body, "lastLsn");
        long lastApplied = longField(body, "lastAppliedLsn");
        long masterLsn = longField(body, "masterLsn");
        long lag = longField(body, "lag");
        int port = (int) longField(body, "replicationPort");
        long delay = longField(body, "replicationDelayMs");
        return new ReplicationStatus(role, lastLsn, lastApplied, masterLsn, lag, port, delay);
    }

    public List<String> walDump(int n) {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/api/v1/replication/wal?n=" + n)).GET().build());
        require(r, 200);
        return parseJsonStringArray(r.body());
    }

    public ReplicationStatus promote() {
        HttpResponse<String> r = send(HttpRequest.newBuilder(uri("/api/v1/replication/promote"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        require(r, 200);
        return status();
    }

    private URI uri(String path) {
        return URI.create(base + path);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("HTTP " + request.method() + " " + request.uri() + " failed: " + e.getMessage(), e);
        }
    }

    private static void require(HttpResponse<String> r, int expected) {
        if (r.statusCode() != expected) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " (expected " + expected + "): " + r.body());
        }
    }

    private static LsnToken tokenFrom(HttpResponse<String> r, boolean required) {
        Optional<String> header = r.headers().firstValue("X-VecDB-LSN");
        long lsn = header.map(Long::parseLong).orElseGet(() -> longField(r.body(), "lsn"));
        if (lsn < 1) {
            if (required) {
                throw new IllegalStateException("response has no LSN: " + r.body());
            }
            return null;
        }
        return new LsnToken(lsn);
    }

    private static VectorEntry parseEntry(String json) {
        String id = stringField(json, "id");
        double[] vector = parseVector(json);
        String b64 = optionalString(json, "data");
        byte[] data = (b64 == null || b64.isEmpty() || "null".equals(b64))
                ? null
                : Base64.getDecoder().decode(b64);
        return new VectorEntry(id, vector, data);
    }

    private static double[] parseVector(String json) {
        int i = json.indexOf("\"vector\"");
        int lb = json.indexOf('[', i);
        int rb = json.indexOf(']', lb);
        String body = json.substring(lb + 1, rb).trim();
        if (body.isEmpty()) {
            return new double[0];
        }
        String[] parts = body.split(",");
        double[] v = new double[parts.length];
        for (int n = 0; n < parts.length; n++) {
            v[n] = Double.parseDouble(parts[n].trim());
        }
        return v;
    }

    private static List<String> parseJsonStringArray(String json) {
        List<String> out = new ArrayList<>();
        int i = json.indexOf('[');
        if (i < 0) {
            return out;
        }
        int p = i + 1;
        while (p < json.length()) {
            while (p < json.length() && json.charAt(p) != '"' && json.charAt(p) != ']') {
                p++;
            }
            if (p >= json.length() || json.charAt(p) == ']') {
                break;
            }
            StringBuilder s = new StringBuilder();
            boolean esc = false;
            p++;
            while (p < json.length()) {
                char c = json.charAt(p++);
                if (esc) {
                    s.append(c);
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    break;
                } else {
                    s.append(c);
                }
            }
            out.add(s.toString());
        }
        return out;
    }

    private static String stringField(String json, String name) {
        String v = optionalString(json, name);
        if (v == null) {
            throw new IllegalStateException("missing field " + name + " in " + json);
        }
        return v;
    }

    private static String optionalString(String json, String name) {
        String key = "\"" + name + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + key.length());
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p < json.length() && json.charAt(p) == '"') {
            StringBuilder s = new StringBuilder();
            boolean esc = false;
            for (int j = p + 1; j < json.length(); j++) {
                char c = json.charAt(j);
                if (esc) {
                    s.append(c);
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    return s.toString();
                } else {
                    s.append(c);
                }
            }
        }
        return null;
    }

    private static long longField(String json, String name) {
        String key = "\"" + name + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return 0;
        }
        int colon = json.indexOf(':', i + key.length());
        int p = colon + 1;
        while (p < json.length() && (Character.isWhitespace(json.charAt(p)) || json.charAt(p) == '"')) {
            p++;
        }
        int end = p;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) {
            end++;
        }
        if (end == p) {
            return 0;
        }
        return Long.parseLong(json.substring(p, end));
    }
}
