package com.libreshockwave.player.debug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BreakpointManagerTest {

    private BreakpointManager manager;

    @BeforeEach
    void setUp() {
        manager = new BreakpointManager();
    }

    @Test
    void breakpointInHandlerA_doesNotFireInHandlerB_atSameOffset() {
        // This is the core bug: two handlers in the same script, same offset
        int scriptId = 42;
        String handlerA = "mouseDown";
        String handlerB = "mouseUp";
        int offset = 4;

        manager.addBreakpoint(scriptId, handlerA, offset);

        assertTrue(manager.hasBreakpoint(scriptId, handlerA, offset));
        assertFalse(manager.hasBreakpoint(scriptId, handlerB, offset),
            "Breakpoint set on handlerA should NOT match handlerB at same offset");
    }

    @Test
    void breakpointInHandlerA_stillFiresInHandlerA() {
        int scriptId = 42;
        String handler = "mouseDown";
        int offset = 4;

        manager.addBreakpoint(scriptId, handler, offset);

        Breakpoint bp = manager.getBreakpoint(scriptId, handler, offset);
        assertNotNull(bp);
        assertTrue(bp.enabled());
        assertEquals(scriptId, bp.scriptId());
        assertEquals(handler, bp.handlerName());
        assertEquals(offset, bp.offset());
    }

    @Test
    void toggleBreakpoint_addsAndRemoves() {
        int scriptId = 1;
        String handler = "on exitFrame";
        int offset = 0;

        // Add
        Breakpoint added = manager.toggleBreakpoint(scriptId, handler, offset);
        assertNotNull(added);
        assertTrue(manager.hasBreakpoint(scriptId, handler, offset));

        // Remove
        Breakpoint removed = manager.toggleBreakpoint(scriptId, handler, offset);
        assertNull(removed);
        assertFalse(manager.hasBreakpoint(scriptId, handler, offset));
    }

    @Test
    void toggleEnabled_flipsState() {
        int scriptId = 1;
        String handler = "enterFrame";
        int offset = 8;

        manager.addBreakpoint(scriptId, handler, offset);
        assertTrue(manager.getBreakpoint(scriptId, handler, offset).enabled());

        Breakpoint disabled = manager.toggleEnabled(scriptId, handler, offset);
        assertNotNull(disabled);
        assertFalse(disabled.enabled());

        Breakpoint reEnabled = manager.toggleEnabled(scriptId, handler, offset);
        assertNotNull(reEnabled);
        assertTrue(reEnabled.enabled());
    }

    @Test
    void multipleHandlers_sameScript_independentBreakpoints() {
        int scriptId = 10;
        manager.addBreakpoint(scriptId, "new", 0);
        manager.addBreakpoint(scriptId, "new", 4);
        manager.addBreakpoint(scriptId, "dispose", 0);
        manager.addBreakpoint(scriptId, "update", 8);

        assertEquals(4, manager.getBreakpointsForScript(scriptId).size());

        // Remove one — others unaffected
        manager.removeBreakpoint(scriptId, "new", 0);
        assertEquals(3, manager.getBreakpointsForScript(scriptId).size());
        assertFalse(manager.hasBreakpoint(scriptId, "new", 0));
        assertTrue(manager.hasBreakpoint(scriptId, "new", 4));
        assertTrue(manager.hasBreakpoint(scriptId, "dispose", 0));
        assertTrue(manager.hasBreakpoint(scriptId, "update", 8));
    }

    @Test
    void serializeAndDeserialize_roundTrips() {
        manager.addBreakpoint(1, "handlerA", 0);
        manager.addBreakpoint(1, "handlerB", 0);
        manager.addBreakpoint(2, "init", 12);

        String serialized = manager.serialize();
        assertFalse(serialized.isEmpty());
        assertTrue(serialized.contains("\"handlerName\""));

        BreakpointManager restored = new BreakpointManager();
        restored.deserialize(serialized);

        assertTrue(restored.hasBreakpoint(1, "handlerA", 0));
        assertTrue(restored.hasBreakpoint(1, "handlerB", 0));
        assertTrue(restored.hasBreakpoint(2, "init", 12));
        assertFalse(restored.hasBreakpoint(1, "handlerA", 12));
        // Critically: handlerA bp at offset 0 should NOT match handlerB
        assertFalse(restored.hasBreakpoint(1, "handlerB", 12));
    }

    @Test
    void deserialize_legacyFormat_usesEmptyHandlerName() {
        // Legacy format has no handler name — should default to ""
        String legacy = "42:0,4,8;99:12";
        manager.deserialize(legacy);

        assertTrue(manager.hasBreakpoint(42, "", 0));
        assertTrue(manager.hasBreakpoint(42, "", 4));
        assertTrue(manager.hasBreakpoint(42, "", 8));
        assertTrue(manager.hasBreakpoint(99, "", 12));
    }

    @Test
    void deserialize_v2JsonFormat_usesEmptyHandlerName() {
        // v2 JSON had no handlerName field
        String v2 = "{\"version\":2,\"breakpoints\":[{\"scriptId\":5,\"offset\":10,\"enabled\":true}]}";
        manager.deserialize(v2);

        assertTrue(manager.hasBreakpoint(5, "", 10));
    }

    @Test
    void breakpointKey_recordEquality() {
        var k1 = BreakpointManager.BreakpointKey.of(1, "handler", 0);
        var k2 = BreakpointManager.BreakpointKey.of(1, "handler", 0);
        var k3 = BreakpointManager.BreakpointKey.of(1, "other", 0);

        assertEquals(k1, k2);
        assertNotEquals(k1, k3);
    }
}
