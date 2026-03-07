# RESOLVED: Registration dialog buttons unclickable after back navigation

## Root Cause
`PropList.getOne()` was not implemented in `PropListMethodDispatcher`. The Habbo window system's
`unmerge()` calls `pElemList.getOne(tItem)` to find element keys for deletion. Without it,
`unmerge` silently failed to clean up `pElemList`. When `buildVisual` ran again after back
navigation, it found existing element IDs and appended group numbers (e.g., "reg_olderage_button"
became "reg_olderage_button1"). The Event Broker passed this mangled ID to
`eventProcFigurecreator`, which has exact string matching that no longer matched.

## Fixes Applied
1. **`PropListMethodDispatcher.java`** — Added `getOne` implementation (searches by value, returns key)
2. **`EventDispatcher.java`** — Added `vm.resetErrorState()` to `dispatchSpriteEvent` and
   `dispatchFrameAndMovieEvent` (secondary robustness fix)

## Test
`node player-wasm/src/test/js/wasm-registration-test.js` — both assertions pass.
