package com.db.exception;

public class InvalidCollectionNameException extends DbException {

    public InvalidCollectionNameException(String name) {
        super("invalid collection name: '" + name
                + "' (allowed: A-Z, a-z, 0-9, '_', '-', length 1..64)");
    }
}
