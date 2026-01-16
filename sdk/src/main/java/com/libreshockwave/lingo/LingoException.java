package com.libreshockwave.lingo;

/**
 * Exception thrown during Lingo script execution.
 */
public class LingoException extends RuntimeException {

    public LingoException(String message) {
        super(message);
    }

    public LingoException(String message, Throwable cause) {
        super(message, cause);
    }

    public static LingoException typeMismatch(String expected, String actual) {
        return new LingoException("Type mismatch: expected " + expected + ", got " + actual);
    }

    public static LingoException undefinedHandler(String name) {
        return new LingoException("Handler not defined: " + name);
    }

    public static LingoException undefinedVariable(String name) {
        return new LingoException("Variable not defined: " + name);
    }

    public static LingoException indexOutOfBounds(int index, int size) {
        return new LingoException("Index " + index + " out of bounds for size " + size);
    }
}
