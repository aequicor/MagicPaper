# MagicPaper UI

For interface work, read and apply `skills/magicpaper-desktop-ui/SKILL.md` and
`docs/desktop-ui/BRANDBOOK.md`. All new reusable visual and interactive components
belong to `:designSystem`; application screens and plugins consume its Paper API.
Run `python3 docs/desktop-ui/verify-design-system.py --self-test` and relevant
Gradle checks. Keep user-facing copy limited to labels, actions, results,
validation and information needed for decisions; implementation explanations
belong in code.

By default, center button content horizontally and vertically within the hit area;
center an icon and label together as one group. Use a different alignment only
when an explicit component or platform guideline requires it.
