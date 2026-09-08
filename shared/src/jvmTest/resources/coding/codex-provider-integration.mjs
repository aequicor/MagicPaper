// Local integration: real Codex + real provider library + fake model. No account or paid API.
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import readline from 'node:readline';
import { createBridge } from '../../../jvmMain/resources/coding/provider-bridge.mjs';
const { streamSimple } = await import(process.env.MAGICPAPER_PI_AI + 'compat.js');
const root = await mkdtemp(join(tmpdir(), 'magicpaper-codex-provider-'));
const requests = [];
const upstream = http.createServer(async (req, res) => {
  let raw = ''; for await (const chunk of req) raw += chunk;
  const body = JSON.parse(raw); requests.push(body);
  const tool = body.tools?.find(t => /exec_command|shell_command|shell/.test(t.function?.name));
  const hasResult = body.messages.some(m => m.role === 'tool');
  res.writeHead(200, { 'Content-Type': 'text/event-stream' });
  const send = (delta, reason) => res.write('data: ' + JSON.stringify({ id: 'test', object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason: reason }] }) + '\n\n');
  if (tool && !hasResult) {
    const keys = Object.keys(tool.function.parameters.properties || {});
    const args = keys.includes('cmd') ? { cmd: 'printf bridge-ok' } : keys.includes('command') ? { command: keys.includes('shell') ? ['sh', '-c', 'printf bridge-ok'] : 'printf bridge-ok' } : {};
    send({ role: 'assistant', tool_calls: [{ index: 0, id: 'call_fixture', type: 'function', function: { name: tool.function.name, arguments: JSON.stringify(args) } }] }, null);
    send({}, 'tool_calls');
  } else { send({ role: 'assistant', content: 'Provider bridge verified.' }, null); send({}, 'stop'); }
  res.end('data: [DONE]\n\n');
});
upstream.listen(0, '127.0.0.1'); await once(upstream, 'listening');
const bridges = [];
async function bridgeConfig() {
  const bridge = createBridge({ key: 'fixture-local', apiKey: 'fixture', model: { id: 'fixture-model', name: 'Fixture', api: 'openai-completions', provider: 'magicpaper',
    baseUrl: 'http://127.0.0.1:' + upstream.address().port + '/v1', reasoning: false, input: ['text'], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096 } }, streamSimple);
  bridges.push(bridge); bridge.server.listen(0, '127.0.0.1'); await once(bridge.server, 'listening');
  return { 'model_provider': 'magicpaper-api', 'model_providers.magicpaper-api': { name: 'Fixture', base_url: 'http://127.0.0.1:' + bridge.server.address().port,
    wire_api: 'responses', experimental_bearer_token: 'fixture-local', requires_openai_auth: false, supports_websockets: false }, web_search: 'disabled' };
}
let codex;
const pending = new Map(); let nextId = 0; const events = []; let stderr = '';
function startCodex() {
  codex = spawn(process.env.MAGICPAPER_CODEX_PATH, ['app-server'], { cwd: root, env: { ...process.env, CODEX_HOME: root, OPENAI_API_KEY: '', RUST_LOG: 'error' }, stdio: ['pipe', 'pipe', 'pipe'] });
  codex.stderr.on('data', c => stderr = (stderr + c).slice(-3000));
  readline.createInterface({ input: codex.stdout }).on('line', line => { const message = JSON.parse(line); if (message.id !== undefined && pending.has(message.id)) {
    const { resolve, reject } = pending.get(message.id); pending.delete(message.id); message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result);
  } else events.push(message); });
}
startCodex();
function rpc(method, params) { return new Promise((resolve, reject) => { const id = ++nextId; pending.set(id, { resolve, reject }); codex.stdin.write(JSON.stringify({ id, method, params }) + '\n'); }); }
const timeout = setTimeout(() => { console.error('Integration timed out', stderr, JSON.stringify(events.slice(-3))); codex.kill('SIGKILL'); process.exit(1); }, 50000);
try {
  await rpc('initialize', { clientInfo: { name: 'magicpaper-test', version: '1' }, capabilities: { experimentalApi: false } });
  codex.stdin.write('{"method":"initialized"}\n');
  let config = await bridgeConfig();
  const thread = await rpc('thread/start', { cwd: root, model: 'fixture-model', modelProvider: 'magicpaper-api', config, approvalPolicy: 'never', sandbox: 'read-only' });
  const threadId = thread.thread.id;
  for (let i = 0; i < 2; i++) {
    if (i) { codex.kill('SIGTERM'); await once(codex, 'exit'); startCodex(); await rpc('initialize', { clientInfo: { name: 'magicpaper-test', version: '1' } }); codex.stdin.write('{"method":"initialized"}\n'); bridges[0].close(); config = await bridgeConfig(); await rpc('thread/resume', { threadId, model: 'fixture-model', modelProvider: 'magicpaper-api', config, approvalPolicy: 'never', sandbox: 'read-only' }); }
    const turn = await rpc('turn/start', { threadId, input: [{ type: 'text', text: 'Run printf bridge-ok, then report.' }], model: 'fixture-model', approvalPolicy: 'never', sandboxPolicy: { type: 'readOnly' } });
    while (!events.some(e => e.method === 'turn/completed' && e.params?.turn?.id === turn.turn.id)) await new Promise(r => setTimeout(r, 30));
    const completed = events.find(e => e.method === 'turn/completed' && e.params?.turn?.id === turn.turn.id);
    assert.equal(completed.params.turn.status, 'completed', JSON.stringify(completed));
  }
  assert.ok(requests.length >= 3, 'tool result and resumed request reach the provider');
  assert.ok(requests.some(r => r.messages.some(m => m.role === 'tool')), 'tool output returns to model');
  console.log('PASS: real Codex, tool execution, provider bridge, resumed session with new connection. Requests:', requests.length);
} finally { clearTimeout(timeout); codex.kill('SIGKILL'); bridges.forEach(b => b.close()); upstream.closeAllConnections(); upstream.close(); await rm(root, { recursive: true, force: true }); }
