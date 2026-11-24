package com.db.replication;

/** Структурированные строки журнала с префиксом роли. */
public final class ReplicationLog {

    private ReplicationLog() {
    }

    public static void master(String message) {
        System.out.println("[MASTER] " + message);
    }

    public static void replica(String message) {
        System.out.println("[REPLICA] " + message);
    }

    public static void client(String message) {
        System.out.println("[CLIENT] " + message);
    }
}
