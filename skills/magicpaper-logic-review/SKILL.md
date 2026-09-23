---
name: magicpaper-logic-review
description: "Review MagicPaper state transitions, identities, concurrency, persistence and side effects. Use for logical correctness reviews or risky changes involving navigation, drafts, execution, reset or lifecycle; not for cosmetic review."
---

# Review observable invariants

Inspect the changed owner, its contract and direct callers. Review the requested scope; do not expand
into an unrelated whole-project audit or change code in a review-only task.

For the changed operation identify its input identity, allowed starting states,
durable commit point, external effect, resulting state and cancellation owner.
Check applicable invariants against executable paths, not just intent in comments:

| Area | Counterexample to look for |
| --- | --- |
| Navigation | Two visits to one entity share visit state; Back destroys the runtime; stale dialog dismissal closes a new dialog |
| Drafts | Old send completion clears new typing; restored invalid input is normalized away; byte storage fails after draft metadata commits |
| Persistence | Failed read becomes empty overwrite; late writes revive deleted/reset records; migration removes the only secret copy before verification |
| Execution | Restore repeats an accepted launch; UNKNOWN outcome is retried; a stale generation acts; writer lease releases before native cleanup |
| DI/lifecycle | Two runtime instances; duplicated startup observer; screen disposal cancels app work; partial startup leaks resources |
| Forms/links | Opening a route creates an object, applies settings or confirms a request; disabled plugin starts implicitly |

Choose realistic event orders such as edit→send→edit→old completion,
write→reset→late completion, or launch→cancel→unknown native outcome. Use a small
transition table when it makes the issue clearer. A matching unit test must prove
the invariant through behavior; a method-call count alone may miss durable effects.

Report findings with file/line, trigger, consequence and a bounded fix or test.
Distinguish demonstrated defects from hypotheses and unavailable evidence.
If no defect is found, state what was checked and the relevant limits.
