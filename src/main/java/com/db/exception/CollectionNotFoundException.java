package com.db.exception;

public class CollectionNotFoundException extends DbException {

    public CollectionNotFoundException(String name) {
        super("collection not found: " + name);
    }
}
