# Venus Quackster Harness Native Parity

Date: 2026-06-15

Goal: when `http://localhost:3000/venus-quackster-harness` reaches the hotel view with the navigator open, match the native v31 rendering for:

- The Habbo figure shown in the bottom-left identity panel next to the `Quackster` text.
- The public spaces illustration shown in the navigator details pane next to the `Public Spaces` description.

Constraints:

- Do not add client shims, page-specific hardcoding, hardcoded Lingo implementations, or bandaid fixes.
- Keep the production host and ports unchanged:

```text
connection.info.host=verysecret.classichabbo.com
connection.info.port=30100
connection.mus.host=verysecret.classichabbo.com
connection.mus.port=38201
```
