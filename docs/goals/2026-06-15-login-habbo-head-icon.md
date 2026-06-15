# Login Habbo Head Icon Parity

Date: 2026-06-15

Status: Complete. The Habbo head renders next to `Quackster` in the
bottom-left identity panel in the normal no-debug harness.

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
/home/alex/Pictures/Screenshots/Screenshot_20260615_215344.png
```

In the native v31 screenshots, including the cropped bottom-left reference, a
small Habbo head appears at the far left of the bottom bar, immediately before
the `Quackster` text block. The previous C++/WASM render reached login but
left that image area blank; the verified fix now renders the head there.

The final result must look like the provided references: a small circular Habbo
head icon with face and hair is visible inside the black bottom-left identity
panel, aligned left of the `Quackster` name. The text block starts to the right
of the head and includes `Quackster`, `Hello Habbo!`, and
`Update My Habbo ID >>`. A screenshot that only shows the logged-in exterior or
the Navigator illustration is not sufficient unless this head icon is visible in
that exact bottom-left position. The final screenshot should match the provided
references closely enough that the face/hair icon is visibly present next to
`Quackster`.

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

Update 2026-06-15: the chained collection property fix was useful for figure
data parsing, but it did not complete this goal. A later review found the
required head is still missing beside `Quackster`; do not use the previous
normal-harness screenshot as acceptance evidence for this item.

Update 2026-06-15 after the XML/prop-list property-call fix: the normal
harness was rebuilt and run without `?debug=1` for 90 seconds. Screenshot
`/tmp/venus-head-proplist-fix-bottom-left-no-overlay.png` still shows no Habbo
head beside `Quackster`, while `/tmp/venus-head-proplist-fix-stage-no-overlay.png`
shows the hotel exterior and Navigator Public Spaces illustration. The goal is
still not finished.

Update 2026-06-15 after adding untyped case-insensitive XML property lookup:
the normal harness was rebuilt and run without `?debug=1` for 180 seconds.
That run reached the hotel exterior but ended on the `Problems Connecting`
modal with the socket closed, so `/tmp/venus-head-after-xml-fix-bottom-left.png`
does not show the logged-in `Quackster` identity panel and is not valid
acceptance evidence. The goal remains not finished until a no-debug harness run
reaches login and visibly shows the head beside `Quackster`.

Update 2026-06-15 five-minute no-debug harness run: the normal harness reached
the logged-in hotel exterior and Navigator after waiting 300 seconds. Screenshot
`/tmp/venus-head-5min-stage.png` shows the hotel and Navigator, but
`/tmp/venus-head-5min-bottom-left.png` still shows only the `Quackster`,
`Hello Habbo!`, and `Update My Habbo ID >>` text block with no Habbo head
beside it. This is a valid failure capture, and the goal is still not finished.

Update 2026-06-15 debug run after the five-minute failure: the missing head is
not caused by the harness failing to reach login. With `?debug=1`, targeted
figure diagnostics showed:

```text
Figure Data Class.parseData result=int
pColorList=propList(0)
pSetList=propList(0)
pSetTypeList=propList(0)

Figure System Class.parseNewTypeFigure result=propList(0)
Figure Preview Class.getHumanPartImg result=image(1x1)
Figure Preview Class.feedHumanPreview arg2=image(1x1)
```

This means the user identity panel is receiving an effectively empty 1x1
preview image because figure XML parsing is still not populating the generic
figure data tables. The next debugging boundary is the XML Xtra/property
traversal used by Lingo expressions such as `tParserObject.child.count`,
`tParserObject.child[i].name`, `tElement.attributeName[j]`, and
`tElement.attributeValue[j]`. Do not fix this with client shims or hardcoded
Habbo Lingo behavior; the fix should remain in generic Director-compatible C++
property/Xtra semantics.

Update 2026-06-15 after fixing the VM fast prop-list object-call path: the
normal harness was rebuilt and run without `?debug=1` for 300 seconds at:

```text
http://localhost:3000/venus-quackster-harness
```

The logged-in hotel exterior and Navigator rendered, and the bottom-left
identity panel now shows the small Habbo face/hair head icon beside
`Quackster`, matching the native reference placement. Acceptance screenshots:

```text
/tmp/venus-head-fast-proplist-stage.png
/tmp/venus-head-fast-proplist-bottom-left.png
```

Root cause: compiled Lingo calls such as `tElement.child[j]` can reach the VM
fast prop-list object-call path before the full prop-list dispatcher. That fast
path returned the `child` list for `getPropRef(#child, j)` and ignored the
third index argument, so `tElement.name` became void and figure XML parsing
never populated `pColorList`, `pSetList`, or `pSetTypeList`. The generic fix is
to make fast prop-list `count(key)` and `getProp`/`getPropRef(key, index)`
mirror the dispatcher behavior for nested list properties.

The fix remains generic: chained `count`/`length` on lists and prop lists,
property-name calls on XML node prop lists, XML Xtra property access, and the
VM fast prop-list object-call path now resolve through Director-compatible
collection semantics. The solution does not special-case Habbo figure data,
`figuredata.xml`, or the harness.

## Acceptance Criteria

- The bottom-left Habbo head renders beside `Quackster` after login.
- The result matches the placement and scale shown in
  `/opt/git/v31_room_load/v31_native.png`.
- The Navigator Public Spaces illustration continues to render.
- No endpoint, port, or harness-side shim changes are introduced.
- The fix is personally confirmed in the normal harness, without `?debug=1`,
  after waiting for the login flow to complete.
