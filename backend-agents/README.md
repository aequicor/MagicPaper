# Native backend boundary

This group has JVM targets only. `:api` is engine-neutral: the descriptor and
capabilities that configure an engine, the adapter and lifecycle contracts, and the host
ports an engine is given. It declares nothing named after one engine; `Pi*` and `Codex*`
types (`PiLaunchRequest`, `PiInstallation`, `CodexRunRequest`, `CodexClient` and the like)
live in `:pi` and `:codex`, and `tools/verify/verify-module-architecture.py` rejects one in `:api`.
`:factory` is its only construction entry point; it returns API types and hides both
implementation modules from consumers. Only `:magic-agent:runtime:impl` consumes the factory.
Neither backend implementation depends on features, application tools, storage,
AI services, Compose, or DI. Their only project dependency is `:backend-agents:api`,
whose shared values come from `:core:model`.

The implemented engines are Pi, Codex and Claude Code. The target architecture's reference to
five engines has no counterpart in the current source; three empty adapters are
not created.

The extracted Pi adapter owns model configuration, wire-event/usage parsing,
isolated Node/npm/MinGit installation, bundled search tools and each process
attempt: command construction, stdin, tolerant UTF-8 stdout,
cancellation, exit and process-tree cleanup. The runtime passes a fully prepared
`NativeAgentRequest` and a durable ownership port, from which the adapter builds its own
`PiExecutionRequest`; ownership is recorded before
stdin can deliver a prompt and is only cleared after shutdown is confirmed.
Cancellation remains control flow. Launch errors preserve their original cause,
and failed cleanup cannot be returned as success.
`PiInstallation` receives packaged bytes through the host resource port and keeps
the existing `coding/prefix`, `coding/node`, `coding/shell` and `coding/bin` paths.
Installer cancellation stops the command process tree; output consumption cannot
block the command deadline. Native preparation failures are reported through safe
status text and diagnostics, while cancellation remains control flow.

`AgentRunResources` in the runtime prepares application tools, research,
computer/browser access and questionnaire extensions. It owns their handles for
the whole run, including auto-continuations, releases partial acquisitions on
failure and attempts all cleanup in reverse order. The backend receives only
extension paths, environment values and tool names. It never resolves a
`ToolSession`, starts an application bridge or reads application state.

The runtime reaches an engine's own account (login, model list, one plain completion)
through `NativeSubscriptionAccess`, created by `createNativeSubscriptionAccess` from the
engine's `BackendAgentContribution`; an engine without an account returns none.

Claude Code (`:claude`) is an externally installed CLI run once per request as
`claude -p --output-format stream-json`. It owns command construction (`ClaudeCommand`), the
stream parser, and the child process; the prompt goes through stdin, the system instruction and MCP
configuration through per-attempt files that never reach argv. It declares no native approvals: a
read-only run is limited to `Read`, `Grep`, `Glob` and application tools, a coding run skips permission
prompts. Its journaled delivery stage is `CLAUDE_STDIN`.

Codex owns sandbox/approval policy, native start/resume/turn payload construction,
web-item labels, terminal evidence, account/login/model RPC, native approvals and
the app-server connection lifetime. `CodexRunRequest`
separates provider configuration from already resolved application MCP endpoints.
Read-only policy overrides provider settings and excludes inherited endpoints
while preserving the explicitly supplied application tools.

The runtime's subscription facade implements the application AI contract and
prepares messages, attachments, tools and usage accounting before calling Codex.
Narrow host ports supply durable process receipts, diagnostics, access tokens,
tool presentation and questionnaire delivery. Refresh tokens and the raw auth
document never cross this boundary. The questionnaire service and its journal
remain application-owned; native delivery begins before the pipe write and an
unacknowledged write retains an unknown outcome. Failed interrupts also retain
the durable process receipt and require reconciliation. Cleanup requests remain
bound to the original connection and report failure without hiding cancellation.

App composition constructs one `DesktopNativeRuntime` bundle. `BackendAgentContribution`
entries are discovered by `ServiceLoader` in the backend factory; Gradle includes each
installed engine project automatically. Contributions hold metadata only. Each explicit
catalog construction creates separate native state, validates declared optional ports,
and closes partially created instances if construction fails. Duplicate identities are
rejected before any environment is requested.

The host has one `GenericNativeRuntime`: it resolves application policies, profile controls,
questionnaire/tool/computer/browser resources and provider usage, then passes inert
`NativeAgentRequest` values to the selected backend. Pi owns native configuration files,
attempts and auto-continuation. Codex owns per-session clients and RPC lifetime. History,
removal, approval ports and model proxy requirements come from declared capabilities;
engine-specific instruction text comes from the descriptor. Settings and engine selectors
read the installed catalog. Compatibility entry points for historical integration fixtures
also delegate to the generic host; the two-engine fixture list lives only in test sources.
Adding an adapter for an already declared identity needs its module and include. A genuinely
new persisted identity still requires an explicit `CodingEngine` enum extension; discovery
does not silently migrate saved identities.

The provider library owns the model-only process and Responses proxy. One application
installation instance is shared by these transports and native Pi execution. The host
supplies current access tokens and resolved generation controls, and owns usage recording.
Before native model access, a dedicated provider receipt port reconciles only processes
whose recorded application owner is dead and whose PID/start-time pair still matches.
Live-owner receipts are untouched; unreadable ownership or failed cleanup remains visible.
Browser commands use their `ToolSession` receipt identity, while computer endpoints use
the explicitly bound native run identity. Both resources close before terminal completion.

