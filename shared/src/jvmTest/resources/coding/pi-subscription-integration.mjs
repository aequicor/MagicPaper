// Real pi agent and native subscription transport, with synthetic tokens and local model servers.
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { once } from 'node:events';
import { createBridge } from '../../../jvmMain/resources/coding/provider-bridge.mjs';
const library = process.env.MAGICPAPER_PI_AI;
const { streamSimple } = await import(library + 'compat.js');
const root = await mkdtemp(join(tmpdir(), 'magicpaper-pi-subscription-'));
const token = 'test.' + Buffer.from(JSON.stringify({ 'https://api.openai.com/auth': { chatgpt_account_id: 'fixture' } })).toString('base64url') + '.test';
let tokenCalls = 0; let modelCalls = 0;
const auth = http.createServer((req, res) => { assert.equal(req.headers.authorization, 'Bearer broker-fixture'); tokenCalls++; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ token })); });
auth.listen(0, '127.0.0.1'); await once(auth, 'listening');
const upstream = http.createServer(async (req, res) => {
  let text = ''; for await (const chunk of req) text += chunk;
  const request = JSON.parse(text); modelCalls++;
  res.writeHead(200, { 'Content-Type': 'text/event-stream' });
  const send = (delta, finish_reason) => res.write('data: ' + JSON.stringify({ id: 'fixture', object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason }] }) + '\n\n');
  if (!request.messages.some(m => m.role === 'tool')) {
    send({ role: 'assistant', tool_calls: [{ index: 0, id: 'call_read', type: 'function', function: { name: 'read', arguments: JSON.stringify({ path: join(root, 'fixture.txt') }) } }] }, null); send({}, 'tool_calls');
  } else { send({ role: 'assistant', content: 'Pi subscription verified.' }, null); send({}, 'stop'); }
  res.end('data: [DONE]\n\n');
});
upstream.listen(0, '127.0.0.1'); await once(upstream, 'listening');
const model = { id: 'fixture-gpt', name: 'Fixture', api: 'openai-completions', provider: 'magicpaper', baseUrl: 'http://127.0.0.1:' + upstream.address().port + '/v1',
  reasoning: false, input: ['text'], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096 };
const bridge = createBridge({ key: token, apiKey: 'fixture', model }, streamSimple);
bridge.server.listen(0, '127.0.0.1'); await once(bridge.server, 'listening');
let pi;
try {
  await mkdir(join(root, 'adapter/api'), { recursive: true }); await mkdir(join(root, 'adapter/utils'), { recursive: true });
  await writeFile(join(root, 'adapter/package.json'), '{"type":"module"}');
  await writeFile(join(root, 'adapter/api/openai-codex-responses.js'), `import { streamSimple as native } from ${JSON.stringify(library + 'api/openai-codex-responses.js')}; export const streamSimple = (model, context, options) => native({ ...model, baseUrl: ${JSON.stringify('http://127.0.0.1:' + bridge.server.address().port)} }, context, { ...options, transport: 'sse', fetch: (url, init) => fetch(new URL('/responses', url), init) });`);
  await writeFile(join(root, 'adapter/utils/event-stream.js'), `export * from ${JSON.stringify(library + 'utils/event-stream.js')};`);
  await writeFile(join(root, 'models.json'), JSON.stringify({ providers: { magicpaper: { api: 'openai-codex-responses', apiKey: 'managed-by-magicpaper', baseUrl: 'https://chatgpt.com/backend-api', models: [{ ...model, api: 'openai-codex-responses' }] } } }));
  await writeFile(join(root, 'settings.json'), '{"defaultProjectTrust":"never","telemetry":false}');
  await writeFile(join(root, 'fixture.txt'), 'fixture content');
  const extension = new URL('../../../jvmMain/resources/coding/subscription-provider.mjs', import.meta.url).pathname;
  pi = spawn(process.execPath, [process.env.MAGICPAPER_PI_CLI, '--mode', 'json', '--provider', 'magicpaper', '--model', 'fixture-gpt', '--session-dir', join(root, 'sessions'), '--no-extensions', '--no-skills', '--no-prompt-templates', '--no-themes', '--no-approve', '--extension', extension], {
    cwd: root, env: { ...process.env, PI_CODING_AGENT_DIR: root, PI_OFFLINE: '1', PI_SKIP_VERSION_CHECK: '1', PI_TELEMETRY: '0',
      MAGICPAPER_PI_AI: pathToFileURL(join(root, 'adapter/')).href, MAGICPAPER_TOKEN_URL: 'http://127.0.0.1:' + auth.address().port + '/token', MAGICPAPER_TOKEN_KEY: 'broker-fixture' }, stdio: ['pipe', 'pipe', 'pipe'] });
  let stdout = '', stderr = ''; pi.stdout.on('data', c => stdout += c); pi.stderr.on('data', c => stderr += c);
  const timer = setTimeout(() => pi.kill('SIGKILL'), 30000);
  pi.stdin.end('Read fixture.txt and report the result.');
  const [code] = await once(pi, 'exit'); clearTimeout(timer);
  assert.equal(code, 0, stderr + stdout.slice(-2000));
  assert.ok(stdout.includes('Pi subscription verified.'), stderr + stdout.slice(-2500));
  assert.ok(tokenCalls >= 2, 'a fresh access token is requested for each model call');
  assert.ok(modelCalls >= 2, 'pi executed the read tool and returned its result');
  assert.ok(!stdout.includes(token), 'credentials never enter the event log');
  console.log('PASS: real pi, native subscription transport, tool execution, token refresh per model request. Calls:', tokenCalls);
} finally { pi?.kill('SIGKILL'); bridge.close(); auth.closeAllConnections(); auth.close(); upstream.closeAllConnections(); upstream.close(); await rm(root, { recursive: true, force: true }); }
