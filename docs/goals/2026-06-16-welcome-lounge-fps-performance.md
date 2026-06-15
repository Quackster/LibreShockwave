# Welcome Lounge FPS Performance

Date: 2026-06-16

Goal: improve frame pacing and FPS after entering the v31 Welcome Lounge from:

```text
http://localhost:3000/venus-quackster-harness
```

Reproduction:

1. Open the harness and wait for the logged-in hotel exterior with the Navigator open.
2. In the Navigator `Public Spaces` tab, select `Welcome Lounge`.
3. Click `Go`.
4. After the room loads, compare frame pacing against native v31.

User-supplied references:

```text
/opt/git/v31_room_load/v31_native.png
/opt/git/v31_room_load/v31_welcome_lounge.png
```

The native exterior reference shows the expected Navigator state before clicking
`Go`. The native room reference shows the target Welcome Lounge scene where the
FPS drop is noticeable in the C++/WASM harness.

Relevant v31 asset and Lingo paths:

```text
/opt/git/v31_assets/
/opt/git/v31_assets/hh_interface.cct
/opt/git/v31_assets/hh_interface
/opt/git/v31_assets/projectorrays_lingo/
/opt/git/v31_assets/projectorrays_lingo/fuse_client/
/opt/git/v31_assets/projectorrays_lingo/fuse_client/casts/External/ParentScript 51 - Connection Instance Class.ls
/opt/git/v31_assets/projectorrays_lingo/fuse_client/casts/External/ParentScript 52 - Multiuser Instance Class.ls
/opt/git/v31_assets/projectorrays_lingo/hh_entry_init/casts/External/ParentScript 5 - HugeInt15.ls
/opt/git/v31_assets/projectorrays_lingo/hh_entry_init/casts/External/ParentScript 8 - Login Handler Class.ls
```

## Implementation Guardrails

2026-06-16 user instruction: do not hardcode Lingo functions into C++ as part
of this FPS work. BigInt-style hotspots are the concrete example: the v31
BigInt/HugeInt/HugeInt15 scripts must continue to run as app-authored Lingo,
and any speedup must come from generic VM/runtime behavior rather than C++
recognizing those script or handler names.

Do not hardcode app-authored Lingo functions into C++ to improve this screen's
FPS. BigInt/HugeInt is the explicit example: the runtime must not grow native
branches for project-authored handlers such as `BigInt`, `HugeInt`, `HugeInt15`,
`powMod`, `Modulo`, `div`, `getString`, or `getByteArray`.

This rule is broader than BigInt. Do not teach C++ about v31 parent-script
names, movie-script handler names, room/login scripts, Resource API helper
methods, or any other asset-pack-specific Lingo APIs just because they appear
hot in the profiler. Treat app-authored BigInt/HugeInt-style Lingo as ordinary
Lingo.

Retained fixes must be generic VM/runtime, Director-semantics, data-structure,
bytecode-dispatch, builtin-dispatch, or scheduling improvements. It is
acceptable to optimize generic Director/Lingo semantics such as integer
arithmetic, list and property-list operations, script-instance property access,
string conversion, handler lookup, bytecode dispatch, cooperative scheduling,
and builtin dispatch, as long as those changes remain independent of Habbo v31
script names and handler names.

Current instruction: do not hardcode Lingo functions into C++ as a fix for this
goal. BigInt/HugeInt is the concrete example, but the rule applies to any
project-authored Lingo handler that appears hot in profiling. If a handler such
as `powMod`, `Modulo`, `div`, `getString`, or `getByteArray` becomes faster, it
must be because generic VM/runtime behavior improved, not because C++ recognizes
that Lingo API by name.

2026-06-16 addendum: this remains a hard constraint for follow-up work. Do not
add C++ branches keyed to authored Lingo function names, parent-script names, or
movie-script names, including the BigInt/HugeInt/HugeInt15 methods and the
v31 Resource API helpers seen in the room profile. Acceptable fixes must be
generic to the VM/runtime, Director semantics, handler dispatch, bytecode
execution, data structures, or cooperative scheduling. If a future profile
shows `getmemnum`, `memberExists`, `updateProcess`, `createPassiveObject`,
`solveMembers`, `parseCallback`, `HugeInt15`, `powMod`, `Modulo`, `div`,
`getString`, or `getByteArray` as hot, do not implement those APIs natively by
name in C++.

Hard rule for this goal: do not hardcode authored Lingo functions into the C++
runtime, even when profiling proves they are hot. BigInt/HugeInt/HugeInt15 is
the canonical example, but the rule applies to every project-authored handler.
The engine may optimize generic Lingo execution, lists, property lists,
script-instance properties, handler lookup, arithmetic, strings, bytecode
dispatch, scheduling, or Director builtins. It must not add special cases that
recognize app script functions by name.

## Findings So Far

The visible frame drop is not primarily caused by browser canvas presentation.
The web shell renders every produced frame with `putImageData`, but profiling
shows the large stalls occur before frame delivery, inside the WASM worker.

Measured warnings from the harness after instrumentation:

```text
tick took 2977.8ms
tick took 1325.6ms
tick took 3550.3ms
tick took 27065.7ms
socket message took 29386.4ms
host xtra callbacks took 29386ms
multiuser message took 29386ms
```

The diagnostic split shows `QueuedMultiuserBridge::deliverMessageBytes` is not
the expensive phase. The time is spent in host Xtra callbacks, specifically:

```text
cpp/wasm/WasmBridge.cpp
processHostXtraCallbacks(...)
  ctx.player->xtraManager().tickAll()
  ctx.player->vm().flushDeferredTasks()
```

The Habbo connection Lingo callback path is:

```text
ParentScript 51 - Connection Instance Class.ls
xtraMsgHandler
  decrypt/decode incoming content
  msghandler
  forwardMsg
  listener callbacks
```

One incoming WebSocket message can contain many Habbo protocol messages. The
current runtime processes the entire callback batch synchronously inside the
socket event handling path, so the worker cannot produce frames while that batch
runs. This explains the observed multi-second freezes and the apparent FPS drop
around room entry.

There are also long normal `tick` stalls during the same login/room setup flow.
Those happen in the ordinary frame cycle script pump, not only in the WebSocket
event handler:

```text
cpp/src/player/Player.cpp
Player::executeFrameCycle(...)
  inputHandler_.processInputEvents()
  frameContext_.executeFrame()
  timeoutProcessor_()
  xtraManager_.tickAll()
  processUpdatingObjects()
  vm_.flushDeferredTasks()
  frameContext_.advanceFrame()
```

## Current Diagnostic Changes

Temporary or useful diagnostic instrumentation was added in:

```text
cpp/wasm/WasmBridge.cpp
```

It reports separate slow-operation warnings for:

```text
host xtra callbacks
multiuser message delivery
```

This made the current bottleneck clear: the slow phase is callback execution,
not byte decoding or queue insertion.

A generic micro-optimization was also added in:

```text
cpp/src/lingo/builtin/BuiltinRegistry.cpp
cpp/tests/sdk_foundation_test.cpp
```

`StringBuiltins::offset` now uses `std::string_view` for direct string, field
text, and symbol inputs instead of copying both arguments before scanning. This
matches the hot Habbo parser call:

```lingo
offset(numToChar(1), tContent)
```

This optimization is behavior-preserving and tested, but it did not remove the
large ~27-29 second stalls by itself.

## BigInt Hotspot

Deeper handler timing narrowed the biggest callback stall to the v31
Diffie-Hellman/security Lingo:

```text
ParentScript 8 - Login Handler Class.ls
responseWithPublicKey
  clientG.powMod(pClientSecret, clientP).getString()

handleServerSecretKey
  tClientBig.powMod(pClientSecret, clientP)
  tSharedKey.getByteArray()
```

The bigint implementation is in:

```text
ParentScript 5 - HugeInt15.ls
powMod
Modulo
div
getByteArray
getString
```

With a temporary 100ms handler threshold, `powMod` showed repeated
`Modulo -> div` calls. Each `div` was about 100ms in WASM:

```text
handler div took 100-102ms (~18k instructions)
handler Modulo took 100-102ms
handler powMod took 26885ms (4357 instructions)
handler responseWithPublicKey took 26988ms
```

The socket-side shared-secret callback also showed:

```text
socket message took 3211.7ms
handler getByteArray took 2251ms
handler handleServerSecretKey took 3206ms
host xtra callbacks took 3211ms
multiuser message took 3211ms
```

## Non-Goal: Hardcoded Lingo Functions

A C++ bigint fast path was briefly used as a proof-of-cause diagnostic. It
confirmed that the room-entry freeze is dominated by the interpreted
`HugeInt15` Diffie-Hellman path:

```text
powMod
getString
getByteArray
```

That change was removed and must not be restored as the final fix. The engine
should not hardcode app-level Lingo parent-script methods into C++, including
`HugeInt15.powMod`, `getString`, or `getByteArray`. The acceptable direction is
generic VM/runtime improvement, scheduling, data representation, or built-in
Director semantics that benefits this script without baking this script into
the engine.

