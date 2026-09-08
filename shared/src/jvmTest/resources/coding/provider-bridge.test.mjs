import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createBridge, contextFromResponses } from '../../../jvmMain/resources/coding/provider-bridge.mjs';
const model = { api: 'openai-completions', provider: 'magicpaper', id: 'fixture' };
const usage = { input: 10, output: 3, cacheRead: 0, totalTokens: 13 };
function stream(final) {
  return { async *[Symbol.asyncIterator]() {
    yield { type: 'text_delta', contentIndex: 0, delta: 'Hello', partial: { content: [{ type: 'text', text: 'Hello' }] } };
  }, result: async () => final };
}
async function withBridge(run, final = { content: [{ type: 'text', text: 'Hello' }], usage, stopReason: 'stop' }) {
  let captured;
  const bridge = createBridge({ key: 'local-key', apiKey: 'private-key', model }, (m, ctx) => { captured = ctx; return stream(final); });
  bridge.server.listen(0, '127.0.0.1'); await once(bridge.server, 'listening');
  try { await run('http://127.0.0.1:' + bridge.server.address().port, () => captured); } finally { bridge.close(); }
}
test('preserves instructions, images, custom tools and their results', () => {
  const ctx = contextFromResponses({ instructions: 'System', tools: [{ type: 'custom', name: 'apply_patch' }], input: [
    { role: 'developer', content: [{ type: 'input_text', text: 'Rules' }] },
    { role: 'user', content: [{ type: 'input_image', image_url: 'data:image/png;base64,YQ==' }] },
    { type: 'custom_tool_call', call_id: 'call_1', name: 'apply_patch', input: 'patch' },
    { type: 'custom_tool_call_output', call_id: 'call_1', output: 'ok' },
  ] }, model);
  assert.match(ctx.systemPrompt, /System.*Rules/s);
  assert.equal(ctx.messages[0].content[0].mimeType, 'image/png');
  assert.deepEqual(ctx.messages[1].content[0].arguments, { input: 'patch' });
  assert.equal(ctx.messages[2].toolName, 'apply_patch');
  assert.equal(ctx.messages[2].content[0].text, 'ok');
});
test('rejects missing auth and browser origins', async () => withBridge(async url => {
  assert.equal((await fetch(url + '/responses', { method: 'POST' })).status, 403);
  assert.equal((await fetch(url + '/responses', { method: 'POST', headers: { Authorization: 'Bearer local-key', Origin: 'https://example.com' } })).status, 403);
}));
test('emits streamed text, final output and usage', async () => withBridge(async (url, captured) => {
  const result = await fetch(url + '/responses', { method: 'POST', headers: { Authorization: 'Bearer local-key' }, body: JSON.stringify({ input: 'Hi' }) });
  const text = await result.text();
  assert.match(text, /response.output_text.delta/); assert.match(text, /response.completed/);
  assert.match(text, /"input_tokens":10/); assert.equal(captured().messages[0].content[0].text, 'Hi');
}));
test('returns executable custom tool output', async () => withBridge(async url => {
  const result = await fetch(url + '/responses', { method: 'POST', headers: { Authorization: 'Bearer local-key' }, body: JSON.stringify({ input: 'edit', tools: [{ type: 'custom', name: 'apply_patch' }] }) });
  const text = await result.text(); assert.match(text, /custom_tool_call/); assert.match(text, /"input":"patch"/);
}, { content: [{ type: 'text', text: 'Hello' }, { type: 'toolCall', id: 'c1', name: 'apply_patch', arguments: { input: 'patch' } }], usage, stopReason: 'toolUse' }));
test('truncation fails instead of claiming successful completion', async () => withBridge(async url => {
  const result = await fetch(url + '/responses', { method: 'POST', headers: { Authorization: 'Bearer local-key' }, body: JSON.stringify({ input: 'Hi' }) });
  const text = await result.text(); assert.match(text, /response.failed/); assert.doesNotMatch(text, /response.completed/);
}, { content: [], usage, stopReason: 'length' }));
