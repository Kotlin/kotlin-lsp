import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { showsStreamInTerminal, terminalChunk } from './runTerminalModel';

describe('showsStreamInTerminal', () => {
  test('the integrated terminal renders the streamed output', () => {
    assert.equal(showsStreamInTerminal('integratedTerminal'), true);
  });

  test('an external terminal launch falls back to the integrated terminal: no program runs on the client', () => {
    assert.equal(showsStreamInTerminal('externalTerminal'), true);
  });

  test('the internal console leaves the output to the Debug Console', () => {
    assert.equal(showsStreamInTerminal('internalConsole'), false);
  });

  test('a console of none leaves the output to the client code that asked for it', () => {
    assert.equal(showsStreamInTerminal('none'), false);
  });

  test('an absent console means the Debug Console', () => {
    assert.equal(showsStreamInTerminal(undefined), false);
  });
});

describe('terminalChunk', () => {
  const output = (category: string | undefined, text: string) => ({
    type: 'event',
    event: 'output',
    body: category === undefined ? { output: text } : { category, output: text },
  });

  test('stdout is rendered with newlines normalized to CRLF', () => {
    assert.equal(terminalChunk(output('stdout', 'one\ntwo\n')), 'one\r\ntwo\r\n');
  });

  test('a CRLF from the program is not doubled', () => {
    assert.equal(terminalChunk(output('stderr', 'oops\r\n')), 'oops\r\n');
  });

  test('system messages of the tool integration are rendered too', () => {
    assert.equal(terminalChunk(output('console', '[Build] done\n')), '[Build] done\r\n');
  });

  test('an output event without a category is rendered', () => {
    assert.equal(terminalChunk(output(undefined, 'line\n')), 'line\r\n');
  });

  test('telemetry is addressed to client code, never to the terminal', () => {
    assert.equal(terminalChunk(output('telemetry', '##teamcity[testStarted]')), undefined);
  });

  test('the exit event contributes nothing: the server already reports the exit code in the stream', () => {
    assert.equal(terminalChunk({ type: 'event', event: 'exited', body: { exitCode: 3 } }), undefined);
  });

  test('other events contribute nothing', () => {
    assert.equal(terminalChunk({ type: 'event', event: 'terminated' }), undefined);
    assert.equal(terminalChunk({ type: 'event', event: 'stopped', body: { reason: 'breakpoint' } }), undefined);
  });

  test('requests and responses contribute nothing', () => {
    assert.equal(terminalChunk({ type: 'response', command: 'launch' }), undefined);
    assert.equal(terminalChunk({ type: 'request', command: 'runInTerminal' }), undefined);
  });

  test('an output event without text contributes nothing', () => {
    assert.equal(terminalChunk({ type: 'event', event: 'output', body: { category: 'stdout' } }), undefined);
  });
});