Do not add special C++ implementations for app-authored Lingo functions just
because they are expensive in this asset pack. This includes BigInt/HugeInt
helpers and any similarly named arbitrary-precision routines. The VM may improve
generic arithmetic, list/property access, handler dispatch, bytecode execution,
or scheduling, but it must not learn specific script APIs like `BigInt`,
`HugeInt`, `HugeInt15`, `powMod`, `Modulo`, `div`, `getString`, or
`getByteArray`.

This applies broadly to Lingo methods that happen to be hot in v31. Do not add
C++ branches that recognize specific app-level handler names, parent-script
names, or room/login scripts such as the v31 `HugeInt15` BigInt implementation.
If a future optimization helps `HugeInt15`, it should be because a generic
operation became faster, not because the engine knows that script by name.

Treat app-authored BigInt/HugeInt-style Lingo as ordinary Lingo. Do not move
project-specific arbitrary-precision helpers into C++ by name, and do not add
native replacements for methods such as `powMod`, `Modulo`, `div`, `getString`,
or `getByteArray` just because this v31 asset pack uses them during login. A
generic numeric representation, bytecode/JIT-style optimization, interpreter
dispatch improvement, or cooperative scheduling change can be considered, but
the engine should not become aware of the v31 BigInt script API.

## Generic Runtime Work Tried

Current generic changes under test:

```text
cpp/include/libreshockwave/lingo/Datum.hpp
cpp/include/libreshockwave/lingo/builtin/BuiltinRegistry.hpp
cpp/src/lingo/Datum.cpp
cpp/src/lingo/builtin/BuiltinRegistry.cpp
cpp/src/lingo/vm/ExecutionContext.cpp
cpp/src/lingo/vm/OpcodeRegistry.cpp
cpp/src/lingo/vm/util/AncestorChainWalker.cpp
cpp/src/player/SpriteProperties.cpp
```

`ExecutionContext::popArgs` now reserves and appends popped values before a
single reverse, avoiding default construction followed by assignment for every
nested call.

The immediate object-call fast path for built-in Lingo lists now covers more
generic Director-style list methods:

```text
count
getFirst
getLast
addAt
deleteAt
duplicate
```

This extends existing generic list fast paths for:

```text
getAt
setAt
add
append
```

Temporary opcode profiling showed that much of the apparent opcode time is
charged to nested `pushArgList` / `pushArgListNoRet` calls, because those opcodes
execute child handlers immediately. The performance issue is therefore many
nested interpreted Lingo calls under `HugeInt15.div` / `Modulo`, not a single
slow arithmetic opcode.

Early profiling with the generic list and argument changes did not materially
move the profiled `powMod` path. Rebuild and remeasure without opcode-profiler
overhead before deciding the next runtime-level optimization.

## Runtime Improvements Kept

Generic script-instance property indexing was added to `Datum::ScriptInstanceRef`.
Script instance properties were previously scanned linearly with
case-insensitive comparisons on every get/set/has-property operation. The new
index mirrors the existing prop-list index shape and is used by both direct
`ScriptInstanceRef` access and the ancestor-chain helper.

The built-in `script()` resolver now caches successful string/symbol lookups by
resolved cast-library scope plus normalized script name. This is a generic
Director built-in optimization. It avoids resolving the same parent script name
thousands of times during arithmetic-heavy constructor loops such as:

```lingo
pScript = script("HugeInt15")
```

Declared script properties for brand-new script instances are now initialized
directly instead of checking `hasProperty` before inserting each declared
property. This applies only to fresh instances created from a script cast member.

Immediate object-call handling now also covers common prop-list methods for
name-keyed access:

```text
count
getProp
getPropRef
getAProp
getProperty
getAt with string/symbol keys
setAt
setProp
setAProp
```

Numeric `getAt` on prop lists is intentionally left on the existing path so
snapshot-style indexed iteration keeps its mutation semantics.

The member-registry alias refresh used by the generic
`MemberRegistryMethodDispatcher` now runs at most once per script-instance
registry during a top-level handler. This avoids rescanning every cast library
for `memberalias.index` on every unresolved member lookup inside a single
socket/tick batch, while still allowing a fresh refresh on later top-level
callbacks.

Fallback member-name resolution for the same member registry is now cached per
top-level handler. The cache is keyed by normalized member name and stores the
resolved Director slot, including alias hits and misses. It is consulted only
after the instance registry prop-list has been checked and current alias text
has been refreshed, and it is cleared at top-level handler boundaries, alias
index imports, and dynamic member creation paths. This keeps the optimization
generic to resource/member registry lookup and does not recognize v31 Resource
API handler names.

Script-instance property lookup now tries the exact property-name index before
falling back to the case-insensitive index. This preserves Director lookup
semantics but avoids lowercasing/allocation on the common compiled-Lingo path
where property names already match exactly. `GET_OBJ_PROP` also has direct
generic fast branches for common object types, including script instances,
lists, prop lists, strings, images, and cast members, before falling through to
the broad property dispatcher.

The exact script-instance property-name index now uses transparent string
lookup, so an exact-name hit can probe the `unordered_map` with the existing
`std::string_view` instead of constructing a temporary `std::string`. This is a
small generic allocation cleanup in the property path; it does not recognize
any v31 or BigInt-specific script names.

Prop-list untyped key lookup now also has a transparent string-view path for
Java-like key names. The untyped key index uses case-insensitive hashing and
equality, preserving the old normalized lookup semantics while avoiding a
temporary `Datum` plus lowercased `std::string` in hot generic prop-list probes.
The main object-call and prop-list dispatch paths use this for existing
`getProp`/`getAProp`/`getProperty`/`getPropRef` and keyed `count` calls.

Script-instance handler lookup is now cached for the duration of one top-level
handler. The key includes the receiver/ancestor identity chain plus the method
name, and the cache stores both hits and misses. It is cleared at top-level
handler boundaries together with the other short-lived dispatch caches. This is
generic method-dispatch caching; it does not know any v31, Resource API, or
BigInt/HugeInt handler names.

Immediate object-call folding now also covers generic script-instance
property-style methods directly, avoiding `popArgs()` and temporary method
argument vectors for methods that already had built-in script-instance
semantics:

```text
count
getAt
getAProp
getProp
getPropRef
setAt
setAProp
setProp
addProp
deleteProp
ilk
addAt
```

Real script-handler dispatch still uses the normal handler lookup/execution
path. This optimization does not recognize app-level handler names.

The fallback script-instance property-method path now also uses string views
for Java-like property keys where possible. This keeps behavior aligned with
the immediate path and avoids avoidable string allocations when calls reach
`scriptInstanceObjectMethod` through less common bytecode shapes. Ancestor-chain
property traversal was also tightened to keep ancestor `shared_ptr` owners alive
while walking raw pointers.

The disabled `traceScript` prologue skipper now caches its per-handler decision
in existing handler metadata. The skip itself already existed; caching avoids
re-resolving the same prologue name references on every repeated handler entry
when tracing is disabled. This targets the repeated generic trace boilerplate
seen at the start of many interpreted handlers without recognizing any
application handler names.

Normal harness timing after these generic changes:

```text
tick took 18650.7ms
handler powMod took 18028ms
handler responseWithPublicKey took 18123ms
socket message took 20524.3ms
handler powMod took 18202ms
handler getByteArray took 1322ms
handler handleServerSecretKey took 20510ms
host xtra callbacks took 20524ms
multiuser message took 20524ms
```

Compared with the generic-only baseline immediately before `script()` caching:

```text
handler powMod took 26273-26535ms
handler getByteArray took 2232ms
host xtra callbacks took 29762ms
multiuser message took 29762ms
```

This is a meaningful improvement, but the security callback still blocks the
worker for about 20 seconds and is not acceptable as the final performance
state.

## Diagnostic Profiling Notes

Temporary aggregate handler profiling, now removed, showed the largest
`powMod` self-time buckets before `script()` caching:

```text
dif self ~= 9.5-10.5s
new self ~= 8.2-9.2s
prod self ~= 2.9s
div self ~= 2.2-2.5s
mul self ~= 1.2s
```

Temporary opcode profiling with child-handler time subtracted, also removed,
showed the hot `new` handler time was concentrated in `pushArgList`, matching
the repeated built-in calls inside `new`, especially `script("HugeInt15")`.

Temporary aggregate profiling after `script()` caching showed that `new` was no
longer the dominant BigInt self-time bucket. The remaining security callback
hotspots were:

```text
dif self ~= 10.3-10.5s
prod self ~= 2.9s
div self ~= 2.5s
mul self ~= 1.2s
greaterThan self ~= 0.7s
equals self ~= 0.5s
```

Clean active-object profiling before the member-registry alias refresh cache:

```text
socket message took 7846.0ms
handler updateProcess took 7837ms
getmemnum self ~= 4190ms
memberExists self ~= 2788ms
createRoomObject total ~= 7641ms
```

After caching alias refresh per top-level handler, the same path measured:

```text
socket message took 6536.2ms
handler updateProcess took 6528ms
getmemnum self ~= 3401ms
memberExists self ~= 2279ms
createRoomObject total ~= 6346ms
```

The clean non-profiler run then measured:

