# Settings and profiles

Start with `api` contracts, then `impl/.../ui/DefaultSettingsService.kt`,
`SettingsDrafts.kt`, and `data/storage/JsonSettingsRepository.kt` or
`JsonLlmProfileRepository.kt`. Here `...` means
`src/commonMain/kotlin/io/aequicor/magicpaper`.

- Persist raw form input, including invalid values. Parsing/validation must not
  erase the draft or apply settings during restore.
- A profile link opens a saved profile or its existing draft; an arbitrary missing
  ID must not create a profile. Explicit creation is a separate action.
- Put secret fields through `SecretStore`. Migrations write and verify the secret
  before removing inline values; interrupted migration must be retryable.
- Preserve explicit profile import/export semantics, including saved keys when
  requested. Do not add drafts, navigation or subscription tokens to export.
- Successful save/discard/delete clears the corresponding draft versions and
  owned secrets. Check dependent forms and restart behavior, not only cached UI.
- Keep model/provider configuration changes separate from editor navigation.

Storage implementation changes also require `core/storage/AGENTS.md`.
