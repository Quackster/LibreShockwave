package com.libreshockwave.player.debug;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages breakpoints with support for conditions, log points, enable/disable, and hit counts.
 * Thread-safe for concurrent access from UI and VM threads.
 */
public class BreakpointManager {

    /**
     * Key for breakpoint lookup (scriptId:offset).
     */
    public record BreakpointKey(int scriptId, int offset) {
        public static BreakpointKey of(int scriptId, int offset) {
            return new BreakpointKey(scriptId, offset);
        }

        public static BreakpointKey of(Breakpoint bp) {
            return new BreakpointKey(bp.scriptId(), bp.offset());
        }

        @Override
        public String toString() {
            return scriptId + ":" + offset;
        }
    }

    private final ConcurrentHashMap<BreakpointKey, Breakpoint> breakpoints = new ConcurrentHashMap<>();

    /**
     * Get a breakpoint at the specified location, or null if none exists.
     */
    public Breakpoint getBreakpoint(int scriptId, int offset) {
        return breakpoints.get(BreakpointKey.of(scriptId, offset));
    }

    /**
     * Check if a breakpoint exists at the specified location.
     */
    public boolean hasBreakpoint(int scriptId, int offset) {
        return breakpoints.containsKey(BreakpointKey.of(scriptId, offset));
    }

    /**
     * Set or update a breakpoint.
     */
    public void setBreakpoint(Breakpoint bp) {
        breakpoints.put(BreakpointKey.of(bp), bp);
    }

    /**
     * Add a simple breakpoint. If one already exists, it will be replaced.
     */
    public Breakpoint addBreakpoint(int scriptId, int offset) {
        Breakpoint bp = Breakpoint.simple(scriptId, offset);
        setBreakpoint(bp);
        return bp;
    }

    /**
     * Remove a breakpoint.
     * @return the removed breakpoint, or null if none existed
     */
    public Breakpoint removeBreakpoint(int scriptId, int offset) {
        return breakpoints.remove(BreakpointKey.of(scriptId, offset));
    }

    /**
     * Toggle a breakpoint at the specified location.
     * If no breakpoint exists, creates a simple enabled breakpoint.
     * If a breakpoint exists, removes it.
     * @return the new breakpoint if added, or null if removed
     */
    public Breakpoint toggleBreakpoint(int scriptId, int offset) {
        BreakpointKey key = BreakpointKey.of(scriptId, offset);
        Breakpoint existing = breakpoints.get(key);
        if (existing != null) {
            breakpoints.remove(key);
            return null;
        } else {
            Breakpoint bp = Breakpoint.simple(scriptId, offset);
            breakpoints.put(key, bp);
            return bp;
        }
    }

    /**
     * Toggle the enabled state of a breakpoint.
     * @return the updated breakpoint, or null if no breakpoint exists
     */
    public Breakpoint toggleEnabled(int scriptId, int offset) {
        BreakpointKey key = BreakpointKey.of(scriptId, offset);
        return breakpoints.computeIfPresent(key, (k, bp) -> bp.withEnabled(!bp.enabled()));
    }

    /**
     * Reset the hit count of a breakpoint to 0.
     * @return the updated breakpoint, or null if no breakpoint exists
     */
    public Breakpoint resetHitCount(int scriptId, int offset) {
        BreakpointKey key = BreakpointKey.of(scriptId, offset);
        return breakpoints.computeIfPresent(key, (k, bp) -> bp.withResetHitCount());
    }

    /**
     * Increment and update the hit count of a breakpoint.
     * @return the updated breakpoint with incremented hit count, or null if no breakpoint exists
     */
    public Breakpoint incrementHitCount(int scriptId, int offset) {
        BreakpointKey key = BreakpointKey.of(scriptId, offset);
        return breakpoints.computeIfPresent(key, (k, bp) -> bp.withIncrementedHitCount());
    }

    /**
     * Get all breakpoints.
     */
    public Collection<Breakpoint> getAllBreakpoints() {
        return Collections.unmodifiableCollection(breakpoints.values());
    }

    /**
     * Get all breakpoints for a specific script.
     */
    public List<Breakpoint> getBreakpointsForScript(int scriptId) {
        return breakpoints.values().stream()
            .filter(bp -> bp.scriptId() == scriptId)
            .collect(Collectors.toList());
    }

    /**
     * Get all offsets with breakpoints for a specific script.
     */
    public Set<Integer> getOffsetsForScript(int scriptId) {
        return breakpoints.values().stream()
            .filter(bp -> bp.scriptId() == scriptId)
            .map(Breakpoint::offset)
            .collect(Collectors.toSet());
    }

    /**
     * Clear all breakpoints.
     */
    public void clearAll() {
        breakpoints.clear();
    }

    /**
     * Reset all hit counts to 0.
     */
    public void resetAllHitCounts() {
        breakpoints.replaceAll((key, bp) -> bp.withResetHitCount());
    }

    /**
     * Get all breakpoints as a map (scriptId -> set of offsets).
     * This is for backward compatibility with the old breakpoint storage format.
     */
    public Map<Integer, Set<Integer>> toOffsetMap() {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (Breakpoint bp : breakpoints.values()) {
            result.computeIfAbsent(bp.scriptId(), k -> new HashSet<>()).add(bp.offset());
        }
        return result;
    }

    /**
     * Set breakpoints from an offset map (old format).
     * Creates simple enabled breakpoints for each entry.
     */
    public void setFromOffsetMap(Map<Integer, Set<Integer>> offsetMap) {
        clearAll();
        if (offsetMap != null) {
            for (Map.Entry<Integer, Set<Integer>> entry : offsetMap.entrySet()) {
                int scriptId = entry.getKey();
                for (int offset : entry.getValue()) {
                    setBreakpoint(Breakpoint.simple(scriptId, offset));
                }
            }
        }
    }

