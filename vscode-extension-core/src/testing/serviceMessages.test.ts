// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import test from 'node:test';
import assert from 'node:assert/strict';
import { ServiceMessage, ServiceMessageStream } from './serviceMessages';

test('parses a message with no attributes', () => {
  const message = ServiceMessage.parse('##teamcity[treeEnded]');
  assert.deepEqual(message, { name: 'treeEnded', attributes: {} });
});

test('parses testStarted into a typed TestStarted', () => {
  const message = ServiceMessage.parse(
    "##teamcity[testStarted name='testFoo' nodeId='1' parentNodeId='0']",
  );
  assert.deepEqual(message, {
    name: 'testStarted',
    attributes: { name: 'testFoo', nodeId: '1', parentNodeId: '0' },
    testName: 'testFoo',
  });
});

test('tolerates extra whitespace around "="', () => {
  const message = ServiceMessage.parse("##teamcity[testStarted name = 'testFoo']");
  assert.deepEqual(message, {
    name: 'testStarted',
    attributes: { name: 'testFoo' },
    testName: 'testFoo',
  });
});

test('unescapes all documented single-character escape sequences', () => {
  const message = ServiceMessage.parse("##teamcity[m v='a|nb|rc|bd||e|'f|[g|]h']");
  assert.equal(message?.attributes.v, "a\nb\rc\bd|e'f[g]h");
});

test('unescapes the |x, |l and |p legacy separator escapes', () => {
  const message = ServiceMessage.parse("##teamcity[m v='a|xb|lc|pd']");
  assert.equal(message?.attributes.v, 'a\u0085b\u2028c\u2029d');
});

test('unescapes the |0xHHHH hex form used for non-ASCII characters', () => {
  const message = ServiceMessage.parse(
    "##teamcity[testStarted name='|0x0442|0x0435|0x0441|0x0442']",
  );
  assert.equal(message?.attributes.name, 'тест');
});

test('parses the quoted-argument message form', () => {
  const message = ServiceMessage.parse("##teamcity[buildStatus 'SUCCESS|nwith |'details|'']");
  assert.deepEqual(message, {
    name: 'buildStatus',
    attributes: {},
    argument: "SUCCESS\nwith 'details'",
  });
});

test('parses testFinished into a typed TestFinished, with testDuration parsed to a number', () => {
  const message = ServiceMessage.parse("##teamcity[testFinished name='testFoo' duration='42']");
  assert.deepEqual(message, {
    name: 'testFinished',
    attributes: { name: 'testFoo', duration: '42' },
    testName: 'testFoo',
    testDuration: 42,
  });
});

test('leaves testDuration undefined when the duration attribute is absent or not a number', () => {
  const message = ServiceMessage.parse("##teamcity[testFinished name='testFoo']");
  assert.deepEqual(message, {
    name: 'testFinished',
    attributes: { name: 'testFoo' },
    testName: 'testFoo',
    testDuration: undefined,
  });
});

test('parses testFailed into a typed TestFailed with message/details/actual/expected', () => {
  const message = ServiceMessage.parse(
    "##teamcity[testFailed name='testFoo' message='msg' details='line one|nline two' actual='1' expected='2']",
  );
  assert.deepEqual(message, {
    name: 'testFailed',
    attributes: {
      name: 'testFoo',
      message: 'msg',
      details: 'line one\nline two',
      actual: '1',
      expected: '2',
    },
    testName: 'testFoo',
    error: false,
    failureMessage: 'msg',
    stacktrace: 'line one\nline two',
    actual: '1',
    expected: '2',
    testDuration: undefined,
  });
});

test('parses testFailed error=true as an uncaught exception rather than an assertion failure', () => {
  const message = ServiceMessage.parse(
    "##teamcity[testFailed name='testFoo' error='true' message='boom']",
  );
  assert.equal(message?.name, 'testFailed');
  assert.equal((message as { error: boolean }).error, true);
});

test('drops a trailing unterminated attribute but keeps whatever parsed before it', () => {
  const message = ServiceMessage.parse(
    "##teamcity[testFailed name='testFoo' message='unterminated]",
  );
  assert.deepEqual(message, {
    name: 'testFailed',
    attributes: { name: 'testFoo' },
    testName: 'testFoo',
    error: false,
    failureMessage: undefined,
    stacktrace: undefined,
    actual: undefined,
    expected: undefined,
    testDuration: undefined,
  });
});

