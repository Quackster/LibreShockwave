# LibreShockwave — Project Status

## Goal

Render Macromedia Director (Shockwave) content — specifically the Habbo Hotel v14 client — in a browser. LibreShockwave is a Java reimplementation of the Director player runtime, including the Lingo VM, cast library system, and network layer.

## Architecture

Multi-module Gradle project:

| Module | Purpose |
|---|---|
| `sdk` | Director file format parsing (chunks, casts, scripts) |
| `vm` | Lingo bytecode VM, opcodes, builtins, ancestor chain |
| `player-core` | Player runtime: cast loading, net manager, frame loop |
| `player` | Desktop player (Swing) |
| `player-wasm` | Browser player (TeaVM → WebAssembly) |
| `cast-extractor` | Tooling for extracting/inspecting cast libraries |

## Startup Flow (Habbo v14)

The Habbo client uses the **Fuse framework** — a Lingo application framework that manages objects, downloads, threads, and sprites through a centralized Object Manager.

### Startup Sequence

1. **Frame 1–4**: Score plays through empty frames
2. **Frame 5 (exitFrame on "Init" script)**: `netDone()` check → calls `startClient()`
3. **startClient()**: Constructs the Object Manager, dumps system variables, initializes CastLoad/Special Services/String Services managers, creates the Thread Manager, and creates the Core Thread (`create(#core)`)
4. **Core Thread construct()**: Calls `updateState("load_variables")` which:
   - Shows the loading logo via Sprite Manager
   - Calls `queueDownload("external_variables.txt")` → Download Manager creates a Download Instance, starts `getNetText(url)`
   - Registers a download callback: when done, call `updateState("load_params")` on `#core_component`
5. **Frame 6+ (prepareFrame on Object Manager)**: Should broadcast `call(#update, pUpdateList)` each frame, which triggers Download Manager → Download Instance → checks `netDone()`/`netError()`/`getStreamStatus()` → fires callback → advances state machine
6. **State machine advances**: `load_variables` → `load_params` → `load_texts` → `init_threads` → ... → timeout creation (Milestone 3)

### Milestones (tracked by StartupTraceTest)

| # | Milestone | Status |
|---|---|---|
| 1 | `create(#core)` returns successfully | ✅ Reached |
| 2 | `prepareFrame` fires on Object Manager | ✅ Reached |
| 3 | Timeout created (state machine completes) | ❌ Blocked |

## Current Blocker: Bug 16 — `GET_OBJ_PROP` on PropList doesn't support `.count`

### Root Cause (Identified)

`GET_OBJ_PROP` on `Datum.PropList` only does key lookup (`pl.properties().getOrDefault(propName, VOID)`). It does **not** support built-in properties like `count`, `ilk`, or `length`. In Director, `[:].count` returns `0` and `[#a: 1, #b: 2].count` returns `2`.

### How This Blocks Milestone 3

The chain of events:

1. `queueDownload("external_variables.txt")` calls `Download Manager Class.queue()`, which adds the download to `pWaitingList` (a PropList) and then calls `updateQueue()`.
2. `updateQueue()` checks `pActiveList.count < getIntVariable("net.operation.count")` and `pWaitingList.count > 0` before activating downloads.
3. With the bug, `pWaitingList.count` resolves via `getObjProp "count"` on a PropList → key lookup → returns **VOID** (not the actual count).
4. `VOID > 0` evaluates to false → the download activation code is skipped.
5. **`receiveUpdate(me.getID())` at offset 147 in `updateQueue` is never reached** — this is the call that registers the Download Manager in the Object Manager's `pUpdateList`.
6. `pUpdateList` stays empty → `prepareFrame` broadcasts to nothing → Download Manager never receives `update()` → `netDone()` never checked → state machine never advances.

### Who Calls `receiveUpdate` (Bytecode Scan)

A full scan of all handlers in fuse_client for `extCall 109` (receiveUpdate) and `extCall 107` (receivePrepare) found:

| Script | Handler | Calls |
|---|---|---|
| Object API | receiveUpdate | OBJ_CALL → Object Manager (wrapper) |
| Object API | receivePrepare | OBJ_CALL → Object Manager (wrapper) |
| **Download Manager Class** | **updateQueue** | **extCall receiveUpdate** (offset 147) |
| CastLoad Manager Class | AddNextpreloadNetThing | extCall receivePrepare |
| Visualizer Instance Class | drag | extCall receiveUpdate |
| Window Instance Class | scale | extCall receivePrepare |
| Window Instance Class | drag | extCall receiveUpdate |
| Event Agent Class | registerEvent | extCall receivePrepare |
| Loading Bar Class | define | extCall receivePrepare |
| FPS Test Class | construct | extCall receiveUpdate |

