package io.aequicor.magicpaper.data.research

import kotlinx.serialization.json.JsonPrimitive

internal object PiResearchExtension {
    val source = """
        import { randomUUID } from 'node:crypto';
        export default function(pi) {
          const url = process.env.MAGICPAPER_RESEARCH_URL;
          const token = process.env.MAGICPAPER_RESEARCH_TOKEN;
          if (!url || !token) return;
          const headers = {'Content-Type':'application/json', 'Accept':'application/json, text/event-stream',
            'MCP-Protocol-Version':'2025-06-18', 'Authorization':'Bearer ' + token};
          pi.registerTool({name:'research_check', label:'Защищённая проверка',
            description:${JsonPrimitive(ResearchCheckTool.instructions)}, parameters:${ResearchCheckTool.schema},
            async execute(callId, args, signal) {
              const id = randomUUID() + ':' + callId;
              const cancel = () => fetch(url, {method:'POST',headers,signal:AbortSignal.timeout(2000),
                body:JSON.stringify({jsonrpc:'2.0',method:'notifications/cancelled',params:{requestId:id}})}).catch(() => {});
              signal?.throwIfAborted();
              signal?.addEventListener('abort',cancel,{once:true});
              try {
                const response = await fetch(url, {method:'POST',headers,signal,
                  body:JSON.stringify({jsonrpc:'2.0',id,method:'tools/call',params:{name:'research_check',arguments:args}})});
                if (!response.ok) throw new Error('Research check connection failed: ' + response.status);
                const message = await response.json();
                if (message.error) throw new Error(message.error.message);
                return {content:message.result.content,details:{}};
              } catch (error) { cancel(); throw error; }
              finally { signal?.removeEventListener('abort',cancel); }
            }
          });
        }
    """.trimIndent()
}
