#!/usr/bin/env python3
"""Verify explicit surface assignments against Kotlin; never infer missing targets.

--write renders the review table and evidence. --self-test checks rejection of
missing bindings, unknown components, and invalid stages, in memory only.
"""
from pathlib import Path
import copy
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/desktop-ui'
STAGES = {
    '3': '8879dbba-68bb-48bb-bc7a-ece3421572c1',
    '5': '21417b79-d79e-4b17-a51f-763b483863b9',
    '6': '1c1bef7f-a975-4183-9220-cf925d24dec2',
    '7': 'e3995487-9da9-419d-ac67-e5774675cc1e',
}
COMPONENTS = set('''PaperAppShell PaperTheme PaperTokens PaperTypography
PaperBackground PaperWindowHost PaperWindowInsets PaperCommandMenu PaperNavigation
PaperSplitPane PaperPluginHost PaperToolbar PaperIconButton PaperNotice PaperPanel
PaperGraphCanvas PaperGraphNode PaperField PaperButton PaperChatTranscript PaperComposer
PaperMarkdown PaperDialog PaperChoice PaperListRow PaperStatus PaperImage
PaperAttachmentRow PaperAttachmentChip PaperCodeBlock PaperScroll PaperTooltip
PaperApprovalDock PaperText PaperReader PaperLink PaperQuestionnaire PaperScheduleEditor
PaperActivityIndicator PaperWorkspaceHeading PaperWorkspaceComposer PaperPromptField PaperWorkSurface
PaperPage PaperTab PaperTreeRow PaperSettingsSection PaperMenu'''.split())
PATTERN = re.compile(
    r'@(?:androidx\.compose\.runtime\.)?Composable\s+'
    r'(?:@[\w.]+(?:\([^\n]*\))?\s+)*'
    r'(?:(?:private|internal|public|override|actual|expect)\s+)*fun\s+([\w.]+)\s*\('
)
GETTER = re.compile(r'\bval\s+(\w+)[^\n]*\n\s*(@Composable\s+get\s*\()')
LAMBDA = re.compile(r'\bval\s+(\w+)\s*:\s*(@Composable[^\n]*=\s*\{)')
ENTRIES = {
    ('desktopApp/src/main/kotlin/io/aequicor/magicpaper/main.kt', 'main', None),
    ('androidApp/src/main/kotlin/io/aequicor/magicpaper/MainActivity.kt', 'onCreate', None),
    ('webApp/src/webMain/kotlin/io/aequicor/magicpaper/main.kt', 'main', None),
}

def discover():
    found = set(ENTRIES)
    for module in ('shared', 'desktopApp', 'androidApp', 'webApp'):
        for p in (ROOT/module/'src').rglob('*.kt'):
            if any('test' in x.lower() for x in p.relative_to(ROOT).parts): continue
            text = p.read_text()
            for m in PATTERN.finditer(text):
                found.add((p.relative_to(ROOT).as_posix(), m.group(1), text.count('\n', 0, m.start())+1))
            for pattern in (GETTER, LAMBDA):
                for m in pattern.finditer(text):
                    found.add((p.relative_to(ROOT).as_posix(), m.group(1), text.count('\n',0,m.start(2))+1))
    return found

def validate(rows, expected):
    keys = [(r['path'], r['symbol'], r['annotationLine']) for r in rows]
    assert len(keys) == len(set(keys)), 'Duplicate surface binding'
    missing, stale = expected-set(keys), set(keys)-expected
    assert not missing and not stale, f'Missing: {sorted(missing)}; stale: {sorted(stale)}'
    for r in rows:
        assert r['stage'] in STAGES, f'Unknown stage: {r}'
        assert r['targets'] and set(r['targets']) <= COMPONENTS, f'Unknown/empty DS targets: {r}'
        assert r['kind'] in ('surface', 'composition-helper', 'ui-contract', 'entry', 'getter', 'local-surface'), r
        if r['kind'] == 'entry':
            assert re.search(r'\bfun\s+'+r['symbol']+r'\s*\(', (ROOT/r['path']).read_text()), r
    # Explicit screen and platform obligations, independent of file-level audit.
    names = {r['symbol'] for r in rows}
    assert {'WelcomeScreen','ChatScreen','CodingScreen','PluginsScreen','DocsScreen','SettingsScreen',
            'ProjectsPanel','SessionsPanel','ProfileEditor','Questionnaire','CodingComposer',
            'StageDetailsDialog','SkillCatalogPanel','ProjectSkillRollback'} <= names
    for basename in ('ProjectSkills.kt','ProjectSkillsPanel.kt','LocalSkillsPlugin.kt','LocalExperiencePlugin.kt'):
        assert any(Path(r['path']).name == basename and r['symbol']=='Content' for r in rows), basename

