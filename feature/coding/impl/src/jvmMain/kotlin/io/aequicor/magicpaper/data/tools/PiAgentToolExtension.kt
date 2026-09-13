package io.aequicor.magicpaper.data.tools

import io.aequicor.magicpaper.domain.tools.ToolSession
import kotlinx.serialization.json.JsonArray


internal object PiAgentToolExtension {
    fun source(session: ToolSession) = """
        export default function(pi) {
          const url = process.env.MAGICPAPER_AGENT_TOOLS_URL;
          const token = process.env.MAGICPAPER_AGENT_TOOLS_TOKEN;
          if (!url || !token) return;
          delete process.env.MAGICPAPER_AGENT_TOOLS_URL;
          delete process.env.MAGICPAPER_AGENT_TOOLS_TOKEN;
          const headers = {'Content-Type':'application/json', 'Accept':'application/json, text/event-stream',
            'MCP-Protocol-Version':'2025-06-18', 'Authorization':'Bearer ' + token};
          const definitions = ${JsonArray(session.definitions.map { it.protocolDefinition() })};
          for (const definition of definitions) pi.registerTool({name:definition.name, label:definition.description,
            description:definition.description, parameters:definition.inputSchema,
            async execute(callId, args, signal) {
              const id = callId;
              const cancel = () => fetch(url, {method:'POST',headers,signal:AbortSignal.timeout(2000),
                body:JSON.stringify({jsonrpc:'2.0',method:'notifications/cancelled',params:{requestId:id}})}).catch(() => {});
              signal?.throwIfAborted();
              signal?.addEventListener('abort',cancel,{once:true});
              try {
                const response = await fetch(url, {method:'POST',headers,signal,
                  body:JSON.stringify({jsonrpc:'2.0',id,method:'tools/call',params:{name:definition.name,arguments:args}})});
                if (!response.ok) throw new Error('Agent tools connection failed: ' + response.status);
                const message = await response.json();
                if (message.error) throw new Error(message.error.message);
                if (message.result.isError) throw new Error(message.result.content.map(c => c.text || '').join('\n'));
                return {content:message.result.content,details:{}};
              } catch (error) { cancel(); throw error; }
              finally { signal?.removeEventListener('abort',cancel); }
            }
          });
        }
    """.trimIndent()
}
