# MagicPaper — cinematic welcome

Standalone Russian-language web prototype of MagicPaper's WelcomeScreen.
Selected direction: a cloud title sequence followed by a living book and three
chapters (model, search, plugins). No build step, package installation or external
network dependency is required.

## Run

From the repository root:

```sh
python3 -m http.server 8847 --bind 127.0.0.1 --directory output/welcome-cinema
```

Open http://127.0.0.1:8847. The page also works from `index.html`; browser rules may
limit localStorage on file URLs. Storage failure is reported without blocking the tour.

## Interaction

- Start/resume, forward/back, direct chapter navigation, and skip.
- Model and search radio groups; individually selectable plugins from the real
  MagicPaper catalog: Notes, Focus, Calculator.
- A final summary reflects the selection, including the no-plugin state.
- Three explicitly labeled, prewritten teaching examples. No AI request is made.
- Versioned local progress contains choices only; no credentials are requested.
- Quiet original Web Audio ambience requires an explicit click. No music download,
  microphone access, autoplay, or franchise soundtrack is used.
- Motion can be switched off, respects OS reduced-motion preferences, and stops
  while the page is hidden. New navigation interrupts the current animation.
- Native form controls, keyboard focus, live answer region, responsive layout.

This is a tutorial prototype, not an account connection or a settings writer for
the installed MagicPaper app. The page states this at the point of completion.

## Assets and provenance

Images were generated with the **built-in image_gen tool**. Exact prompts are in
`image-prompts.json`.

- `assets/clouds.png`: original cinematic cloud backdrop.
- `assets/book.png`: original living-book chapter illustration.
- `concepts/welcome-directions.png`: the three concept directions for WelcomeScreen.
- `assets/display.ttf`: project's Cormorant Garamond Medium.
- `assets/body.ttf`: project's Literata Regular.
- `assets/OFL_ALL.txt`: the font license notice copied with the fonts.

Image assets are local and bundled; nothing references the generator cache.

## Verification — 2026-09-17

PASS: JavaScript syntax (`node --check app.js`); whitespace check; browser walkthrough
of the three chapters, model/search choices, plugin toggle, final summary, skip,
resume after reload, first-question example, keyboard Space on motion toggle,
audio toggle state, and absence of console warnings/errors during those checks.

PASS: actual desktop render inspected; 390 × 844 responsive layout inspected,
document width = viewport width = 390 px; Back and Next bounds remain inside the
viewport. Responsive viewport override was reset after testing.

Found and fixed during browser testing: snapshot-based page transitions could eat
a rapid second click. Replaced with interruptible animation of the live content;
retested consecutive navigation successfully.

Not verified: physical audio output, native mobile Safari/Android, screen reader,
OS reduced-motion integration on a separate device, and performance profiling.
Browser testing does not establish a measured frame rate.

The Kotlin application has not been modified by this web deliverable.
