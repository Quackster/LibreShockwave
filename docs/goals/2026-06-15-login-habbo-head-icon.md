# Login Habbo Head Icon Parity

Date: 2026-06-15

Goal: when `http://localhost:3000/venus-quackster-harness` reaches the logged-in
hotel exterior, the bottom-left identity panel must render the Habbo head next
to:

```text
Quackster
Hello Habbo!
Update My Habbo ID >>
```

Native reference:

```text
/opt/git/v31_room_load/v31_native.png
```

In the native v31 screenshot, a small Habbo head appears at the far left of the
bottom bar, immediately before the `Quackster` text block. The current C++/WASM
render reaches login but leaves that image area blank.

## Asset And Lingo Lead

The relevant Lingo and assets are expected under the v31 interface cast:

```text
/opt/git/v31_assets/hh_interface.cct
```

Locally extracted paths to inspect:

```text
/opt/git/v31_assets/hh_interface
/opt/git/v31_assets/projectorrays_lingo/hh_interface
/opt/git/v31_assets/projectorrays_lingo/hh_interface.cst
```

The runtime entry path already identified is:

```text
updateEntryBar
createMyHeadIcon
Figure_Preview.createHumanPartPreview
Figure_Preview.getHumanPartImg(#head, ...)
Human Class EX.getPartialPicture(#head, ...)
```

## Constraints

- Do not add client shims.
- Do not hardcode movie-specific Lingo implementations.
- Do not add page-specific, member-name-specific, or harness-only fixes.
- Keep the `verysecret.classichabbo.com` hosts and ports unchanged.
- Fix the C++ runtime, VM, cast, image, window, or renderer behavior that owns
  the missing image.

## Investigation Plan

1. Inspect `hh_interface` cast members and Lingo for the bottom bar element
   `ownhabbo_icon_image` and related member definitions.
2. Verify whether `Figure_Preview.createHumanPartPreview` is called after login.
3. If called, inspect whether `getHumanPartImg(#head, ...)` returns a valid
   image or fails while creating the temporary human object.
4. If the generated image is valid, trace `feedHumanPreview`,
   `clearImage/feedImage`, the dynamic member bitmap, and `SpriteBaker`.
5. If the generated image is empty, trace generic figure data loading and body
   part rendering: `Figure_System.parseFigure`, `human.partset.head.*`,
   bodypart class creation, part ordering, and `copyPicture`.
6. Add focused C++ coverage for the missing generic behavior once identified.
7. Rebuild native tests and WASM dist, then verify with a fresh screenshot of
   the logged-in harness.
8. For final visual confirmation, use the normal harness URL and wait long
   enough for login to complete:

```text
http://localhost:3000/venus-quackster-harness
```

Do not use `?debug=1` for the final confirmation screenshot.

## Current Diagnosis

The login flow reaches the hotel exterior, but `Figure_System.parseData` leaves
`pSetList` empty. The root XML object is loaded correctly, but Lingo loops such
as `repeat with i = 1 to tParserObject.child.count` depend on chained `.count`
property access on lists. The C++ VM only handled numeric chained access on
lists, so `child.count` returned void and the figure data loops never populated
sets or colors.

The fix should remain generic: chained `count`/`length` on lists and prop lists
must resolve through the same runtime property helpers used elsewhere. Do not
special-case Habbo figure data, `figuredata.xml`, or the harness.

## Acceptance Criteria

- The bottom-left Habbo head renders beside `Quackster` after login.
- The result matches the placement and scale shown in
  `/opt/git/v31_room_load/v31_native.png`.
- The Navigator Public Spaces illustration continues to render.
- No endpoint, port, or harness-side shim changes are introduced.
- The fix is personally confirmed in the normal harness, without `?debug=1`,
  after waiting for the login flow to complete.
