package com.db.exception;

public class IdNotFoundException extends DbException {
    
    public IdNotFoundException(String id) {
        super("id not found: " + id);
    }
}
