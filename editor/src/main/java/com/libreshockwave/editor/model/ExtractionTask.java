package com.libreshockwave.editor.model;

/**
 * Represents a task to extract an asset (bitmap or sound) from a Director file.
 */
public record ExtractionTask(String filePath, CastMemberInfo memberInfo) {
}
