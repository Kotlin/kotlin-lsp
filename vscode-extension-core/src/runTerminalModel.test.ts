// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  InputLine,
  OutputLines,
  TerminalInput,
  showsStreamInOsWindow,
  showsStreamInTerminal,
  terminalChunk,
} from './runTerminalModel';

describe('showsStreamInOsWindow', () => {
  test('an external terminal opens an OS window in a local window', () => {
    assert.equal(showsStreamInOsWindow('externalTerminal', undefined), true);
  });

  test('an external terminal keeps the integrated terminal in a remote window: the host can have no display', () => {
    assert.equal(showsStreamInOsWindow('externalTerminal', 'ssh-remote'), false);
  });

  test('the integrated terminal never opens an OS window', () => {
    assert.equal(showsStreamInOsWindow('integratedTerminal', undefined), false);
  });
});

describe('showsStreamInTerminal', () => {
  test('the integrated terminal renders the streamed output', () => {
    assert.equal(showsStreamInTerminal('integratedTerminal'), true);
  });

  test('an external terminal renders the streamed output in an OS window', () => {
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
    assert.equal(
      terminalChunk({ type: 'event', event: 'exited', body: { exitCode: 3 } }),
      undefined,
    );
  });

  test('other events contribute nothing', () => {
    assert.equal(terminalChunk({ type: 'event', event: 'terminated' }), undefined);
    assert.equal(
      terminalChunk({ type: 'event', event: 'stopped', body: { reason: 'breakpoint' } }),
      undefined,
    );
  });

  test('requests and responses contribute nothing', () => {
    assert.equal(terminalChunk({ type: 'response', command: 'launch' }), undefined);
    assert.equal(terminalChunk({ type: 'request', command: 'runInTerminal' }), undefined);
  });

  test('an output event without text contributes nothing', () => {
    assert.equal(
      terminalChunk({ type: 'event', event: 'output', body: { category: 'stdout' } }),
      undefined,
    );
  });
});

describe('InputLine', () => {
  test('typed keys are echoed and Enter sends the line', () => {
    const input = new InputLine();
    assert.deepEqual(input.feed('ab'), { echo: 'ab', lines: [], interrupt: false });
    assert.deepEqual(input.feed('c\r'), { echo: 'c\r\n', lines: ['abc\n'], interrupt: false });
  });

  test('Backspace removes the last character, also a surrogate pair, and does nothing on an empty line', () => {
    const input = new InputLine();
    assert.deepEqual(input.feed('\x7fa😀\x7f\b\x7f\r'), {
      echo: 'a😀\b \b\b \b\r\n',
      lines: ['\n'],
      interrupt: false,
    });
  });

  test('a paste with mixed line ends sends one line per line end', () => {
    assert.deepEqual(new InputLine().feed('one\r\ntwo\nthree\r').lines, [
      'one\n',
      'two\n',
      'three\n',
    ]);
  });

  test('escape sequences such as arrow keys are ignored', () => {
    assert.deepEqual(new InputLine().feed('a\x1b[D\x1bOAb\x1b[1;5C\r').lines, ['ab\n']);
  });

  test('Ctrl+C asks to stop and adds nothing to the line', () => {
    const input = new InputLine();
    assert.deepEqual(input.feed('x\x03'), { echo: 'x', lines: [], interrupt: true });
    assert.deepEqual(input.feed('\r').lines, ['x\n']);
  });
});

describe('TerminalInput', () => {
  const recorder = () => {
    const calls: string[] = [];
    const input = new TerminalInput({
      echo: (text) => calls.push(`echo ${JSON.stringify(text)}`),
      send: (line) => calls.push(`send ${JSON.stringify(line)}`),
      interrupt: () => calls.push('interrupt'),
    });
    return { calls, input };
  };

  test('the typed text is echoed before the line goes to the program', () => {
    const { calls, input } = recorder();
    input.handle('Ann\r');
    assert.deepEqual(calls, ['echo "Ann\\r\\n"', 'send "Ann\\n"']);
  });

  test('a paste of two lines sends both, in order', () => {
    const { calls, input } = recorder();
    input.handle('one\ntwo\n');
    assert.deepEqual(calls.slice(1), ['send "one\\n"', 'send "two\\n"']);
  });

  test('Ctrl+C stops the session after the text before it', () => {
    const { calls, input } = recorder();
    input.handle('x\x03');
    assert.deepEqual(calls, ['echo "x"', 'interrupt']);
  });

  test('the keys of an ended session go nowhere', () => {
    const { calls, input } = recorder();
    input.handle('half');
    input.end();
    input.handle(' line\r\x03');
    assert.deepEqual(calls, ['echo "half"']);
  });
});

describe('OutputLines', () => {
  // The patterns the Java provider of the server sends.
  const isJavaTraceLine = (line: string) =>
    /^\s*at\s+\S.*\([^()]*\)\s*$/.test(line) ||
    /^\s*(?:\.\.\. \d+ |Caused by: |Suppressed: )/.test(line);
  const trace = [
    'Starting',
    'Exception in thread "main" java.lang.IllegalStateException: boom',
    '\tat demo.Service.run(Service.java:5)',
    '\tat demo.Main.main(Main.java:6)',
    'Caused by: java.lang.NullPointerException',
    '\tat demo.Service.load(Service.java:9)',
    '\t... 1 more',
    'Process finished with exit code 1',
  ];

  test('a frame comes with the trace lines above it and the message line above them', () => {
    const output = new OutputLines();
    output.append(trace.join('\r\n') + '\r\n');
    assert.deepEqual(
      output.traceEndingWith('\tat demo.Main.main(Main.java:6)', isJavaTraceLine),
      trace.slice(1, 4),
    );
  });

  test('a frame after a cause comes with the whole trace', () => {
    const output = new OutputLines();
    output.append(trace.join('\r\n') + '\r\n');
    assert.deepEqual(
      output.traceEndingWith('\tat demo.Service.load(Service.java:9)   ', isJavaTraceLine),
      trace.slice(1, 6),
    );
  });

  test('a frame the terminal renders with spaces for its tab is found', () => {
    const output = new OutputLines();
    output.append(trace.join('\r\n') + '\r\n');
    assert.deepEqual(
      output.traceEndingWith('        at demo.Main.main(Main.java:6)', isJavaTraceLine),
      trace.slice(1, 4),
    );
  });

  test('a line split across chunks is one line, the unterminated last one included', () => {
    const output = new OutputLines();
    output.append('java.lang.IllegalStateException\r\n\tat demo.Ser');
    output.append('vice.run(Service.java:5)');
    assert.deepEqual(
      output.traceEndingWith('\tat demo.Service.run(Service.java:5)', isJavaTraceLine),
      ['java.lang.IllegalStateException', '\tat demo.Service.run(Service.java:5)'],
    );
  });

  test('the last occurrence of a repeated frame is the one clicked', () => {
    const output = new OutputLines();
    output.append(
      'first\r\n\tat demo.Main.main(Main.java:6)\r\nsecond\r\n\tat demo.Main.main(Main.java:6)\r\n',
    );
    assert.deepEqual(output.traceEndingWith('\tat demo.Main.main(Main.java:6)', isJavaTraceLine), [
      'second',
      '\tat demo.Main.main(Main.java:6)',
    ]);
  });

  test('a line no longer in the output is sent alone', () => {
    const output = new OutputLines();
    assert.deepEqual(output.traceEndingWith('\tat demo.Main.main(Main.java:6)', isJavaTraceLine), [
      '\tat demo.Main.main(Main.java:6)',
    ]);
  });
});
