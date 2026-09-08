// A transport adapter only. The pi agent continues to own tools and its session.
export default async function(pi) {
  const tokenUrl = process.env.MAGICPAPER_TOKEN_URL;
  const tokenKey = process.env.MAGICPAPER_TOKEN_KEY;
  // Tool subprocesses do not inherit credentials for the access-token broker.
  delete process.env.MAGICPAPER_TOKEN_URL;
  delete process.env.MAGICPAPER_TOKEN_KEY;
  const { streamSimple: nativeStream } = await import(process.env.MAGICPAPER_PI_AI + 'api/openai-codex-responses.js');
  const { createAssistantMessageEventStream } = await import(process.env.MAGICPAPER_PI_AI + 'utils/event-stream.js');
  pi.registerProvider('magicpaper', {
    api: 'openai-codex-responses',
    streamSimple(model, context, options) {
      const output = createAssistantMessageEventStream();
      (async () => {
        try {
          const response = await fetch(tokenUrl, {
            method: 'POST', headers: { Authorization: 'Bearer ' + tokenKey },
            signal: options?.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(35000)]) : AbortSignal.timeout(35000),
          });
          if (!response.ok) throw new Error('Войдите в ChatGPT в настройках MagicPaper.');
          const { token } = await response.json();
          for await (const event of nativeStream(model, context, { ...options, apiKey: token })) output.push(event);
          output.end();
        } catch (error) {
          const message = { role: 'assistant', content: [], api: model.api, provider: model.provider, model: model.id,
            usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
            stopReason: options?.signal?.aborted ? 'aborted' : 'error',
            errorMessage: 'Не удалось получить доступ к подписке ChatGPT. Проверьте вход в настройках MagicPaper.', timestamp: Date.now() };
          output.push({ type: 'error', reason: message.stopReason, error: message }); output.end(message);
        }
      })();
      return output;
    },
  });
}
