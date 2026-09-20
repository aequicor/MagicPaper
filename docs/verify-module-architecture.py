#!/usr/bin/env python3
"""Verify the Gradle API/implementation dependency boundary, including nested modules.

The desktop-only rule here is advisory and fast: it names the intent and fails locally in
a readable way. The authoritative check that coding cannot reach Android or the browser is
`./gradlew compileMigrationTargets`, which actually builds those artifacts.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
IGNORED = {'build', '.gradle', '.git', 'node_modules', '.magicpaper', 'build-logic'}
HOSTS = {':desktopApp', ':androidApp', ':webApp'}
# Modules that declare only a jvm target. Reaching them from a source set that also
# compiles for Android, JS or Wasm breaks those artifacts, so the edge is curated here.
DESKTOP_ONLY = {':feature:coding:impl'}
JVM_SOURCE_SETS = {'jvmMain', 'jvmTest'}

def is_retired_module(name):
    return name == ':shared' or name.startswith(':shared:')

def is_application_module(name):
    # Same predicate as docs/desktop-ui/verify-design-system.py: the application
    # graph is :app, the platform hosts and the features. Foundational modules
    # such as :core:logging are not part of it.
    return name == ':app' or name in HOSTS or name == ':feature' or name.startswith(':feature:')

def production_build(text):
    # Tests may assemble concrete implementations for integration fixtures.
    for match in reversed(list(re.finditer(r'\w*[Tt]est\.dependencies\s*\{', text))):
        depth, end = 1, match.end()
        while depth and end < len(text):
            depth += (text[end] == '{') - (text[end] == '}')
            end += 1
        text = text[:match.start()] + text[end:]
    return text

def scoped_dependencies(text):
    """Attribute each project dependency to the Kotlin source set that declares it.

    A dependency outside any `<sourceSet>.dependencies { }` block reaches every target and
    is reported as 'unscoped'. Blocks do not nest, so a forward brace walk is enough.
    """
    scoped, covered = set(), []
    for match in re.finditer(r'(\w+)\.dependencies\s*\{', text):
        depth, end = 1, match.end()
        while depth and end < len(text):
            depth += (text[end] == '{') - (text[end] == '}')
            end += 1
        body = text[match.end():max(match.end(), end - 1)]
        covered.append((match.start(), end))
        for dependency in re.findall(r'project\(\s*["\'](:[^"\']+)["\']\s*\)', body):
            scoped.add((match.group(1), dependency))
    remainder, previous = '', 0
    for start, end in covered:
        remainder += text[previous:start]
        previous = max(previous, end)
    remainder += text[previous:]
    for dependency in re.findall(r'project\(\s*["\'](:[^"\']+)["\']\s*\)', remainder):
        scoped.add(('unscoped', dependency))
    return scoped

def local_settings(text):
    # Composite-build substitution refers to the external build's project paths. Its
    # :shared is unrelated to MagicPaper's retired module. Never exempt local aliases.
    pattern = r'includeBuild\(\s*["\']tools/mission-visualization["\']\s*\)\s*\{'
    for match in reversed(list(re.finditer(pattern, text))):
        depth, end = 1, match.end()
        while depth and end < len(text):
            depth += (text[end] == '{') - (text[end] == '}')
            end += 1
        text = text[:match.start()] + text[end:]
    return text

def violations(root):
    errors, graph = [], {}
    for name in ('settings.gradle.kts', 'settings.gradle'):
        settings = root / name
        if not settings.is_file():
            continue
        text = local_settings(settings.read_text(encoding="utf-8"))
        for declaration in re.findall(r'\binclude\s*\((.*?)\)', text, re.S):
            for included in re.findall(r'["\']([^"\']+)["\']', declaration):
                if is_retired_module(':' + included.lstrip(':')):
                    errors.append(f'{name}: :shared is retired; application composition belongs to :app')
        if re.search(r'\bproject\(\s*["\']:shared(?::[^"\']*)?["\']', text):
            errors.append(f'{name}: do not recreate :shared through a project alias; use :app')
    # Convention plugins configure targets only. Keeping project edges in module
    # builds makes this source-level graph complete and easy to audit.
    for convention in (root / 'build-logic/src').rglob('*.gradle.kts'):
        if re.search(r'project\(\s*["\']:', convention.read_text(encoding="utf-8")):
            errors.append(f'{convention.relative_to(root)}: declare project dependencies in the consuming module')
    for build in root.rglob('build.gradle.kts'):
        relative = build.relative_to(root)
        if relative.parts[:2] == ('tools', 'mission-visualization'):
            continue  # Separate build and ownership graph.
        if any(part in IGNORED for part in relative.parts):
            continue
        raw = build.read_text(encoding="utf-8")
        # A test dependency or root-level wiring must not resurrect the retired module either.
        if re.search(r'project\(\s*["\']:shared(?::[^"\']*)?["\']', raw):
            errors.append(f'{relative}: :shared is retired; depend on the owning feature API or :app')
        if build.parent == root:
            continue
        name = ':' + ':'.join(relative.parts[:-1])
        if is_retired_module(name):
            errors.append(f'{name}: retired module directory; application composition belongs to app/')
        deps = set(re.findall(r'project\(\s*["\'](:[^"\']+)["\']\s*\)', production_build(raw)))
        graph[name] = deps
        for dependency in deps:
            if name.endswith(':api') and (dependency.endswith(':impl') or dependency == ':app'):
                errors.append(f'{name}: API depends on implementation {dependency}')
            if name.startswith(':feature:') and name.endswith(':impl') and dependency.endswith(':impl'):
                errors.append(f'{name}: consume the API of {dependency}')
            if dependency == ':app' and name not in HOSTS:
                errors.append(f'{name}: only platform applications may depend on :app')
            if name in {':core:model', ':core:logging'} and dependency:
                errors.append(f'{name}: foundational module must not depend on project {dependency}')
            if name == ':designSystem' and is_application_module(dependency):
                errors.append(f'{name}: Paper must remain independent of application modules')
        for source_set, dependency in scoped_dependencies(raw):
            if dependency in DESKTOP_ONLY and source_set not in JVM_SOURCE_SETS:
                errors.append(f'{name}: only jvmMain may depend on {dependency}; '
                              f'{source_set} compiles for Android and the browser')
    # Kotlin/JVM top-level facade names can hide API extension functions when an
    # implementation file keeps the same package and basename after extraction.
    facades = {}
    for source in root.rglob('*.kt'):
        relative = source.relative_to(root)
        if relative.parts[:2] == ('tools', 'mission-visualization'):
            continue
        if any(part in IGNORED for part in relative.parts) or 'src' not in relative.parts:
            continue
        index = relative.parts.index('src')
        if index + 1 >= len(relative.parts) or not relative.parts[index + 1].endswith('Main'):
            continue
        module = relative.parts[:index]
        content = source.read_text(encoding="utf-8")
        package = re.search(r'^package ([\w.]+)', content, re.M)
        if not package or not re.search(r'^(?:(?:public|internal|private|suspend|inline|expect|actual) )*(?:fun|val|var)\b', content, re.M):
            continue
        key = (package.group(1), source.stem)
        previous = facades.get(key)
        if previous and previous[0] != module:
            errors.append(f'Duplicate JVM facade {key[0]}.{key[1]}Kt: {previous[1]} and {relative}')
        facades[key] = (module, relative)
    active, complete = set(), set()
    def visit(name, trail):
        if name in active:
            errors.append('Module cycle: ' + ' -> '.join(trail + [name]))
            return
        if name in complete:
            return
        active.add(name)
        for dep in graph.get(name, ()):
            visit(dep, trail + [name])
        active.remove(name)
        complete.add(name)
    for name in graph:
        visit(name, [])
    return errors

if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as folder:
        root = Path(folder)
        for name, dependency in [('feature/session/api', ':feature:session:impl'), ('feature/session/impl', ':feature:session:api')]:
            target = root / name / 'build.gradle.kts'
            target.parent.mkdir(parents=True)
            target.write_text(f'commonMain.dependencies {{ implementation(project("{dependency}")) }}')
        assert len(violations(root)) == 2
        (root / 'feature/session/api/build.gradle.kts').write_text('')
        assert not violations(root)
        (root / 'feature/session/impl/build.gradle.kts').write_text('commonTest.dependencies { implementation(project(":feature:skills:impl")) }')
        assert not violations(root)
        for module in ('feature/session/api', 'feature/session/impl'):
            source = root / module / 'src/commonMain/kotlin/Shared.kt'
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('package fixture\npublic fun operation() = Unit\n')
        assert any('Duplicate JVM facade' in error for error in violations(root))
        convention = root / 'build-logic/src/main/kotlin/example.gradle.kts'
        convention.parent.mkdir(parents=True, exist_ok=True)
        convention.write_text('dependencies { implementation(project(":hidden")) }')
        assert any('declare project dependencies' in error for error in violations(root))
    with TemporaryDirectory() as folder:
        root = Path(folder)
        for name, dependency in [('app', ':feature:session:impl'), ('desktopApp', ':app')]:
            target = root / name / 'build.gradle.kts'
            target.parent.mkdir(parents=True)
            target.write_text(f'commonMain.dependencies {{ implementation(project("{dependency}")) }}')
        assert not violations(root)
        api = root / 'feature/session/api/build.gradle.kts'
        api.parent.mkdir(parents=True)
        api.write_text('commonMain.dependencies { implementation(project(":app")) }')
        assert any('API depends on implementation :app' in error for error in violations(root))
        api.write_text('commonTest.dependencies { implementation(project(":shared")) }')
        assert any(':shared is retired' in error for error in violations(root))
        api.write_text('')
        design = root / 'designSystem/build.gradle.kts'
        design.parent.mkdir()
        design.write_text('jvmMain.dependencies { implementation(project(":core:logging")) }')
        assert not any('Paper must remain independent' in error for error in violations(root)), 'foundational logging is not an application module'
        for application in (':app', ':desktopApp', ':feature:session:api', ':feature:session:impl'):
            design.write_text(f'commonMain.dependencies {{ implementation(project("{application}")) }}')
            assert any('Paper must remain independent' in error for error in violations(root)), application
        design.write_text('')
        settings = root / 'settings.gradle.kts'
        for declaration in ('include(":shared")', 'include("shared")', 'include(":shared:api")',
                            'project(":shared").projectDir = file("app")'):
            settings.write_text(declaration)
            assert any(':shared' in error for error in violations(root)), declaration
        settings.write_text('include(":app")')
        assert not violations(root)
        retired = root / 'shared/build.gradle.kts'
        retired.parent.mkdir()
        retired.write_text('')
        assert any('retired module directory' in error for error in violations(root))
    with TemporaryDirectory() as folder:
        root = Path(folder)
        (root / 'feature/coding/impl').mkdir(parents=True)
        (root / 'feature/coding/impl/build.gradle.kts').write_text('')
        app = root / 'app/build.gradle.kts'
        app.parent.mkdir(parents=True)
        desktop_only = next(iter(DESKTOP_ONLY))
        for allowed in ('jvmMain', 'jvmTest'):
            app.write_text(f'kotlin {{ sourceSets {{ {allowed}.dependencies '
                           f'{{ implementation(project("{desktop_only}")) }} }} }}')
            assert not violations(root), allowed
        for blocked in ('commonMain', 'androidMain', 'webMain', 'jsMain', 'wasmJsMain', 'commonTest'):
            app.write_text(f'kotlin {{ sourceSets {{ {blocked}.dependencies '
                           f'{{ implementation(project("{desktop_only}")) }} }} }}')
            assert any('only jvmMain may depend on' in error for error in violations(root)), blocked
        app.write_text(f'dependencies {{ implementation(project("{desktop_only}")) }}')
        assert any('only jvmMain may depend on' in error for error in violations(root)), 'unscoped block'
        # A regex walk must not attribute one block's dependency to its neighbours.
        app.write_text('kotlin { sourceSets {\n'
                       '  commonMain.dependencies { implementation(project(":core:model")) }\n'
                       f'  jvmMain.dependencies {{ implementation(project("{desktop_only}")) }}\n'
                       '  androidMain.dependencies { implementation(project(":core:platform")) }\n'
                       '} }')
        assert not violations(root), 'neighbouring source sets must stay separate'
if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as folder:
        root = Path(folder)
        for name in ('mission-visualization', 'paper-plugin', 'mission-visualization-copy'):
            module = root / 'tools' / name
            module.mkdir(parents=True)
            (module / 'build.gradle.kts').write_text('implementation(project(":shared"))')
        (root / 'settings.gradle.kts').write_text('includeBuild("tools/mission-visualization") { dependencySubstitution { substitute(module("x")).using(project(":shared")) } }')
        found = violations(root)
        assert len(found) == 2, found
        (root / 'settings.gradle.kts').write_text('project(":shared").projectDir = file("app")')
        assert len(violations(root)) == 3, 'Local aliases must still fail'
        assert all('tools/mission-visualization/' not in error for error in found)
errors = violations(ROOT)
if errors:
    raise SystemExit('FAIL\n' + '\n'.join(errors))
print('PASS: acyclic API/impl graph; platform hosts compose through :app; :shared is retired')
