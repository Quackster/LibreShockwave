# Bug Tracker

## Status: FIXED

### Bug Description
When changing external_variables.txt `cast.entry.16=hh_entry_br` to `cast.entry.16=hh_entry_au`, the headless JS test (wasm-chromium-test.js) gets stuck at the Sulake logo loading screen with only 2 sprites. Never progresses past loading.

### Reproduction
1. Changed `C:\xampp\htdocs\gamedata\external_variables.txt` line 11: `cast.entry.16=hh_entry_br` → `cast.entry.16=hh_entry_au`
2. Ran: `node player-wasm/src/test/js/wasm-chromium-test.js`
3. Result: FAIL — stuck at 2 sprites, frame 10, loading times out after 5001 ticks
4. Screenshot shows Sulake logo on black screen, no loading bar

### Findings
- Worker code (shockwave-worker.js:881) had a hardcoded locale override that replaced `cast.entry.2=.*` with `cast.entry.2=hh_entry_au`
- This was originally added to override hh_patch_uk with hh_entry_au
- When external_variables.txt entry 16 was ALSO changed to hh_entry_au, the cast was loaded twice as a duplicate
- Duplicate cast loading caused the player to fail silently during initialization — loading bar never appeared
- Removing the hardcoded override and letting external_variables.txt be used as-is fixes the issue

### Resolution
Removed the hardcoded `cast.entry.2=hh_entry_au` locale override from `shockwave-worker.js` (lines 872-884). The external_variables.txt entries are now passed through to the WASM engine unmodified, so the user can control which locale cast is used by editing external_variables.txt directly.

### Files Involved
- player-wasm/src/main/resources/web/shockwave-worker.js (removed locale override)
- C:\xampp\htdocs\gamedata\external_variables.txt (cast.entry.16)

### Watchlist
- player-wasm/src/main/resources/web/shockwave-worker.js
