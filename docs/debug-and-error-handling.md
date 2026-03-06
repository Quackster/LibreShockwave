# Debug and Error Handling

## Overview

LibreShockwave provides debug playback logging and Lingo call stack traces to help diagnose script errors during movie playback. This is especially useful for complex movies like Habbo Hotel where errors occur deep in the Lingo call chain.

## Error Handling Flow

When a Lingo script error occurs:

1. **Exception thrown** during bytecode execution (e.g. `LingoException`)
2. **`LingoVM.executeHandler()` catch block** logs the error and call stack (if debug enabled)
3. **`fireAlertHook()`** checks if `the alertHook` is set to a script instance
   - If the alertHook handler returns `true`, the error is **suppressed** and execution continues
   - If it returns `false` or no alertHook is set, the exception propagates
4. **`safeExecuteHandler()`** (in `CallOpcodes` and `ScriptInstanceMethodDispatcher`) catches `LingoException`, logs it with the call stack, sets the VM error state, and returns `VOID`
5. When `inErrorState` is `true`, no more handlers execute (like dirplayer-rs `stop()`)

### Habbo Example

The `initializeValidPartLists` handler fails during figuredata parsing. The error flows:

```
initializeValidPartLists -> LingoException
  -> executeHandler catch -> logs stack trace
  -> fireAlertHook() -> alertHook handler returns true
  -> error suppressed, player shows in-game error dialog
  -> playback continues
```

## Lingo Call Stack Traces

When `DebugConfig.isDebugPlaybackEnabled()` is `true` (the default), errors print a Java-style call stack:

```
[Lingo] Exception in initializeValidPartLists: ...
Lingo call stack:
  at initializeValidPartLists ("Figure Data" PARENT_SCRIPT) [bytecode 45]
  at initThread ("Fuse Entry" PARENT_SCRIPT) [bytecode 88]
  at startMovie ("Movie Script" MOVIE_SCRIPT) [bytecode 12]
```

Stack traces are printed at all three error catch sites:
- `LingoVM.executeHandler()` catch block
- `CallOpcodes.safeExecuteHandler()`
- `ScriptInstanceMethodDispatcher.safeExecuteHandler()`

## DebugConfig

`com.libreshockwave.vm.DebugConfig` is a static configuration class controlling debug output.

### Java API

```java
DebugConfig.isDebugPlaybackEnabled();       // default: true
DebugConfig.setDebugPlaybackEnabled(false);  // disable logging
```

### WASM Export

```javascript
// From worker or direct WASM access:
exports.setDebugPlaybackEnabled(1);  // enable
exports.setDebugPlaybackEnabled(0);  // disable
```

### JavaScript API (shockwave-lib.js)

```javascript
// Via options on create:
var player = ShockwavePlayer.create(canvas, {
    debugPlayback: true,          // default: true
    onDebugLog: function(log) {   // callback for each tick's debug output
        console.log(log);
    }
});

// Toggle at runtime:
player.setDebugPlayback(false);
```

## Playback Event Logging

When debug is enabled, handler entry is logged (except high-frequency handlers):

```
[Lingo] >> initThread in "Fuse Entry" (PARENT_SCRIPT)
[Lingo] >> startMovie in "Movie Script" (MOVIE_SCRIPT)
```

Suppressed high-frequency handlers (would flood the log):
- `exitFrame`, `enterFrame`, `stepFrame`, `idle`, `prepareFrame`

## WASM Debug Log Pipeline

1. WASM `System.out`/`System.err` writes to `WasmEntry.debugLog` StringBuilder
2. Each tick, the worker calls `getDebugLog()` to drain the buffer
3. Debug log string is included in the `frame` message to main thread
4. Main thread fires `onDebugLog` callback if configured
