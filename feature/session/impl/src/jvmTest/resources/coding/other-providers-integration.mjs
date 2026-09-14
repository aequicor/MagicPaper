import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createBridge } from '../../../jvmMain/resources/coding/provider-bridge.mjs';
const { streamSimple } = await import(process.env.MAGICPAPER_PI_AI + 'compat.js');
for (const provider of ['anthropic', 'google', 'openrouter']) {
  const requests = [];
  const upstream = http.createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw); requests.push(body);
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const data = value => res.write('data: ' + JSON.stringify(value) + '\n\n');
    const event = value => { res.write('event: ' + value.type + '\n'); data(value); };
    if (provider === 'anthropic') {
      event({ type: 'message_start', message: { id: 'm1', type: 'message', role: 'assistant', content: [], model: 'fixture', stop_reason: null, stop_sequence: null, usage: { input_tokens: 10, output_tokens: 0 } } });
      event({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'c1', name: 'echo', input: {} } });
      event({ type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{"text":"ok"}' } });
      event({ type: 'content_block_stop', index: 0 }); event({ type: 'message_delta', delta: { stop_reason: 'tool_use' }, usage: { output_tokens: 4 } }); event({ type: 'message_stop' });
    } else if (provider === 'google') {
      data({ candidates: [{ content: { role: 'model', parts: [{ functionCall: { name: 'echo', args: { text: 'ok' } } }] }, finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 4, totalTokenCount: 14 } });
    } else {
      data({ id: 'm1', choices: [{ index: 0, delta: { role: 'assistant', tool_calls: [{ index: 0, id: 'c1', type: 'function', function: { name: 'echo', arguments: '{"text":"ok"}' } }] }, finish_reason: null }] });
      data({ id: 'm1', choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }] }); res.write('data: [DONE]\n\n');
    }
    res.end();
  });
  upstream.listen(0, '127.0.0.1'); await once(upstream, 'listening');
  const api = { anthropic: 'anthropic-messages', google: 'google-generative-ai', openrouter: 'openai-completions' }[provider];
  const parameters = provider === 'google' ? { temperature: 0.2, topP: 0.8, maxOutputTokens: 4096 } : { temperature: 0.2, top_p: 0.8, max_tokens: 4096 };
  const bridge = createBridge({ key: 'fixture', apiKey: 'fixture', parameters, model: { id: 'fixture', name: 'Fixture', api, provider, baseUrl: 'http://127.0.0.1:' + upstream.address().port,
    reasoning: false, input: ['text'], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096 } }, streamSimple);
  bridge.server.listen(0, '127.0.0.1'); await once(bridge.server, 'listening');
  try {
    const response = await fetch('http://127.0.0.1:' + bridge.server.address().port + '/responses', { method: 'POST', headers: { Authorization: 'Bearer fixture' },
      body: JSON.stringify({ instructions: 'Test', input: 'Echo ok', tools: [{ type: 'function', name: 'echo', parameters: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } }] }) });
    const text = await response.text(); assert.ok(text.includes('response.completed'), text);
    assert.ok(text.includes('function_call'), text); assert.ok(text.includes('echo'), text);
    const parametersSent = provider === 'google' ? requests[0].generationConfig : requests[0];
    assert.equal(parametersSent.temperature, 0.2); assert.equal(parametersSent[provider === 'google' ? 'topP' : 'top_p'], 0.8);
    console.log('PASS:', provider, 'native API, tools and generation settings');
  } finally { bridge.close(); upstream.closeAllConnections(); upstream.close(); }
}
