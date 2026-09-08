import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import register, { planningGit, planningTools } from '../../../jvmMain/resources/coding/planning-tools.mjs';

test('status, both diffs and pagination preserve the working tree and index', async () => {
  const root = await mkdtemp(join(tmpdir(), 'planning-git-'));
  const git = (...args) => execFileSync('git', args, { cwd: root, env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' } }).toString();
  try {
    git('init', '-q'); git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@example.test');
    await writeFile(join(root, 'tracked.txt'), 'base\n'); git('add', '.'); git('commit', '-qm', 'initial');
    await writeFile(join(root, 'tracked.txt'), 'staged marker\n'); git('add', '.');
    await writeFile(join(root, 'tracked.txt'), 'unstaged marker\n');
    await writeFile(join(root, 'new.txt'), 'new file');
    // A configured diff driver must never run during inspection.
    await writeFile(join(root, '.gitattributes'), 'tracked.txt diff=unsafe\n');
    git('config', 'diff.unsafe.command', 'touch should-not-exist');
    git('config', 'diff.unsafe.textconv', 'touch should-not-exist');
    const before = await readFile(join(root, '.git/index'));
    const status = await planningGit(root, { action: 'status' });
    assert.match(status.text, /MM tracked.txt/); assert.match(status.text, /\?\? new.txt/);
    const staged = await planningGit(root, { action: 'diff', staged: true });
    const unstaged = await planningGit(root, { action: 'diff' });
    assert.match(staged.text, /\+staged marker/); assert.match(unstaged.text, /\+unstaged marker/);
    let combined = '', offset = 0;
    do {
      const page = await planningGit(root, { action: 'diff', offset, limit: 15 });
      combined += page.text; offset = page.nextOffset;
      if (offset !== null) assert.match(page.note, /усечён/);
    } while (offset !== null);
    assert.equal(combined, unstaged.text);
    assert.deepEqual(await readFile(join(root, '.git/index')), before);
    assert.equal(await readFile(join(root, 'tracked.txt'), 'utf8'), 'unstaged marker\n');
    await assert.rejects(readFile(join(root, 'should-not-exist')));
    await assert.rejects(planningGit(root, { action: 'reset' }));
    await assert.rejects(planningGit(root, { action: 'diff', path: '../outside' }));
    await assert.rejects(planningGit(root, { action: 'diff', limit: 64001 }));
    const controller = new AbortController(); controller.abort();
    await assert.rejects(planningGit(root, { action: 'status' }, controller.signal));
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('non-Git folders report the cause and forbidden tools are blocked', async () => {
  const root = await mkdtemp(join(tmpdir(), 'planning-no-git-'));
  try { await assert.rejects(planningGit(root, { action: 'status' }), /not a git repository/i); }
  finally { await rm(root, { recursive: true, force: true }); }
  let gate; let tool;
  register({ registerTool: value => { tool = value; }, on: (name, callback) => { if (name === 'tool_call') gate = callback; } });
  assert.equal(tool.name, 'planning_git');
  for (const name of ['write', 'edit', 'bash', 'powershell', 'computer']) assert.equal(gate({ toolName: name }).block, true);
  for (const name of planningTools) assert.equal(gate({ toolName: name }), undefined);
});
