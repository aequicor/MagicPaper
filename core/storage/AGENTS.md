# Persistent state

Start with `api/.../data/storage/Persistence.kt` and `DraftSession.kt`, then
`impl/.../data/storage/DurablePersistence.kt`; `...` is
`src/commonMain/kotlin/io/aequicor/magicpaper`. Platform backends live beside their
platform tests. Use fresh backend instances to test reopening persisted data.

- Serialize writes and reject stale revisions/generations, including queued writes
  after delete/reset. A failed read or decode must not turn into an empty overwrite.
- Entity drafts are shared across visits; navigation stores visit identity and
  presentation references. Preserve invalid input and owned attachment bytes.
- Clear after successful acceptance/save/discard, with version checks. A completion
  for an older value must not clear newer edits. Respect shared blob references.
- Keep secrets in the dedicated plaintext store, as the product explicitly requires;
  do not introduce encryption/master-password behavior as an incidental refactor.
- Codex app-server remains the writer of its canonical authorization file. Generated
  Pi configuration uses the environment reference, not copied credential values.
- Report persistence errors without logging payloads or secret values. Preserve
  evidence needed for a retry. Test interrupted migration and deletion/reset races.

IndexedDB behavior requires a browser test; successful JS/Wasm compilation is not
evidence of transaction durability or cross-tab coordination.
