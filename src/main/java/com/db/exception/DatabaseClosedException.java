package com.db.exception;

public class DatabaseClosedException extends DbException {

    public DatabaseClosedException(String name) {
        super("database is closed: " + name);
    }
}