```text
socket message took 6621.2ms
handler updateProcess took 6612ms (1104 instructions)
handler validateActiveObjects took 6612ms
handler handle_activeobjects took 6615ms
host xtra callbacks took 6621ms
multiuser message took 6621ms
screenshot: /tmp/venus-clean-after-alias-cache.png
logs: /tmp/venus-clean-after-alias-cache-logs.txt
```

A first attempt at a generic bytecode-shape fast path for trivial script
instance handlers did not improve the clean room-entry measurement:

```text
socket message took 6632.5ms
handler updateProcess took 6623ms (1104 instructions)
handler validateActiveObjects took 6623ms
handler handle_activeobjects took 6626ms
host xtra callbacks took 6632ms
multiuser message took 6632ms
screenshot: /tmp/venus-clean-after-shape-fastpath.png
logs: /tmp/venus-clean-after-shape-fastpath-logs.txt
```

Do not keep or extend this path unless profiling proves it actually catches a
hot generic pattern. If revisited, the matcher must remain bytecode/semantic
shape based and must not recognize `getmemnum`, `exists`, `memberExists`, or
other v31 Resource API handler names directly.

Temporary script-instance dispatch profiling, now removed, showed the remaining
Welcome Lounge active-object stall was spending seconds in member-registry
`prefill` work for repeated resource/member lookups. A liveness cache for
numeric slots did not improve the clean room-entry measurement, but a generic
per-top-level-handler member-name-to-slot cache did:

```text
with dispatch probe:
socket message took 3007.5ms
handler updateProcess took 2907ms (1104 instructions)
getmemnum prefill ~= 1482ms at the final active-object sample
exists prefill ~= 270ms early in the active-object sample

clean run after removing the probe:
socket message took 2909.5ms
handler updateProcess took 2901ms (1104 instructions)
handler validateActiveObjects took 2901ms
handler handle_activeobjects took 2904ms
host xtra callbacks took 2909ms
multiuser message took 2909ms
screenshot: /tmp/venus-member-slot-cache-clean.png
logs: /tmp/venus-member-slot-cache-clean-logs.txt
```

This improves the clean active-object room-entry callback from roughly 6.6s to
roughly 2.9s. The cache still leaves about 1.5s of `getmemnum` prefill in the
profiled sample and does not address the separate ~20s security/login callback.

Moving the same cache ahead of remembered-alias scanning preserved the same
clean timing band but did not materially reduce the remaining active-object
stall:

```text
socket message took 2987.3ms
handler updateProcess took 2889ms (1104 instructions)
handler validateActiveObjects took 2889ms
handler handle_activeobjects took 2893ms
host xtra callbacks took 2987ms
multiuser message took 2987ms
screenshot: /tmp/venus-member-slot-cache-before-alias-clean.png
logs: /tmp/venus-member-slot-cache-before-alias-clean-logs.txt
```

Adding direct generic `GET_OBJ_PROP` handling and exact-first script-instance
property lookup kept the active-object callback in the same best observed band,
with only small/noisy improvement. It did not materially move the ~20s
security/login callback:

```text
after direct GET_OBJ_PROP:
socket message took 2883.8ms
handler updateProcess took 2874ms (1104 instructions)
handler handle_activeobjects took 2878ms
security socket message took 20440.2ms

after exact-first script-instance property lookup:
socket message took 2880.1ms
handler updateProcess took 2871ms (1104 instructions)
handler handle_activeobjects took 2875ms
security socket message took 20671.3ms
screenshot: /tmp/venus-exact-property-lookup-clean.png
logs: /tmp/venus-exact-property-lookup-clean-logs.txt

after transparent exact property-index lookup:
tick took 18575.9ms
handler powMod took 17970ms
security socket message took 20215.9ms
handler getByteArray took 1301ms
room-entry socket message took 2905.0ms
handler updateProcess took 2896ms (1104 instructions)
screenshot: /tmp/venus-transparent-property-lookup-clean.png
logs: /tmp/venus-transparent-property-lookup-clean-logs.txt

after transparent prop-list untyped key lookup:
tick took 18625.4ms
handler powMod took 18006ms
security socket message took 20459.9ms
handler getByteArray took 1319ms
room-entry socket message took 2974.1ms
handler updateProcess took 2878ms (1104 instructions)
screenshot: /tmp/venus-transparent-proplist-key-clean.png
logs: /tmp/venus-transparent-proplist-key-clean-logs.txt

after top-level script-instance handler lookup cache:
tick took 18803.0ms
handler powMod took 18155ms
security socket message took 20613.1ms
handler getByteArray took 1326ms
room-entry socket message took 2935.7ms
handler updateProcess took 2838ms (1104 instructions)
screenshot: /tmp/venus-script-handler-cache-clean.png
logs: /tmp/venus-script-handler-cache-clean-logs.txt

after immediate script-instance property-style object calls:
tick took 17916.7ms
handler powMod took 17275ms
security socket message took 19774.3ms
handler getByteArray took 1301ms
room-entry socket message took 2860.5ms
handler updateProcess took 2852ms (1104 instructions)
screenshot: /tmp/venus-immediate-script-instance-props-clean.png
logs: /tmp/venus-immediate-script-instance-props-clean-logs.txt

after script-instance fallback property key string-view cleanup:
tick took 18441.0ms
handler powMod took 17802ms
security socket message took 20083.5ms
handler getByteArray took 1314ms
room-entry socket message took 2876.4ms
handler updateProcess took 2867ms (1104 instructions)
screenshot: /tmp/venus-script-instance-view-fallback-clean.png
logs: /tmp/venus-script-instance-view-fallback-clean-logs.txt

after cached disabled traceScript prologue decision:
tick took 17845.7ms
handler powMod took 17212ms
security socket message took 19685.5ms
handler getByteArray took 1292ms
room-entry socket message took 2878.3ms
handler updateProcess took 2868ms (1104 instructions)
screenshot: /tmp/venus-trace-prologue-cache-clean.png
logs: /tmp/venus-trace-prologue-cache-clean-logs.txt

current remeasure before the string-chunk view cleanup:
handler decodeUTF8 took 2537ms (11257669 instructions)
handler dumpTextField took 2707ms
handler parseCallback took 2978ms
tick took 17914.2ms
handler powMod took 17266ms
security socket message took 19676.6ms
handler getByteArray took 1296ms
room-entry socket message took 2873.1ms
handler updateProcess took 2864ms (1104 instructions)
screenshot: /tmp/venus-current-remeasure.png
logs: /tmp/venus-current-remeasure-logs.txt

after direct string-view support for `StringChunk` and reserving materialized
string `line` property lists:
handler decodeUTF8 took 2685ms (11257669 instructions)
handler dumpTextField took 2858ms
handler parseCallback took 2951ms
tick took 18120.2ms
handler powMod took 17491ms
security socket message took 19819.1ms
handler getByteArray took 1304ms
room-entry socket message took 2937.3ms
handler updateProcess took 2837ms (1104 instructions)
screenshot: /tmp/venus-stringchunk-view-clean.png
logs: /tmp/venus-stringchunk-view-clean-logs.txt

after generic `value()` parser cleanup for no-escape quoted strings and direct
integer parsing:
handler decodeUTF8 took 2585ms (11257669 instructions)
handler dumpTextField took 2756ms
handler parseCallback took 2913ms (267304 instructions)
tick took 17938.0ms
handler powMod took 17302ms
security socket message took 19592.6ms
handler getByteArray took 1281ms
room-entry socket message took 2875.1ms
handler updateProcess took 2866ms (1104 instructions)
screenshot: /tmp/venus-value-parser-clean.png
logs: /tmp/venus-value-parser-clean-logs.txt

after immediate primitive extcall handling for small generic built-ins
(`charToNum`, `numToChar`, `length`, `bitAnd`):
handler decodeUTF8 took 2376ms (11257669 instructions)
handler dumpTextField took 2546ms
handler parseCallback took 2818ms (267304 instructions)
tick took 17774.9ms
handler powMod took 17162ms
security socket message took 19573.8ms
handler getByteArray took 1277ms
room-entry socket message took 2880.9ms
handler updateProcess took 2870ms (1104 instructions)
screenshot: /tmp/venus-immediate-primitive-extcall-clean.png
logs: /tmp/venus-immediate-primitive-extcall-clean-logs.txt
```

A matching direct `SET_OBJ_PROP` shortcut was tried and removed because it
changed observable tracing/listener behavior in the native tests:

```text
testLingoVmScopeAndExecutionContextFoundation:
variableTraces.size() == 1
```

An `int`/`int` arithmetic/comparison opcode shortcut was also tried and
removed. It used direct stack replacement for common numeric opcodes instead of
two pops plus one push, while leaving non-int and divide/modulo error paths on
the existing implementation. A clean harness run did not show a win:

```text
security socket message took 22583.3ms
handler powMod took 20097ms
handler getByteArray took 1611ms
room-entry socket message took 3698.9ms
handler updateProcess took 3688ms (1104 instructions)
screenshot: /tmp/venus-int-opcode-stack-replace-clean.png
logs: /tmp/venus-int-opcode-stack-replace-clean-logs.txt
```

Because this was worse than the current timing band and added interpreter
complexity, it was reverted.

