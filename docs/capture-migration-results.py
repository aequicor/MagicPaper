#!/usr/bin/env python3
"""Freeze completed Gradle JUnit reports before another filtered run replaces them.

Run only after every relevant Gradle test task has finished. This command never
runs Gradle and does not interpret NO-SOURCE as a test pass. Baseline comparison
uses compare-jvm-baseline.py unchanged, including its exact identity/error rules.
"""
from pathlib import Path
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
import os
import runpy
import tempfile
import xml.etree.ElementTree as ET


JVM_TASKS = {'jvmTest', 'test'}
CURRENT_TASKS = JVM_TASKS | {'jsBrowserTest', 'wasmJsBrowserTest', 'testAndroidHostTest', 'testDebugUnitTest'}
SKIP_DIRECTORIES = {'.git', '.gradle', '.kotlin', 'node_modules'}


def reports(root):
    for directory, children, files in os.walk(root):
        children[:] = sorted(name for name in children if name not in SKIP_DIRECTORIES)
        relative = Path(directory).relative_to(root)
        parts = relative.parts
        if len(parts) < 3 or parts[-3:-1] != ('build', 'test-results'):
            continue
        # Historical named snapshots also live beneath test-results in this workspace.
        # They are evidence for their old revision, never results of current Gradle tasks.
        if parts[-1] not in CURRENT_TASKS:
            continue
        for name in sorted(files):
            if name.startswith('TEST-') and name.endswith('.xml'):
                yield relative / name


def capture(root, destination, baseline=None, run_logs=()):
    root = root.resolve()
    destination = destination.resolve()
    if destination == root or root in destination.parents:
        raise ValueError('Keep snapshots outside the source tree to avoid recursive capture.')
    if destination.exists() and any(destination.iterdir()):
        raise ValueError('Snapshot destination must be new or empty; previous evidence is immutable.')
    destination.mkdir(parents=True, exist_ok=True)
    groups = {}
    identities = defaultdict(set)
    manifest = []
    for relative in reports(root):
        source = root / relative
        data = source.read_bytes()
        suite = ET.fromstring(data)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        module = ':' + ':'.join(relative.parts[:-4])
        task = relative.parts[-2]
        counts = groups.setdefault((module, task), dict(module=module, task=task,
            executions=0, failures=0, skipped=0, reports=0, seconds=0.0))
        counts['reports'] += 1
        for case in suite.iter('testcase'):
            counts['executions'] += 1
            counts['failures'] += int(case.find('failure') is not None or case.find('error') is not None)
            counts['skipped'] += int(case.find('skipped') is not None)
            counts['seconds'] += float(case.get('time', '0'))
            if task in JVM_TASKS:
                identities[case.get('classname', '') + '.' + case.get('name', '')].add(module)
        manifest.append(dict(path=str(relative), sha256=hashlib.sha256(data).hexdigest(),
            bytes=len(data), sourceModifiedNs=source.stat().st_mtime_ns))
    if not manifest:
        raise ValueError('No completed JUnit reports found.')
    by_task = [groups[key] for key in sorted(groups)]
    jvm = {field: sum(group[field] for group in by_task if group['task'] in JVM_TASKS)
        for field in ('executions', 'failures', 'skipped', 'reports')}
    jvm['uniqueIdentities'] = len(identities)
    jvm['duplicateIdentities'] = {key: sorted(owners) for key, owners in sorted(identities.items()) if len(owners) > 1}
    logs = []
    for source in run_logs:
        data = source.read_bytes()
        logs.append(dict(path=str(source.resolve()), sha256=hashlib.sha256(data).hexdigest(), bytes=len(data)))
    write_json(destination / 'manifest.json', dict(capturedAtUtc=datetime.now(timezone.utc).isoformat(),
        sourceRoot=str(root), reports=manifest, runLogs=logs))
    counts = dict(byTask=by_task, jvm=jvm)
    write_json(destination / 'counts.json', counts)
    compared = None
    if baseline is not None:
        compare = runpy.run_path(str(Path(__file__).with_name('compare-jvm-baseline.py')))
        compared = compare['comparison'](compare['results'](baseline), compare['results'](destination))
        write_json(destination / 'jvm-comparison.json', compared)
    return counts, compared


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def self_test():
    with tempfile.TemporaryDirectory(prefix='migration-capture-test-') as temporary:
        root = Path(temporary)
        baseline = root / 'baseline'
        current = root / 'current'
        payload = '<testsuite><testcase classname="sample.Owner" name="same" time="0.1"/></testsuite>'
        for directory in (baseline / 'shared', current / 'feature' / 'chat' / 'impl', current / 'feature' / 'coding' / 'impl'):
            report = directory / 'build' / 'test-results' / 'jvmTest' / 'TEST-sample.Owner.xml'
            report.parent.mkdir(parents=True)
            report.write_text(payload)
        archive = current / 'shared' / 'build' / 'test-results' / 'historic-snapshot' / 'TEST-old.xml'
        archive.parent.mkdir(parents=True)
        archive.write_text('<testsuite><testcase classname="old.Archive" name="old"/></testsuite>')
        counts, compared = capture(current, root / 'snapshot', baseline)
        assert counts['jvm']['executions'] == 2 and counts['jvm']['uniqueIdentities'] == 1
        assert not compared['new_tests'] and not compared['regressions']
        assert len(counts['jvm']['duplicateIdentities']) == 1
        assert len(counts['byTask']) == 2
        try:
            capture(current, root / 'snapshot')
        except ValueError:
            pass
        else:
            raise AssertionError('Existing evidence was overwritten')
    print('PASS: migrated identities, separate owner executions, archive exclusion and immutable capture')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, nargs='?')
    parser.add_argument('destination', type=Path, nargs='?')
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--run-log', type=Path, action='append', default=[])
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if args.source is None or args.destination is None:
            parser.error('source and destination are required')
        counts, compared = capture(args.source, args.destination, args.baseline, args.run_log)
        print(json.dumps(dict(snapshot=str(args.destination.resolve()), jvm=counts['jvm']), ensure_ascii=False, indent=2))
        raise SystemExit(bool(compared and (compared['regressions'] or compared['changed_failures'])))
