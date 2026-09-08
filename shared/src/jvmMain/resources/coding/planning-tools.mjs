import { spawn } from 'node:child_process';
import { resolve, relative, isAbsolute } from 'node:path';
import { realpath } from 'node:fs/promises';

export const planningTools = ['read', 'grep', 'find', 'ls', 'planning_git'];

/** Fixed Git operations, no shell, hooks, diff drivers, pagers or index refresh writes. */
export async function planningGit(cwd, input, signal) {
  if (!['status', 'diff'].includes(input.action)) throw new Error('Доступны только status и diff.');
  if (input.staged !== undefined && typeof input.staged !== 'boolean') throw new Error('staged должен быть boolean.');
  const offset = input.offset ?? 0, limit = input.limit ?? 16000;
  if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 64000)
    throw new Error('offset ≥ 0; limit от 1 до 64000 символов.');
  const root = await realpath(cwd);
  const args = ['--no-pager', '-c', 'core.fsmonitor=false', '-c', 'core.untrackedCache=false', '-c', 'core.hooksPath=',
    '-c', 'color.ui=false', input.action];
  if (input.action === 'status') args.push('--porcelain=v1', '--untracked-files=all');
  else { args.push('--no-ext-diff', '--no-textconv', '--no-color'); if (input.staged) args.push('--cached'); }
  if (input.path !== undefined) {
    if (typeof input.path !== 'string' || input.path.includes('\0')) throw new Error('Некорректный путь.');
    const path = relative(root, resolve(root, input.path));
    if (path === '..' || path.startsWith('../') || path.startsWith('..\\') || isAbsolute(path))
      throw new Error('Путь должен находиться внутри проекта.');
    args.push('--', ':(literal)' + (path || '.'));
  }
  signal?.throwIfAborted();
  const env = { ...process.env, GIT_OPTIONAL_LOCKS: '0', GIT_TERMINAL_PROMPT: '0', GIT_PAGER: 'cat', PAGER: 'cat' };
  // Git configuration inherited from a parent process must not change the selected repository/command.
  for (const key of Object.keys(env)) if (/^GIT_(DIR|WORK_TREE|INDEX_FILE|CONFIG.*|EXTERNAL_DIFF|DIFF_OPTS)$/.test(key)) delete env[key];
  return new Promise((resolveResult, reject) => {
    const child = spawn('git', args, { cwd: root, env, shell: false, windowsHide: true, signal,
      stdio: ['ignore', 'pipe', 'pipe'] });
    let total = 0, text = '', error = '';
    const timer = setTimeout(() => { child.kill(); }, 30000);
    child.stdout.setEncoding('utf8'); child.stderr.setEncoding('utf8');
    child.stdout.on('data', chunk => {
      const start = Math.max(0, offset - total), end = Math.min(chunk.length, offset + limit - total);
      if (end > start) text += chunk.slice(start, end);
      total += chunk.length;
    });
    child.stderr.on('data', chunk => { error = (error + chunk).slice(-4000); });
    child.on('error', cause => { clearTimeout(timer); reject(cause); });
    child.on('close', (code, terminated) => {
      clearTimeout(timer);
      if (code !== 0) { reject(new Error(error.trim() || (terminated ? 'Просмотр Git остановлен.' : 'Git недоступен.'))); return; }
      const nextOffset = offset + text.length < total ? offset + text.length : null;
      resolveResult({ text, totalCharacters: total, nextOffset,
        note: nextOffset !== null ? `Вывод усечён. Продолжите с offset=${nextOffset}.` : '' });
    });
  });
}

export default function (pi) {
  pi.registerTool({
    name: 'planning_git', label: 'Просмотр изменений Git',
    description: 'Read-only Git status or diff in the project. For diff, staged=false shows working-tree changes and staged=true shows index changes. Read untracked files with read. Paginate using nextOffset; no commands or arbitrary Git options are accepted.',
    parameters: { type: 'object', additionalProperties: false, required: ['action'], properties: {
      action: { type: 'string', enum: ['status', 'diff'] }, staged: { type: 'boolean' }, path: { type: 'string' },
      offset: { type: 'integer', minimum: 0 }, limit: { type: 'integer', minimum: 1, maximum: 64000 }
    } },
    async execute(_id, args, signal, _onUpdate, ctx) {
      const result = await planningGit(ctx.cwd, args, signal);
      return { content: [{ type: 'text', text: result.text + (result.note ? '\n' + result.note : '') }], details: result };
    }
  });
  // Even a fabricated call to a tool absent from the advertised schema cannot execute it.
  pi.on('tool_call', event => planningTools.includes(event.toolName) ? undefined :
    { block: true, reason: 'При планировании доступны только чтение, поиск и просмотр Git.' });
}