An indexed fast path for exact `PropList::put` replacement was also tried and
removed. The idea was to reuse the same-type key index before falling back to
the old linear scan, which could help generic bracket-style prop-list writes.
In the clean harness run it did not improve the target path:

```text
security socket message took 19935.0ms
handler powMod took 17643ms
handler getByteArray took 1303ms
room-entry socket message took 3064.2ms
handler updateProcess took 2965ms (1104 instructions)
screenshot: /tmp/venus-proplist-put-index-clean.png
logs: /tmp/venus-proplist-put-index-clean-logs.txt
```

Because it added index rebuild work to a core mutation path and worsened the
room-entry measurement, it was reverted.

A simple per-tick Multiuser/Xtra callback cap was considered but not added for
this trace. `MultiuserXtra::tick()` does currently drain all queued network
messages synchronously, but the measured 18-20s security stalls and the ~2.9s
active-object stall are each dominated by one Lingo callback after one delivered
message reaches the movie. Capping the number of queued Xtra messages per tick
would help only when many separate `NetMessage`s are queued; it would not split
the single expensive `xtraMsgHandler` callback shown in the current logs.

The string-chunk view cleanup is generic and covered by focused assertions, but
the clean harness run did not show a measurable improvement. Treat it as a small
allocation cleanup rather than a target-path fix. The `decodeUTF8`,
`dumpTextField`, and `parseCallback` stalls remain real and need a more
substantial generic approach, likely around chunked string access, repeated line
lookup on large field text, or the interpreter cost of tight string/list loops.

The generic parser cleanup slightly reduced the measured `parseCallback` stall
in one clean run, but only by a few dozen milliseconds and within the noisy
range. It is still useful as generic parser hygiene because large Lingo value
lists contain many quoted strings and numeric tokens, but it is not sufficient
to fix frame pacing.

The immediate primitive extcall path is a clearer generic win for startup text
processing. It avoids building a temporary argument vector for tiny built-ins
that occur heavily in interpreted string/list loops. This improves
`decodeUTF8`, `dumpTextField`, and `parseCallback` by a few hundred
milliseconds in the clean run, but does not materially affect the later
Diffie-Hellman/login stall or the room-entry active-object callback.

The immediate primitive extcall path was tightened so it preserves normal
`EXT_CALL` precedence before doing stack-only builtin execution. If a
same-named authored handler would win, the fast path now falls back to the
existing argument-vector path. The generic stack-only coverage was then extended
to additional small Director built-ins used heavily in tight interpreted loops:
`bitOr`, `bitXor`, `string`, `integer`, `min`, and `max`. This remains generic
runtime work and does not recognize any v31 script, BigInt/HugeInt method, or
Resource API handler name.

Latest clean harness run after the expanded immediate primitive path:

```text
handler decodeUTF8 took 2504ms (11257669 instructions)
handler parseCallback took 2910ms (267304 instructions)
tick took 18277.0ms
handler powMod took 17650ms
security socket message took 19873.5ms
handler powMod took 17624ms
handler getByteArray took 1299ms
room-entry socket message took 2883.2ms
handler updateProcess took 2873ms (1104 instructions)
handler validateActiveObjects took 2873ms
screenshot: /tmp/venus-immediate-more-primitives.png
logs: /tmp/venus-immediate-more-primitives-logs.txt
```

This did not solve the target stall. The room-entry active-object callback is
still in the same ~2.9s band, and the security callback is still roughly 20s
overall. The useful part of this pass is semantic hardening of the generic fast
path plus a small/noisy improvement in the BigInt-adjacent primitive-heavy code;
the next pass still needs a larger generic VM/runtime or scheduling change.

Generic script-instance object-method dispatch was then changed to pass method
arguments as `std::span<const Datum>` internally, avoiding the unconditional
`methodArgs.assign(...)` vector copy for script-instance object calls. The
existing vector is still materialized only when needed for authored handler
execution or the close-thread deferrer. This is a generic dispatch cleanup, not
a Resource API or v31 handler-name fast path.

Clean harness run after the span-based script-instance argument path:

```text
handler decodeUTF8 took 2638ms (11257669 instructions)
handler parseCallback took 3010ms (267304 instructions)
tick took 18851.0ms
handler powMod took 18204ms
security socket message took 20507.2ms
handler powMod took 18192ms
handler getByteArray took 1319ms
room-entry socket message took 2894.0ms
handler updateProcess took 2885ms (1104 instructions)
handler validateActiveObjects took 2885ms
screenshot: /tmp/venus-scriptinstance-span-args.png
logs: /tmp/venus-scriptinstance-span-args-logs.txt
```

This did not materially improve the Welcome Lounge room-entry stall. Keep it as
generic allocation/dispatch hygiene only if later review accepts the added
`std::span` plumbing; do not treat it as a target-path fix. The next useful
target is likely deeper than argument-vector construction: either the residual
generic member/resource lookup itself, a broader bytecode-dispatch cost in the
active-object loop, or cooperative scheduling around long single callbacks.

## 2026-06-16 Additional Findings

Generic immediate primitive fast paths were extended for:

```text
count
listp
voidp
abs
```

These avoid constructing an arg-list/vector for common one-argument Director
builtins, and are covered by native opcode assertions. They are not
v31-specific and still respect authored-handler precedence. Measured room-entry
effect before the cast lookup change was neutral:

```text
/tmp/venus-listp-voidp-abs-primitives-logs.txt
post-click socket message took 2850.9ms
updateProcess took 2842ms

/tmp/venus-count-listp-voidp-abs-primitives-logs.txt
post-click socket message took 2950.7ms
updateProcess took 2941ms
```

A generic cast-library member-name index was then added in:

```text
cpp/include/libreshockwave/player/cast/CastLib.hpp
cpp/src/player/cast/CastLib.cpp
```

Before this, exact member-name lookup scanned cast member maps on every lookup.
`solveMembers` generates many member-name probes while constructing room
objects, so this scan showed up indirectly through the repeated object creation
stall. The new index is case-insensitive, preserves existing lookup priority
(authored cast chunks first, runtime members second), and is invalidated on cast
reload, external binding reset, dynamic member creation/reuse, and member name
assignment.

Two harness runs after the cast member-name index entered the Welcome Lounge and
did not emit the previous post-click `socket message took ~2.9s` /
`updateProcess took ~2.9s` warning:

```text
/tmp/venus-cast-member-name-index.png
/tmp/venus-cast-member-name-index-logs.txt

/tmp/venus-cast-member-name-index-confirm.png
/tmp/venus-cast-member-name-index-confirm-logs.txt
```

Both screenshots show the Welcome Lounge loaded. Treat this as strong evidence
that the active-object room-entry stall has moved below the current 1000ms slow
warning threshold, but not yet as final acceptance: the harness still has large
pre-room login/security stalls in interpreted `HugeInt15` Diffie-Hellman
handling, and the final FPS/frame-pacing target needs a steady-room measurement
after the remaining startup stalls are addressed or scheduled.

Additional generic fast paths were then added for bytecode shapes emitted by the
same Lingo compiler, without recognizing any v31 script names:

```text
script-instance getAt(target, propertyName, subKey)
script-instance setAt/setAProp(target, propertyName, subKey, value)
one-argument new(scriptRef)
```

The nested script-instance `getAt` / `setAt` path is useful for authored objects
that store list or prop-list state in properties, because it avoids falling
through to the broad object-method dispatcher when the operation is still just a
generic property/container access. The `new(scriptRef)` path preserves normal
constructor semantics: it creates a script instance, initializes declared
script properties, and invokes the target `new` handler through the generic
handler-dispatch hook if one exists. It does not know about `HugeInt15`,
`BigInt`, or any other app-authored constructor name.

Native and WASM verification after these generic fast paths:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
```

The new native assertions passed before the known existing full-suite failure:

```text
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Latest harness run after the `new(scriptRef)` path reached the Welcome Lounge
and did not show a post-click slow-operation warning after the Navigator `Go`
click:

```text
screenshot: /tmp/venus-immediate-new-scriptref.png
logs: /tmp/venus-immediate-new-scriptref-logs.txt
```

The remaining startup/security stall is still too large and still comes from
ordinary interpreted Lingo execution, not from room canvas presentation:

```text
handler powMod took about 9408-9475ms
socket message took about 10809.9ms
handler handleServerSecretKey took about 10807ms
```

This is a significant improvement from the earlier ~18-20s security timing band
seen before the later generic runtime work, but it is not final acceptance. The
next target remains the generic cost of tight interpreted arithmetic/property/
list loops, or scheduling long callbacks so one security/login callback cannot
block worker frame delivery for many seconds. Do not solve that remaining stall
by adding native C++ implementations of the v31 BigInt methods.

A temporary 100ms warning-threshold run confirmed that the post-click room-entry
active-object callback is now below the old 1000ms warning threshold:

```text
screenshot: /tmp/venus-100ms-current.png
logs: /tmp/venus-100ms-current-logs.txt

after Navigator Go:
handler executeRoomEntry took 133ms
handler prepareRoomEntry took 133ms
handler updateProcess took 412ms
handler validateActiveObjects took 412ms
handler handle_activeobjects took 412ms
handler xtraMsgHandler took 493ms
```

