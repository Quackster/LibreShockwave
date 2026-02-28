package com.libreshockwave.player.timeout;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;
import com.libreshockwave.vm.builtin.CastLibProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TimeoutManager.dispatchSystemEvent() — system event forwarding to timeout targets.
 *
 * Verifies that when system events (prepareFrame, exitFrame, etc.) fire, they are
 * forwarded to all active timeout targets by calling the handler name on each target's
 * script instance.
 */
class TimeoutManagerSystemEventTest {

    private TimeoutManager timeoutManager;
    private LingoVM vm;
    private List<String> handlerCalls;  // Records handler entries: "handlerName@scriptId"

    @BeforeEach
    void setUp() {
        timeoutManager = new TimeoutManager();
        vm = new LingoVM(null);
        handlerCalls = new ArrayList<>();

        // TraceListener records every handler entry
        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                handlerCalls.add(info.handlerName() + "@" + info.scriptId());
            }
        });
    }

    @AfterEach
    void tearDown() {
        CastLibProvider.clearProvider();
    }

    // ========== Helper Methods ==========

    /**
     * Create a minimal ScriptChunk with handlers that just RET.
     * Each handler name is mapped to its name ID (index in the names list).
     */
    private ScriptChunk createScript(int scriptId, String... handlerNames) {
        List<ScriptChunk.Handler> handlers = new ArrayList<>();
        for (int i = 0; i < handlerNames.length; i++) {
            // Single RET instruction
            List<ScriptChunk.Handler.Instruction> instructions = List.of(
                new ScriptChunk.Handler.Instruction(0, Opcode.RET, 0x01, 0)
            );
            handlers.add(new ScriptChunk.Handler(
                i,              // nameId = index
                0,              // handlerVectorPos
                1,              // bytecodeLength
                0,              // bytecodeOffset
                0,              // argCount
                0,              // localCount
                0,              // globalsCount
                0,              // lineCount
                List.of(),      // argNameIds
                List.of(),      // localNameIds
                instructions,
                Map.of(0, 0)    // bytecodeIndexMap
            ));
        }

        return new ScriptChunk(
            null,           // file
            scriptId,
            ScriptChunk.ScriptType.PARENT,
            0,              // behaviorFlags
            handlers,
            List.of(),      // literals
            List.of(),      // properties
            List.of(),      // globals
            new byte[0]     // rawBytecode
        );
    }

    /**
     * Create a ScriptInstance targeting a specific scriptId.
     */
    private Datum.ScriptInstance createInstance(int scriptId) {
        return new Datum.ScriptInstance(scriptId, new LinkedHashMap<>());
    }

    /**
     * Create a ScriptInstance with an ancestor chain.
     */
    private Datum.ScriptInstance createInstanceWithAncestor(int scriptId, Datum.ScriptInstance ancestor) {
        Map<String, Datum> props = new LinkedHashMap<>();
        props.put(Datum.PROP_ANCESTOR, ancestor);
        return new Datum.ScriptInstance(scriptId, props);
    }

    /**
     * Install a mock CastLibProvider that maps scriptId -> handlerName -> HandlerLocation.
     */
    private void installProvider(Map<Integer, ScriptChunk> scripts) {
        CastLibProvider.setProvider(new CastLibProvider() {
            @Override
            public int getCastLibByNumber(int n) { return -1; }
            @Override
            public int getCastLibByName(String name) { return -1; }
            @Override
            public Datum getCastLibProp(int n, String prop) { return Datum.VOID; }
            @Override
            public boolean setCastLibProp(int n, String prop, Datum v) { return false; }
            @Override
            public Datum getMember(int castLib, int member) { return Datum.VOID; }
            @Override
            public Datum getMemberByName(int castLib, String name) { return Datum.VOID; }
            @Override
            public int getCastLibCount() { return 0; }
            @Override
            public Datum getMemberProp(int castLib, int member, String prop) { return Datum.VOID; }
            @Override
            public boolean setMemberProp(int castLib, int member, String prop, Datum v) { return false; }

            @Override
            public HandlerLocation findHandlerInScript(int scriptId, String handlerName) {
                ScriptChunk script = scripts.get(scriptId);
                if (script == null) return null;

                // Find handler by nameId index (matches our naming convention)
                for (int i = 0; i < script.handlers().size(); i++) {
                    ScriptChunk.Handler handler = script.handlers().get(i);
                    // Our test scripts use nameId == index into handler list
                    // We need to get the actual handler name from our scripts map
                    String[] names = getHandlerNames(script);
                    if (i < names.length && names[i].equalsIgnoreCase(handlerName)) {
                        return new HandlerLocation(1, script, handler, null);
                    }
                }
                return null;
            }
        });
    }

    /** Lookup table: scriptId -> handler names (matches createScript ordering). */
    private final Map<Integer, String[]> scriptHandlerNames = new HashMap<>();

    private ScriptChunk createScriptTracked(int scriptId, String... handlerNames) {
        scriptHandlerNames.put(scriptId, handlerNames);
        return createScript(scriptId, handlerNames);
    }

    private String[] getHandlerNames(ScriptChunk script) {
        return scriptHandlerNames.getOrDefault(script.id(), new String[0]);
    }

    // ========== Tests ==========

    @Test
    void testDispatchPrepareFrameToSingleTarget() {
        // Create a script with a prepareFrame handler
        ScriptChunk script = createScriptTracked(10, "prepareFrame");
        installProvider(Map.of(10, script));

        // Create a timeout with a script instance target
        Datum.ScriptInstance target = createInstance(10);
        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);

        // Dispatch prepareFrame system event
        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");

        // Should have called prepareFrame on the target (not onTimer!)
        assertEquals(1, handlerCalls.size());
        assertEquals("handler#0@10", handlerCalls.get(0));
    }

    @Test
    void testDispatchToMultipleTargets() {
        // Two scripts, each with prepareFrame
        ScriptChunk script1 = createScriptTracked(10, "prepareFrame");
        ScriptChunk script2 = createScriptTracked(20, "prepareFrame");
        installProvider(Map.of(10, script1, 20, script2));

        Datum.ScriptInstance target1 = createInstance(10);
        Datum.ScriptInstance target2 = createInstance(20);
        timeoutManager.createTimeout("timer1", 1000, "onTimer", target1);
        timeoutManager.createTimeout("timer2", 2000, "onTick", target2);

        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");

        // Both targets should receive prepareFrame
        assertEquals(2, handlerCalls.size());
        assertEquals("handler#0@10", handlerCalls.get(0));
        assertEquals("handler#0@20", handlerCalls.get(1));
    }

    @Test
    void testDispatchDifferentSystemEvents() {
        // Script with both prepareFrame and exitFrame
        ScriptChunk script = createScriptTracked(10, "prepareFrame", "exitFrame");
        installProvider(Map.of(10, script));

        Datum.ScriptInstance target = createInstance(10);
        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);

        // Dispatch prepareFrame
        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");
        assertEquals(1, handlerCalls.size());
        assertEquals("handler#0@10", handlerCalls.get(0));

        handlerCalls.clear();

        // Dispatch exitFrame
        timeoutManager.dispatchSystemEvent(vm, "exitFrame");
        assertEquals(1, handlerCalls.size());
        assertEquals("handler#1@10", handlerCalls.get(0));
    }

    @Test
    void testMissingHandlerSilentlySkipped() {
        // Script with ONLY prepareFrame (no exitFrame)
        ScriptChunk script = createScriptTracked(10, "prepareFrame");
        installProvider(Map.of(10, script));

        Datum.ScriptInstance target = createInstance(10);
        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);

        // Dispatch exitFrame — target doesn't have this handler
        timeoutManager.dispatchSystemEvent(vm, "exitFrame");

        // Should silently skip — no calls, no exceptions
        assertEquals(0, handlerCalls.size());
    }

    @Test
    void testNonScriptInstanceTargetSkipped() {
        // Create a timeout with a non-ScriptInstance target (e.g., a string)
        timeoutManager.createTimeout("timer1", 1000, "onTimer", Datum.of("notAnInstance"));

        // Should not throw, should silently skip
        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");
        assertEquals(0, handlerCalls.size());
    }

    @Test
    void testNoTimeoutsIsNoOp() {
        // Empty timeout list — should be a no-op
        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");
        assertEquals(0, handlerCalls.size());
    }

    @Test
    void testForgottenTimeoutNotDispatched() {
        ScriptChunk script = createScriptTracked(10, "prepareFrame");
        installProvider(Map.of(10, script));

        Datum.ScriptInstance target = createInstance(10);
        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);

        // Forget it
        timeoutManager.forgetTimeout("timer1");

        // Dispatch — should not call anything
        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");
        assertEquals(0, handlerCalls.size());
    }

    @Test
    void testDispatchCallsEventNameNotTimeoutHandler() {
        // Key distinction: system events call the EVENT name (prepareFrame),
        // NOT the timeout's own handler (onTimer)
        ScriptChunk script = createScriptTracked(10, "onTimer", "prepareFrame");
        installProvider(Map.of(10, script));

        Datum.ScriptInstance target = createInstance(10);
        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);

        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");

        // Should call prepareFrame (handler#1), NOT onTimer (handler#0)
        assertEquals(1, handlerCalls.size());
        assertEquals("handler#1@10", handlerCalls.get(0));
    }

    @Test
    void testDispatchPassesNoArgs() {
        // Verify that system event dispatch passes empty args (not TimeoutRef)
        // and passes the target as the receiver
        List<List<Datum>> capturedArgs = new ArrayList<>();
        List<Datum> capturedReceivers = new ArrayList<>();

        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                capturedArgs.add(info.arguments());
                capturedReceivers.add(info.receiver());
                handlerCalls.add(info.handlerName() + "@" + info.scriptId());
            }
        });

        ScriptChunk script = createScriptTracked(10, "prepareFrame");
        installProvider(Map.of(10, script));

        Datum.ScriptInstance target = createInstance(10);
        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);

        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");

        assertEquals(1, capturedArgs.size());
        // System events pass empty args (no TimeoutRef, unlike periodic firing)
        List<Datum> args = capturedArgs.get(0);
        assertTrue(args.isEmpty(), "System event args should be empty, got: " + args);
        // The target instance is passed as receiver
        assertInstanceOf(Datum.ScriptInstance.class, capturedReceivers.get(0));
    }

    @Test
    void testAncestorChainWalking() {
        // Target instance (scriptId=10) does NOT have prepareFrame
        // Its ancestor (scriptId=20) DOES have prepareFrame
        ScriptChunk script10 = createScriptTracked(10, "someOtherHandler");
        ScriptChunk script20 = createScriptTracked(20, "prepareFrame");
        installProvider(Map.of(10, script10, 20, script20));

        Datum.ScriptInstance ancestor = createInstance(20);
        Datum.ScriptInstance target = createInstanceWithAncestor(10, ancestor);

        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);

        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");

        // Should find prepareFrame on the ancestor and call it
        assertEquals(1, handlerCalls.size());
        assertEquals("handler#0@20", handlerCalls.get(0));
    }

    @Test
    void testMixedTargets_SomeWithHandlerSomeWithout() {
        // target1 (scriptId=10) HAS prepareFrame
        // target2 (scriptId=20) does NOT have prepareFrame
        // target3 (scriptId=30) HAS prepareFrame
        ScriptChunk script10 = createScriptTracked(10, "prepareFrame");
        ScriptChunk script20 = createScriptTracked(20, "exitFrame");  // different handler
        ScriptChunk script30 = createScriptTracked(30, "prepareFrame");
        installProvider(Map.of(10, script10, 20, script20, 30, script30));

        timeoutManager.createTimeout("t1", 1000, "h1", createInstance(10));
        timeoutManager.createTimeout("t2", 1000, "h2", createInstance(20));
        timeoutManager.createTimeout("t3", 1000, "h3", createInstance(30));

        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");

        // Only target1 and target3 should be called; target2 silently skipped
        assertEquals(2, handlerCalls.size());
        assertEquals("handler#0@10", handlerCalls.get(0));
        assertEquals("handler#0@30", handlerCalls.get(1));
    }

    @Test
    void testNoProviderSilentlySkips() {
        // No CastLibProvider installed — should silently skip all targets
        CastLibProvider.clearProvider();

        Datum.ScriptInstance target = createInstance(10);
        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);

        // Should not throw
        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");
        assertEquals(0, handlerCalls.size());
    }

    @Test
    void testClearedTimeoutsNotDispatched() {
        ScriptChunk script = createScriptTracked(10, "prepareFrame");
        installProvider(Map.of(10, script));

        timeoutManager.createTimeout("t1", 1000, "h1", createInstance(10));
        timeoutManager.createTimeout("t2", 1000, "h2", createInstance(10));

        // Clear all
        timeoutManager.clear();

        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");
        assertEquals(0, handlerCalls.size());
    }

    @Test
    void testAllFiveSystemEvents() {
        // Script with all 5 system event handlers
        ScriptChunk script = createScriptTracked(10,
            "prepareMovie", "startMovie", "stopMovie", "prepareFrame", "exitFrame");
        installProvider(Map.of(10, script));

        timeoutManager.createTimeout("timer1", 1000, "onTimer", createInstance(10));

        String[] events = {"prepareMovie", "startMovie", "stopMovie", "prepareFrame", "exitFrame"};
        for (String event : events) {
            handlerCalls.clear();
            timeoutManager.dispatchSystemEvent(vm, event);
            assertEquals(1, handlerCalls.size(),
                "Expected 1 call for event '" + event + "'");
        }
    }

    @Test
    void testScriptRefBasedLookup() {
        // Target has a __scriptRef__ property (castLib=1, member=10)
        // Provider should be called with (castLib, member, handlerName)
        ScriptChunk script = createScriptTracked(10, "prepareFrame");

        List<String> lookupCalls = new ArrayList<>();

        CastLibProvider.setProvider(new CastLibProvider() {
            @Override
            public int getCastLibByNumber(int n) { return -1; }
            @Override
            public int getCastLibByName(String name) { return -1; }
            @Override
            public Datum getCastLibProp(int n, String prop) { return Datum.VOID; }
            @Override
            public boolean setCastLibProp(int n, String prop, Datum v) { return false; }
            @Override
            public Datum getMember(int castLib, int member) { return Datum.VOID; }
            @Override
            public Datum getMemberByName(int castLib, String name) { return Datum.VOID; }
            @Override
            public int getCastLibCount() { return 0; }
            @Override
            public Datum getMemberProp(int castLib, int member, String prop) { return Datum.VOID; }
            @Override
            public boolean setMemberProp(int castLib, int member, String prop, Datum v) { return false; }

            @Override
            public HandlerLocation findHandlerInScript(int castLib, int member, String handlerName) {
                lookupCalls.add("findHandler(" + castLib + "," + member + "," + handlerName + ")");
                if (castLib == 1 && member == 10 && handlerName.equals("prepareFrame")) {
                    return new HandlerLocation(1, script, script.handlers().get(0), null);
                }
                return null;
            }

            @Override
            public HandlerLocation findHandlerInScript(int scriptId, String handlerName) {
                lookupCalls.add("findHandlerById(" + scriptId + "," + handlerName + ")");
                return null;
            }
        });

        // Create instance with __scriptRef__
        Map<String, Datum> props = new LinkedHashMap<>();
        props.put(Datum.PROP_SCRIPT_REF, new Datum.ScriptRef(1, 10));
        Datum.ScriptInstance target = new Datum.ScriptInstance(10, props);

        timeoutManager.createTimeout("timer1", 1000, "onTimer", target);
        timeoutManager.dispatchSystemEvent(vm, "prepareFrame");

        // Should use the ScriptRef-based lookup (castLib, member, handlerName)
        assertTrue(lookupCalls.stream().anyMatch(c -> c.startsWith("findHandler(1,10,")),
            "Expected castLib-based lookup call; got: " + lookupCalls);
        assertEquals(1, handlerCalls.size());
    }
}