### `updateQueue` Bytecode (Download Manager Class)

```
[0000] getProp 773          ; pActiveList (PropList)
[0003] getObjProp 22        ; .count  ← BUG: returns VOID instead of 0
[0005] pushCons 9           ; "net.operation.count"
[0009] extCall 376          ; getIntVariable → 2
[0012] lt                   ; VOID < 2 → true (VOID=0)
[0013] jmpIfZ 136           ; passes
[0016] getProp 772          ; pWaitingList (PropList with 1 queued download)
[0019] getObjProp 22        ; .count  ← BUG: returns VOID instead of 1
[0021] pushZero 0
[0022] gt                   ; VOID > 0 → false ← BLOCKS HERE
[0023] jmpIfZ 126           ; jumps past download activation + receiveUpdate
...
[0147] extCall 109          ; receiveUpdate ← never reached
```

### Fix (In Progress)

Add built-in property support for PropList in `GET_OBJ_PROP` (file: `vm/.../opcode/PropertyOpcodes.java`):

```java
// Before (bug):
case Datum.PropList pl -> pl.properties().getOrDefault(propName, Datum.VOID);

// After (fix):
case Datum.PropList pl -> getPropListProp(pl, propName);

private static Datum getPropListProp(Datum.PropList pl, String propName) {
    String prop = propName.toLowerCase();
    return switch (prop) {
        case "count", "length" -> Datum.of(pl.properties().size());
        case "ilk" -> Datum.symbol("propList");
        default -> pl.properties().getOrDefault(propName, Datum.VOID);
    };
}
```

**Complication**: This fix exposes a pre-existing bug in `replaceChunks` (String Services Class). The `replaceChunks` handler hits the VM step limit (500000 instructions — infinite loop) when called with `replaceChunks("text...", #\r, "")`. This happens during `startClient` → `dumpTextField("System Texts")` → `dump()` → `replaceChunks()`. The `replaceChunks` infinite loop must be fixed as Bug 17 before Bug 16's fix can land cleanly.

### Diagnostic Enhancements

The `StartupTraceTest` now dumps:
- `registerManager` bytecode (shows it adds to pManagerList, not pUpdateList)
- `receiveUpdate` bytecode (shows it adds to pUpdateList, also creates a timeout)
- `create` bytecode (full Object Manager create flow)
- Declared property names
- Object Manager instance property values after each `registerManager` call
- **NEW**: All handlers in fuse_client that reference `receiveUpdate`/`receivePrepare`
- **NEW**: `constructDownloadManager`, `queueDownload`, `updateQueue` bytecodes
- **NEW**: Download Manager Class `construct` and `update` bytecodes

## Bugs Fixed

See [BUGS.md](BUGS.md) for detailed bug descriptions.

### Milestone 1 Bugs (1–6)
Bytecode/opcode fixes: variable multiplier, POP count, JMP_IF_Z, PUSH_LIST/PROP_LIST, string operations, list operations, property access, ancestor chain walking, script instance initialization.

### Milestone 2 Bugs (7–9)
- Bug 7: `field()` combined slot number decoding
- Bug 8: `call()` builtin for broadcasting to object lists
- Bug 9: `handler()` method on ScriptInstance

### Milestone 3 Bugs (10–15, all fixed, but milestone not yet reached)
- Bug 10: `getNetText()` alias registration
- Bug 11: `netError()` returns `"OK"` string
- Bug 12: `getStreamStatus()` returns PropList
- Bug 13: `new(#type, castLib)` dynamic member creation
- Bug 14: `member.text` property set/get
- Bug 15: `cursor()` stub

### Next Bugs (16–17)
- **Bug 16**: `GET_OBJ_PROP` on PropList doesn't support `.count` — root cause of `pUpdateList` staying empty (fix written, blocked by Bug 17)
- **Bug 17**: `replaceChunks` infinite loop — pre-existing bug exposed by Bug 16 fix; need to dump `replaceChunks` bytecode and fix loop termination

## Running

```bash
# Build & test
./gradlew build
./gradlew test

# Run startup trace (requires habbo.dcr at configured path)
./gradlew :player-core:runStartupTraceTest
```
