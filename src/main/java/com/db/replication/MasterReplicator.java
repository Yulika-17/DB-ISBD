package com.db.replication;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ведущий слушает TCP и асинхронно досылает WAL ведомому, начиная с lastAppliedLsn+1.
 */
final class MasterReplicator {

    private final WalLog wal;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int bindPort;
    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private volatile int actualPort;

    MasterReplicator(WalLog wal, int bindPort) {
        this.wal = wal;
        this.bindPort = bindPort;
    }

    void start() {
        try {
            server = new ServerSocket(bindPort);
            actualPort = server.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind replication port " + bindPort, e);
        }
        acceptThread = new Thread(this::acceptLoop, "vecdb-repl-master");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int port() {
        return actualPort;
    }

    void stop() {
        running.set(false);
        closeQuietly(server);
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = server.accept();
                Thread session = new Thread(() -> serve(socket), "vecdb-repl-session");
                session.setDaemon(true);
                session.start();
            } catch (SocketException closed) {
                if (!running.get()) {
                    return;
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[MASTER] replication accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String hello = in.readLine();
            if (hello == null || !WalJson.isHello(hello)) {
                return;
            }
            long from = WalJson.helloLastApplied(hello);
            long sentUpTo = from;
            while (running.get()) {
                List<WalRecord> batch = wal.recordsAfter(sentUpTo);
                if (batch.isEmpty()) {
                    wal.awaitNew(sentUpTo, 500);
                    continue;
                }
                for (WalRecord rec : batch) {
                    out.write(WalJson.toJson(rec));
                    out.write('\n');
                    out.flush();
                    ReplicationLog.master("sent lsn=" + rec.lsn());
                    sentUpTo = rec.lsn();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // ведомый отключился — догонит при следующем HELLO
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // shutdown
        }
    }
}
