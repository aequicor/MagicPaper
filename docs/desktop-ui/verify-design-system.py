#!/usr/bin/env python3
"""Enforce the renderer and interactive-primitive boundary in production sources."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN = re.compile(r'androidx\s*\.\s*compose\s*\.\s*material(?:3)?\b|com\s*\.\s*mikepenz\s*\.\s*markdown\b|androidx\.compose\.foundation\.(?:clickable|combinedClickable|text\.BasicTextField|selection\.(?:selectable|toggleable))\b')


def violations(root):
    errors = []
    for build in root.glob('*/build.gradle.kts'):
        if build.parent.name == 'designSystem':
            if 'project(":shared")' in build.read_text(encoding='utf-8'):
                errors.append(':designSystem must not depend on :shared')
            continue
        # Remove only explicit test dependency scopes. Scan all remaining build
        # syntax, including top-level dependencies and fully qualified artifacts.
        build_text = build.read_text(encoding='utf-8')
        for match in reversed(list(re.finditer(r'\w*[Tt]est\.dependencies\s*\{', build_text))):
            depth, end = 1, match.end()
            while depth and end < len(build_text):
                depth += (build_text[end] == '{') - (build_text[end] == '}')
                end += 1
            build_text = build_text[:match.start()] + build_text[end:]
        if re.search(r'compose\.material|markdownRenderer|multiplatform-markdown-renderer|compose\.material3:', build_text):
            errors.append(f'{build.relative_to(root)}: production renderer dependency')
        for source in (build.parent / 'src').rglob('*.kt'):
            if any('test' in part.lower() for part in source.relative_to(build.parent / 'src').parts[:-1]):
                continue
            # Strip comments, but keep qualified calls, aliases and wildcard imports.
            text = re.sub(r'/\*.*?\*/|//[^\n]*', '', source.read_text(encoding='utf-8'), flags=re.S)
            for line, value in enumerate(text.splitlines(), 1):
                if FORBIDDEN.search(value):
                    errors.append(f'{source.relative_to(root)}:{line}: use Paper API')
    return errors


if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as folder:
        root = Path(folder)
        (root / 'feature/src/commonMain').mkdir(parents=True)
        (root / 'feature/build.gradle.kts').write_text('commonMain.dependencies { implementation(libs.compose.material3) }')
        file = root / 'feature/src/commonMain/Bad.kt'
        for bad in ['import androidx.compose.material3.Text as Hidden', 'androidx.compose.material.Text("x")', 'import androidx.compose.material3.*', 'import com.mikepenz.markdown.m3.Markdown', 'import androidx.compose.foundation.clickable', 'import androidx.compose.foundation.text.BasicTextField']:
            file.write_text(bad)
            assert len(violations(root)) == 2, bad
        (root / 'feature/build.gradle.kts').write_text('commonMain.dependencies { implementation(project(":designSystem")) }')
        file.write_text('import io.aequicor.magicpaper.designsystem.PaperText')
        assert violations(root) == []
errors = violations(ROOT)
if errors:
    raise SystemExit('FAIL\n' + '\n'.join(errors))
print('PASS: production modules use Paper API; renderer dependencies and raw clickable imports are contained')
