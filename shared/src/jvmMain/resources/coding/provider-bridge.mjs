import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import readline from 'node:readline';
import * as zlib from 'node:zlib';

const usageZero = () => ({ input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } });
const id = prefix => prefix + randomUUID().replaceAll('-', '');

export function toolCatalog(tools, namespace) {
  return (tools || []).flatMap(tool => {
    if (tool.type === 'namespace') return toolCatalog(tool.tools, tool.name);
    if (!['function', 'custom'].includes(tool.type)) throw new Error('Неподдерживаемый инструмент Codex: ' + tool.type);
    return [{ ...tool, namespace, bridgeName: namespace ? namespace + '__' + tool.name : tool.name }];
  });
}
export function contextFromResponses(body, model) {
  const context = { systemPrompt: body.instructions || '', messages: [], tools: [] };
  const toolNames = new Map();
  for (const tool of toolCatalog(body.tools)) {
    if (tool.type !== 'function' && tool.type !== 'custom') throw new Error('Неподдерживаемый инструмент Codex: ' + tool.type);
    context.tools.push({ name: tool.bridgeName, description: tool.description || '', parameters: tool.type === 'custom'
      ? { type: 'object', properties: { input: { type: 'string' } }, required: ['input'] }
      : tool.parameters || { type: 'object', properties: {} } });
  }
  function content(parts) {
    if (typeof parts === 'string') return [{ type: 'text', text: parts }];
    return (parts || []).map(part => {
      if (part.type === 'input_text' || part.type === 'output_text' || part.type === 'text') return { type: 'text', text: part.text || '' };
      if (part.type === 'input_image') {
        const url = typeof part.image_url === 'string' ? part.image_url : part.image_url?.url;
        const match = /^data:([^;]+);base64,(.+)$/s.exec(url || '');
        if (!match) throw new Error('Адаптер поддерживает изображения, приложенные к сессии.');
        return { type: 'image', mimeType: match[1], data: match[2] };
      }
      throw new Error('Неподдерживаемый тип содержимого Codex: ' + part.type);
    });
  }
  const input = typeof body.input === 'string' ? [{ role: 'user', content: body.input }] : body.input || [];
  for (const item of input) {
    if (item.type === 'reasoning') continue; // Encrypted OpenAI reasoning cannot be forwarded to another provider.
    if (item.type === 'function_call' || item.type === 'custom_tool_call') {
      const arguments_ = item.type === 'custom_tool_call' ? { input: item.input } : JSON.parse(item.arguments || '{}');
      const name = item.namespace ? item.namespace + '__' + item.name : item.name;
      toolNames.set(item.call_id, name);
      const call = { type: 'toolCall', id: item.call_id, name, arguments: arguments_ };
      const last = context.messages.at(-1);
      if (last?.role === 'assistant') { last.content.push(call); last.stopReason = 'toolUse'; }
      else context.messages.push({ role: 'assistant', content: [call], stopReason: 'toolUse', api: model.api,
        provider: model.provider, model: model.id, usage: usageZero(), timestamp: Date.now() });
    } else if (item.type === 'function_call_output' || item.type === 'custom_tool_call_output') {
      context.messages.push({ role: 'toolResult', toolCallId: item.call_id, toolName: toolNames.get(item.call_id) || 'tool',
        content: content(item.output), isError: false, timestamp: Date.now() });
    } else if (!item.type || item.type === 'message') {
      if (item.role === 'system' || item.role === 'developer') {
        context.systemPrompt += '\n\n' + content(item.content).map(p => p.text || '').join('\n');
      } else if (item.role === 'assistant') {
        context.messages.push({ role: 'assistant', content: content(item.content), stopReason: 'stop', api: model.api,
          provider: model.provider, model: model.id, usage: usageZero(), timestamp: Date.now() });
      } else context.messages.push({ role: 'user', content: content(item.content), timestamp: Date.now() });
    } else throw new Error('Неподдерживаемый элемент истории Codex: ' + item.type);
  }
  return context;
}

