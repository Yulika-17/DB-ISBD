package com.db.exception;

public class CollectionAlreadyExistsException extends DbException {

    public CollectionAlreadyExistsException(String name) {
        super("collection already exists: " + name);
    }
}
