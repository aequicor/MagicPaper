#!/usr/bin/env python3
"""Compare JUnit XML by test identity when a migration changes Gradle module ownership."""
from pathlib import Path
import argparse
import json
import re
import xml.etree.ElementTree as ET


def results(root):
    tests = {}
    for report in sorted(root.rglob('TEST-*.xml')):
        if 'test-results' not in report.parts:
            continue
        task = report.parts[report.parts.index('test-results') + 1]
        if task not in {'jvmTest', 'test'}:
            continue
        for case in ET.parse(report).getroot().iter('testcase'):
            key = case.get('classname', '') + '.' + case.get('name', '')
            failure = case.find('failure')
            if failure is None:
                failure = case.find('error')
            value = None
            if failure is not None:
                message = failure.get('message', '').splitlines()[0]
                # Temporary native fixtures change paths between otherwise identical runs.
                message = re.sub(r'/(?:private/)?(?:var/folders|tmp)/[^\s)>]+', '<temporary-path>', message)
                value = {'type': failure.get('type', ''), 'message': message}
            if key not in tests or value is not None:
                tests[key] = value
    return tests


def comparison(before, after):
    common = before.keys() & after.keys()
    return {
        'baseline_tests': len(before), 'current_tests': len(after),
        'baseline_failures': sum(value is not None for value in before.values()),
        'current_failures': sum(value is not None for value in after.values()),
        'regressions': sorted(key for key in after if after[key] is not None and before.get(key) is None),
        'unchanged_failures': sorted(key for key in common if after[key] is not None and after[key] == before[key]),
        'changed_failures': {key: {'before': before[key], 'after': after[key]} for key in sorted(common)
                             if before[key] is not None and after[key] is not None and before[key] != after[key]},
        'resolved_failures': sorted(key for key in common if before[key] is not None and after[key] is None),
        'baseline_only_tests': sorted(before.keys() - after.keys()),
        'new_tests': sorted(after.keys() - before.keys()),
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('current', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = comparison(results(args.baseline), results(args.current))
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(rendered)
    print(rendered)
    raise SystemExit(bool(result['regressions'] or result['changed_failures']))