`NativeLifecycleMachine` in the API owns admission, attempt identity, delivery, external
outcome, cleanup and explicit recovery acknowledgement. Its private interpreter lives in
`:backend-agents:lifecycle:impl`, consumed only by the factory. Engines receive a scoped
observation port through `NativeAttemptContext`; they cannot construct or reload the owner.
The host adapts its real EventJournal to `NativeLifecycleJournal` under the unchanged native
home namespace. Journal records contain identities and lifecycle facts, never prompts,
credentials, questionnaire answers or provider bodies. Process maps are live handle indexes,
not admission authority.

| Input | Durable decision before returning | Permitted consequence |
| --- | --- | --- |
| Begin fresh request | Exact request admitted once; prior unknown/cleanup fenced | Enter native adapter |
| LaunchRequested | New attempt ordinal recorded | Create one passive native process |
| Attached | Exact receipt/PID/start identity recorded | Prepare request delivery |
| DeliveryRequested | Outcome becomes UNKNOWN before pipe/RPC write | Deliver this stage once |
| Terminal | Protocol proves SUCCEEDED or FAILED | Preserve result; cleanup still required |
| Stopping / Stop | Cleanup outcome becomes UNKNOWN | Stop the owned resource |
| Stopped | Interpreter proves resource cleanup | Keep external outcome unchanged |
| Acknowledge | Exact parent decision retained for a stopped predecessor | One explicit fresh request may consume acknowledgement |
| Restored | Unfinished termination becomes UNKNOWN; admission closes for affected session | No effects, no retry, no automatic acknowledgement |
| PersistenceUnknown | All further lifecycle mutation is fenced | Inspect/report only |

The interpreter validates identities, journal positions and generation. Lost acknowledgements
are accepted only after an exact previous record/position prefix and exact new input are read
back. Cancellation after a committed append adopts that state but never returns its effect.
`JournaledBackendAgent` withholds Finished until native cleanup and RunFinished are committed.
Reset closes admission, cancels and joins live executors, proves cleanup, then permits the host
to clear persistence; resume loads the new generation without running anything.

Remaining recovery work is explicit: an absent or reused parent PID cannot prove that orphaned
descendants stopped. Restore and legacy PID reconciliation therefore keep termination UNKNOWN;
they cannot authorize Abandon or a fresh request. A future verified process-group cleanup port
must supply that proof before those cases can be released. Installation/account control resources
retain their existing dedicated cancellation/cleanup contracts; this journal owns agent attempts
and provider transport resources. Durable legacy process receipts remain host implementations of
backend-owned ports, separate from terminal model evidence.

Existing Kotlin packages and native resource paths are preserved. No session IDs,
saved engine identities, skill adapter names or provider IDs change.

Checks:

```sh
./gradlew :backend-agents:api:jvmTest :backend-agents:lifecycle:impl:jvmTest :backend-agents:pi:jvmTest :backend-agents:codex:jvmTest :backend-agents:claude:jvmTest :backend-agents:factory:jvmTest :backend-agents:pi:nodeProtocolTest
python3 tools/verify/verify-module-architecture.py --self-test
```

Protocol tests cover model limits, reasoning, usage and malformed wire events;
Codex tests cover permission boundaries and exact terminal evidence, and drive the
client's event handling, approval routing and process-tree recovery against local
fakes of the host ports (`CodexTestSupport.kt`). The application's durable receipts and
questionnaire journal are covered by their owners. A local Java
process fixture checks Pi argument boundaries, child-only environment, ownership
before prompt delivery, malformed UTF-8, cancellation of silent children, abort
before launch, consumer failure and cleanup failure without installing an agent
or making network requests. Live engine integrations remain
opt-in in the runtime owner.
Native RPC fixtures also cover cancellation during account refresh, unavailable
rate limits without losing the signed-in state, and cleanup failures. Runtime
integration fixtures pair the real native client with durable process receipts
and the application questionnaire journal, without depending on engine modules.

Subscription chat uses `LlmGateway.turn` and the same application tool loop as
other providers. Its native transport imports only the installed pi-ai 0.84.4
`api/openai-codex-responses.js` provider export, never an agent or native tools.
The backend owns the single-call process, native model/context mapping, usage and
opaque assistant continuation. The runtime supplies prepared messages, common tool
definitions/results and the short-lived access token; the native library resolves its
dependency locations and supplies the token through stdin. Account refresh remains with Codex; no auth document is written by this path.
Call IDs and signed thinking are preserved using pi-ai's `openai-codex` provider
identity. Unknown/truncated calls fail before application tools can execute.
One shared installation owner prevents chat and agent preparation from racing.
Provider shutdown and cancellation terminate children before clearing ownership;
provider errors have safe presentation text and separate content-free diagnostics.

`PiProviderProtocolTest`, `PiProviderTurnExecutionTest` and the runtime's
`SubscriptionProviderTurnsTest` cover continuation, tool identity, error sanitation,
usage, installation failure and cancellation. Setting `MAGICPAPER_PI_AI_DIST` to
an existing **0.84.4** pi-ai `dist` directory enables an additional test of the
actual package with mocked SSE/fetch and a synthetic token; it forbids live fetch
and does not install dependencies or access account credentials.