The same low-threshold run still showed the remaining pre-room blockers:

```text
handler powMod took 9427-9494ms
socket message took 10771.4ms
handler handleServerSecretKey took 10759ms
repeated handler parseCallback took about 455-475ms
```

A small generic chained-property cleanup was added after that run:

```text
cpp/src/lingo/vm/OpcodeRegistry.cpp
```

Strict integer parsing in this VM file now trims with `std::string_view`, and
`GET_CHAINED_PROP` no longer tries to parse every property name as an integer
before checking the object type. Script-instance chained properties now avoid
that numeric parse for ordinary named properties while preserving the existing
numeric-name behavior. This targets generic `object.property` traffic in tight
interpreted loops and does not recognize any v31 handler or property names.

Normal-threshold harness run after this cleanup:

```text
screenshot: /tmp/venus-lazy-chained-prop.png
logs: /tmp/venus-lazy-chained-prop-logs.txt

handler decodeUTF8 took 2499ms
handler dumpTextField took 2672ms
handler parseCallback took 2923ms
handler powMod took 9611ms
socket message took 10857.2ms
handler handleServerSecretKey took 10844ms
```

No post-click 1000ms slow-operation warning was emitted after `CLICKED 909 137`
in that normal-threshold run, and the screenshot shows the Welcome Lounge
loaded. The cleanup is generic and safe to keep if tests pass, but it is not a
material fix for the remaining security/login and startup parsing stalls.

A generic string-chunk stack fast path was then added for the bytecode shape
used by large text-field parsers:

```text
string.count(#line)
string.getProp(#line, index)
```

The fast path uses a no-throw chunk-type resolver and avoids constructing an
argument vector for `count(#line)`. This is generic Director string/chunk
behavior, not a shortcut for a v31 parser handler. Native coverage was added for
the immediate `string.count(#line)` path.

Normal-threshold harness run after this string count fast path:

```text
screenshot: /tmp/venus-string-count-fastpath.png
logs: /tmp/venus-string-count-fastpath-logs.txt

handler decodeUTF8 took 2534ms
handler dumpTextField took 2707ms
handler parseCallback took 2952ms
handler powMod took 9515ms
socket message took 10840.2ms
handler handleServerSecretKey took 10837ms
```

This pass was neutral in the harness. The useful finding is that `parseCallback`
is not dominated by argument-vector construction for `line.count`; it is more
likely dominated by repeated full-text line scans and `value(...)` parsing:

```text
tmember.text.line.count
tmember.text.line[l]
value(tmember.text.line[l])
```

The next parsing-focused optimization should be a generic cached line-index path
for field text/string chunks, with clear invalidation when field text changes.
Do not special-case the `parseCallback` handler or any Dynamic Downloader script
name.

Do not keep the temporary profilers in the final tree. They were only used to
pick the next generic runtime target.

## Next Investigation Steps

1. Confirm the active-object room-entry improvement with a lower handler/socket
   threshold or frame-timeline instrumentation, then keep the cast member-name
   index if the sub-1000ms result is repeatable.
2. Continue reducing the remaining Diffie-Hellman/login stall without
   hardcoding the v31 BigInt Lingo implementation or any equivalent
   app-authored Lingo function names into C++.
3. If any active-object callback still exceeds the lower threshold, continue
   reducing the generic Resource API / Resource Manager member lookup path:

   ```text
   fuse_client/casts/External/MovieScript 9 - Resource API.ls
   getmemnum -> getResourceManager().getmemnum(tMemName)
   memberExists -> getResourceManager().exists(tMemName)

   fuse_client/casts/External/ParentScript 30 - Resource Manager Class.ls
   getmemnum -> pAllMemNumList[tMemName]
   exists -> not voidp(pAllMemNumList[tMemName])
   ```

   Any next optimization should be generic, such as faster global handler
   dispatch, faster script-instance method dispatch, or faster prop-list lookup
   on hot variable keys. Do not hardcode these v31 Resource API handlers by
   name.
4. Profile the remaining multi-second `decodeUTF8`, `dumpTextField`, and
   `parseCallback` startup stalls if they still affect room-entry latency after
   the VM hot paths improve.
5. Consider cooperative scheduling for host Xtra callbacks if later room
   messages still batch too much work into a single worker turn.
6. Preserve Director semantics: do not add harness-only shims, room-name
   special cases, endpoint changes, or fake room data.
7. Do not hardcode Lingo script functions into C++ as the final fix. In
   particular, do not solve this by recognizing and replacing app-level methods
   such as `HugeInt15.powMod`, `BigInt`/`HugeInt` helpers, `Modulo`, `div`,
   `getString`, or `getByteArray` with C++ equivalents. Performance work should
   improve generic VM/runtime behavior, scheduling, data representation, or
   built-in Director semantics rather than baking specific Lingo parent-script
   implementations into the engine.

## Field Line Index Attempt

Added a generic field/member text line-index cache for Director string chunk
semantics. This does not recognize any v31 Lingo handler names. The retained
shape is:

```text
cpp/include/libreshockwave/cast/CastMember.hpp
cpp/src/cast/CastMember.cpp
cpp/include/libreshockwave/lingo/Datum.hpp
cpp/src/lingo/Datum.cpp
cpp/include/libreshockwave/lingo/builtin/BuiltinRegistry.hpp
cpp/include/libreshockwave/lingo/vm/util/StringChunkUtils.hpp
cpp/src/lingo/vm/util/StringChunkUtils.cpp
cpp/src/lingo/vm/OpcodeRegistry.cpp
cpp/src/player/cast/CastLibManager.cpp
cpp/tests/sdk_foundation_test.cpp
```

The runtime now tracks a generic text revision on `CastMember::setDynamicText`.
Field text datums and text-like cast member `text` properties carry that member
identity/revision as string-compatible `FieldText`. The immediate object-call
fast path for `string.line.count` and `string.line[index]` can reuse a cached
line-offset index for real member-backed field text. Manually constructed
`FieldText` values with revision `0` still follow the uncached path.

Focused native assertions cover:

```text
fieldText.line.count
fieldText.line[2]
cache reuse with the same cast/member/revision
cache bypass with a newer revision token
```

Verification after this attempt:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the existing later failure:

```text
cpp/tests/sdk_foundation_test.cpp:13432
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Harness captures:

```text
/tmp/venus-field-line-index.png
/tmp/venus-field-line-index-logs.txt
/tmp/venus-member-text-line-index.png
/tmp/venus-member-text-line-index-logs.txt
```

Both screenshots reached the Welcome Lounge after clicking `Go`, with no
post-click slow-handler warnings at the normal 1000ms threshold. The startup
`parseCallback` stall did not materially improve:

```text
before member text identity:
parseCallback took 2944ms

after member text identity:
parseCallback took 2895ms
```

Conclusion: generic cached line indexing is safe and retained, but the current
evidence says it is not the dominant remaining `parseCallback` cost. The next
target should profile inside `parseCallback`/`value(...)`/field dump parsing
more directly, still without hardcoding `parseCallback`, Resource API handlers,
room scripts, or BigInt/HugeInt methods into C++.

## Generic `value()` String-View Pass

Removed an avoidable full-string copy from the generic `value()` builtin. For
direct string-like datums (`String`, `FieldText`, `StringChunk`, and symbols),
`value()` now parses from `std::string_view` and only allocates when it must
call the generic evaluator or return a member-name fallback string. This is a
generic runtime cleanup and does not recognize any v31 handler names.

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary again reaches the existing later failure after the
`value()` assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13432
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Harness capture:

```text
/tmp/venus-value-stringview.png
/tmp/venus-value-stringview-logs.txt
```

The screenshot reached the Welcome Lounge after clicking `Go`, with no
post-click slow-handler warnings at the normal 1000ms threshold. The pre-click
startup stalls remained effectively unchanged:

```text
decodeUTF8 took 2527ms
dumpTextField took 2699ms
parseCallback took 2890ms
powMod took 9309ms / 9483ms
handleServerSecretKey took 10803ms
```

Conclusion: removing the extra `value()` input copy is safe and retained, but
it is not the main remaining `parseCallback` cost. The strongest current lead
is still the interpreted Lingo work inside app-authored UTF-8 decoding and data
materialization. Do not replace `decodeUTF8` or `parseCallback` by name in C++;
look for generic VM wins in character chunk access, list appends/indexing,
integer arithmetic, and loop dispatch.

## Generic Single-Character Chunk Fast Path

Added a generic immediate object-call fast path for single-character string
chunk access:

```text
string.char[i]
fieldText.char[i]
```

This is in the existing generic `string.getProp/getPropRef` handling and only
short-circuits `#char` with a single index. It avoids the general chunk utility
and delimiter/range machinery on the common character-loop shape used by
interpreted Lingo such as UTF-8 decoding. It does not recognize `decodeUTF8`,
`parseCallback`, or any v31 script/handler name.

Focused native assertions cover:

```text
"abcd".char[3] == "c"
fieldText("wxyz").char[2] == "x"
"wxyz".char[9] == EMPTY
```

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary again reaches the existing later failure after the
new assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13456
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Harness capture:

