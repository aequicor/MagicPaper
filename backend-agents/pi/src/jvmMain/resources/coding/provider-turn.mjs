import { pathToFileURL } from 'node:url';
import { realpathSync } from 'node:fs';

// Pinned pi-ai's provider API only. No agent runtime or tool implementation is imported.
export async function providerTurn(config, streamSimple) {
  const assistant = await streamSimple(config.model, config.context, {
    apiKey: config.accessToken,
    transport: 'sse',
    maxTokens: config.model.maxTokens,
    reasoning: config.reasoning,
    maxRetries: 0,
  }).result();
  if (assistant.stopReason === 'error' || assistant.stopReason === 'aborted') {
    // Provider failures may embed credentials and request contents. Only a stable code crosses stdout.
    return { type: 'failure', code: 'PROVIDER_FAILED', usage: assistant.usage };
  }
  return { type: 'result', assistant };
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  try {
    let input = '';
    for await (const chunk of process.stdin) {
      input += chunk;
      if (input.length > 32 * 1024 * 1024) throw new Error('INPUT_TOO_LARGE');
    }
    const config = JSON.parse(input);
    const library = pathToFileURL(config.libraryPath.replace(/\/$/, '') + '/api/openai-codex-responses.js').href;
    const { streamSimple } = await import(library);
    const result = await providerTurn(config, streamSimple);
    process.stdout.write(JSON.stringify(result) + '\n');
  } catch (_) {
    process.stdout.write(JSON.stringify({ type: 'failure', code: 'TRANSPORT_FAILED' }) + '\n');
    process.exitCode = 1;
  }
}
