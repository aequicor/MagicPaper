import test from 'node:test';
import assert from 'node:assert/strict';
import register, { DEFAULT_TIMEOUT_SECONDS, withDefaultTimeout } from '../../../jvmMain/resources/coding/shell-timeout.mjs';

test('missing, non-positive and broken timeouts fall back to the default', () => {
  assert.equal(withDefaultTimeout({ command: 'git status' }).timeout, DEFAULT_TIMEOUT_SECONDS);
  for (const timeout of [null, 0, -5, NaN, '', 'soon', {}, []]) {
    assert.equal(withDefaultTimeout({ command: 'git status', timeout }).timeout, DEFAULT_TIMEOUT_SECONDS);
  }
});

test('an explicit timeout wins, numeric strings are accepted', () => {
  assert.equal(withDefaultTimeout({ command: 'gradlew build', timeout: 1200 }).timeout, 1200);
  assert.equal(withDefaultTimeout({ command: 'gradlew build', timeout: '1200' }).timeout, 1200);
  assert.equal(withDefaultTimeout({ command: 'gradlew build', timeout: 1.5 }).timeout, 1.5);
});

test('the extension patches shell tools only and never blocks', () => {
  let gate;
  register({ on: (name, callback) => { if (name === 'tool_call') gate = callback; } });
  const plain = { toolName: 'bash', input: { command: 'gradlew build' } };
  assert.equal(gate(plain), undefined);
  assert.equal(plain.input.timeout, DEFAULT_TIMEOUT_SECONDS);
  const explicit = { toolName: 'powershell', input: { command: 'gradlew build', timeout: 1200 } };
  assert.equal(gate(explicit), undefined);
  assert.equal(explicit.input.timeout, 1200);
  const reader = { toolName: 'read', input: { path: 'README.md' } };
  gate(reader);
  assert.equal(reader.input.timeout, undefined);
  assert.equal(gate({ toolName: 'bash' }), undefined);
});
