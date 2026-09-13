#!/usr/bin/env python3
"""Enforce the renderer and interactive-primitive boundary in production sources."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN = re.compile(r'androidx\s*\.\s*compose\s*\.\s*material(?:3)?\b|com\s*\.\s*mikepenz\s*\.\s*markdown\b|androidx\.compose\.ui\.window\.(?:Dialog|Popup)\b|androidx\.compose\.foundation\.(?:clickable|combinedClickable|text\.BasicTextField|selection\.(?:selectable|toggleable))\b')
APPLICATION_DEPENDENCY = re.compile(
    r'project\s*\(\s*(?:path\s*=\s*)?["\']:(?:app|desktopApp|androidApp|webApp|feature)(?::[^"\']*)?["\']'
    r'|\bprojects\.(?:app|desktopApp|androidApp|webApp|feature)\b'
)


def violations(root):
    errors = []
    for build in root.rglob('build.gradle.kts'):
        if build.relative_to(root).parts[:2] == ('tools', 'mission-visualization'):
            continue  # Independent Git submodule, not a Paper consumer.
        if any(part in {'build', '.gradle', '.git', 'node_modules', '.magicpaper'} for part in build.relative_to(root).parts):
            continue
        if build.parent.name == 'designSystem':
            if APPLICATION_DEPENDENCY.search(build.read_text(encoding='utf-8')):
                errors.append(':designSystem must not depend on :app, platform hosts or feature modules')
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
        for bad in ['import androidx.compose.material3.Text as Hidden', 'androidx.compose.material.Text("x")', 'import androidx.compose.material3.*', 'import com.mikepenz.markdown.m3.Markdown', 'import androidx.compose.foundation.clickable', 'import androidx.compose.foundation.text.BasicTextField', 'androidx.compose.ui.window.Dialog(onDismissRequest = {}) {}', 'import androidx.compose.ui.window.Popup as Hidden']:
            file.write_text(bad)
            assert len(violations(root)) == 2, bad
        (root / 'feature/build.gradle.kts').write_text('commonMain.dependencies { implementation(project(":designSystem")) }')
        file.write_text('import io.aequicor.magicpaper.designsystem.PaperText')
        assert violations(root) == []
        nested = root / 'feature/nested/impl'
        (nested / 'src/commonMain').mkdir(parents=True)
        (nested / 'build.gradle.kts').write_text('commonMain.dependencies { implementation(libs.compose.material3) }')
        (nested / 'src/commonMain/Bad.kt').write_text('import androidx.compose.material3.Text')
        assert len(violations(root)) == 2, 'nested feature modules must be checked'
        design_system = root / 'designSystem'
        design_system.mkdir()
        for dependency in ['project(":app")', 'project(path = ":feature:chat:api")', 'projects.feature.coding.impl']:
            (design_system / 'build.gradle.kts').write_text(f'implementation({dependency})')
            assert len(violations(root)) == 3, dependency
if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as folder:
        root = Path(folder)
        for name in ('mission-visualization', 'paper-plugin', 'mission-visualization-copy'):
            module = root / 'tools' / name
            (module / 'src/main').mkdir(parents=True)
            (module / 'build.gradle.kts').write_text('implementation(libs.compose.material3)')
            (module / 'src/main/Bad.kt').write_text('import androidx.compose.material3.Text')
        found = violations(root)
        assert len(found) == 4, found
        assert all('tools/mission-visualization/' not in error for error in found)
errors = violations(ROOT)
if errors:
    raise SystemExit('FAIL\n' + '\n'.join(errors))
print('PASS: production modules use Paper API; renderer dependencies and raw clickable imports are contained')