test('parses testIgnored into a typed TestIgnored', () => {
  const message = ServiceMessage.parse("##teamcity[testIgnored name='testFoo' message='disabled']");
  assert.deepEqual(message, {
    name: 'testIgnored',
    attributes: { name: 'testFoo', message: 'disabled' },
    testName: 'testFoo',
    ignoreComment: 'disabled',
  });
});

test('parses testStdOut and testStdErr from the same "out" attribute', () => {
  const stdOut = ServiceMessage.parse("##teamcity[testStdOut name='testFoo' out='hello']");
  assert.deepEqual(stdOut, {
    name: 'testStdOut',
    attributes: { name: 'testFoo', out: 'hello' },
    testName: 'testFoo',
    stdOut: 'hello',
  });

  const stdErr = ServiceMessage.parse("##teamcity[testStdErr name='testFoo' out='oops']");
  assert.deepEqual(stdErr, {
    name: 'testStdErr',
    attributes: { name: 'testFoo', out: 'oops' },
    testName: 'testFoo',
    stdErr: 'oops',
  });
});

test('parses testSuiteStarted/testSuiteFinished into typed suite messages', () => {
  const started = ServiceMessage.parse("##teamcity[testSuiteStarted name='MyClass']");
  assert.deepEqual(started, {
    name: 'testSuiteStarted',
    attributes: { name: 'MyClass' },
    suiteName: 'MyClass',
  });

  const finished = ServiceMessage.parse(
    "##teamcity[testSuiteFinished name='MyClass' duration='7']",
  );
  assert.deepEqual(finished, {
    name: 'testSuiteFinished',
    attributes: { name: 'MyClass', duration: '7' },
    suiteName: 'MyClass',
    testDuration: 7,
  });
});

test('returns undefined for a line that is not a service message', () => {
  assert.equal(ServiceMessage.parse('just some program output'), undefined);
});

test('ServiceMessageStream buffers a message split across two feed() calls', () => {
  const stream = new ServiceMessageStream();
  const first = stream.feed("##teamcity[testStarted name='testFoo'");
  assert.deepEqual(first, { messages: [], lines: [] });

  const second = stream.feed("]\n##teamcity[testFinished name='testFoo']\n");
  assert.deepEqual(second.messages, [
    { name: 'testStarted', attributes: { name: 'testFoo' }, testName: 'testFoo' },
    {
      name: 'testFinished',
      attributes: { name: 'testFoo' },
      testName: 'testFoo',
      testDuration: undefined,
    },
  ]);
  assert.deepEqual(second.lines, []);
});

test('ServiceMessageStream separates passthrough output from service messages', () => {
  const stream = new ServiceMessageStream();
  const result = stream.feed(
    "hello from the test\n##teamcity[testStarted name='testFoo']\nmore output\n",
  );
  assert.deepEqual(result.messages, [
    { name: 'testStarted', attributes: { name: 'testFoo' }, testName: 'testFoo' },
  ]);
  assert.deepEqual(result.lines, ['hello from the test', 'more output']);
});

test('ServiceMessageStream keeps an incomplete trailing line pending until the next feed()', () => {
  const stream = new ServiceMessageStream();
  const first = stream.feed('partial line without a terminator');
  assert.deepEqual(first, { messages: [], lines: [] });

  const second = stream.feed(' continues here\n');
  assert.deepEqual(second.lines, ['partial line without a terminator continues here']);
});

test('ServiceMessageStream.flush() surfaces a final message with no trailing newline', () => {
  const stream = new ServiceMessageStream();
  stream.feed("##teamcity[testStarted name='testFoo']\n##teamcity[testFailed name='testFoo']");
  assert.deepEqual(stream.flush(), {
    messages: [
      {
        name: 'testFailed',
        attributes: { name: 'testFoo' },
        testName: 'testFoo',
        error: false,
        failureMessage: undefined,
        stacktrace: undefined,
        actual: undefined,
        expected: undefined,
        testDuration: undefined,
      },
    ],
    lines: [],
  });
});

test('ServiceMessageStream.flush() surfaces a final passthrough line with no trailing newline', () => {
  const stream = new ServiceMessageStream();
  stream.feed('no terminator here');
  assert.deepEqual(stream.flush(), { messages: [], lines: ['no terminator here'] });
});

test('ServiceMessageStream.flush() is a no-op when nothing is pending', () => {
  const stream = new ServiceMessageStream();
  stream.feed('complete line\n');
  assert.deepEqual(stream.flush(), { messages: [], lines: [] });
});
