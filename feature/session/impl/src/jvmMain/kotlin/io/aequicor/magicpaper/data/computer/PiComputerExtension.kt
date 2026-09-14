package io.aequicor.magicpaper.data.computer

import kotlinx.serialization.json.JsonPrimitive

/** Only the schema is persisted; endpoint credentials are passed to the child in its environment. */
internal object PiComputerExtension {
    val source: String get() = """
        import { randomUUID } from 'node:crypto';
        export default function(pi) {
          const url = process.env.MAGICPAPER_COMPUTER_URL;
          const token = process.env.MAGICPAPER_COMPUTER_TOKEN;
          if (!url || !token) return;
          const prefix = randomUUID();
          let nextId = 0;
          let ready;
          const headers = {
            'Content-Type': 'application/json',
            'Accept': 'application/json, text/event-stream',
            'MCP-Protocol-Version': '2025-06-18',
            'Authorization': 'Bearer ' + token
          };
          pi.registerTool({
            name: 'computer', label: 'Экран и управление',
            description: ${JsonPrimitive(ComputerTool.instructions)},
            parameters: ${ComputerTool.schema},
            async execute(_callId, args, signal) {
              const id = prefix + '-' + (++nextId);
              const cancel = () => {
                fetch(url, { method: 'POST', headers, signal: AbortSignal.timeout(2000),
                  body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: id } })
                }).catch(() => {});
              };
              signal?.throwIfAborted();
              signal?.addEventListener('abort', cancel, { once: true });
              try {
                if (!ready) ready = (async () => {
                  const init = await fetch(url, { method: 'POST', headers, signal: AbortSignal.timeout(10000),
                    body: JSON.stringify({ jsonrpc: '2.0', id: prefix + '-init', method: 'initialize', params: {
                      protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'MagicPaper Pi', version: '1' }
                    } })
                  });
                  if (!init.ok || (await init.json()).error) throw new Error('Computer initialization failed. Check access in MagicPaper.');
                  const notification = await fetch(url, { method: 'POST', headers, signal: AbortSignal.timeout(2000),
                    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })
                  });
                  if (!notification.ok) throw new Error('Computer initialization was interrupted.');
                })();
                await ready;
                signal?.throwIfAborted();
                const response = await fetch(url, {
                  method: 'POST', headers,
                  signal: AbortSignal.any([AbortSignal.timeout(30000), ...(signal ? [signal] : [])]),
                  body: JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name: 'computer', arguments: args } })
                });
                if (!response.ok) throw new Error('Computer access unavailable (' + response.status + '). Check access in MagicPaper.');
                const message = await response.json();
                if (message.error) throw new Error(message.error.message);
                const result = message.result;
                if (result.isError) throw new Error(result.content.filter(x => x.type === 'text').map(x => x.text).join('\n'));
                return { content: result.content, details: {} };
              } catch (error) {
                cancel();
                throw error;
              } finally { signal?.removeEventListener('abort', cancel); }
            }
          });
        }
    """.trimIndent()
}
