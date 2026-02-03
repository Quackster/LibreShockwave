package com.libreshockwave.player.debug;

import com.libreshockwave.vm.Datum;

import java.util.UUID;

/**
 * A watch expression that is evaluated when the debugger pauses.
 * The lastValue and lastError are updated after each evaluation.
 */
public record WatchExpression(
    String id,            // Unique identifier
    String expression,    // The Lingo expression to evaluate
    Datum lastValue,      // Result of last successful evaluation (null if error)
    String lastError      // Error message from last evaluation (null if success)
) {
    /**
     * Create a new watch expression with a generated ID.
     */
    public static WatchExpression create(String expression) {
        return new WatchExpression(UUID.randomUUID().toString(), expression, null, null);
    }

    /**
     * Create a watch expression with a specific ID (for loading).
     */
    public static WatchExpression create(String id, String expression) {
        return new WatchExpression(id, expression, null, null);
    }

    /**
     * Check if the last evaluation resulted in an error.
     */
    public boolean hasError() {
        return lastError != null;
    }

    /**
     * Check if this watch has been evaluated at least once.
     */
    public boolean isEvaluated() {
        return lastValue != null || lastError != null;
    }

    /**
     * Return a copy with a new expression.
     */
    public WatchExpression withExpression(String expression) {
        return new WatchExpression(id, expression, null, null);
    }

    /**
     * Return a copy with a successful evaluation result.
     */
    public WatchExpression withValue(Datum value) {
        return new WatchExpression(id, expression, value, null);
    }

    /**
     * Return a copy with an error result.
     */
    public WatchExpression withError(String error) {
        return new WatchExpression(id, expression, null, error);
    }

    /**
     * Get a display string for the result (value or error).
     */
    public String getResultDisplay() {
        if (lastError != null) {
            return "<" + lastError + ">";
        }
        if (lastValue != null) {
            return lastValue.toString();
        }
        return "<not evaluated>";
    }

    /**
     * Get the type name for display.
     */
    public String getTypeName() {
        if (lastError != null) {
            return "Error";
        }
        if (lastValue != null) {
            return lastValue.typeName();
        }
        return "-";
    }

    @Override
    public String toString() {
        return "Watch[" + expression + " = " + getResultDisplay() + "]";
    }
}