def render(rows):
    lines = ['# Экран/панель → DS-компоненты → этап', '',
             'Основное доказательство ac-research-map. Каждое объявление @Composable, включая полное имя аннотации, имеет отдельную явную запись в [surface-bindings.json](surface-bindings.json). Отсутствующее назначение — ошибка; компоненты не наследуются от файла и не подставляются проверкой автоматически.', '',
             'Paper* — конкретные планируемые API, а не уже реализованные компоненты. Общие layout helpers могут остаться в feature; список показывает обязательные DS API для визуальной композиции. Строка указывает аннотацию. Два Content в LocalExperiencePlugin различаются строкой: рабочая панель и unavailable fallback. Невизуальные composition helpers и SPI помечены отдельно.', '',
             'Проверка: `python3 docs/desktop-ui/verify-map.py --self-test`. Исходные токены и поведение — [CONTRACT.md](CONTRACT.md); полный файл-level реестр с невизуальными функциями — [MIGRATION.md](MIGRATION.md).', '',
             '| Source set / файл | Экран, панель или entry | Вид | Конкретные DS API | Этап |',
             '|---|---|---|---|---|']
    for r in rows:
        line = f":{r['annotationLine']}" if r['annotationLine'] else ''
        lines.append(f"| [{r['path']}](../../{r['path']}) | `{r['symbol']}{line}` | {r['kind']} | {', '.join(r['targets'])} | {r['stage']} |")
    lines += ['', '## Постоянные ID этапов', '', '| Этап | taskId |', '|---|---|']
    lines += [f'| {k} | `{v}` |' for k,v in STAGES.items()]
    lines += ['', '## Граница новых составных API', '',
              '`PaperImage`, `PaperAttachmentRow`, `PaperAttachmentChip` — доступный preview изображения, список вложений и отдельное вложение с удалением/открытием. `PaperLink` — фокусируемая ссылка с activation/context copy. `PaperScroll` — viewport/scrollbar и управление follow-end без потери пользовательской позиции. Эти API создаются как составные DS-компоненты до переноса соответствующих поверхностей этапа 6; базовые focus/semantics/controls предоставляет этап 3. Остальные Paper* и платформенные обязанности определены в CONTRACT.md.']
    return '\n'.join(lines)+'\n'

def main():
    rows = json.loads((OUT/'surface-bindings.json').read_text())
    expected = discover()
    validate(rows, expected)
    negative = []
    if '--self-test' in sys.argv:
        for case in ('missing-binding','unknown-component','unknown-stage'):
            bad = copy.deepcopy(rows)
            if case == 'missing-binding': bad.pop()
            elif case == 'unknown-component': bad[0]['targets'] = ['MaterialButton']
            else: bad[0]['stage'] = '4'
            try: validate(bad, expected)
            except AssertionError: negative.append(case)
            else: raise AssertionError(f'Validator accepted {case}')
    table = render(rows)
    if '--write' in sys.argv: (OUT/'SURFACE-MAP.md').write_text(table)
    else: assert (OUT/'SURFACE-MAP.md').read_text() == table, 'Stale review table'
    result = {'criterion':'4aa76be6-198a-4448-8af5-69bd0c97e7f2/ac-research-map',
              'status':'PASS','explicitBindings':len(rows),'composableDeclarations':len(expected)-len(ENTRIES),
              'entries':len(ENTRIES),'missingBindings':[],'staleBindings':[],
              'unknownTargets':[],'unknownStages':[],
              'byStage':{k:sum(r['stage']==k for r in rows) for k in STAGES},
              'negativeControlsRejected':negative,
              'scope':'Source declarations → explicit DS API → valid migration task. Not a runtime UI test or compiler-resolved call graph.'}
    if '--write' in sys.argv: (OUT/'map-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__ == '__main__': main()
