# Coding Module Code Map

## Key Files and Their Responsibilities

### Service Layer
- **`DefaultCodingService.kt`** - Main service implementation
  - `abortCodingRun()` (line 1305) - Abort current coding run
  - `abortCodingSession(sessionId)` (line 1310) - Abort specific session
  - `respondCodingApproval()` (line 1338) - Handle approval decisions
  - `cancelCodingSessionCreation()` (line 559) - Cancel session draft

### UI Components
- **`CodingScreen.kt`** - Main screen composition
  - `CodingChat` composable (line 846) - Chat interface
  - `SessionArea` composable (line 298) - Session content area
  - Parameters: `onStopApproval`, `onApproval`, `onAbort`

- **`CodingApprovalDock.kt`** - Approval confirmation UI (NOT CURRENTLY USED)
  - Shows approval requests with Allow/Deny/Stop buttons
  - Parameters: `approvals`, `onAnswer`, `onStop`

- **`PlanningBlockerDock.kt`** - Planning blocker UI (NOT CURRENTLY USED)
  - Shows blocked execution states
  - Parameters: `session`, `history`, `service`, `busy`

### Interaction System
- **`UserInteractions.kt`** - Converts domain events to UI interactions
  - `approvalInteraction()` - Converts `CodingApproval` to `UserInteractionRequest`
  - `interactionCandidates()` - Aggregates all pending interactions
  - Approvals are shown through questionnaire system, not `CodingApprovalDock`

### Domain Models
- **`CodingApproval`** - Approval request from runtime
- **`UserInteractionRequest`** - UI interaction wrapper
- **`InteractionKind.APPROVAL`** - Approval interaction type

## Search Patterns That Work

### Finding abort/stop functionality:
```bash
# In service layer:
grep -r "abortCoding" feature/coding/impl/src/commonMain/kotlin/io/aequicor/magicpaper/

# In UI callbacks:
grep -r "onStopApproval\|onAbort" feature/coding/impl/src/commonMain/kotlin/io/aequicor/magicpaper/ui/
```

### Finding approval handling:
```bash
# Approval component (unused):
grep -r "CodingApprovalDock" feature/coding/impl/

# Approval interactions (used):
grep -r "approvalInteraction\|InteractionKind.APPROVAL" feature/coding/impl/
```

## Current Architecture Note

**Important**: `CodingApprovalDock` and `PlanningBlockerDock` components exist but are NOT integrated into the UI. Approvals are currently displayed through the questionnaire/interaction system via `UserInteractions.kt`.

If you need to find where approvals are shown to users, look at:
1. `UserInteractions.kt` - converts approvals to interactions
2. Questionnaire rendering components
3. `CodingScreen.kt` line 338-339 - passes approvals to CodingChat
