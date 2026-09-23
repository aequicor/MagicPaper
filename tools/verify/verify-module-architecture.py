#!/usr/bin/env python3
"""Verify the Gradle API/implementation dependency boundary, including nested modules.

The desktop-only rule here is advisory and fast: it names the intent and fails locally in
a readable way. The authoritative check that coding cannot reach Android or the browser is
`./gradlew compileMigrationTargets`, which actually builds those artifacts.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
IGNORED = {'build', '.gradle', '.git', 'node_modules', '.magicpaper', 'build-logic'}
WORKTREES = ('.claude', 'worktrees')

def nested_checkout(root, relative):
    """True when ``relative`` lies inside another working copy of the project.

    Sessions create git worktrees under ``.claude/worktrees/<name>/``. Git ignores them through
    ``.git/info/exclude``, but a walk of the filesystem does not, so every module would be seen
    twice and the copy's stale state reported as this tree's. A directory carrying its own ``.git``
    marks a nested checkout for the same reason. ``root`` itself is never tested: a real checkout
    has a ``.git`` of its own. The same rule lives in tools/verify/verify-design-system.py.
    """
    parts = relative.parts
    if any(parts[i:i + 2] == WORKTREES for i in range(len(parts) - 1)):
        return True
    return any((root.joinpath(*parts[:depth]) / '.git').exists() for depth in range(1, len(parts)))
HOSTS = {':desktopApp', ':androidApp', ':webApp'}
# Modules outside the magic-agent and backend-agents groups that declare only a jvm target.
# Reaching one from a source set that also compiles for Android, JS or Wasm breaks those
# artifacts. Both groups are desktop-only by membership (is_desktop_only), and no library
# outside them is jvm-only today, so the set is empty; a new one is added here on purpose.
DESKTOP_ONLY = set()
JVM_SOURCE_SETS = {'jvmMain', 'jvmTest'}
ARCHITECTURE_GROUPS = {'magic-common', 'magic-chat', 'magic-agent', 'backend-agents'}
BACKEND_PUBLIC = {':backend-agents:api', ':backend-agents:factory'}
# What a backend engine may take from outside its own group. `:core:model` carries the values it
# speaks in; `:core:state-machine:api` is a leaf with no project dependency of its own, and it is the
# contract a lifecycle machine implements. Nothing else in :core qualifies — storage, logging and the
# implementations stay out — so this list is a deliberate short one, not a category.
BACKEND_FOUNDATION = {':core:model', ':core:state-machine:api'}
# The two modules everything else stands on carry no project dependency. The one exception is the machine
# contract, a leaf: because it has no project dependency of its own, either of them can take it without
# a cycle, and a machine that lives in :core:model can implement it. The leaf is held to the same rule it
# is exempt in, so it cannot grow a dependency of its own and quietly close a loop.
FOUNDATIONAL = {':core:model', ':core:logging'}
FOUNDATION_LEAVES = {':core:state-machine:api'}
# Private lifecycle infrastructure is not an installable engine contribution.
BACKEND_INFRASTRUCTURE = {':backend-agents:lifecycle:impl'}
BACKEND_CONSUMER = ':magic-agent:runtime:impl'
BACKEND_SPI = 'io.aequicor.magicpaper.backend.BackendAgentContribution'
NATIVE_CONTRACTS = {
    'RuntimeStatus', 'RuntimePhase', 'CodingRuntime', 'CodingService', 'CodingComponent',
    'CodingFeature', 'CodingFeatureDependencies', 'CodingUi', 'CodingState',
    'ComputerUse', 'ComputerPermissions', 'PlanningWorkspace', 'TaskWorkspace',
}


def architecture_group(name):
    group = name.lstrip(':').split(':', 1)[0]
    return group if group in ARCHITECTURE_GROUPS else None


def is_desktop_only(name):
    return name in DESKTOP_ONLY or architecture_group(name) in {'magic-agent', 'backend-agents'}


def group_edge_violations(name, dependency):
    owner, target = architecture_group(name), architecture_group(dependency)
    errors = []
    if owner == 'magic-common' and target != 'magic-common' and not dependency.startswith(':core:') and dependency != ':designSystem':
        errors.append(f'{name}: magic-common must not depend on {dependency}')
    if owner == 'magic-chat' and target in {'magic-agent', 'backend-agents'}:
        errors.append(f'{name}: chat must not depend on native agent {dependency}')
    if owner == 'backend-agents' and target != 'backend-agents' and dependency not in BACKEND_FOUNDATION:
        errors.append(f'{name}: backend agents may depend only on their group, :core:model and '
                      f':core:state-machine:api, not {dependency}')
    if target == 'backend-agents' and owner != 'backend-agents':
        if dependency not in BACKEND_PUBLIC:
            errors.append(f'{name}: backend engine implementation {dependency} is private to its factory')
        elif name != BACKEND_CONSUMER:
            errors.append(f'{name}: only {BACKEND_CONSUMER} may consume {dependency}')
    if owner == target == 'backend-agents' and dependency not in BACKEND_PUBLIC and name != ':backend-agents:factory':
        errors.append(f'{name}: only :backend-agents:factory may consume engine {dependency}')
    return errors
# The planning package decides; it never observes. Ids, time and jitter arrive as values
# (StageRetryInputs) and work is described by an effect, so a generated id, a clock, a
# randomness source or a coroutine builder in these files is a transition that cannot be
# replayed from a journal.
PLANNING_PACKAGE = 'core/model/src/commonMain/kotlin/io/aequicor/magicpaper/domain/planning'
# The requirement is keyed on the Kotlin package, not on the module directory: decision 1 of
# the target architecture moves :core:model, and an rglob over a path that stopped resolving
# yields nothing and raises nothing, switching the rule off at exactly the migration it
# guards. A checkout that holds the domain package must hold planning under PLANNING_PACKAGE;
# a synthetic --self-test root or any other temporary tree has no domain package at all.
PLANNING_OWNER = 'io/aequicor/magicpaper/domain'
IMPURE_PLANNING = re.compile(
    r'\bClock\.|\bcurrentTimeMillis\b|\bgetTimeMillis\b|\bnanoTime\b|\bTimeSource\b|\bmarkNow\b'
    r'|\belapsedNow\b|\b(?:Instant|LocalDateTime|LocalDate|LocalTime)\.now\b'
    r'|\bRandom\b|\bMath\.random\b|\bnext(?:Int|Long|Double|Float|Boolean|Bytes)\b|\bshuffled?\b'
    r'|\brandom(?:UUID|Uuid)\b|\bUuid\.random\w*|\buuid4\b'
    r'|\bDispatchers\b|\bGlobalScope\b|\bCoroutineScope\s*\('
    r'|\b(?:launch|async|withContext|runBlocking|coroutineScope|supervisorScope|delay'
    r'|flow|channelFlow|callbackFlow|produce)\s*[({]')

# A machine declares its state space. `reduce` alone is an opaque function: nothing proves that a table
# written about it is complete, so an owner adopts Machine<State, Input, Effect> and a StateSpace, and a
# test runs verifyStateSpace over it. The two folds below are the exceptions the
# design names: they turn a Plan document over its journal, hold no machine state and emit no effects,
# so they are projections of the journal and not machines. A new fold has to be added here on purpose.
PURE_FOLDS = {'CoordinationRules', 'PlanOrchestrationRules'}
# `object|class Name ... : Machine<`, stopping at the first body brace or the next declaration so the name
# captured is the one that implements the contract.
MACHINE_DECLARATION = re.compile(r'\b(?:object|class)\s+(\w+)\b(?:(?!\b(?:object|class)\b)[^{])*?\bMachine\s*<')

def machine_violations(relative, code, module_tests):
    """Errors for one production file that defines a reducer but does not declare its state space."""
    machines = MACHINE_DECLARATION.findall(code)
    if not machines:
        return [f'{relative}: a machine must implement Machine<State, Input, Effect> and declare a StateSpace; '
                f'a pure fold of a document is named in PURE_FOLDS']
    errors = []
    for name in machines:
        if not any(re.search(r'\bverifyStateSpace\s*\(', test) and re.search(rf'\b{name}\b', test) for test in module_tests):
            errors.append(f'{relative}: {name} declares a state space but no test in its module runs '
                          f'verifyStateSpace over it')
    return errors

# A journaled input is found again by its polymorphic discriminator. Without @SerialName that is the full
# name of the class with its nesting, so moving or renaming the branch, or splitting its parent, orphans every
# record written under the old name. The rule is on the declaration, not on the body of Intent or Fact: a
# branch can be declared elsewhere in the package, and a supertype named OrchestrationIntent is one too.
BRANCH_SUPERTYPE = re.compile(r'(?:^|\.)\w*(?:Intent|Fact)$')
CLASS_DECLARATION = re.compile(
    r'((?:@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?\s+)*)'
    r'(?:(?:public|internal|private|protected|data|enum|value|sealed|abstract|open|inner|annotation|fun|companion)\s+)*'
    r'(?:class|object)\s+(\w+)')
CONSTRUCTOR_MODIFIER = re.compile(r'\s*(?:(?:public|internal|private|protected)\s+)?constructor\b')

def balanced(code, position, opening, closing):
    depth = 0
    while True:
        depth += (code[position] == opening) - (code[position] == closing)
        position += 1
        if depth == 0:
            return position

def declared_supertypes(code, position):
    """Supertypes of the declaration whose name ends at position, past type parameters and the constructor."""
    while code[position:position + 1].isspace():
        position += 1
    if code[position:position + 1] == '<':
        position = balanced(code, position, '<', '>')
    constructor = CONSTRUCTOR_MODIFIER.match(code, position)
    if constructor:
        position = constructor.end()
    probe = position
    while code[probe:probe + 1].isspace():
        probe += 1
    if code[probe:probe + 1] == '(':
        position = balanced(code, probe, '(', ')')
    probe = position
    while code[probe:probe + 1].isspace():
        probe += 1
    if code[probe:probe + 1] != ':':
        return []
    end = probe + 1
    while True:
        while end < len(code) and code[end] not in '{=\n':
            end += 1
        if code[probe + 1:end].rstrip().endswith(',') and end < len(code) and code[end] == '\n':
            end += 1
            continue
        break
    return [re.sub(r'<.*', '', part.strip()).replace('()', '').strip()
            for part in code[probe + 1:end].split(',') if part.strip()]

def unpinned_branches(code):
    """(name, supertype) of every @Serializable class or object that extends an Intent or a Fact without @SerialName."""
    found = []
    for declaration in CLASS_DECLARATION.finditer(code):
        annotations, name = declaration.group(1), declaration.group(2)
        if '@Serializable' not in annotations or '@SerialName' in annotations:
            continue
        supertypes = [t for t in declared_supertypes(code, declaration.end()) if BRANCH_SUPERTYPE.search(t)]
        if supertypes:
            found.append((name, supertypes[0]))
    return found

def is_retired_module(name):
    return name == ':shared' or name.startswith(':shared:')

def is_application_module(name):
    # Same predicate as tools/verify/verify-design-system.py: the application
    # graph is :app, the platform hosts and the features. Foundational modules
    # such as :core:logging are not part of it.
    return name == ':app' or name in HOSTS or name == ':feature' or name.startswith(':feature:') or architecture_group(name) is not None

def production_build(text):
    # Tests may assemble concrete implementations for integration fixtures.
    for match in reversed(list(re.finditer(r'\w*[Tt]est\.dependencies\s*\{', text))):
        depth, end = 1, match.end()
        while depth and end < len(text):
            depth += (text[end] == '{') - (text[end] == '}')
            end += 1
        text = text[:match.start()] + text[end:]
    return text

def kotlin_code(text):
    """Blank comments and string literals, keeping line structure, before a token scan.

    Prose about a rule names the thing the rule forbids: StageMachine.kt already says in a
    comment that it never reads a clock. Ordered alternation scanning left to right keeps a
    quote inside a comment from opening a literal, and the reverse.
    """
    pattern = r'"""(?:[^"]|"(?!""))*"""|"(?:\\.|[^"\\\n])*"|/\*.*?\*/|//[^\n]*'
    return re.sub(pattern, lambda match: re.sub(r'[^\n]', ' ', match.group(0)), text, flags=re.S)

# Inputs are journaled and replayed, so a lambda in an Intent or a Fact is exactly the effect
# problem one step earlier: a recorded input that carries behaviour cannot be replayed at all.
EFFECT_SUFFIX = re.compile(r'(?:Effect|Event|Signal|Intent|Fact)$')
SEALED_DECLARATION = re.compile(r'^[ \t]*(?:(?:public|internal|private|expect|actual|abstract|open)\s+)*'
                                r'sealed\s+(?:interface|class)\s+(\w+)', re.M)
DECLARATION = re.compile(r'^[ \t]*(?:(?:public|internal|private|expect|actual|abstract|open|sealed|data'
                         r'|value|inner|enum|annotation)\s+)*(?:class|interface|object)\s+(\w+)', re.M)
MEMBER = re.compile(r'\b(?:val|var)\s+(\w+)\s*:')

def generic_open(text, position):
    # `<` opens a type argument list only after a name; `a < b` is a comparison.
    previous = text[position - 1:position] if position else ''
    return (previous.isalnum() or previous in '_>') and text[position + 1:position + 2] not in {' ', '=', '\n', ''}

def bracket_walk(text, start=0):
    """Yield (position, token, depth) with the depth the token itself sits at.

    `->` is one token, so the `>` of an arrow never closes a type argument list, and the
    depth reported for a bracket is the one outside it: a `)` at depth 0 ends the list the
    scan started inside.
    """
    depth, position = 0, start
    while position < len(text):
        char = text[position]
        if char == '-' and text[position + 1:position + 2] == '>':
            yield position, '->', depth
            position += 2
            continue
        yield position, char, depth
        if char in '([' or (char == '<' and generic_open(text, position)):
            depth += 1
        elif char in ')]>' and depth:
            depth -= 1
        position += 1

def top_level_colon(text):
    for position, token, depth in bracket_walk(text):
        if token == ':' and not depth:
            return position
    return -1

def declaration_header(text, index):
    """Span a declaration from the end of its name to its body brace or the end of its header.

    Constructor parameters and supertype lists wrap, so the walk follows bracket depth and
    ends at a newline only when no `:` or `,` on either side continues the list.
    """
    last = ''
    for position, token, depth in bracket_walk(text, index):
        if not depth:
            if token == '{':
                return text[index:position], position
            if token == '\n' and last not in (':', ',') \
                    and not text[position + 1:position + 64].lstrip().startswith((':', ',')):
                return text[index:position], None
        if not token.isspace():
            last = token[-1]
    return text[index:], None

def function_typed_members(text):
    """Yield properties declared with a function type, wherever `val`/`var` stands.

    The type ends at the first comma, close paren, equals or newline outside its own
    brackets: a single-line constructor property reads through to its arrow, while a `when`
    arm in a getter body stays behind the `=` and can never be mistaken for a type.
    """
    for match in MEMBER.finditer(text):
        arrow = False
        type_text = ''
        for _, token, depth in bracket_walk(text, match.end()):
            if (token == '\n' and not depth and type_text.strip() not in ('', 'suspend')) or (not depth and token in ',)='):
                break
            type_text += token
            arrow = arrow or token == '->'
        arrow = arrow or bool(re.search(r'\b(?:Suspend)?Function\d+\s*<', type_text))
        if arrow:
            yield match.group(1)

def effect_function_members(code, planning, known_names=()):
    """Report function-typed properties of sealed Effect/Event/Signal hierarchies.

    An effect is a description, so a function-typed member smuggles behaviour into the data a
    machine returns and makes the transition untestable without impl. Two shapes carry a
    payload: a member of the sealed body, nested subclass constructors included, and a
    subclass declared beside it whose supertype list names the hierarchy. Sealed roots in
    another file are resolved within the same module and Kotlin package.
    """
    names, spans, found = set(known_names), [], set()
    for declaration in SEALED_DECLARATION.finditer(code):
        name = declaration.group(1)
        if not planning and not EFFECT_SUFFIX.search(name):
            continue
        names.add(name)
        header, brace = declaration_header(code, declaration.end())
        colon = top_level_colon(header)
        for member in function_typed_members(header if colon < 0 else header[:colon]):
            found.add((name, member))
        if brace is None:
            continue
        depth, end = 1, brace + 1
        while depth and end < len(code):
            depth += (code[end] == '{') - (code[end] == '}')
            end += 1
        spans.append((declaration.start(), end))
        for member in function_typed_members(code[brace + 1:max(brace + 1, end - 1)]):
            found.add((name, member))
    for declaration in DECLARATION.finditer(code):
        if any(start <= declaration.start() < end for start, end in spans):
            continue
        header, brace = declaration_header(code, declaration.end())
        colon = top_level_colon(header)
        # The name must head a supertype entry: `Consumer<StageEffect>` is a use, not a branch.
        if colon < 0 or not any(re.search(rf'(?:^|,)\s*{name}\b', header[colon + 1:]) for name in names):
            continue
        branch = header[:colon]
        if brace is not None:
            depth, end = 1, brace + 1
            while depth and end < len(code):
                depth += (code[end] == '{') - (code[end] == '}')
                end += 1
            branch += code[brace + 1:max(brace + 1, end - 1)]
        for member in function_typed_members(branch):
            found.add((declaration.group(1), member))
    return sorted(found)

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

def owns_planning(root):
    # True for a MagicPaper checkout: something in it declares the domain package that
    # planning belongs to, wherever the module holding it has been moved to.
    return any(candidate.is_dir() and not any(part in IGNORED for part in candidate.relative_to(root).parts)
               and not nested_checkout(root, candidate.relative_to(root))
               for candidate in root.rglob(PLANNING_OWNER))

def project_directories(root):
    """Project identity comes from Gradle, including explicit source-preserving relocations."""
    settings = root / 'settings.gradle.kts'
    if not settings.is_file():
        return {}
    return {Path(directory).as_posix(): name for name, directory in re.findall(
        r'project\(\s*["\'](:[^"\']+)["\']\s*\)\.projectDir\s*=\s*file\(\s*["\']([^"\']+)["\']\s*\)',
        local_settings(settings.read_text(encoding="utf-8")))}


def backend_agent_projects(root, directories):
    """The factory includes every concrete backend project declared in settings."""
    settings = root / 'settings.gradle.kts'
    if not settings.is_file():
        return {}
    included = set()
    for block in re.findall(r'\binclude\s*\((.*?)\)', local_settings(settings.read_text(encoding='utf-8')), re.S):
        included.update(':' + name.lstrip(':') for name in re.findall(r'["\']([^"\']+)["\']', block))
    relocated = {name: directory for directory, name in directories.items()}
    return {name: root / relocated.get(name, name.lstrip(':').replace(':', '/'))
            for name in included if architecture_group(name) == 'backend-agents' and name not in BACKEND_PUBLIC | BACKEND_INFRASTRUCTURE
            and (root / relocated.get(name, name.lstrip(':').replace(':', '/')) / 'build.gradle.kts').is_file()}


def backend_catalog_violations(engines):
    errors, owners = [], {}
    for name, directory in sorted(engines.items()):
        registration = directory / 'src/jvmMain/resources/META-INF/services' / BACKEND_SPI
        if not registration.is_file():
            errors.append(f'{name}: backend engine must register its {BACKEND_SPI} service')
            continue
        classes = [line.split('#', 1)[0].strip() for line in registration.read_text(encoding='utf-8').splitlines()]
        classes = [value for value in classes if value]
        if not classes or any(not re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+', value) for value in classes):
            errors.append(f'{name}: invalid backend contribution service registration')
        for value in classes:
            if value in owners:
                errors.append(f'{name}: duplicate backend contribution {value} already registered by {owners[value]}')
            owners[value] = name
    return errors


def violations(root):
    errors, graph = [], {}
    directories = project_directories(root)
    backend_engines = backend_agent_projects(root, directories)
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
        if any(part in IGNORED for part in relative.parts) or nested_checkout(root, relative):
            continue
        raw = build.read_text(encoding="utf-8")
        # A test dependency or root-level wiring must not resurrect the retired module either.
        if re.search(r'project\(\s*["\']:shared(?::[^"\']*)?["\']', raw):
            errors.append(f'{relative}: :shared is retired; depend on the owning feature API or :app')
        if build.parent == root:
            continue
        name = directories.get(build.parent.relative_to(root).as_posix(), ':' + ':'.join(relative.parts[:-1]))
        if name != ':app' and re.search(r'\blibs\.koin\b|["\']io\.insert-koin:', production_build(raw)):
            errors.append(f'{name}: Koin dependencies belong only to :app')
        if is_retired_module(name):
            errors.append(f'{name}: retired module directory; application composition belongs to app/')
        deps = set(re.findall(r'project\(\s*["\'](:[^"\']+)["\']\s*\)', production_build(raw)))
        dynamic_projects = [value.strip() for value in re.findall(r'\bproject\(\s*([^)]*)\)', production_build(raw))
                            if not re.fullmatch(r'["\']:[^"\']+["\']\s*', value)]
        if dynamic_projects:
            if name == ':backend-agents:factory' and dynamic_projects == ['it'] and 'backendAgentProjects.forEach' in raw:
                deps.update(backend_engines)
            else:
                errors.append(f'{name}: dynamic project dependencies are allowed only for the backend factory catalog')
        graph[name] = deps
        for dependency in deps:
            errors.extend(group_edge_violations(name, dependency))
            if name.endswith(':api') and (dependency.endswith(':impl') or dependency == ':app'):
                errors.append(f'{name}: API depends on implementation {dependency}')
            if (name.startswith(':feature:') or architecture_group(name)) and name.endswith(':impl') and dependency.endswith(':impl'):
                errors.append(f'{name}: consume the API of {dependency}')
            if dependency == ':app' and name not in HOSTS:
                errors.append(f'{name}: only platform applications may depend on :app')
            if name in FOUNDATIONAL and dependency not in FOUNDATION_LEAVES:
                errors.append(f'{name}: foundational module must not depend on project {dependency}; '
                              f'only the leaf {", ".join(sorted(FOUNDATION_LEAVES))} is allowed')
            if name in FOUNDATION_LEAVES and dependency:
                errors.append(f'{name}: a foundation leaf must not depend on project {dependency}')
            if name == ':designSystem' and is_application_module(dependency):
                errors.append(f'{name}: Paper must remain independent of application modules')
        if architecture_group(name) in {'magic-common', 'magic-chat'} and re.search(r'magicpaper\.jvm(?:-compose)?-library', raw):
            errors.append(f'{name}: shared group must preserve JVM, Android, JS and Wasm targets')
        if architecture_group(name) in {'magic-agent', 'backend-agents'}:
            target_code = kotlin_code(raw)
            if ('magicpaper.kmp-library' in raw or 'magicpaper.compose-library' in raw
                    or re.search(r'\b(?:android|androidTarget|js|wasmJs|iosArm64|iosSimulatorArm64)\s*[({]', target_code)):
                errors.append(f'{name}: native agent module must declare JVM targets only')
        for source_set, dependency in scoped_dependencies(raw):
            if is_desktop_only(dependency) and not is_desktop_only(name) and source_set not in JVM_SOURCE_SETS:
                errors.append(f'{name}: only jvmMain may depend on {dependency}; '
                              f'{source_set} compiles for Android and the browser')
    # Sealed effects may have branches in another file in the same Kotlin package.
    # Gather those roots before checking members; a file move must not disable the rule.
    production_sources, effect_roots = [], {}
    for source in root.rglob('*.kt'):
        relative = source.relative_to(root)
        if relative.parts[:2] == ('tools', 'mission-visualization') or any(part in IGNORED for part in relative.parts) or 'src' not in relative.parts:
            continue
        if nested_checkout(root, relative):
            continue
        index = relative.parts.index('src')
        if index + 1 >= len(relative.parts) or not relative.parts[index + 1].endswith('Main'):
            continue
        content = source.read_text(encoding="utf-8")
        code = kotlin_code(content)
        package = re.search(r'^package ([\w.]+)', code, re.M)
        production_sources.append((source, relative, index, content, code, package))
        if package:
            planning = relative.as_posix().startswith(PLANNING_PACKAGE + '/')
            key = (relative.parts[:index], package.group(1), planning)
            effect_roots.setdefault(key, set()).update(declaration.group(1) for declaration in SEALED_DECLARATION.finditer(code)
                                                     if planning or EFFECT_SUFFIX.search(declaration.group(1)))
    # Kotlin/JVM top-level facade names can hide API extension functions when an
    # implementation file keeps the same package and basename after extraction.
    facades = {}
    has_backend_catalog = False
    tests_by_module = {}
    for source, relative, index, content, code, package in production_sources:
        module = relative.parts[:index]
        module_name = directories.get('/'.join(module), ':' + ':'.join(module))
        code = kotlin_code(content)
        if architecture_group(module_name) == 'backend-agents' and re.search(r'\binterface\s+BackendAgentContribution\b', code):
            has_backend_catalog = True
        if architecture_group(module_name) != 'backend-agents' and module_name != ':core:model':
            if re.search(r'\bCodingEngine\s*\.\s*(?:entries\b|values\s*\()', code):
                errors.append(f'{relative}: enumerate the installed backend descriptors, not CodingEngine identities')
            if re.search(r'(?:==|!=)\s*CodingEngine\.\w+|\bCodingEngine\.\w+\s*(?:==|!=|->)', code):
                errors.append(f'{relative}: branch on backend capabilities, not CodingEngine identities')
        if module_name == ':backend-agents:api':
            for declaration in DECLARATION.finditer(code):
                if re.match(r'(?:Pi|Codex)[A-Z]', declaration.group(1)):
                    errors.append(f'{relative}: {declaration.group(1)} belongs to one engine; declare it in that engine\'s '
                                  f'module, and keep :backend-agents:api engine-neutral')
        if re.search(r'\b(?:class|object|typealias)\s+ToolHost\b', code):
            errors.append(f'{relative}: ToolHost is retired; inject the owning tool ports through constructors')
        if re.search(r'\b(?:class|object|typealias)\s+(?:UnavailableCoding\w*|UnsupportedCodingComponentFactory|NoopCodingRuntime)\b', code):
            errors.append(f'{relative}: native execution has no production fallback; an absent platform has no registration')
        if architecture_group(module_name) not in {'magic-agent', 'backend-agents'}:
            for declaration in DECLARATION.finditer(code):
                if declaration.group(1) in NATIVE_CONTRACTS:
                    errors.append(f'{relative}: {declaration.group(1)} is an executable native contract; '
                                  f'declare it in the JVM agent API, not a shared module')
        if module_name != ':app' and re.search(r'^\s*import\s+org\.koin\b', code, re.M):
            errors.append(f'{relative}: Koin belongs only to :app')
        # The suffix is a naming convention, not the rule: every sealed hierarchy in the
        # planning package is an effect vocabulary by construction, whatever it is called.
        # The scan runs on blanked code so a brace inside a literal or a comment cannot move
        # the body walk; positions are still reported against the real file.
        planning = relative.as_posix().startswith(PLANNING_PACKAGE + '/')
        api_machine = module_name.endswith(':api') and re.search(r'\bfun\s+reduce\s*\(', code)
        if api_machine:
            for token in sorted(set(IMPURE_PLANNING.findall(code))):
                errors.append(f'{relative}: {token.strip()} performs work inside a machine API; '
                              f'pass ids, time and randomness as input values')
            for declaration in re.finditer(r'\b(?:data\s+)?class\s+(\w*State)\b\s*([^\n{]*)', code):
                header = declaration.group(2).lstrip()
                if not header.startswith('internal constructor'):
                    errors.append(f'{relative}: {declaration.group(1)} state requires an internal constructor')
        machine_module = module_name.endswith(':api') or module_name == ':core:model'
        if machine_module and re.search(r'\bfun\s+reduce\s*\(', code) and source.stem not in PURE_FOLDS:
            if module not in tests_by_module:
                sources = root.joinpath(*module) / 'src'
                tests_by_module[module] = [kotlin_code(test.read_text(encoding="utf-8")) for test in sources.rglob('*.kt')
                                           if test.relative_to(sources).parts[0].endswith('Test')]
            errors.extend(machine_violations(relative, code, tests_by_module[module]))
        for branch, supertype in unpinned_branches(code):
            errors.append(f'{relative}: {branch} is a serializable branch of {supertype} without @SerialName; its stored '
                          f'name would be the class path, and the branch could never move')
        for owner, member in effect_function_members(code, planning, effect_roots.get((module, package.group(1), planning), ()) if package else ()):
            errors.append(f'{relative}: {owner}.{member} declares a function type; '
                          f'an effect carries data, not behaviour')
        package = re.search(r'^package ([\w.]+)', content, re.M)
        if not package or not re.search(r'^(?:(?:public|internal|private|suspend|inline|expect|actual) )*(?:fun|val|var)\b', content, re.M):
            continue
        key = (package.group(1), source.stem)
        previous = facades.get(key)
        if previous and previous[0] != module:
            errors.append(f'Duplicate JVM facade {key[0]}.{key[1]}Kt: {previous[1]} and {relative}')
        facades[key] = (module, relative)
    if has_backend_catalog:
        errors.extend(backend_catalog_violations(backend_engines))
    # Scoped by package path rather than a *Machine.kt name: PlanProjection and PlanStrategy
    # are not machines yet owe the same purity, and a rename must not escape the rule.
    if not (root / PLANNING_PACKAGE).is_dir() and owns_planning(root):
        errors.append(f'{PLANNING_PACKAGE}: planning package not found, so the purity rule scans '
                      f'nothing; point PLANNING_PACKAGE at the module that now holds it')
    for source in sorted((root / PLANNING_PACKAGE).rglob('*.kt')):
        code = kotlin_code(source.read_text(encoding="utf-8"))
        for token in sorted(set(IMPURE_PLANNING.findall(code))):
            errors.append(f'{source.relative_to(root)}: {token.strip()} generates an id, reads a clock '
                          f'or randomness, or launches work; a planning transition takes ids, time and '
                          f'effects as values')
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
        (root / 'settings.gradle.kts').write_text('include(":backend-agents:alpha", ":backend-agents:beta", ":backend-agents:lifecycle:impl")\n')
        infrastructure = root / 'backend-agents/lifecycle/impl'
        infrastructure.mkdir(parents=True)
        (infrastructure / 'build.gradle.kts').write_text('plugins { id("magicpaper.jvm-library") }\n')
        for engine in ('alpha', 'beta'):
            directory = root / 'backend-agents' / engine
            directory.mkdir(parents=True)
            (directory / 'build.gradle.kts').write_text('plugins { id("magicpaper.jvm-library") }\n')
        engines = backend_agent_projects(root, {})
        assert set(engines) == {':backend-agents:alpha', ':backend-agents:beta'}
        assert group_edge_violations(':app', ':backend-agents:lifecycle:impl')
        assert group_edge_violations(':magic-agent:runtime:impl', ':backend-agents:lifecycle:impl')
        assert group_edge_violations(':backend-agents:alpha', ':backend-agents:lifecycle:impl')
        assert not group_edge_violations(':backend-agents:factory', ':backend-agents:lifecycle:impl')
        assert len(backend_catalog_violations(engines)) == 2
        for name, directory in engines.items():
            registration = directory / 'src/jvmMain/resources/META-INF/services' / BACKEND_SPI
            registration.parent.mkdir(parents=True)
            registration.write_text('fixture.' + name.rsplit(':', 1)[-1].title() + 'Contribution\n')
        assert not backend_catalog_violations(engines)
        registration = engines[':backend-agents:beta'] / 'src/jvmMain/resources/META-INF/services' / BACKEND_SPI
        registration.write_text('fixture.AlphaContribution\n')
        assert any('duplicate backend contribution' in e for e in backend_catalog_violations(engines))
        registration.write_text('not a class\n')
        assert any('invalid backend contribution' in e for e in backend_catalog_violations(engines))
        registration.write_text('fixture.BetaContribution\n')
        factory = root / 'backend-agents/factory/build.gradle.kts'
        factory.parent.mkdir(parents=True)
        factory.write_text('jvmMain.dependencies { backendAgentProjects.forEach { implementation(project(it)) } }')
        assert not violations(root), violations(root)
        # The derived factory edges participate in the same cycle check as literal ones.
        (engines[':backend-agents:alpha'] / 'build.gradle.kts').write_text(
            'commonMain.dependencies { implementation(project(":backend-agents:factory")) }')
        assert any('Module cycle:' in e for e in violations(root))
        (engines[':backend-agents:alpha'] / 'build.gradle.kts').write_text(
            'commonMain.dependencies { implementation(project(hiddenModule)) }')
        assert any('dynamic project dependencies' in e for e in violations(root))
    with TemporaryDirectory() as folder:
        root = Path(folder)
        source = root / 'magic-agent/runtime/impl/src/commonMain/kotlin/EngineUi.kt'
        source.parent.mkdir(parents=True)
        for statement in ('CodingEngine.entries', 'CodingEngine.values()',
                          'engine == CodingEngine.PI', 'CodingEngine.CODEX != engine',
                          'when (engine) { CodingEngine.PI -> run() }'):
            source.write_text('package fixture\nfun selection() = ' + statement + '\n')
            assert any('backend' in e and 'identities' in e for e in violations(root)), violations(root)
        source.write_text('package fixture\nval legacyDefault = CodingEngine.PI\n'
                          'fun selection() = descriptors.filter { it.capabilities.resume }\n')
        assert not violations(root), violations(root)
    with TemporaryDirectory() as folder:
        root = Path(folder)
        source = root / 'core/model/src/commonMain/kotlin/NativeLeak.kt'
        source.parent.mkdir(parents=True)
        for declaration in ('class RuntimeStatus', 'interface CodingRuntime', 'enum class RuntimePhase'):
            source.write_text('package fixture\n' + declaration + '\n')
            assert any('executable native contract' in error for error in violations(root))
        source.write_text('package fixture\nenum class CodingEngine { PI, CODEX }\n')
        assert not violations(root), violations(root)
        source.unlink()
        native = root / 'magic-agent/runtime/api/src/commonMain/kotlin/Native.kt'
        native.parent.mkdir(parents=True)
        native.write_text('package fixture\ninterface CodingRuntime\nclass RuntimeStatus\n')
        assert not violations(root), violations(root)
        native.write_text('package fixture\nobject UnavailableCodingFeature\n')
        assert any('no production fallback' in error for error in violations(root))
        native.write_text('package fixture\nobject NoopCodingRuntime\n')
        assert any('no production fallback' in error for error in violations(root))
        native.unlink()
        fixture = root / 'magic-agent/runtime/impl/src/commonTest/kotlin/NoopCodingRuntime.kt'
        fixture.parent.mkdir(parents=True)
        fixture.write_text('package fixture\nobject NoopCodingRuntime\n')
        assert not violations(root), violations(root)
    with TemporaryDirectory() as folder:
        root = Path(folder)
        leak = root / 'backend-agents/api/src/commonMain/kotlin/Leak.kt'
        leak.parent.mkdir(parents=True)
        for declaration in ('class PiLaunchRequest', 'interface CodexClient', 'object PiInstallation'):
            leak.write_text('package fixture\n' + declaration + '\n')
            assert any('belongs to one engine' in error for error in violations(root)), declaration
        leak.write_text('package fixture\nclass NativeAgentRequest\ninterface Pillow\n')
        assert not any('belongs to one engine' in error for error in violations(root)), violations(root)
        leak.unlink()
        engine = root / 'backend-agents/pi/src/jvmMain/kotlin/PiLaunchRequest.kt'
        engine.parent.mkdir(parents=True)
        engine.write_text('package fixture\nclass PiLaunchRequest\n')
        assert not any('belongs to one engine' in error for error in violations(root)), violations(root)
    with TemporaryDirectory() as folder:
        root = Path(folder)
        module = root / 'magic-chat/impl/build.gradle.kts'
        module.parent.mkdir(parents=True)
        module.write_text('plugins { id("magicpaper.jvm-compose-library") }')
        assert any('shared group must preserve' in error for error in violations(root))
        module.write_text('plugins { id("magicpaper.compose-library") }')
        assert not violations(root), violations(root)
        machine = root / 'magic-common/questionnaire/api/src/commonMain/kotlin/QuestionMachine.kt'
        machine.parent.mkdir(parents=True)
        machine.write_text('package fixture\nclass QuestionState\nfun reduce(state: QuestionState) = Clock.System.now()\n')
        errors = violations(root)
        assert any('internal constructor' in error for error in errors), errors
        assert any('performs work inside a machine API' in error for error in errors), errors
        # The fixture now also owes the state-space rule, so it declares a machine and a test.
        contract = ('package fixture\nclass QuestionState internal constructor(val now: Long)\n'
                    'object QuestionMachine : Machine<QuestionState, Int, Int> {\n    fun reduce(state: QuestionState) = state.now\n}\n')
        machine.write_text(contract)
        test = root / 'magic-common/questionnaire/api/src/commonTest/kotlin/QuestionSpaceTest.kt'
        test.parent.mkdir(parents=True)
        test.write_text('package fixture\nclass QuestionSpaceTest { fun t() = verifyStateSpace(QuestionMachine, states, inputs) }\n')
        assert not violations(root), violations(root)

    # A reducer is a machine only once it declares its state space and has a test that runs the harness over it.
    # Each of the two is checked apart, so neither hides the other.
    with TemporaryDirectory() as folder:
        root = Path(folder)
        machine = root / 'magic-agent/browser/api/src/commonMain/kotlin/BrowserMachine.kt'
        machine.parent.mkdir(parents=True)
        test = root / 'magic-agent/browser/api/src/commonTest/kotlin/BrowserSpaceTest.kt'
        test.parent.mkdir(parents=True)
        declared = ('package fixture\nclass BrowserState internal constructor(val open: Boolean)\n'
                    'object BrowserMachine : Machine<BrowserState, Int, Int> {\n    fun reduce(state: BrowserState) = state.open\n}\n')
        tested = 'package fixture\nclass BrowserSpaceTest { fun t() = verifyStateSpace(BrowserMachine, states, inputs) }\n'
        def found(fragment):
            return [error for error in violations(root) if fragment in error and 'BrowserMachine.kt' in error]

        # No contract at all: the bare reducer of the old idiom.
        machine.write_text('package fixture\nclass BrowserState internal constructor(val open: Boolean)\n'
                           'object BrowserMachine {\n    fun reduce(state: BrowserState) = state.open\n}\n')
        test.write_text(tested)
        assert len(found('must implement Machine<')) == 1, violations(root)
        # A generic mention that is not a supertype must not pass for the contract.
        machine.write_text('package fixture\nclass BrowserState internal constructor(val open: Boolean)\n'
                           'object BrowserMachine {\n    fun reduce(state: BrowserState) = state.open\n    val tag: Machine<Int, Int, Int>? = null\n}\n')
        assert len(found('must implement Machine<')) == 1, violations(root)
        machine.write_text(declared)
        assert not violations(root), violations(root)
        # Declared, but nothing runs the harness over it: the test names another machine, mentions the
        # harness only in a comment or a string, or is not a test source set at all.
        for body in ('class BrowserSpaceTest { fun t() = verifyStateSpace(OtherMachine, states, inputs) }',
                     'class BrowserSpaceTest { /* verifyStateSpace(BrowserMachine, states, inputs) */ }',
                     'class BrowserSpaceTest { val note = "verifyStateSpace(BrowserMachine, states, inputs)" }',
                     'class BrowserSpaceTest { fun t() = BrowserMachine.reduce(state) }'):
            test.write_text('package fixture\n' + body + '\n')
            assert len(found('runs verifyStateSpace')) == 1, (body, violations(root))
        test.unlink()
        assert len(found('runs verifyStateSpace')) == 1, violations(root)
        (root / 'magic-agent/browser/api/src/commonMain/kotlin/BrowserSpaceTest.kt').write_text(tested)
        assert len(found('runs verifyStateSpace')) == 1, 'a production file is not a test'
        (root / 'magic-agent/browser/api/src/commonMain/kotlin/BrowserSpaceTest.kt').unlink()
        # A harness call through a wrapper that names the machine is still a harness run over it.
        test.write_text('package fixture\nclass BrowserSpaceTest {\n    private object Guarded : Machine<Int, Int, Int> { val inner = BrowserMachine }\n'
                        '    fun t() = verifyStateSpace(Guarded, states, inputs)\n}\n')
        assert not found('runs verifyStateSpace'), violations(root)
        test.write_text(tested)
        assert not violations(root), violations(root)
        # The pure folds are exempt by name, and only by name.
        fold = machine.with_name('CoordinationRules.kt')
        fold.write_text('package fixture\nfun reduce(plan: Plan, event: PlanEvent): Plan = plan\n')
        assert not violations(root), violations(root)
        fold.unlink()
        fold = machine.with_name('ReplayRules.kt')
        fold.write_text('package fixture\nfun reduce(plan: Plan, event: PlanEvent): Plan = plan\n')
        assert any('ReplayRules.kt' in error and 'must implement Machine<' in error for error in violations(root)), violations(root)
        fold.unlink()
        # The rule reaches :core:model, whose machines are not in an :api module, and other machines of one file.
        core = root / 'core/model/src/commonMain/kotlin/CoreMachine.kt'
        core.parent.mkdir(parents=True)
        core.write_text('package fixture\nobject CoreMachine : Machine<Int, Int, Int> {\n    fun reduce(state: Int) = state\n}\n')
        assert any('CoreMachine.kt' in error and 'runs verifyStateSpace' in error for error in violations(root)), violations(root)
        core.unlink()
        # A second machine in the same file owes its own test.
        machine.write_text(declared + 'object ExtraMachine : Machine<BrowserState, Int, Int> {\n    fun reduce(state: BrowserState) = state.open\n}\n')
        errors = violations(root)
        assert any('ExtraMachine' in error and 'runs verifyStateSpace' in error for error in errors), errors
        assert not any('BrowserMachine ' in error for error in errors), errors

    # A serializable branch of an Intent or a Fact is journaled, so its stored name has to be pinned. Each way
    # of writing the declaration is checked apart: a rule that misses the odd shape lets the branch back in.
    with TemporaryDirectory() as folder:
        root = Path(folder)
        source = root / 'magic-agent/planning/api/src/commonMain/kotlin/Branches.kt'
        source.parent.mkdir(parents=True)
        def unpinned(text):
            source.write_text('package fixture\n' + text + '\n')
            return [error for error in violations(root) if 'without @SerialName' in error]
        missing = {
            'plain data class': ('@Serializable data class A(val x: Int) : Fact', 'A'),
            'data object': ('@Serializable data object B : Intent', 'B'),
            'constructor over several lines': ('@Serializable data class C(\n    val a: Int,\n    val b: String,\n) : Fact', 'C'),
            'another annotation and an internal constructor':
                ('@Serializable @ConsistentCopyVisibility data class D internal constructor(val a: Int) : Fact', 'D'),
            'colon on the next line': ('@Serializable data class E(val a: Int)\n    : Intent', 'E'),
            'several supertypes': ('@Serializable data class F(val a: Int) : Marker, Fact', 'F'),
            'qualified supertype': ('@Serializable data class G(val a: Int) : Machine.Input.Fact', 'G'),
            'prefixed supertype': ('@Serializable data class H(val a: Int) : OrchestrationIntent', 'H'),
            'supertype list over two lines': ('@Serializable data class I(val a: Int) : Marker,\n    Fact', 'I'),
            'annotation on its own line': ('@Serializable\ndata class J(val a: Int) : Fact', 'J'),
            'the pin named only in a default value': ('@Serializable data class K(val s: String = "@SerialName") : Fact', 'K'),
            'the pin named only in a comment': ('// @SerialName("x")\n@Serializable data class L(val a: Int) : Fact', 'L'),
        }
        for label, (declaration, name) in missing.items():
            errors = unpinned(declaration)
            assert len(errors) == 1 and f': {name} is a serializable branch' in errors[0].replace('Branches.kt: ', ': '), (label, errors)
        pinned = {
            'after Serializable': '@Serializable @SerialName("x.A") data class A(val x: Int) : Fact',
            'before Serializable': '@SerialName("x.A") @Serializable data class A(val x: Int) : Fact',
            'on its own line': '@Serializable\n@SerialName("x.A")\ndata class A(val x: Int) : Fact',
            'on an object': '@Serializable @SerialName("x.B") data object B : Intent',
        }
        for label, declaration in pinned.items():
            assert not unpinned(declaration), (label, unpinned(declaration))
        # Only a serializable branch of an Intent or a Fact is journaled: everything else is left alone.
        for label, declaration in {
            'not serializable': 'data class A(val x: Int) : Fact',
            'another supertype': '@Serializable data class A(val x: Int) : Effect',
            'a look-alike that is not a Fact': '@Serializable data class A(val x: Int) : Artifact',
            'a factory': '@Serializable data class A(val x: Int) : Factory',
            'no supertype, a Fact only as a field': '@Serializable data class A(val fact: Fact)',
            'a Fact only inside the body': '@Serializable data class A(val x: Int) { val f: Fact? = null }',
        }.items():
            assert not unpinned(declaration), (label, unpinned(declaration))
        # One unpinned branch beside a pinned one is one error, and it names the branch that lacks the pin.
        errors = unpinned('@Serializable sealed interface Fact : Input {\n'
                          '    @Serializable @SerialName("x.P") data class P(val a: Int) : Fact\n'
                          '    @Serializable data class Q(val a: Int) : Fact\n}')
        assert len(errors) == 1 and ': Q is a serializable branch' in errors[0].replace('Branches.kt: ', ': '), errors
        # The rule covers every production module and no test source set.
        source.unlink()
        other = root / 'feature/skills/impl/src/commonMain/kotlin/Skill.kt'
        other.parent.mkdir(parents=True)
        other.write_text('package fixture\n@Serializable data class A(val x: Int) : Fact\n')
        assert len([error for error in violations(root) if 'without @SerialName' in error]) == 1, violations(root)
        other.unlink()
        test = root / 'feature/skills/impl/src/commonTest/kotlin/SkillTest.kt'
        test.parent.mkdir(parents=True)
        test.write_text('package fixture\n@Serializable data class A(val x: Int) : Fact\n')
        assert not [error for error in violations(root) if 'without @SerialName' in error], violations(root)

if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as folder:
        root = Path(folder)
        (root / 'settings.gradle.kts').write_text(
            'include(":magic-agent:runtime:impl")\n'
            'project(":magic-agent:runtime:impl").projectDir = file("legacy/runtime")\n')
        runtime = root / 'legacy/runtime/build.gradle.kts'
        runtime.parent.mkdir(parents=True)
        runtime.write_text('commonMain.dependencies { implementation(project(":backend-agents:factory")) }')
        assert not violations(root), violations(root)
        runtime.write_text('commonMain.dependencies { implementation(project(":backend-agents:pi")) }')
        assert any('engine implementation' in error and ':magic-agent:runtime:impl' in error for error in violations(root))
    with TemporaryDirectory() as folder:
        root = Path(folder)
        source = root / 'magic-common/questionnaire/api/src/commonMain/kotlin/Effects.kt'
        source.parent.mkdir(parents=True)
        source.write_text('package fixture\nsealed interface QuestionEffect\n')
        branch = source.with_name('Answer.kt')
        branch.write_text('package fixture\ndata class Answer(val callback: () -> Unit) : QuestionEffect\n')
        found = [error for error in violations(root) if 'declares a function type' in error]
        assert len(found) == 1 and 'Answer.callback' in found[0], found
        for declaration in (
            'class Answer : QuestionEffect { val callback: () -> Unit = {} }',
            'data class Answer(val callback: \n    (String) -> Unit) : QuestionEffect',
            'data class Answer(val callback: (\n    String\n) -> Unit) : QuestionEffect',
            'data class Answer(val callback: Function1<String, Unit>) : QuestionEffect',
        ):
            branch.write_text('package fixture\n' + declaration + '\n')
            found = [error for error in violations(root) if 'declares a function type' in error]
            assert len(found) == 1 and 'Answer.callback' in found[0], found
        branch.write_text('package fixture\ndata class Answer(val text: String) : QuestionEffect\n')
        assert not violations(root), violations(root)
        branch.write_text('package another\ndata class Answer(val callback: () -> Unit) : QuestionEffect\n')
        assert not violations(root), 'unrelated packages do not share a sealed hierarchy'
        # An input is replayed from the journal, so it owes the same rule as an effect.
        source.write_text('package fixture\nsealed interface QuestionIntent\nsealed interface QuestionFact\n')
        branch.write_text('package fixture\ndata class Answer(val onDone: () -> Unit) : QuestionIntent\n')
        found = [error for error in violations(root) if 'declares a function type' in error]
        assert len(found) == 1 and 'Answer.onDone' in found[0], found
        branch.write_text('package fixture\ndata class Delivered(val ack: (Long) -> Unit) : QuestionFact\n')
        found = [error for error in violations(root) if 'declares a function type' in error]
        assert len(found) == 1 and 'Delivered.ack' in found[0], found
        branch.write_text('package fixture\ndata class Delivered(val at: Long) : QuestionFact\n')
        assert not violations(root), violations(root)

if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    # The foundation modules carry no project dependency, save the machine contract, which is a leaf.
    with TemporaryDirectory() as folder:
        root = Path(folder)
        for foundation in ('core/model', 'core/logging'):
            build = root / foundation / 'build.gradle.kts'
            build.parent.mkdir(parents=True)
            build.write_text('commonMain.dependencies { api(project(":core:state-machine:api")) }')
            assert not violations(root), (foundation, violations(root))
            for dependency in (':core:storage:api', ':core:ai:api', ':core:state-machine:impl', ':designSystem', ':app'):
                build.write_text(f'commonMain.dependencies {{ implementation(project("{dependency}")) }}')
                assert any('foundational module must not depend' in error for error in violations(root)), (foundation, dependency)
            # A test dependency is not part of the production graph and stays allowed, as it always was.
            build.write_text('commonTest.dependencies { implementation(project(":core:ai:api")) }')
            assert not violations(root), violations(root)
        # The leaf may take nothing, or the exemption would let a loop in through it.
        leaf = root / 'core/state-machine/api/build.gradle.kts'
        leaf.parent.mkdir(parents=True)
        leaf.write_text('')
        assert not violations(root), violations(root)
        for dependency in (':core:model', ':core:logging'):
            leaf.write_text(f'commonMain.dependencies {{ api(project("{dependency}")) }}')
            assert any('a foundation leaf must not depend' in error for error in violations(root)), dependency
        leaf.write_text('')
        (root / 'core/model/build.gradle.kts').write_text('commonMain.dependencies { api(project(":core:state-machine:api")) }')
        assert not violations(root), violations(root)
if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as folder:
        root = Path(folder)
        consumer = root / 'magic-common/tools/api/build.gradle.kts'
        consumer.parent.mkdir(parents=True)
        for dependency in (':magic-chat:api', ':magic-agent:runtime:api', ':backend-agents:api'):
            consumer.write_text(f'commonMain.dependencies {{ implementation(project("{dependency}")) }}')
            assert any('magic-common must not depend' in error for error in violations(root)), dependency
        consumer.write_text('commonMain.dependencies { api(project(":core:model")) }')
        assert not violations(root), violations(root)
        native = root / 'backend-agents/pi/build.gradle.kts'
        native.parent.mkdir(parents=True)
        for dependency in (':core:storage:api', ':core:logging', ':core:state-machine:impl', ':magic-common:tools:api',
                           ':magic-chat:api', ':magic-agent:runtime:api'):
            native.write_text(f'commonMain.dependencies {{ implementation(project("{dependency}")) }}')
            assert any('backend agents may depend only' in error for error in violations(root)), dependency
        native.write_text('commonMain.dependencies { api(project(":backend-agents:api")) }')
        assert not violations(root), violations(root)
        # The machine contract is the one foundation module besides :core:model a backend may take, and
        # only its api: the implementation renders diagrams and is not a leaf.
        for dependency in (':core:model', ':core:state-machine:api'):
            native.write_text(f'commonMain.dependencies {{ api(project("{dependency}")) }}')
            assert not violations(root), (dependency, violations(root))
        # The same holds for the group's own api module, where the lifecycle machine actually lives.
        lifecycle = root / 'backend-agents/api/build.gradle.kts'
        lifecycle.parent.mkdir(parents=True)
        lifecycle.write_text('commonMain.dependencies { api(project(":core:model")); api(project(":core:state-machine:api")) }')
        assert not violations(root), violations(root)
        lifecycle.write_text('commonMain.dependencies { api(project(":core:state-machine:api")); api(project(":core:storage:api")) }')
        assert any('backend agents may depend only' in error and ':core:storage:api' in error for error in violations(root)), violations(root)
        lifecycle.unlink()
        native.write_text('commonMain.dependencies { api(project(":backend-agents:api")) }')
        assert not violations(root), violations(root)
        consumer.write_text('')
        chat = root / 'magic-chat/impl/build.gradle.kts'
        chat.parent.mkdir(parents=True)
        chat.write_text('jvmMain.dependencies { implementation(project(":magic-agent:runtime:api")) }')
        assert any('chat must not depend on native agent' in error for error in violations(root))
        chat.write_text('')
        runtime = root / 'magic-agent/runtime/impl/build.gradle.kts'
        runtime.parent.mkdir(parents=True)
        runtime.write_text('commonMain.dependencies { implementation(project(":backend-agents:factory")) }')
        assert not violations(root), violations(root)
        runtime.write_text('commonMain.dependencies { implementation(project(":backend-agents:pi")) }')
        assert any('engine implementation' in error for error in violations(root))
        runtime.write_text('')
        app = root / 'app/build.gradle.kts'
        app.parent.mkdir()
        app.write_text('jvmMain.dependencies { implementation(project(":backend-agents:factory")) }')
        assert any('only :magic-agent:runtime:impl' in error for error in violations(root))
        for source_set in ('commonMain', 'commonTest', 'androidMain', 'jsMain', 'wasmJsMain'):
            app.write_text(f'{source_set}.dependencies {{ implementation(project(":magic-agent:runtime:api")) }}')
            assert any('only jvmMain may depend' in error for error in violations(root)), source_set
        app.write_text('jvmMain.dependencies { implementation(project(":magic-agent:runtime:api")) }')
        assert not violations(root), violations(root)
        for plugin in ('magicpaper.kmp-library', 'magicpaper.compose-library'):
            native.write_text(f'plugins {{ id("{plugin}") }}')
            assert any('JVM targets only' in error for error in violations(root)), plugin
        native.write_text('plugins { id("magicpaper.jvm-library") }')
        assert not violations(root), violations(root)
        factory = root / 'backend-agents/factory/build.gradle.kts'
        factory.parent.mkdir(parents=True)
        factory.write_text('commonMain.dependencies { implementation(project(":backend-agents:pi")) }')
        assert not violations(root), violations(root)
        native.write_text('commonMain.dependencies { implementation(project(":backend-agents:codex")) }')
        assert any('only :backend-agents:factory may consume engine' in error for error in violations(root))
        native.write_text('')
        source = root / 'magic-chat/impl/src/commonMain/kotlin/Fixture.kt'
        source.parent.mkdir(parents=True)
        chat_build = root / 'magic-chat/impl/build.gradle.kts'
        for dependency in ('libs.koin.core', '"io.insert-koin:koin-core:4.0.0"'):
            chat_build.write_text(f'commonMain.dependencies {{ implementation({dependency}) }}')
            assert any('Koin dependencies belong only' in error for error in violations(root))
        chat_build.write_text('')
        source.write_text('package fixture\nimport org.koin.core.Koin\n')
        assert any('Koin belongs only' in error for error in violations(root))
        source.write_text('package fixture\n// import org.koin.core.Koin\n')
        assert not violations(root), violations(root)
        source.write_text('package fixture\nclass ToolHost\n')
        assert any('ToolHost is retired' in error for error in violations(root))
        source.write_text('package fixture\n// class ToolHost\n')
        assert not violations(root), violations(root)

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
    # Another working copy inside the tree is not this tree. A session worktree under
    # .claude/worktrees, or any directory with its own .git, holds a second copy of every module;
    # scanning it reports duplicate facades and stale violations nobody in this tree wrote.
    with TemporaryDirectory() as folder:
        root = Path(folder)
        def module(base, name, dependency=''):
            directory = base / name
            (directory / 'src/commonMain/kotlin').mkdir(parents=True, exist_ok=True)
            (directory / 'build.gradle.kts').write_text(
                f'commonMain.dependencies {{ implementation(project("{dependency}")) }}' if dependency else '')
            (directory / 'src/commonMain/kotlin/Shared.kt').write_text('package fixture\npublic fun operation() = Unit\n')
        module(root, 'feature/session/api')
        assert not violations(root), violations(root)
        # The same module copied where a session worktree lives, carrying an edge that would be
        # reported if it were scanned: neither the facade nor the edge may surface.
        module(root / '.claude/worktrees/other', 'feature/session/api', ':feature:session:impl')
        module(root / '.claude/worktrees/other', 'feature/session/impl', ':feature:session:api')
        assert not violations(root), violations(root)
        # A checkout carries its own .git; the root having one must not hide the tree itself.
        (root / '.git').mkdir()
        module(root, 'feature/session/copy')
        assert any('Duplicate JVM facade' in error for error in violations(root)), 'the root .git must not skip the tree'
        # The same duplicate anywhere else is still reported, including beside a look-alike path.
        for reported in ('elsewhere', '.claude/notes', '.claude/worktrees-archive'):
            with TemporaryDirectory() as other:
                clean = Path(other)
                module(clean, 'feature/session/api')
                module(clean / reported, 'feature/session/api', ':feature:session:impl')
                assert any('Duplicate JVM facade' in error for error in violations(clean)), reported
        # A directory with its own .git is a nested checkout wherever it sits; without one it is not.
        with TemporaryDirectory() as other:
            clean = Path(other)
            module(clean, 'feature/session/api')
            module(clean / 'vendor/checkout', 'feature/session/api', ':feature:session:impl')
            assert any('Duplicate JVM facade' in error for error in violations(clean)), 'no marker, still scanned'
            (clean / 'vendor/checkout/.git').write_text('gitdir: /elsewhere\n')
            assert not violations(clean), violations(clean)
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
        (root / 'magic-agent/runtime/impl').mkdir(parents=True)
        (root / 'magic-agent/runtime/impl/build.gradle.kts').write_text('')
        app = root / 'app/build.gradle.kts'
        app.parent.mkdir(parents=True)
        # A group member is desktop-only by membership; the explicit set holds the exceptions.
        desktop_only = ':magic-agent:runtime:impl'
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
if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as folder:
        root = Path(folder)
        source = root / 'core/model/src/commonMain/kotlin/Effects.kt'
        source.parent.mkdir(parents=True)
        # The KDoc brace, the `when` arms inside the body and the bodiless declaration are
        # all places a coarser walk would either desync or report an arbitrary `->`.
        source.write_text('package fixture\n'
                          '/** Effects are descriptions; a lambda member would hide work behind a `{`. */\n'
                          'sealed interface StageEffect {\n'
                          '    data class Block(val issue: PlanningIssue) : StageEffect\n'
                          '    val issue: PlanningIssue\n'
                          '    val onDone: () -> Unit\n'
                          '    val label: String get() = when (this) {\n'
                          '        is Block -> "block"\n'
                          '        else -> ""\n'
                          '    }\n'
                          '}\n'
                          'sealed interface PlanEvent\n'
                          'class NavigationEvents {\n'
                          '    sealed interface Event {\n'
                          '        val perform: () -> Unit\n'
                          '    }\n'
                          '}\n')
        found = [error for error in violations(root) if 'declares a function type' in error]
        assert len(found) == 2, found
        assert any('StageEffect.onDone' in error for error in found), found
        assert any('Event.perform' in error for error in found), 'a nested hierarchy is still a hierarchy'
        assert not any('issue' in error or 'label' in error for error in found), found
        # The form this codebase actually writes: the payload rides the constructor, on one
        # line, so a scan anchored to a line-leading `val` would never see it.
        source.write_text('package fixture\n'
                          'sealed interface StageEffect {\n'
                          '    data class Ask(val onAnswer: (String) -> Unit) : StageEffect\n'
                          '    data class Block(val issue: PlanningIssue) : StageEffect\n'
                          '    val label: String get() = when (this) {\n'
                          '        is Block -> "block"\n'
                          '    }\n'
                          '}\n')
        found = [error for error in violations(root) if 'declares a function type' in error]
        assert len(found) == 1 and 'StageEffect.onAnswer' in found[0], found
        for subclass in ('data class Ask(val onAnswer: (String) -> Unit) : StageEffect\n',
                         'data class Ask(\n    val onAnswer: (String) -> Unit,\n) : StageEffect\n'):
            source.write_text('package fixture\nsealed interface StageEffect\n' + subclass)
            found = [error for error in violations(root) if 'declares a function type' in error]
            assert len(found) == 1 and 'Ask.onAnswer' in found[0], found
        # A brace inside a literal must not move the body walk in either direction.
        source.write_text('package fixture\n'
                          'sealed interface StageEffect {\n'
                          '    data class Block(val label: String = "{") : StageEffect\n'
                          '}\n'
                          'class Unrelated {\n'
                          '    val onDone: () -> Unit = {}\n'
                          '}\n')
        assert not any('declares a function type' in error for error in violations(root)), violations(root)
        source.write_text('package fixture\n'
                          'sealed interface StageEffect {\n'
                          '    data class Block(val label: String = "}") : StageEffect\n'
                          '    val onDone: () -> Unit\n'
                          '}\n')
        found = [error for error in violations(root) if 'declares a function type' in error]
        assert len(found) == 1 and 'StageEffect.onDone' in found[0], found
        # CoordinatorContinuation is an effect vocabulary by every criterion but its name, so
        # the planning package carries the rule and the same name elsewhere does not.
        source.write_text('package fixture\n')
        vocabulary = ('package fixture\n'
                      'sealed interface CoordinatorContinuation {\n'
                      '    data class Ask(val onAnswer: (String) -> Unit) : CoordinatorContinuation\n'
                      '}\n')
        planning = root / PLANNING_PACKAGE
        planning.mkdir(parents=True)
        (planning / 'CoordinationMachine.kt').write_text(vocabulary)
        (root / 'core/model/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Session.kt').write_text(vocabulary)
        found = [error for error in violations(root) if 'declares a function type' in error]
        assert len(found) == 1 and 'planning/CoordinationMachine.kt' in found[0], found

if '--self-test' in sys.argv:
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as folder:
        root = Path(folder)
        planning = root / PLANNING_PACKAGE
        planning.mkdir(parents=True)
        (planning / 'StageMachine.kt').write_text('package fixture\nval now = Clock.System.now()\n')
        found = [error for error in violations(root) if 'reads a clock' in error]
        assert len(found) == 1 and 'StageMachine.kt' in found[0] and 'Clock.' in found[0], found
        # A pure sibling: the forbidden names appear only as prose and as data, which the
        # comment and string blanking must not confuse with a call.
        (planning / 'PlanProjection.kt').write_text(
            'package fixture\n'
            '// Time enters as a value: Clock.System.now() is the caller\'s business, not ours.\n'
            '/* Nor does this file launch( ) anything. */\n'
            'data class StageRetryInputs(val limit: Int?, val now: Long, val jitter: Long)\n'
            'fun label() = "runBlocking(worker)"\n')
        assert not any('PlanProjection' in error for error in violations(root)), violations(root)
        for builder in ('launch(block)', 'async(block)', 'withContext(context) { }', 'runBlocking { }',
                        'launch { work() }'):
            (planning / 'StageMachine.kt').write_text(f'package fixture\nfun start() = scope.{builder}\n')
            assert any('launches work' in error for error in violations(root)), builder
        (planning / 'StageMachine.kt').write_text('package fixture\nfun relaunch() = describe(asynchronous)\n')
        assert not any('launches work' in error for error in violations(root)), 'a builder name is a whole word'
        (planning / 'StageMachine.kt').write_text('package fixture\nval seed = Random.nextInt(4)\n')
        assert len([error for error in violations(root) if 'reads a clock' in error]) == 2, 'one entry per distinct token'
        # A generated id is the transition most likely to be written by accident, and the
        # clock, randomness and coroutine vocabularies have more than one spelling each.
        for impure in ('val id = UUID.randomUUID()', 'val id = Uuid.random()', 'val share = Math.random()',
                       'val seed = rng.nextLong()', 'val jitter = rng.nextDouble()',
                       'val start = System.nanoTime()', 'val mark = TimeSource.Monotonic.markNow()',
                       'fun run() = execute(Dispatchers.IO)', 'val stages = flow { }',
                       'suspend fun run() = coroutineScope { }'):
            (planning / 'StageMachine.kt').write_text(f'package fixture\n{impure}\n')
            assert any('StageMachine.kt' in error and 'planning transition' in error
                       for error in violations(root)), impure
        # The shape StageMachine.kt actually writes: time arrives as a value and the wait is
        # an effect, so neither the field nor the effect name may be read as a call.
        (planning / 'StageMachine.kt').write_text(
            'package fixture\n'
            'fun wait(inputs: StageRetryInputs) = StageEffect.Delay(delayMillis(inputs.now))\n')
        assert not any('StageMachine.kt' in error for error in violations(root)), violations(root)
        # The rule is bounded by the package, not by the module or the filename.
        outside = root / 'core/model/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Session.kt'
        outside.write_text('package fixture\nval started = Clock.System.now()\n')
        assert not any('Session.kt' in error for error in violations(root)), violations(root)
    with TemporaryDirectory() as folder:
        root = Path(folder)
        assert not violations(root), 'a tree with no domain package is not a checkout to guard'
        owner = root / 'magic-common/core-model/src/commonMain/kotlin' / PLANNING_OWNER
        owner.mkdir(parents=True)
        (owner / 'Session.kt').write_text('package fixture\n')
        assert any('planning package not found' in error for error in violations(root)), violations(root)
        # Moving the module keeps the Kotlin package: the rule must fail loudly rather than
        # scan an empty directory and let the migration through.
        moved = owner / 'planning'
        moved.mkdir()
        (moved / 'StageMachine.kt').write_text('package fixture\nval now = Clock.System.now()\n')
        assert any('planning package not found' in error for error in violations(root)), 'a moved package is unresolved'

errors = violations(ROOT)
if errors:
    raise SystemExit('FAIL\n' + '\n'.join(errors))
print('PASS: acyclic API/impl and architecture groups; JVM agent boundary; value effects; composition in :app')
