# Session implementation map

Start with [the repository task map](../../../docs/agent-workflows/CODEMAP.md).
Source paths below are relative to `src/commonMain/kotlin/io/aequicor/magicpaper`.

| Task | Owner |
| --- | --- |
| Chat input, durable queue, pause/resume, model selection | `ui/DefaultChatService.kt` |
| Project input, checkpoint, queue and native cleanup | `ui/DefaultCodingService.kt` |
| Shared composer and project transcript | `ui/screens/CodingScreen.kt`: `CodingComposer`, `CodingChat` |
| Chat transcript and composer adapter | `ui/screens/ChatScreen.kt` |
| Approvals and questionnaires | `ui/UserInteractions.kt`, `ui/components/PlanningQuestionsDock.kt` |
| Planning and application tool effects | `domain/OrchestrationService.kt` |
| Parent/child runtime ownership and workspace leases | `domain/SessionTreeRuntime.kt` |
| Atomic organism state, generations and tombstones | `data/coding/SessionOrganismStore.kt` |
| Tool scopes, receipts and native bridge | `domain/tools/ToolHost.kt`, `ToolEnabledCodingRuntime.kt` |
| Orchestration catalog and access policy | `../../custom-tools/api` and `../../custom-tools/impl` |
| Native Pi/Codex and skill input | JVM `data/coding/DesktopCodingRuntime.kt`, `PiCodingRuntime.kt` |

`CodingApprovalDock` and `PlanningBlockerDock` are legacy components. Current
approvals use `UserInteractionRequest` and the questionnaire/interaction dock.

Search the relevant owner with `rg`, excluding `build`. Tests and live fixture
opt-ins are listed in [VERIFICATION.md](../../../docs/agent-workflows/VERIFICATION.md).
