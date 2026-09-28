package com.db.exception;

public class DimensionMismatchException extends DbException {

    public DimensionMismatchException(String message) {
        super(message);
    }

    public DimensionMismatchException(int expected, int actual) {
        super("dimension mismatch: expected " + expected + ", got " + actual);
    }
}