    /**
     * Serialize breakpoints to JSON format.
     */
    public String serialize() {
        if (breakpoints.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":2,\"breakpoints\":[");

        boolean first = true;
        for (Breakpoint bp : breakpoints.values()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"scriptId\":").append(bp.scriptId()).append(",");
            sb.append("\"offset\":").append(bp.offset()).append(",");
            sb.append("\"enabled\":").append(bp.enabled()).append(",");
            sb.append("\"condition\":").append(jsonString(bp.condition())).append(",");
            sb.append("\"logMessage\":").append(jsonString(bp.logMessage())).append(",");
            sb.append("\"hitCountThreshold\":").append(bp.hitCountThreshold());
            // Note: hitCount is not serialized (runtime state only)
            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Deserialize breakpoints from a string.
     * Supports both new JSON format and legacy format for backward compatibility.
     */
    public void deserialize(String data) {
        clearAll();
        if (data == null || data.isEmpty()) {
            return;
        }

        data = data.trim();
        if (data.startsWith("{")) {
            // New JSON format
            deserializeJson(data);
        } else {
            // Legacy format: "scriptId:offset,offset;scriptId:offset,offset;..."
            deserializeLegacy(data);
        }
    }

    private void deserializeJson(String json) {
        try {
            // Simple JSON parsing (no external dependencies)
            // Expected format: {"version":2,"breakpoints":[{...},{...}]}

            // Find breakpoints array
            int breakpointsStart = json.indexOf("\"breakpoints\":[");
            if (breakpointsStart < 0) return;

            int arrayStart = json.indexOf('[', breakpointsStart);
            int arrayEnd = json.lastIndexOf(']');
            if (arrayStart < 0 || arrayEnd < 0 || arrayEnd <= arrayStart) return;

            String arrayContent = json.substring(arrayStart + 1, arrayEnd);
            if (arrayContent.isBlank()) return;

            // Parse each breakpoint object
            int depth = 0;
            int objStart = -1;
            for (int i = 0; i < arrayContent.length(); i++) {
                char c = arrayContent.charAt(i);
                if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String objStr = arrayContent.substring(objStart, i + 1);
                        Breakpoint bp = parseBreakpointJson(objStr);
                        if (bp != null) {
                            setBreakpoint(bp);
                        }
                        objStart = -1;
                    }
                }
            }
        } catch (Exception e) {
            // On parse error, try legacy format as fallback
            deserializeLegacy(json);
        }
    }

    private Breakpoint parseBreakpointJson(String json) {
        try {
            int scriptId = parseJsonInt(json, "scriptId", 0);
            int offset = parseJsonInt(json, "offset", 0);
            boolean enabled = parseJsonBoolean(json, "enabled", true);
            String condition = parseJsonString(json, "condition");
            String logMessage = parseJsonString(json, "logMessage");
            int hitCountThreshold = parseJsonInt(json, "hitCountThreshold", 0);

            return new Breakpoint(scriptId, offset, enabled, condition, logMessage, 0, hitCountThreshold);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseJsonInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;

        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return defaultValue;

        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean parseJsonBoolean(String json, String key, boolean defaultValue) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;

        int start = idx + pattern.length();
        if (json.regionMatches(start, "true", 0, 4)) return true;
        if (json.regionMatches(start, "false", 0, 5)) return false;
        return defaultValue;
    }

    private String parseJsonString(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;

        int start = idx + pattern.length();
        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;

        if (start >= json.length()) return null;

        // Check for null
        if (json.regionMatches(start, "null", 0, 4)) return null;

        // Check for string
        if (json.charAt(start) != '"') return null;

        start++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (start < json.length()) {
            char c = json.charAt(start);
            if (c == '"') break;
            if (c == '\\' && start + 1 < json.length()) {
                char next = json.charAt(start + 1);
                switch (next) {
                    case '"', '\\', '/' -> { sb.append(next); start += 2; continue; }
                    case 'n' -> { sb.append('\n'); start += 2; continue; }
                    case 'r' -> { sb.append('\r'); start += 2; continue; }
                    case 't' -> { sb.append('\t'); start += 2; continue; }
                }
            }
            sb.append(c);
            start++;
        }
        return sb.toString();
    }

    private void deserializeLegacy(String data) {
        // Format: "scriptId:offset,offset;scriptId:offset,offset;..."
        try {
            String[] scripts = data.split(";");
            for (String script : scripts) {
                if (script.isEmpty()) continue;
                String[] parts = script.split(":");
                if (parts.length != 2) continue;
                int scriptId = Integer.parseInt(parts[0]);
                String[] offsetStrs = parts[1].split(",");
                for (String offsetStr : offsetStrs) {
                    if (!offsetStr.isEmpty()) {
                        int offset = Integer.parseInt(offsetStr);
                        setBreakpoint(Breakpoint.simple(scriptId, offset));
                    }
                }
            }
        } catch (NumberFormatException e) {
            // Ignore parse errors in legacy format
        }
    }

    /**
     * Serialize breakpoints to legacy format for backward compatibility.
     * Format: "scriptId:offset,offset;scriptId:offset,offset;..."
     */
    public String serializeLegacy() {
        Map<Integer, Set<Integer>> offsetMap = toOffsetMap();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, Set<Integer>> entry : offsetMap.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            if (!first) sb.append(";");
            first = false;
            sb.append(entry.getKey()).append(":");
            boolean firstOffset = true;
            for (Integer offset : entry.getValue()) {
                if (!firstOffset) sb.append(",");
                firstOffset = false;
                sb.append(offset);
            }
        }
        return sb.toString();
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
