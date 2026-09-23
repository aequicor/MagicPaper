# Vendored UI-creation skills

Third-party agent skills for creating and reviewing user interfaces, vendored
verbatim from [MengTo/skills](https://github.com/MengTo/skills) at commit
`5f47e389dac337a1bca5cddf376419248b3010f6` (upstream folders `agent-skills/ui/`
and `agent-skills/web-design/`). License: MIT, © Meng To — see [LICENSE](LICENSE),
which covers every skill in this folder.

These are design playbooks, not MagicPaper contracts. They complement
[`magicpaper-desktop-ui`](../magicpaper-desktop-ui/SKILL.md); they never override
the Paper API boundary, the Paper palette (`PaperTheme.kt`) or `AGENTS.md`.

## What was vendored, and what was not

Copied: `SKILL.md`, `REFERENCES.md`, `ARTICLE.md` and `agents/openai.yaml`.
Skipped: upstream `demo/` folders (`index.html`, `preview.jpg`, `source.json`,
multi-megabyte reference screenshots). No vendored `SKILL.md` links to them, so
the playbooks stay complete and the repository stays lean. Relative links
between skills are preserved: `audit-ai-design-slop/SKILL.md` points at
`../no-ai-design-slop/ARTICLE.md`, so both live side by side here.

Upstream writes for the web (Tailwind, CSS, GSAP, IntersectionObserver,
Three.js). MagicPaper renders Compose Multiplatform through the Paper design
system: take the judgment, timing, easing and hierarchy rules; re-express the
recipe with Paper tokens and Compose modifiers. Never copy a CSS/JS snippet into
a feature module, and never bypass `:designSystem` to reproduce an effect.

## Catalog

Design quality and prompting (upstream `ui/`):

| Skill | Use it for | Kind |
| --- | --- | --- |
| [no-ai-design-slop](no-ai-design-slop/SKILL.md) | Passive quality gate while creating or revising UI: catch generic generated defaults, decorative stacking and hierarchy loss without forcing a neutral redesign | Principles, platform-neutral |
| [audit-ai-design-slop](audit-ai-design-slop/SKILL.md) | Evidence-backed review of an existing screen or render; removal-first cleanup plan | Principles, platform-neutral |
| [design-first-ui-prompting](design-first-ui-prompting/SKILL.md) | Turn a fuzzy idea into a spec-driven UI prompt: layout, type system, color, states, variations | Principles, platform-neutral |

Motion and surface polish (upstream `web-design/`):

| Skill | Use it for | Kind |
| --- | --- | --- |
| [animation-systems](animation-systems/SKILL.md) | Motion system decisions: easing/duration defaults, choreography, interruptibility, reduced motion, performance budget | Principles, transferable to Compose |
| [staggered-word-reveal](staggered-word-reveal/SKILL.md) | Staggered text/word entrance timing and per-item delay curves | Principles + web recipe |
| [animation-on-scroll](animation-on-scroll/SKILL.md) | Scroll-triggered reveals, one-shot vs repeat triggering, in-view thresholds | Web recipe |
| [masked-reveal](masked-reveal/SKILL.md) | Revealing text through an overflow mask while it scrolls into view | Web recipe |
| [beautiful-shadows](beautiful-shadows/SKILL.md) | Layered neutral elevation for cards, panels, popovers; shadow stacking without colored tinting | Web recipe, maps to Paper elevation |
| [progressive-blur](progressive-blur/SKILL.md) | Stepped edge blur for depth at a viewport edge (compare `paperChatTopShadow`) | Web recipe |
| [css-alpha-masking](css-alpha-masking/SKILL.md) | Gradient edge fades via alpha masks; Compose equivalent is a `Brush` fade/`graphicsLayer` | Web recipe |
| [reveal-hover-effect](reveal-hover-effect/SKILL.md) | Pointer-following spotlight reveal between two aligned treatments | Web recipe, pointer-only: needs a non-hover path in MagicPaper |

## How to work with them

1. Pick the skill that matches the task; do not chain-read the whole folder.
2. Read the local surface first: the Paper palette in `PaperTheme.kt`, the Paper
   component you are changing and its existing previews.
3. Apply the skill's judgment gates to the real render, at the real window sizes
   and text scales, not to the source text.
4. Keep the acceptance evidence MagicPaper requires: named previews, inspected
   renders, measured interaction quality, and `NOT_RUN` for what was not checked.

Discovery links for agent harnesses live in `.agents/skills/`, one per skill,
pointing at the canonical folders here.
