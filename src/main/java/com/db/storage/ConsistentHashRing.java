package com.db.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.List;

/** Consistent-hash ring with virtual nodes for deterministic vector placement. */
final class ConsistentHashRing<T> {
    private final NavigableMap<Long, T> ring = new TreeMap<>();

    ConsistentHashRing(Map<String, T> nodes, int virtualNodes) {
        if (virtualNodes < 1) throw new IllegalArgumentException("virtualNodes must be positive");
        nodes.forEach((name, node) -> {
            for (int i = 0; i < virtualNodes; i++) {
                ring.put(hash(name + "#" + i), node);
            }
        });
        if (ring.isEmpty()) throw new IllegalArgumentException("at least one shard is required");
    }

    T locate(String key) {
        Objects.requireNonNull(key, "key");
        Map.Entry<Long, T> entry = ring.ceilingEntry(hash(key));
        return (entry == null ? ring.firstEntry() : entry).getValue();
    }

    List<Token<T>> tokens() {
        return ring.entrySet().stream().map(e -> new Token<>(e.getKey(), e.getValue())).toList();
    }

    record Token<T>(long hash, T node) { }

    private static long hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < Long.BYTES; i++) result = (result << 8) | (digest[i] & 0xffL);
            return result;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
