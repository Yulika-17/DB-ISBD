package com.db.replication;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Ведомый подключается к ведущему, кладёт полученные записи в очередь
 * и применяет их с настраиваемой задержкой {@code --replication-delay-ms}.
 */
final class ReplicaFollower {

    private final String host;
    private final int port;
    private final long delayMs;
    private final LongSupplier lastApplied;
    private final WalApplier applier;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong masterLsn = new AtomicLong();
    private final BlockingQueue<WalRecord> inbox = new LinkedBlockingQueue<>();
    private volatile Thread receiver;
    private volatile Thread applierThread;

    ReplicaFollower(String host, int port, long delayMs, LongSupplier lastApplied, WalApplier applier) {
        this.host = host;
        this.port = port;
        this.delayMs = delayMs;
        this.lastApplied = lastApplied;
        this.applier = applier;
    }

    void start() {
        receiver = new Thread(this::receiveLoop, "vecdb-repl-receiver");
        receiver.setDaemon(true);
        receiver.start();
        applierThread = new Thread(this::applyLoop, "vecdb-repl-applier");
        applierThread.setDaemon(true);
        applierThread.start();
    }

    long masterLsn() {
        return masterLsn.get();
    }

    void stop() {
        running.set(false);
        if (receiver != null) {
            receiver.interrupt();
        }
        if (applierThread != null) {
            applierThread.interrupt();
        }
        inbox.clear();
    }

    private void receiveLoop() {
        while (running.get()) {
            try (Socket socket = new Socket(host, port);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                inbox.clear();
                out.write(WalJson.hello(lastApplied.getAsLong()));
                out.write('\n');
                out.flush();
                String line;
                while (running.get() && (line = in.readLine()) != null) {
                    WalRecord rec = WalJson.parseRecord(line);
                    masterLsn.set(rec.lsn());
                    inbox.put(rec);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (!running.get()) {
                    return;
                }
                sleepQuietly(200);
            }
        }
    }

    private void applyLoop() {
        while (running.get()) {
            try {
                WalRecord rec = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (rec == null) {
                    continue;
                }
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                if (!running.get()) {
                    return;
                }
                applier.apply(rec);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface WalApplier {
        void apply(WalRecord record);
    }
}
