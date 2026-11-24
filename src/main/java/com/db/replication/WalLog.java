package com.db.replication;

import com.db.exception.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Append-only журнал логических операций: одна JSON-строка на запись, fsync после append.
 */
public final class WalLog implements AutoCloseable {

    public static final String WAL_FILE = "wal.log";
    public static final String CHECKPOINT_FILE = "wal.checkpoint";

    private final Path walPath;
    private final Path checkpointPath;
    private final List<WalRecord> records = new ArrayList<>();
    private final Object lock = new Object();
    private FileChannel channel;
    private long lastLsn;

    private WalLog(Path dataDir) {
        this.walPath = dataDir.resolve(WAL_FILE);
        this.checkpointPath = dataDir.resolve(CHECKPOINT_FILE);
    }

    public static WalLog open(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new StorageException("cannot create WAL directory " + dataDir, e);
        }
        WalLog log = new WalLog(dataDir);
        log.openChannelAndLoad();
        return log;
    }

    public long lastLsn() {
        synchronized (lock) {
            return lastLsn;
        }
    }

    public long checkpointLsn() {
        if (!Files.isRegularFile(checkpointPath)) {
            return 0;
        }
        try {
            String text = Files.readString(checkpointPath, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (IOException | NumberFormatException e) {
            throw new StorageException("cannot read WAL checkpoint", e);
        }
    }

    public void writeCheckpoint(long lsn) {
        try {
            Path tmp = checkpointPath.resolveSibling(CHECKPOINT_FILE + ".tmp");
            Files.writeString(tmp, Long.toString(lsn), StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            try {
                Files.move(tmp, checkpointPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tmp, checkpointPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("cannot write WAL checkpoint", e);
        }
    }

    /** Назначает следующий LSN, пишет строку, fsync. */
    public WalRecord append(WalRecord unsigned) {
        synchronized (lock) {
            WalRecord signed = unsigned.withLsn(lastLsn + 1);
            writeLocked(signed);
            return signed;
        }
    }

    /** Сохраняет запись с уже назначенным LSN (ведомый повторяет журнал ведущего). */
    public void appendWithLsn(WalRecord signed) {
        synchronized (lock) {
            if (signed.lsn() <= lastLsn) {
                return;
            }
            if (signed.lsn() != lastLsn + 1) {
                throw new StorageException("WAL gap: expected lsn " + (lastLsn + 1) + ", got " + signed.lsn());
            }
            writeLocked(signed);
        }
    }

    public List<WalRecord> recordsAfter(long lsnExclusive) {
        synchronized (lock) {
            List<WalRecord> out = new ArrayList<>();
            for (WalRecord r : records) {
                if (r.lsn() > lsnExclusive) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    public List<WalRecord> last(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("N must be >= 1");
        }
        synchronized (lock) {
            int from = Math.max(0, records.size() - n);
            return List.copyOf(records.subList(from, records.size()));
        }
    }

    public void awaitNew(long currentLastLsn, long timeoutMs) throws InterruptedException {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (lastLsn <= currentLastLsn) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    return;
                }
                lock.wait(wait);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (channel != null) {
                try {
                    channel.force(true);
                    channel.close();
                } catch (IOException ignored) {
                    // учебный проект: закрытие при shutdown
                }
                channel = null;
            }
        }
    }

    private void openChannelAndLoad() {
        try {
            channel = FileChannel.open(walPath, EnumSet.of(
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
            byte[] all = new byte[(int) Math.min(Integer.MAX_VALUE, channel.size())];
            channel.position(0);
            ByteBuffer buf = ByteBuffer.wrap(all);
            while (buf.hasRemaining()) {
                int n = channel.read(buf);
                if (n < 0) {
                    break;
                }
            }
            String text = new String(all, 0, buf.position(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                WalRecord rec = WalJson.parseRecord(trimmed);
                records.add(rec);
                lastLsn = rec.lsn();
            }
            channel.position(channel.size());
        } catch (IOException e) {
            throw new StorageException("cannot open WAL " + walPath, e);
        }
    }

    private void writeLocked(WalRecord signed) {
        try {
            byte[] bytes = (WalJson.toJson(signed) + "\n").getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
            records.add(signed);
            lastLsn = signed.lsn();
            lock.notifyAll();
        } catch (IOException e) {
            throw new StorageException("WAL append failed at lsn " + signed.lsn(), e);
        }
    }
}
