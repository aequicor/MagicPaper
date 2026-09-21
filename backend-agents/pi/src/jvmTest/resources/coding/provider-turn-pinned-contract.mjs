import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';

const [library, adapter] = process.argv.slice(2);
assert.equal(JSON.parse(readFileSync(resolve(library, '../package.json'))).version, '0.84.4');
const {streamSimple} = await import(pathToFileURL(resolve(library, 'api/openai-codex-responses.js')));
const {providerTurn} = await import(pathToFileURL(adapter));
assert.equal(typeof streamSimple, 'function');
// Any accidental real HTTP request fails locally. Both responses below are immutable protocol fixtures.
globalThis.fetch = async () => { throw new Error('Live network is forbidden in this fixture'); };
const usage = {input_tokens:14,output_tokens:3,total_tokens:17,input_tokens_details:{cached_tokens:2}};
const reasoning = {type:'reasoning',id:'rs_fixture',summary:[],encrypted_content:'signed-fixture'};
const call = {type:'function_call',id:'fc_fixture',call_id:'call_fixture',name:'search',arguments:'{"query":"fixture"}'};
const answer = {type:'message',id:'msg_fixture',role:'assistant',content:[{type:'output_text',text:'Done',annotations:[]}]};
const responses = [[reasoning, call], [answer]];
const bodies = [];
let requests = 0;
const transport = (model, context, options) => streamSimple(model, context, {
  ...options,
  onPayload(body) { bodies.push(body); return body; },
  fetch: async () => {
    const items = responses[requests++];
    assert.ok(items, 'Unexpected provider request/retry');
    const events = items.map((item, output_index) => ({type:'response.output_item.done',output_index,item}));
    events.push({type:'response.completed',response:{id:'response_fixture',status:'completed',output:items,usage}});
    return new Response(events.map(event => `data: ${JSON.stringify(event)}\n\n`).join(''),
      {status:200,headers:{'content-type':'text/event-stream'}});
  },
});
const config = {
  accessToken: `fixture.${Buffer.from(JSON.stringify({'https://api.openai.com/auth':{chatgpt_account_id:'fixture-account'}})).toString('base64url')}.fixture`,
  model:{id:'gpt-5.4',name:'Fixture',api:'openai-codex-responses',provider:'openai-codex',baseUrl:'https://chatgpt.com/backend-api',
    input:['text'],reasoning:true,contextWindow:128000,maxTokens:8192,cost:{input:0,output:0,cacheRead:0,cacheWrite:0}},
  context:{systemPrompt:'Fixture',messages:[{role:'user',content:[{type:'text',text:'Question'}],timestamp:0}],
    tools:[{name:'search',description:'Fixture tool',parameters:{type:'object',properties:{query:{type:'string'}}}}]},
};
const first = await providerTurn(config, transport);
assert.equal(first.type, 'result');
assert.equal(first.assistant.stopReason, 'toolUse');
assert.equal(first.assistant.content[1].id, 'call_fixture|fc_fixture');
assert.equal(first.assistant.usage.totalTokens, 17);
config.context.messages.push(first.assistant,
  {role:'toolResult',toolCallId:first.assistant.content[1].id,toolName:'search',content:[{type:'text',text:'Common executor result'}],isError:false,timestamp:0});
const second = await providerTurn(config, transport);
assert.equal(second.type, 'result');
assert.equal(second.assistant.content[0].text, 'Done');
assert.equal(requests, 2);
assert.deepEqual(bodies[1].input.find(item => item.type === 'reasoning'), reasoning);
assert.equal(bodies[1].input.find(item => item.type === 'function_call').call_id, 'call_fixture');
assert.equal(bodies[1].input.find(item => item.type === 'function_call').id, 'fc_fixture');
assert.equal(bodies[1].input.find(item => item.type === 'function_call_output').call_id, 'call_fixture');
assert.deepEqual(bodies[0].tools.map(tool => tool.name), ['search']);
console.log('Pinned pi-ai 0.84.4: two offline turns, native IDs and signed reasoning preserved');
