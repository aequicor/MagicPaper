# Core ownership

- `logging`: structured safe diagnostics; no app/container dependencies, bounded retention and platform sinks.
- `model`: shared values, serialized identities and pure rules. No Compose,
  concrete storage, service container or feature-implementation dependencies.
- `platform`: platform capabilities and shared attachment handling; UI selects a
  capability through a port rather than branching on the OS throughout a screen.
- `ai:api`: LLM/search/usage contracts. `ai:impl`: clients and adapters.
- `storage:api`: storage contracts and shared draft primitives.
  `storage:impl`: platform persistence; read its more specific instructions.

Keep platform APIs in their source sets. Shared code must still compile for JVM,
Android, JS and Wasm. A JVM pass alone does not validate a common API change.
Check serialized names and old payloads before moving or changing model types.
Provider/client tests use deterministic fakes or local fixtures by default.
