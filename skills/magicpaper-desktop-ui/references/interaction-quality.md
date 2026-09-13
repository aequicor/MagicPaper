# Goal completion and smooth interaction

Read for changes to action flows, navigation, scrolling, streaming, animation or
UI performance. Visual composition and preview states are covered by the adjacent
references; this one focuses on completing the user's action efficiently.

## Minimize the path to the goal

- Define the actual goal and starting context: open the chosen session, send a
  prepared message, change a model, recover a failed operation. Count the complete
  meaningful path, including clicks/taps, menu/dialog steps and avoidable navigation.
- Expose frequent primary actions where they are needed. Use a valid existing
  selection and context instead of asking the user to select them again. A sidebar
  selection should reach its destination directly; an intermediate screen needs
  a concrete purpose.
- Make primary actions visible and directly usable with pointer/touch. Do not add
  keyboard shortcuts to simplify a flow or require memorized combinations. Evaluate
  the complete visible action path; a shortcut does not reduce its complexity.
  Preserve keyboard accessibility through focus navigation and standard activation,
  and preserve OS/browser-owned input behavior. Do not make hover or an undocumented
  gesture the only route.
- Compare before/after paths for the changed goal. Reducing visible buttons by
  moving a frequent action into extra menus does not necessarily simplify the task.
  Group less frequent actions coherently and keep primary intent clear.
- Preserve meaningful review/confirmation for destructive or consequential actions.
  Do not trade away safety, user choice, necessary input or understandable feedback
  to improve a click count. Prefer safe defaults and reversible steps when applicable.

## Responsiveness and frame pacing

- Separate input response from operation completion. A click, key or scroll should
  receive prompt visible feedback; long work needs an accurate busy/progress state
  and cancellation where supported. Do not block the main thread while waiting.
- Aim for the display's refresh cadence during scrolling, dragging, resize and
  transitions. The frame interval is `1000 / refreshHz` milliseconds: about 16.7 ms
  at 60 Hz and 8.3 ms at 120 Hz. Use the relevant device's target; do not hard-code a
  universal frame rate or claim that an average FPS proves consistently smooth frames.
- Keep file/network I/O, model work, heavy parsing and large transformations away
  from the UI thread. In Compose, inspect unstable inputs, broad state reads,
  missing stable list keys, repeated allocations and unnecessary measure/layout.
  Scope state observation and caching to actual owners; avoid speculative complexity.
- For streamed output, batch/coalesce display work where it preserves semantics.
  Do not reparse/rebuild an entire long transcript for each token. Preserve selection,
  scroll anchor and follow-end intent when updates arrive or rows expand.
- Animations express continuity and feedback. Keep them short, interruptible and
  consistent with Paper/platform motion; a new action must not wait for decoration
  to finish. Respect reduced motion and avoid perpetual invalidation when idle.
  Do not reset state or replay entrance motion during ordinary recomposition.

Use the official [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance)
and [Compose performance practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
for measurement and targeted fixes; adapt Android tooling to the actual host.

## Acceptance evidence

Record the goal's before/after steps, the measured platform/device, build mode,
window/data size and relevant interaction. Exercise a realistic long transcript,
active streaming, scrolling/resizing or simultaneous work when affected by the fix.

Use supported Compose inspection tools for recomposition/layout and the platform
profiler/frame timing tools for runtime cost. Observe frame-time spikes/dropped
frames and input latency, not just a screenshot or an idle FPS number. Prefer a
representative optimized build for performance conclusions; identify instrumentation
or preview overhead in development measurements.

Preview interactive/animation tooling helps iterate, but preview rendering and
unit tests do not establish application FPS. State what was measured and what was
only inspected; if runtime profiling is unavailable, mark that evidence `NOT_RUN`.
Keep diagnostic payload logging off in normal performance runs, and record any
explicit TRACE setting that affects a measurement.
