# Feature contracts and implementations

Each feature owns its API, component, screen state, services and repository ports.
Use `api/src/commonMain` for contracts and `impl/src/commonMain` for behavior;
platform adapters stay in the implementation's platform source sets.

- Expose component input, immutable state, actions, output events and a factory
  accepting `ComponentContext`. Keep navigation decisions in the root.
- Use another feature's API, never its implementation. Shared models are values,
  not a place to move services until a dependency cycle disappears.
- Components own screen subscriptions and picker work. Services own durable
  entity drafts and background tasks. A route/visit ID is not an entity ID.
- Keep tests beside their owner. Integration-test dependencies may assemble
  several implementations; do not widen production visibility just for tests.
- Tool vocabulary, schemas, access matrix and rejection types belong to `tools:api`;
  execution, receipts and the native bridge belong to `coding:impl`.
- `coding:impl` declares only a jvm target and is consumed from `:app`'s `jvmMain`
  alone. Anything it must share with chat belongs to `session:api` (contracts) or
  `transcript` (session presentation), never to a dependency on another `impl`.
- A host that cannot run an agent binds `UnavailableCodingFeature`. Never omit a
  coding binding: an absent one surfaces as a retry the host can never satisfy.
- Plugin SPI belongs to `plugins:api`; Notes/Focus/Calc to `plugins:impl`;
  skills panels to `skills:impl`; planning UI/runtime to `session:impl`.
  Preserve plugin IDs and registration order. ProjectSkills UI and the coding
  runtime contract are separate capabilities.

For visual changes, apply the root Paper UI instructions. For API changes, inspect
the factory binding in `:app` and the API's direct callers.
