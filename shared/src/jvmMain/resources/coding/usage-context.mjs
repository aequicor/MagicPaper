import { randomUUID } from 'node:crypto';
import { writeSync } from 'node:fs';

// pi redirects extension stdout to stderr in JSON mode. Use the JSON stream's descriptor.
const emit = event => writeSync(1, JSON.stringify(event) + '\n');

/** Only metrics go to the JSON event stream; never prompts, headers or provider payloads. */
export default function usageContext(pi) {
  function context(ctx) {
    const usage = ctx.getContextUsage();
    emit({ type: 'magicpaper_context', tokens: usage?.tokens ?? null,
      contextWindow: usage?.contextWindow ?? ctx.model?.contextWindow ?? null, approximate: true });
  }
  pi.on('before_provider_request', (_, ctx) => {
    context(ctx);
    emit({ type: 'magicpaper_request', id: randomUUID() });
  });
  for (const event of ['session_start', 'message_end', 'turn_end', 'session_compact', 'session_compact_failed']) {
    pi.on(event, (_, ctx) => { context(ctx); });
  }
}
