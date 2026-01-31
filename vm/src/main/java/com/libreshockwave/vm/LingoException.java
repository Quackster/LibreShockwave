package com.libreshockwave.vm;

/**
 * Exception thrown during Lingo script execution.
 */
public class LingoException extends RuntimeException {

    private final String handlerName;
    private final int bytecodeOffset;

    public LingoException(String message) {
        super(message);
        this.handlerName = null;
        this.bytecodeOffset = -1;
    }

    public LingoException(String message, String handlerName, int bytecodeOffset) {
        super(message + " at " + handlerName + " [" + bytecodeOffset + "]");
        this.handlerName = handlerName;
        this.bytecodeOffset = bytecodeOffset;
    }

    public LingoException(String message, Throwable cause) {
        super(message, cause);
        this.handlerName = null;
        this.bytecodeOffset = -1;
    }

    public String getHandlerName() {
        return handlerName;
    }

    public int getBytecodeOffset() {
        return bytecodeOffset;
    }
}