export function createBridge(config, streamSimple) {
  const controllers = new Set();
  const server = http.createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer ' + config.key || req.headers.origin) { res.writeHead(403).end(); return; }
    if (req.method !== 'POST' || !['/responses', '/responses/compact'].includes(req.url)) { res.writeHead(404).end(); return; }
    const abort = new AbortController(); controllers.add(abort);
    res.on('error', () => abort.abort());
    res.on('close', () => { abort.abort(); controllers.delete(abort); });
    let heartbeat;
    let sequence = 0;
    const emit = (type, data) => res.write('event: ' + type + '\ndata: ' + JSON.stringify({ type, sequence_number: sequence++, ...data }) + '\n\n');
    const responseId = id('resp_');
    const response = { id: responseId, object: 'response', created_at: Math.floor(Date.now() / 1000), model: config.model.id,
      status: 'in_progress', output: [], error: null };
    const safeError = error => String(error?.message || error).replaceAll(config.apiKey || '\0', '[скрыто]').slice(0, 1500);
    try {
      let size = 0; const chunks = [];
      for await (const chunk of req) { size += chunk.length; if (size > 32 * 1024 * 1024) throw new Error('Слишком большой запрос'); chunks.push(chunk); }
      let bytes = Buffer.concat(chunks);
      const encoding = req.headers['content-encoding'];
      if (encoding && encoding !== 'identity') {
        const decompress = { gzip: zlib.gunzipSync, deflate: zlib.inflateSync, br: zlib.brotliDecompressSync, zstd: zlib.zstdDecompressSync }[encoding];
        if (!decompress) throw new Error('Неподдерживаемое сжатие запроса: ' + encoding);
        bytes = decompress(bytes, { maxOutputLength: 32 * 1024 * 1024 });
      }
      const body = JSON.parse(bytes.toString('utf8'));
      if (body.previous_response_id) throw new Error('Адаптеру нужна полная история запроса.');
      const context = contextFromResponses(body, config.model);
      const parameters = config.parameters || {};
      const options = { apiKey: config.apiKey, signal: abort.signal, maxTokens: config.model.maxTokens,
        reasoning: config.reasoning, temperature: parameters.temperature, transport: 'sse',
        onPayload(payload) {
          if (config.model.api === 'google-generative-ai') return { ...payload, config: { ...payload.config, ...parameters } };
          return { ...payload, ...parameters };
        } };
      if (req.url.endsWith('/compact')) {
        context.systemPrompt += '\nSummarize the conversation for another coding agent to continue. Preserve user requirements, tool results, pending work and file paths. Do not perform actions.';
        context.tools = [];
        context.messages.push({ role: 'user', content: 'Write the continuation summary now.', timestamp: Date.now() });
        const final = await streamSimple(config.model, context, options).result();
        if (final.stopReason === 'error' || final.stopReason === 'aborted') throw new Error(final.errorMessage || 'Сводка не создана');
        const summary = final.content.filter(p => p.type === 'text').map(p => p.text).join('\n');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ output: [{ type: 'message', role: 'user', content: [{ type: 'input_text', text: summary }] }] })); return;
      }
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      emit('response.created', { response }); emit('response.in_progress', { response });
      heartbeat = setInterval(() => res.write(': keepalive\n\n'), 10000);
      const entries = new Map();
      const catalog = toolCatalog(body.tools);
      const customTools = new Set(catalog.filter(t => t.type === 'custom').map(t => t.bridgeName));
      function ensure(index, part) {
        if (entries.has(index)) return entries.get(index);
        const tool = catalog.find(t => t.bridgeName === part.name);
        const item = part.type === 'text'
          ? { id: id('msg_'), type: 'message', role: 'assistant', status: 'in_progress', content: [{ type: 'output_text', text: '', annotations: [] }] }
          : part.type === 'thinking' ? { id: id('rs_'), type: 'reasoning', summary: [{ type: 'summary_text', text: '' }] }
          : { id: id('fc_'), type: customTools.has(part.name) ? 'custom_tool_call' : 'function_call', call_id: part.id,
            name: tool?.name || part.name, ...(tool?.namespace ? { namespace: tool.namespace } : {}), status: 'in_progress', ...(customTools.has(part.name) ? { input: '' } : { arguments: '' }) };
        const entry = { item, index: response.output.length };
        response.output.push(item); entries.set(index, entry);
        emit('response.output_item.added', { output_index: entry.index, item });
        if (part.type === 'text') emit('response.content_part.added', { item_id: item.id, output_index: entry.index, content_index: 0, part: item.content[0] });
        if (part.type === 'thinking') emit('response.reasoning_summary_part.added', { item_id: item.id, output_index: entry.index, summary_index: 0, part: item.summary[0] });
        return entry;
      }
      const upstream = streamSimple(config.model, context, options);
      for await (const event of upstream) {
        if (abort.signal.aborted) break;
        if (event.type === 'error') throw new Error(event.error.errorMessage || 'Ошибка провайдера');
        const part = event.partial?.content?.[event.contentIndex];
        if (!part || (part.type === 'toolCall' && !part.name)) continue;
        const entry = ensure(event.contentIndex, part);
        const common = { item_id: entry.item.id, output_index: entry.index };
        if (event.type === 'text_delta') {
          entry.item.content[0].text += event.delta;
          emit('response.output_text.delta', { ...common, content_index: 0, delta: event.delta });
        } else if (event.type === 'thinking_delta') {
          entry.item.summary[0].text += event.delta;
          emit('response.reasoning_summary_text.delta', { ...common, summary_index: 0, delta: event.delta });
        } else if (event.type === 'toolcall_delta' && entry.item.type === 'function_call') {
          entry.item.arguments += event.delta;
          emit('response.function_call_arguments.delta', { ...common, delta: event.delta });
        }
      }
      const final = await upstream.result();
      if (final.stopReason === 'error' || final.stopReason === 'aborted') throw new Error(final.errorMessage || 'Запрос остановлен');
      if (final.stopReason === 'length') throw new Error('Ответ провайдера обрезан лимитом токенов. Увеличьте лимит модели.');
      final.content.forEach((part, index) => {
        const entry = ensure(index, part); const item = entry.item;
        const common = { item_id: item.id, output_index: entry.index };
        if (part.type === 'text') {
          item.content[0].text = part.text; item.status = 'completed';
          emit('response.output_text.done', { ...common, content_index: 0, text: part.text });
          emit('response.content_part.done', { ...common, content_index: 0, part: item.content[0] });
        } else if (part.type === 'thinking') {
          item.summary[0].text = part.thinking;
          emit('response.reasoning_summary_text.done', { ...common, summary_index: 0, text: part.thinking });
          emit('response.reasoning_summary_part.done', { ...common, summary_index: 0, part: item.summary[0] });
        } else {
          const tool = catalog.find(t => t.bridgeName === part.name);
          item.name = tool?.name || part.name; item.call_id = part.id;
          if (tool?.namespace) item.namespace = tool.namespace;
          item.status = 'completed';
          if (item.type === 'custom_tool_call') {
            item.input = part.arguments.input || '';
            emit('response.custom_tool_call_input.delta', { ...common, delta: item.input });
            emit('response.custom_tool_call_input.done', { ...common, input: item.input });
          } else {
            item.arguments = JSON.stringify(part.arguments);
            emit('response.function_call_arguments.done', { ...common, arguments: item.arguments });
          }
        }
        emit('response.output_item.done', { output_index: entry.index, item });
      });
      response.status = 'completed';
      response.usage = { input_tokens: (final.usage.input || 0) + (final.usage.cacheRead || 0), output_tokens: final.usage.output || 0,
        total_tokens: final.usage.totalTokens || 0, input_tokens_details: { cached_tokens: final.usage.cacheRead || 0 } };
      emit('response.completed', { response }); res.end();
    } catch (error) {
      if (res.destroyed) return;
      if (!res.headersSent) res.writeHead(400, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: { message: safeError(error) } }));
      else { response.status = 'failed'; response.error = { code: 'provider_error', message: safeError(error) }; emit('response.failed', { response }); res.end(); }
    } finally { clearInterval(heartbeat); controllers.delete(abort); }
  });
  const close = () => { for (const controller of controllers) controller.abort(); server.closeAllConnections(); server.close(); };
  return { server, close };
}

async function main() {
  const reader = readline.createInterface({ input: process.stdin });
  const config = JSON.parse(await new Promise(resolve => reader.once('line', resolve)));
  const { streamSimple } = await import(process.env.MAGICPAPER_PI_AI + 'compat.js');
  const bridge = createBridge(config, streamSimple);
  process.stdin.on('end', bridge.close);
  process.on('SIGTERM', () => { bridge.close(); process.exit(0); });
  bridge.server.listen(0, '127.0.0.1', () => process.stdout.write(JSON.stringify({ port: bridge.server.address().port }) + '\n'));
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main().catch(() => { process.stderr.write('Не удалось запустить адаптер провайдера.\n'); process.exit(1); });
