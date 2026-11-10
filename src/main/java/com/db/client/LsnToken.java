package com.db.client;

/**
 * Токен записи: LSN, который клиент получил в ответе ведущего.
 * Чтение с {@code waitLsn} на ведомом ждёт применения этой позиции — read-your-writes.
 */
public record LsnToken(long lsn) {
    public LsnToken {
        if (lsn < 1) {
            throw new IllegalArgumentException("lsn must be >= 1");
        }
    }
}
