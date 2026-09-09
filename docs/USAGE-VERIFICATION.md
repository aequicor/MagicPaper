# Usage and context verification

Verified on macOS on 2026-09-09. UI baseline: `7cc89b7`. Feature: `61c2f80`, `cffd54f`. Integrated main commits `9bb3171` and `5ef0c9c` and the current uncommitted user edits.

## Results

- Final affected JVM suite: {'shared': {'tests': 200, 'failures': 0, 'skipped': 0}, 'designSystem': {'tests': 11, 'failures': 0, 'skipped': 0}}. All passed, none skipped.
- Real pi and Codex against a local model server: planning, usage, cache, actual request counts and owner context passed.
- Node: all 14 provider bridge checks passed, including Anthropic, Google, OpenRouter, Codex and the pi subscription transport. No paid requests.
- Desktop compileKotlin, Android assembleDebug, JS compileKotlinJs, Wasm compileKotlinWasmJs: passed.
- Design-system boundary guard and git diff --check: passed.
- JVM visual checks at 390/1000 px: menu filters, unknown/estimated context, purple system messages, expandable initial context and preserved drafts. Images: shared/build/reports/usage/.
- Native Windows manual check: NOT_RUN. Follow up on Windows with Tab/Enter/Space/Escape and window resizing.

## Existing UI baseline failures

Full shared JVM suite: 946 tests, 8 failures, 2 skipped. Design system: 10 tests passed. All eight failures were independently reproduced in a detached worktree of the baseline 7cc89b7; the failing test names match exactly. They concern bounded Markdown previews, scrolling and pin-marker placement in the unfinished UI migration. This feature does not repair those pre-existing regressions.

- `io.aequicor.magicpaper.ui.components.LongMessageRenderTest.codingAnswerAndMegabyteUserMessageExpandLazilyInTheOuterTimeline[jvm]`
- `io.aequicor.magicpaper.ui.components.LongMessageRenderTest.hugeMessagesExpandIntoTheChatListAndCollapseWithoutADialog[jvm]`
- `io.aequicor.magicpaper.ui.components.LongMessageRenderTest.oversizedCodeListTableAndPlainParagraphHaveBoundedLazyLayout[jvm]`
- `io.aequicor.magicpaper.ui.screens.ChatScrollToBottomTest.arrowReturnsToTheBottomOfLongAnswersAndResumesFollowingInBothScreens[jvm]`
- `io.aequicor.magicpaper.ui.screens.CodingChatScrollTest.streamingAnswerDoesNotMoveTheReaderInsideTheMessage[jvm]`
- `io.aequicor.magicpaper.ui.screens.CodingChatScrollTest.streamingAnswerKeepsItsHeightBetweenChunksAndFollowsTheBottom[jvm]`
- `io.aequicor.magicpaper.ui.screens.LargeCodingChatRenderTest.expandingAThousandThinkingParagraphsOnlyComposesTheVisibleTail[jvm]`
- `io.aequicor.magicpaper.ui.screens.RequestPinsBrowserTest.messageButtonOpensItsOwnPinAndSelectingAnotherPinRevealsTheSourceInBothChats[jvm]`

## Reproduction

Native JVM integrations use -Pmagicpaper.pi.it=true -Pmagicpaper.codex.it=true with installed engines. Node scripts use local MAGICPAPER_PI_AI, MAGICPAPER_PI_CLI and MAGICPAPER_CODEX_PATH; test credentials are fixtures.

Local main only; no remote push. Late user changes remain uncommitted. A file snapshot and Git stash preserve their pre-merge state.
