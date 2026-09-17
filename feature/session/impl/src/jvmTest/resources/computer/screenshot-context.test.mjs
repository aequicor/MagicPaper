import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

test('keeps only latest tool screenshot, preserves user/non-screen images and durable history', async () => {
  let hook, registrations = 0;
  const pi = { on(name, value) { assert.equal(name, 'context'); hook = value; registrations++; } };
  const source = readFileSync(new URL('../../../jvmMain/resources/computer/screenshot-context.js', import.meta.url), 'utf8');
  const context = vm.createContext({ pi });
  vm.runInContext(source, context);
  vm.runInContext(source, context);
  assert.equal(registrations, 1);
  const image = data => ({ type: 'image', data, mimeType: 'image/jpeg' });
  const shot = (toolName, data) => ({ role: 'toolResult', toolName, content: [{ type: 'text', text: 'id and geometry' }, image(data)] });
  const messages = [shot('computer', 'old'), { role: 'user', content: [image('user')] },
    shot('application', 'middle'), shot('drawing', 'keep'), shot('magicpaper_browser_screenshot', 'latest')];
  const original = JSON.stringify(messages);
  const result = (await hook({ messages })).messages;
  assert.equal(JSON.stringify(messages), original);
  assert.equal(result[0].content.filter(x => x.type === 'image').length, 0);
  assert.equal(result[2].content.filter(x => x.type === 'image').length, 0);
  assert.equal(result[0].content[0].text, 'id and geometry');
  for (const i of [1, 3, 4]) assert.equal(result[i], messages[i]);
  assert.equal(JSON.stringify((await hook({ messages: result })).messages), JSON.stringify(result));
});