```text
/tmp/venus-char-single-fastpath.png
/tmp/venus-char-single-fastpath-logs.txt
```

The screenshot reached the Welcome Lounge after clicking `Go`, with no
post-click slow-handler warnings at the normal 1000ms threshold. The pre-click
startup stalls remained effectively unchanged:

```text
decodeUTF8 took 2530ms
dumpTextField took 2705ms
parseCallback took 2927ms
powMod took 9562ms / 9512ms
handleServerSecretKey took 10856ms
```

Conclusion: the single-character chunk fast path is safe and retained, but it
is too small to materially improve the current Welcome Lounge startup stalls.
The remaining `decodeUTF8` and `parseCallback` costs are dominated by broader
interpreted loop/list/numeric work. Continue looking for generic VM improvements
there, not handler-name replacements.

## Generic Parser Colon Metadata Pass

Changed the generic Lingo literal list splitter to record the first top-level
colon for each list element while it is already scanning the list text. This
lets property-list parsing avoid rescanning each element to find the same colon.
The change is generic parser work and does not recognize v31 data files,
`parseCallback`, `decodeUTF8`, or any app-authored handler name.

The existing parser-heavy native assertions cover nested lists/proplists,
quoted keys, unquoted keys, dynamic asset-style literals, and large nested
literal lists. The full native binary again reaches the existing later failure
after those parser assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13456
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

Harness capture:

```text
/tmp/venus-parser-colon-metadata.png
/tmp/venus-parser-colon-metadata-logs.txt
```

The screenshot reached the Welcome Lounge after clicking `Go`, with no
post-click slow-handler warnings at the normal 1000ms threshold. The pre-click
startup stalls were not improved and were slightly worse/noisy in this run:

```text
decodeUTF8 took 2579ms
dumpTextField took 2757ms
parseCallback took 3047ms
powMod took 9780ms / 9730ms
handleServerSecretKey took 11103ms
```

Conclusion: parser colon metadata removes generic duplicate scans, but it does
not materially improve the Welcome Lounge run. The remaining bottlenecks are
still dominated by interpreted Lingo execution and the app-authored BigInt and
UTF-8/data processing paths. Keep avoiding handler-name replacements in C++.

## Runtime Slow-Handler Threshold Diagnostic

Added a generic diagnostic control so the browser harness can lower VM
slow-handler warnings at runtime without rebuilding C++ or changing app Lingo:

```text
cpp/include/libreshockwave/lingo/vm/LingoVM.hpp
cpp/src/lingo/vm/LingoVM.cpp
cpp/include/libreshockwave/player/Player.hpp
cpp/src/player/Player.cpp
cpp/wasm/WasmBridge.cpp
cpp/CMakeLists.txt
cpp/web/libreshockwave-cpp-worker.js
cpp/web/libreshockwave-cpp-player.js
cpp/web/index.html
```

The public browser player API now exposes:

```text
window.__player.setSlowHandlerWarningMs(milliseconds)
```

and the generic page honors:

```text
?slowHandlerWarningMs=...
```

This is diagnostic-only runtime plumbing. It does not recognize any v31 room
script, login script, Resource API helper, or BigInt/HugeInt handler name.

Verification:

```text
node --check cpp/web/libreshockwave-cpp-worker.js
node --check cpp/web/libreshockwave-cpp-player.js
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the existing later failure after the
new diagnostic API builds:

```text
cpp/tests/sdk_foundation_test.cpp:13456
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

## 100ms Room-Entry Threshold Capture

Harness capture after setting the warning threshold to 100ms immediately before
clicking `Go`:

```text
/tmp/venus-100ms-runtime-threshold.png
/tmp/venus-100ms-runtime-threshold-logs.txt
```

The runtime setter was present and accepted the threshold:

```text
SET_THRESHOLD_AVAILABLE true
SET_THRESHOLD 100
```

Pre-click login/data warnings remained dominated by interpreted Lingo and
startup parsing:

```text
decodeUTF8 took 2449ms
dumpTextField took 2622ms
parseCallback took 2883ms
powMod took 9278ms
socket message took 10838.3ms
handleServerSecretKey took 10830ms
```

The lower room-entry threshold exposed post-click stalls that the normal 1000ms
threshold hides:

```text
executeRoomEntry took 133ms (29 instructions)
prepareRoomEntry took 133ms (59 instructions)
handleRoomListClicked took 134ms (92 instructions)
eventProcNavigatorPublic took 134ms (28 instructions)
redirectEvent took 134ms (51 instructions)
mouseDown took 134ms (17 instructions)
redirectEvent took 134ms (39 instructions)
mouseDown took 134ms (31 instructions)
prepare took 293ms (365 instructions)
prepareFrame took 296ms (152 instructions)
xtraMsgHandler took 100ms (571 instructions)
updateProcess took 415ms (1104 instructions)
validateActiveObjects took 415ms (22 instructions)
handle_activeobjects took 415ms (28 instructions)
forwardMsg took 415ms (84 instructions)
msghandler took 417ms (136 instructions)
xtraMsgHandler took 495ms (319 instructions)
```

Conclusion: steady-state room cadence can look acceptable, but room entry still
has sub-1000ms blocking work. The next useful target is the generic work under
the active-object validation/message path. The low instruction counts around
`validateActiveObjects` and `handle_activeobjects` suggest expensive native
runtime operations or nested calls inside a small amount of interpreted bytecode,
so the next profile should lower the handler threshold further before choosing a
generic optimization.

## 25ms and 5ms Room-Entry Threshold Captures

25ms capture:

```text
/tmp/venus-25ms-runtime-threshold.png
/tmp/venus-25ms-runtime-threshold-logs.txt
```

The screenshot reached the Welcome Lounge. Compared with the 100ms capture, the
25ms threshold exposed more post-click room-entry work:

```text
hideNavigator/deconstruct/removeWindow: 57-74ms
startCastLoad/loadRoomCasts/enterRoom: 46-99ms
preIndexMembers: 32-45ms
roomConnected/showRoom/showRoomBar/buildVisual/merge: 26-80ms
updateProcess/validateActiveObjects/handle_activeobjects: ~410ms
```

5ms post-click-only capture:

```text
/tmp/venus-5ms-postclick-threshold.png
/tmp/venus-5ms-postclick-threshold-logs.txt
```

The 5ms run confirms that the ~406-410ms active-object validation stall is not a
single large interpreted bytecode loop. It is a small handler that fans out into
many repeated room-object construction calls. The reduced warning totals from
the 5ms post-click log show the object-creation cluster:

```text
37 createRoomObject warnings, 252ms total, max 26ms
37 createPassiveObject warnings, 231ms total, max 10ms
24 define warnings, 171ms total, max 23ms
21 solveMembers warnings, 133ms total, max 8ms
4 buildVisual warnings, 84ms total, max 43ms
3 showRoomBar warnings, 149ms total, max 50ms
```

Relevant v31 Lingo source inspected only to understand the generic operation
shape:

```text
/opt/git/v31_assets/projectorrays_lingo/hh_room/casts/External/ParentScript 4 - Room Component Class.ls
/opt/git/v31_assets/projectorrays_lingo/hh_room/casts/External/ParentScript 5 - Room Handler Class.ls
/opt/git/v31_assets/projectorrays_lingo/hh_furni_classes/casts/External/ParentScript 4 - Passive Object Class.ls
```

The active-object path accumulates room data, then `updateProcess` creates many
passive/active/item/user objects. The passive object `define`/`solveMembers`
path repeatedly exercises generic Director/Lingo operations:

```text
property-list and list indexing
script-instance property get/set
member-registry `getmemnum` / `memberExists`
field lookup
`value(field(...))`
`member(tMemNum).width` / `member(tMemNum).height`
sprite reservation and sprite property writes
```

Conclusion: the next runtime optimization should target generic object
construction/member-resolution/property access paths used by repeated room
object setup. Do not hardcode `updateProcess`, `validateActiveObjects`,
`createPassiveObject`, `solveMembers`, furniture classes, room scripts, or v31
member names. Candidate generic directions are per-handler or per-frame caches
for member-registry/member/field lookup, avoiding full runtime member
materialization for pure existence checks, and faster repeated cast-member
property access.

## Generic Cast Member Existence Fast Path

Added a generic `CastLib::hasMemberNumber()` path and changed
`CastLibManager::memberExists()` to use it. This preserves authored and runtime
dynamic member support but avoids constructing a full runtime `CastMember` when
the caller only asks whether a numeric member exists.

Changed files:

```text
cpp/include/libreshockwave/player/cast/CastLib.hpp
cpp/src/player/cast/CastLib.cpp
cpp/src/player/cast/CastLibManager.cpp
```

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the same existing later failure after
the affected cast-manager assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13456
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Comparable 25ms harness capture after rebuilding WASM:

```text
/tmp/venus-member-exists-fastpath-25ms.png
/tmp/venus-member-exists-fastpath-25ms-logs.txt
```

Post-click active-object timing was essentially unchanged versus the prior 25ms
run:

```text
updateProcess took 406ms
validateActiveObjects took 406ms
handle_activeobjects took 407ms
```

