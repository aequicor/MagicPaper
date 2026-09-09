#!/usr/bin/env python3
"""Read-only source inventory; --write refreshes generated research artifacts only.

Lexical inventory, not Kotlin name resolution: wildcard candidates are marked.
Run from any directory. No network, Gradle, or third-party Python dependencies.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/desktop-ui'
FUN = re.compile(r'\bfun\s+(?:<[^>]+>\s*)?([\w.]+)\s*\(')
IMPORT = re.compile(r'^import\s+(androidx\.compose\.material[\w.*]*)(?:\s+as\s+(\w+))?', re.M)
COMPOSABLE = re.compile(r'@(?:androidx\.compose\.runtime\.)?Composable\b')
EXTRA = {'Button', 'ButtonDefaults', 'Text', 'TextButton', 'OutlinedButton',
         'OutlinedTextField', 'TextField', 'TextFieldDefaults', 'Surface', 'Card', 'OutlinedCard',
         'CardDefaults', 'Switch', 'Checkbox', 'RadioButton', 'Slider',
         'HorizontalDivider', 'VerticalDivider', 'CircularProgressIndicator',
         'LinearProgressIndicator', 'AlertDialog', 'DropdownMenu', 'DropdownMenuItem',
         'MaterialTheme', 'FilterChip', 'FilterChipDefaults', 'Icon', 'IconButton',
         'TooltipBox', 'PlainTooltip', 'TooltipDefaults', 'rememberTooltipState',
         'ExperimentalMaterial3Api', 'LocalTextStyle', 'LocalContentColor',
         'ProvideTextStyle', 'MenuDefaults', 'SwitchDefaults', 'CheckboxDefaults',
         'Shapes', 'Typography', 'lightColorScheme'}

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def sources():
    return sorted(p for module in ('shared', 'desktopApp', 'androidApp', 'webApp')
                  for p in (ROOT / module / 'src').rglob('*.kt')
                  if not any('test' in part.lower() for part in p.relative_to(ROOT).parts))

def assignment(p):
    name, path = p.name, p.as_posix()
    if name == 'MainActivity.kt' or ('webApp/' in path and name == 'main.kt'):
        return '5', ['PaperAppShell', 'PaperTheme']
    if '/theme/' in path or name.startswith(('MagicPaperBackground', 'PaperAnimationPolicy')):
        return '3', ['PaperTheme', 'PaperTokens', 'PaperTypography', 'PaperBackground']
    if '/window/' in path or name == 'DesktopWindowMode.kt' or name == 'main.kt' or name == 'MainActivity.kt':
        return '5', ['PaperWindowHost', 'PaperWindowInsets', 'PaperCommandMenu']
    if name == 'App.kt':
        return '5', ['PaperAppShell', 'PaperToolbar', 'PaperNavigation', 'PaperNotice', 'PaperTheme']
    if '/di/' in path or name in ('PluginRegistry.kt', 'ProjectSkills.kt'):
        return '7', ['PaperPluginHost']
    if '/plugins/' in path:
        return ('6' if name == 'DecisionPlanningPlugin.kt' else '7'), ['PaperPanel', 'PaperListRow', 'PaperForm', 'PaperDialog', 'PaperStatus']
    if name in ('SettingsScreen.kt', 'ModelsSettings.kt', 'EnginesSettings.kt', 'SearchApiSettings.kt', 'SessionsPanel.kt'):
        return '5', ['PaperSettingsSection', 'PaperListRow', 'PaperField', 'PaperChoice', 'PaperDialog']
    if name in ('WelcomeScreen.kt', 'DocsScreen.kt', 'PluginsScreen.kt'):
        return '7', ['PaperPage', 'PaperPanel', 'PaperField', 'PaperNavigation', 'PaperMarkdown']
    if name in ('ToolbarButton.kt', 'MagicFilterChip.kt', 'FadingSingleLineText.kt'):
        return '3', ['PaperIconButton', 'PaperChoice', 'PaperText', 'PaperTooltip']
    if name.startswith('Desktop') and 'Picker' in name:
        return '5', ['PaperFilePicker']
    return '6', ['PaperPanel', 'PaperText', 'PaperButton', 'PaperField', 'PaperMenu', 'PaperDialog']

def target(symbol):
    if symbol in ('MaterialTheme', 'Shapes', 'Typography', 'lightColorScheme', 'LocalTextStyle', 'LocalContentColor', 'ProvideTextStyle'):
        return 'PaperTheme/PaperTokens'
    if 'TextField' in symbol: return 'PaperField'
    if 'Tooltip' in symbol: return 'PaperTooltip'
    if 'Dialog' in symbol: return 'PaperDialog'
    if 'Menu' in symbol: return 'PaperMenu'
    if 'Divider' in symbol: return 'PaperDivider'
    if 'Progress' in symbol: return 'PaperProgress'
    if any(s in symbol for s in ('Switch', 'Checkbox', 'Radio', 'Chip', 'Slider')): return 'PaperChoice'
    if 'Button' in symbol: return 'PaperIconButton' if 'Icon' in symbol else 'PaperButton'
    if symbol in ('Text', 'Icon'): return 'Paper' + symbol
    if symbol in ('Surface', 'Card', 'OutlinedCard', 'CardDefaults'): return 'PaperPanel'
    return 'PaperTheme/internal compatibility'

def inventory():
    files = sources()
    all_symbols = EXTRA | {alias or imp.rsplit('.', 1)[-1]
                           for p in files for imp, alias in IMPORT.findall(p.read_text())
                           if not imp.endswith('*')}
    rows, sites, markdown = [], [], []
    for p in files:
        s = p.read_text()
        rel = p.relative_to(ROOT).as_posix()
        imports = IMPORT.findall(s)
        for n, line in enumerate(s.splitlines(), 1):
            if re.search(r'markdown|highlights', line, re.I):
                markdown.append({'path': rel, 'line': n, 'text': line.strip()})
        own = {alias or imp.rsplit('.', 1)[-1] for imp, alias in imports if not imp.endswith('*')}
        wildcard = any(imp.endswith('*') for imp, _ in imports)
        used = set()
        for n, line in enumerate(s.splitlines(), 1):
            if line.strip().startswith(('import ', '//', '*')): continue
            tokens = set(re.findall(r'\b\w+\b', line)) & (all_symbols if wildcard else own)
            if 'androidx.compose.material' in line:
                tokens |= set(re.findall(r'androidx\.compose\.material\w*\.(\w+)', line))
            for token in sorted(tokens):
                used.add(token)
                sites.append({'path': rel, 'line': n, 'symbol': token,
                              'kind': 'wildcard-candidate' if wildcard and token not in own else 'import-reference',
                              'form': 'call' if re.search(r'\b' + token + r'\s*\(', line) else 'reference',
                              'target': target(token)})
        is_ui = ('/ui/' in rel or '/plugins/' in rel or COMPOSABLE.search(s) or imports
                 or p.name in ('App.kt', 'main.kt', 'MainActivity.kt', 'DesktopWindowMode.kt')
                 or ('/di/' in rel) or ('Picker' in p.name and 'jvmMain' in rel))
        if not is_ui: continue
        stage, targets = assignment(p)
        if re.search(r'Markdown|MessagePreview|StreamingText', p.name): targets += ['PaperMarkdown', 'PaperReader', 'PaperCodeBlock']
        if re.search(r'Chat|Composer|CodingChatRows', p.name): targets += ['PaperChatTranscript', 'PaperComposer']
        if re.search(r'Graph', p.name): targets += ['PaperGraphCanvas', 'PaperGraphNode', 'PaperSplitPane']
        if re.search(r'Questions|Questionnaire|Approval|Blocker', p.name): targets += ['PaperQuestionnaire', 'PaperApprovalDock']
        if re.search(r'Scheduled', p.name): targets += ['PaperScheduleEditor', 'PaperStatus']
        if p.name == 'CodingScreen.kt': targets += ['PaperSplitPane', 'PaperTreeRow', 'PaperTab', 'PaperStatus', 'PaperTooltip']
        if p.name in ('UiState.kt', 'UserInteractions.kt', 'MagicPaperViewModel.kt', 'StageChatState.kt'):
            targets = ['State adapter: preserve behavior, no visual component replacement']
        decls = [{'name':m.group(1), 'line':s.count('\n',0,m.start())+1} for m in FUN.finditer(s)]
        rows.append({'path':rel, 'sha256':sha(p), 'stage':stage, 'targets':sorted(set(targets)),
                     'declarations':decls, 'materialImports':[a for a,_ in imports],
                     'materialSymbols':sorted(used), 'materialTargets':sorted({target(x) for x in used}),
                     'pluginIds':re.findall(r'override val id\s*=\s*"([^"]+)"',s)})
    return {'method':'Lexical full production-source scan; every function in UI files included (also helpers). Material lines are imported references/call candidates, not compiler-resolved AST. In-file panels inherit file stage and target components. Wildcard candidates explicitly marked.',
            'files':rows,'materialSites':sites,'markdownReferences':markdown}

def render(data):
    lines = ['# Реестр миграции desktop UI', '',
             'Сгенерировано `python3 docs/desktop-ui/audit.py --write`. Контракт и порядок — [CONTRACT.md](CONTRACT.md).', '',
             'Отдельные явные связи каждого UI-объявления: [SURFACE-MAP.md](SURFACE-MAP.md), проверяются `verify-map.py`. Эта таблица дополняет их файловой инвентаризацией и невизуальными helpers.', '',
             'Все функции UI-файлов перечислены, включая private-панели и невизуальные helpers. Каждый символ наследует этап и DS-компоненты строки; это карта назначения, а не runtime call graph. Точные строки Material и Markdown — `inventory.json`.', '',
             '| Файл / source set | Функции и панели (строка) | Целевая DS | Этап |',
             '|---|---|---|---|']
    for r in data['files']:
        funcs = ', '.join(f"`{d['name']}:{d['line']}`" for d in r['declarations']) or 'Типы / свойства / контракт'
        link = f"[{r['path']}](../../{r['path']})"
        lines.append(f"| {link} | {funcs} | {', '.join(sorted(set(r['targets']+r['materialTargets'])))} | {r['stage']} |")
    lines += ['', '## Material: замены', '', '| Символ | Целевой компонент | Число строк-ссылок |', '|---|---|---|']
    counts = {}
    for site in data['materialSites']: counts[site['symbol']] = counts.get(site['symbol'],0)+1
    for symbol, count in sorted(counts.items()): lines.append(f'| `{symbol}` | {target(symbol)} | {count} |')
    return '\n'.join(lines)+'\n'

def main():
    data = inventory()
    if '--write' in sys.argv:
        (OUT/'inventory.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
        (OUT/'MIGRATION.md').write_text(render(data))
    saved = json.loads((OUT/'inventory.json').read_text())
    assert data == saved, 'Inventory drift: inspect concurrent changes before regeneration'
    baseline = json.loads((OUT/'baseline.json').read_text())
    changed = [p for p,h in baseline['trackedFileHashes'].items() if not (ROOT/p).is_file() or sha(ROOT/p)!=h]
    protected = [r['path'] for r in baseline['foreignChanges'] if sha(ROOT/r['path'])!=r['sha256']]
    assert not protected, f'Foreign session recovery files changed: {protected}'
    assert all(r['targets'] and r['stage'] in ('3','5','6','7') for r in data['files'])
    covered = {r['path'] for r in data['files']}
    uncovered = [p.relative_to(ROOT).as_posix() for p in sources()
                 if (COMPOSABLE.search(p.read_text()) or IMPORT.search(p.read_text()))
                 and p.relative_to(ROOT).as_posix() not in covered]
    assert not uncovered, f'Unmapped UI: {uncovered}'
    map_result = json.loads(subprocess.check_output(
        [sys.executable, str(OUT/'verify-map.py'), '--self-test'], text=True))
    result = {'inventoryMatches':True,'unmappedUI':uncovered,'uiFiles':len(data['files']),
              'declarations':sum(len(r['declarations']) for r in data['files']),
              'materialFiles':sum(bool(r['materialImports']) for r in data['files']),
              'materialSites':len(data['materialSites']),'markdownReferences':len(data['markdownReferences']),
              'protectedRecoveryFilesUnchanged':not protected,'trackedDriftSinceBaseline':changed,
              'surfaceMap':map_result}
    print(json.dumps(result,ensure_ascii=False,indent=2))
    if '--write' in sys.argv: (OUT/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')

if __name__ == '__main__': main()
