package com.db.api;

import com.db.exception.StorageException;

import java.io.IOException;

public interface IPersistable {

    void save() throws IOException;

    void load() throws IOException;

    default void flush() {
        try {
            save();
        } catch (IOException e) {
            throw new StorageException("flush failed", e);
        }
    }
}