Conclusion: this is safe generic cleanup, but it is not the main Welcome Lounge
room-entry fix. The remaining active-object stall is still the repeated generic
room object creation/definition/member-solving cluster.

## Generic Cast Member Property Read Fast Path

Added two generic cleanups for cast-member property reads:

```text
cpp/src/lingo/vm/OpcodeRegistry.cpp
cpp/src/player/cast/CastLibManager.cpp
```

The VM no longer asks the cast-member existence resolver on every member
property read. That check is only needed for `number` and `memberNum`, which
have special invalid-reference zero semantics. Other properties now go directly
to the property getter, which already handles invalid or missing members.

The cast manager now answers simple non-text `width`, `height`, and `rect`
member properties directly after resolving the member once. This avoids the
previous manager-to-castlib round trip, duplicate member lookup, duplicate
property-name lowercasing, and text-renderer checks for non-text members. This
targets the generic `member(tMemNum).width` / `member(tMemNum).height` shape
that appears repeatedly during room object sprite setup, without recognizing
room scripts, furniture scripts, or member names.

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the same existing later failure after
the affected opcode and cast-manager assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13456
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Comparable 25ms harness capture after rebuilding WASM:

```text
/tmp/venus-cast-member-prop-fastpath-25ms.png
/tmp/venus-cast-member-prop-fastpath-25ms-logs.txt
```

Post-click active-object timing was only slightly lower than the previous 25ms
run and should be treated as neutral/noisy rather than a solved bottleneck:

```text
updateProcess took 403ms
validateActiveObjects took 403ms
handle_activeobjects took 404ms
```

Conclusion: retain this as generic runtime cleanup, but continue looking for
larger wins in repeated object construction, parsed field value caching,
script-instance property access, sprite reservation/property writes, or
cooperative scheduling of room-object creation.

## Generic Revision-Aware `value(field(...))` Cache

Changed the generic parsed field value resolver to accept the `FieldText`
revision carried by field datums:

```text
cpp/include/libreshockwave/lingo/builtin/BuiltinRegistry.hpp
cpp/include/libreshockwave/player/cast/CastLibManager.hpp
cpp/src/lingo/builtin/BuiltinRegistry.cpp
cpp/src/player/cast/CastLibManager.cpp
cpp/tests/sdk_foundation_test.cpp
```

For revisioned field text, `value(field(...))` can now hit the parsed-field
cache without refetching/copying the member text just to compare it with the
cached text. Callers without a revision keep the previous text-comparison
fallback behavior. This is generic field/value cache plumbing and does not
recognize any room script, furniture script, Resource API handler, or v31 member
name.

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the same existing later failure after
the affected value/field assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13456
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Comparable 25ms harness capture after rebuilding WASM:

```text
/tmp/venus-field-value-revision-cache-25ms.png
/tmp/venus-field-value-revision-cache-25ms-logs.txt
```

The active-object stall did not improve in this run:

```text
updateProcess took 408ms
validateActiveObjects took 408ms
handle_activeobjects took 408ms
```

Conclusion: keep the revision-aware cache as generic cleanup, but the Welcome
Lounge room-entry stall is not primarily `value(field(...))` cache validation.
The next useful direction is either deeper instrumentation inside repeated
object construction or scheduling/cooperative chunking of the room-object
creation batch so it does not monopolize one worker turn.

## 1ms Post-Click Threshold Capture

Post-click capture after waiting for the exterior/Navigator state, lowering the
runtime slow-handler threshold to 1ms, clicking `Go`, and recording the room
entry:

```text
/tmp/venus-1ms-postclick-threshold.png
/tmp/venus-1ms-postclick-threshold-logs.txt
```

The log contains 11,098 lines. A whole-log aggregate is dominated by steady
`prepareFrame` warnings after the room is already loaded, so the useful signal is
the active-object creation window around these lines:

```text
line 1053: roomConnected took 81ms
line 1165: handle_OBJECTS took 14ms (4885 instructions)
line 1553: createRoomObject took 26ms (85 instructions)
line 1666: updateProcess took 405ms (1104 instructions)
line 1667: validateActiveObjects took 405ms (22 instructions)
line 1668: handle_activeobjects took 405ms (28 instructions)
```

Active-object window aggregate, focused on lines 1160-1668:

```text
    3    407  405 updateProcess
    1    405  405 handle_activeobjects
    1    405  405 validateActiveObjects
   30    155   26 createRoomObject
    3    153   51 showRoomBar
   37    141   24 define
   29    129    7 createPassiveObject
   29    103    6 solveMembers
    1     81   81 xtraMsgHandler
   65     65    1 getmemnum
    1     45   45 merge
    1     44   44 buildVisual
    1     38   38 msghandler
   32     32    1 memberExists
    27    28    2 create
    1     27   27 createUserObject
    26    27    2 createObject
    1     22   22 forwardMsg
   15     19    5 CreateElement
   16     16    1 solveInk
    1     15   15 setup
    1     14   14 handle_OBJECTS
   14     14    1 solveLocZ
   13     13    1 getResourceManager
   10     10    1 getManager
   10     10    1 solveBlend
    1      8    8 setPartLists
    7      8    2 construct
    1      6    6 getPartialPicture
    1      6    6 getPicture
```

Object-construction window before the final wrapping warnings, lines 1160-1555:

```text
   30    155   26 createRoomObject
   32    132   24 define
   29    129    7 createPassiveObject
   29    103    6 solveMembers
   59     59    1 getmemnum
   32     32    1 memberExists
   20     21    2 createObject
   19     20    2 create
   16     16    1 solveInk
   14     14    1 solveLocZ
   10     10    1 solveBlend
```

Conclusion: the remaining Welcome Lounge room-entry stall is a batch of many
small interpreted Lingo calls during object construction. The low instruction
counts on `validateActiveObjects` and `handle_activeobjects` are wrapper time;
the work is spread across repeated nested calls such as `createRoomObject`,
`createPassiveObject`, `define`, `solveMembers`, `getmemnum`, and
`memberExists`.

The `getmemnum` and `memberExists` names in this capture are app-authored v31
Resource API / Resource Manager Lingo handlers, not native runtime APIs:

```text
/opt/git/v31_assets/projectorrays_lingo/fuse_client/casts/External/MovieScript 9 - Resource API.ls
/opt/git/v31_assets/projectorrays_lingo/fuse_client/casts/External/ParentScript 30 - Resource Manager Class.ls
```

Do not hardcode those handler names into C++ either. The next useful directions
are generic: handler lookup/dispatch caching, interpreter overhead reduction for
repeated small calls, faster generic list/prop-list/script-instance operations,
or cooperative scheduling/chunking so room-object creation does not monopolize a
single worker turn.

## Generic Case-Insensitive Handler Cache Lookup

Changed the existing VM global handler hit/miss caches to use transparent
case-insensitive string hashing/equality:

```text
cpp/include/libreshockwave/lingo/vm/LingoVM.hpp
cpp/src/lingo/vm/LingoVM.cpp
cpp/tests/sdk_foundation_test.cpp
```

Before this change, every `LingoVM::findHandler(...)` call normalized the
incoming handler name into a new lowercase `std::string` before checking the
existing positive and missing-handler caches. Repeated authored global calls
such as the room-entry Resource API helpers were already cached at the VM level,
but still paid that per-call lowercase allocation/probe setup.

The caches now preserve Director-style case-insensitive lookup semantics while
probing directly with the incoming `std::string_view`; allocation is needed only
when a new hit or miss is inserted. This is generic handler-dispatch runtime
work and does not recognize any v31 script name, Resource API helper, room
handler, or BigInt/HugeInt method.

The native test coverage now asserts that a cached external global handler can
be called with different casing without invoking the external global finder a
second time:

```text
callHandler("externalStart")
callHandler("EXTERNALSTART")
```

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the same existing later failure after
the new cache assertion passes:

```text
cpp/tests/sdk_foundation_test.cpp:13458
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Harness remeasurement was not possible in this environment during this pass:
the in-app browser backend reported unavailable, local `playwright`/`puppeteer`
packages were not installed, and no Chromium binary was present on `PATH`.
The server did respond to:

```text
curl -I --max-time 5 http://localhost:3000/venus-quackster-harness
```

Next measurement should rerun the same post-click 1ms threshold capture after
opening the harness with a browser-capable environment. Expect this cache
cleanup to be a small generic dispatch improvement, not a full fix for the
remaining ~405ms active-object batch by itself.

## Generic Script Property-Name Cache

Added a generic cache for declared script property names used when constructing
script instances from script cast members:

```text
cpp/include/libreshockwave/lingo/builtin/BuiltinRegistry.hpp
cpp/src/lingo/builtin/BuiltinRegistry.cpp
cpp/src/lingo/vm/OpcodeRegistry.cpp
cpp/src/lingo/vm/LingoVM.cpp
cpp/tests/sdk_foundation_test.cpp
```

The 1ms room-entry profile shows repeated small object-construction calls:

```text
createRoomObject
createPassiveObject
createObject
create
construct
```

Those calls exercise generic `new(scriptRef)` / script-instance construction.
Before this change, each construction path could ask the player to resolve the
declared property-name list for the same script cast member again. The new
`BuiltinContext::scriptPropertyNamesCache` stores that property-name vector by
cast library/member number and is used by both:

```text
BuiltinRegistry::new(...)
OpcodeRegistry immediate new(scriptRef) path
```

Each new instance still gets its own property entries initialized to VOID, so
instance state is not shared. Only the static declared property-name list is
cached. The cache is cleared from `LingoVM::invalidateHandlerCache()`, which is
already called when external casts/scripts are invalidated.

Native assertions now cover both constructor paths:

```text
registry.invoke("new", context, {scriptRef(5, 9), ...}) twice
immediate PUSH_ARG_LIST + EXT_CALL new(scriptRef(6, 12)) twice
```

In both cases the declared-property resolver is called once for the repeated
script cast member while each constructed instance still contains the declared
VOID properties.

This is generic Director/Lingo runtime work. It does not recognize any v31
script, handler, Resource API helper, furniture class, room function, or
BigInt/HugeInt method by name.

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the same existing later failure after
the new constructor-cache assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13475
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Harness remeasurement was not available in this environment for this pass. The
next browser-capable run should repeat the 1ms post-click threshold capture and
compare the object-construction window around `createRoomObject`,
`createPassiveObject`, `create`, and `construct`.

## Generic Script Instance Property Append Indexing

Added a generic append path for local script-instance properties:

```text
cpp/include/libreshockwave/lingo/Datum.hpp
cpp/src/lingo/Datum.cpp
cpp/src/lingo/builtin/BuiltinRegistry.cpp
cpp/src/lingo/vm/OpcodeRegistry.cpp
cpp/tests/sdk_foundation_test.cpp
```

`Datum::ScriptInstanceRef` now exposes:

```text
reserveLocalProperties(...)
appendLocalProperty(...)
```

The script-instance property index also starts clean for an empty instance.
When a property is appended through `appendLocalProperty`, the exact and
case-insensitive indexes are extended incrementally if they are already clean.

This targets the generic script-instance construction path used during repeated
room object setup. The previous declared-property initialization path populated
new instances through the raw `properties()` vector, which marked the property
index dirty. The first later property access on that freshly constructed
instance then had to rebuild the full index. Declared-property initialization in
both `BuiltinRegistry::new(...)` and the immediate `new(scriptRef)` opcode path
now reserves and appends through the indexed API instead.

Each instance still receives independent local VOID properties, and raw
`properties()` access still invalidates the index for callers that mutate the
vector directly. This is generic script-instance data-structure work and does
not recognize any v31 room script, furniture script, Resource API helper, or
BigInt/HugeInt method by name.

Native assertions now check that properties created through both constructor
paths are immediately findable through `findExactPropertyIndex(...)`:

```text
pDeclared
pOther
pInit
```

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the same existing later failure after
the new property-index assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13480
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Harness remeasurement was not available in this environment for this pass. The
next browser-capable run should compare whether the object-construction cluster
around `createRoomObject`, `createPassiveObject`, `create`, and `construct`
changes after the property-name cache plus indexed append path.

## Generic Exact Local Script-Property Mutation

Added exact local script-instance property mutation helpers:

```text
cpp/include/libreshockwave/lingo/Datum.hpp
cpp/src/lingo/Datum.cpp
cpp/src/lingo/vm/OpcodeRegistry.cpp
cpp/tests/sdk_foundation_test.cpp
```

`Datum::ScriptInstanceRef` now exposes:

```text
putLocalPropertyExact(...)
eraseLocalPropertyExact(...)
```

The immediate script-instance `addProp` / `deleteProp` helper now uses these
methods instead of scanning and mutating the raw `properties()` vector. Exact
updates preserve the current property index and exact appends extend it through
the indexed append path. Deletes still invalidate only when an entry is actually
removed.

This keeps the existing exact local-property semantics used by `addProp` and
`deleteProp`; it does not convert those operations to case-insensitive lookup.
Inherited property writes still go through the normal generic ancestor-aware
property path. The change is generic script-instance data-structure work and
does not recognize any v31 room script, Resource API helper, furniture class,
or BigInt/HugeInt method by name.

Native assertions now cover:

```text
putLocalPropertyExact("ExactLocal", ...)
eraseLocalPropertyExact("ExactLocal")
case-sensitive exact-index behavior for "exactlocal"
```

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the same existing later failure after
the new exact-local mutation assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13489
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Harness remeasurement was not available in this environment for this pass. The
next browser-capable run should compare the 1ms post-click object-construction
window after the script property-name cache, indexed append path, and exact
local mutation changes together.

## Generic Transparent Script Property Index

Changed the script-instance case-insensitive property index to use transparent
case-insensitive string hashing/equality:

```text
cpp/include/libreshockwave/lingo/Datum.hpp
cpp/src/lingo/Datum.cpp
cpp/tests/sdk_foundation_test.cpp
```

Before this change, `findCaseInsensitivePropertyIndex(...)` lowercased the
requested property name into a temporary string before probing the index. The
index now stores the original property key and probes directly with the incoming
`std::string_view`. Exact property lookup still runs first, so this only changes
the fallback case-insensitive path while preserving Director/Lingo property
semantics.

This affects generic script-instance property lookup used throughout room object
setup, including ancestor-aware property access and sprite event broker property
lookups. It does not recognize any v31 room script, Resource API helper,
furniture class, or BigInt/HugeInt method by name.

Native assertions now cover mixed-case property fallback:

```text
findCaseInsensitivePropertyIndex("exactlocal")
getProperty("exactlocal")
```

Verification:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
./cmake-build-debug/cpp/libreshockwave_tests
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
git diff --check
```

The full native test binary still reaches the same existing later failure after
the new transparent-index assertions pass:

```text
cpp/tests/sdk_foundation_test.cpp:13491
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Harness remeasurement was not available in this environment for this pass. The
next browser-capable run should compare the 1ms post-click object-construction
window after the script property-name cache, indexed append/local mutation
paths, and transparent script property index changes together.

## Generic Prop-List Append/Erase Indexing

Added generic property-list append and erase helpers so common Lingo operations
can preserve or deliberately invalidate the prop-list lookup index:

```text
cpp/include/libreshockwave/lingo/Datum.hpp
cpp/src/lingo/Datum.cpp
cpp/src/lingo/builtin/BuiltinRegistry.cpp
cpp/src/lingo/vm/dispatch/PropListMethodDispatcher.cpp
cpp/src/lingo/vm/OpcodeRegistry.cpp
cpp/tests/sdk_foundation_test.cpp
```

`PropList::appendProperty(...)` extends the lookup index when it was already
clean, avoiding a full rebuild after simple append operations. `erasePropertyAt`
invalidates only when an actual erase occurs. Generic `addProp`, `deleteProp`,
`deleteAt`, and prop-list bytecode append paths now route through these helpers.

This targets ordinary Director/Lingo property-list behavior seen during room
object construction. It does not recognize v31 Resource API functions,
BigInt/HugeInt methods, room scripts, parent scripts, or any authored Lingo
handler name in C++.

Native assertions now cover indexed append, transparent untyped lookup after
append, successful erase invalidation, and out-of-range erase behavior.

## Verification Notes

WASM dist was rebuilt successfully with:

```text
cmake --build cmake-build-wasm --target libreshockwave_cpp_wasm_dist -j2
```

Native `libreshockwave_tests` also rebuilds successfully with:

```text
cmake --build cmake-build-debug --target libreshockwave_tests -j2
```

The prop-list transparent untyped key lookup is covered by native assertions in
`testBuiltinRegistryFoundation`; the full binary reaches the later known
runtime failure, so those earlier assertions pass.

The full test binary currently fails before completion at the existing known
assertion:

```text
testLingoVmRuntimeFoundation:
gcCallbacks == 1
```

Reliable click-through after the `script()` cache improvement:

```text
before click frameCount: 240
click position: approximately (909, 137)
transition: stage goes black immediately
room visible: by about 20s after click
steady room interval average: about 41-42ms
steady room max interval spikes: about 60-97ms in sampled windows
screenshot: /tmp/venus-go-wait-1.png
```

Additional screenshots from the 10-second sampling run are:

```text
/tmp/venus-go-sweep-0.png
/tmp/venus-go-wait-0.png
/tmp/venus-go-wait-1.png
/tmp/venus-go-wait-2.png
/tmp/venus-go-wait-3.png
/tmp/venus-go-wait-4.png
/tmp/venus-go-wait-5.png
/tmp/venus-go-wait-6.png
/tmp/venus-go-wait-7.png
/tmp/venus-go-wait-8.png
/tmp/venus-go-wait-9.png
/tmp/venus-go-wait-10.png
/tmp/venus-go-wait-11.png
```

The steady-state room frame cadence after render is close to 24 FPS. Final
acceptance is not met yet because room entry still has large pre-room blocking
stalls, especially the security/login callback. The previous roughly 2.9 second
active-object validation warning did not reappear in two harness runs after the
generic cast member-name index, but that improvement should still be confirmed
with a lower threshold or frame-timeline measurement.

Keep final acceptance tied to the normal harness, not `?debug=1`, and verify
with the supplied native references above.
