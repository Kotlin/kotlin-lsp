// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { type StackFrameParser, TeamCityReader } from './teamCityReader';
import { classDto, FOO_FILE, makeReport, methodDto } from './testRunFixture';

const escape = (value: string): string =>
  value
    .replace(/[|'[\]]/g, (char) => `|${char}`)
    .replace(/\n/g, '|n')
    .replace(/\r/g, '|r');

/** One `##teamcity[...]` line, escaped the way a runner writes it. */
const teamcity = (name: string, attributes: Readonly<Record<string, string>>): string =>
  `##teamcity[${name}${Object.entries(attributes)
    .map(([key, value]) => ` ${key}='${escape(value)}'`)
    .join('')}]`;

/** The classes and methods discovery found, read by one process of a run that launched nothing in particular. */
function readerFor(stackFrames?: StackFrameParser) {
  const made = makeReport({
    dtos: [
      classDto('com.example.Foo'),
      methodDto('com.example.Foo', 'test'),
      methodDto('com.example.Foo', 'paramTest'),
      classDto('com.example.Outer$Inner'),
      methodDto('com.example.Outer$Inner', 'test'),
    ],
  });
  const reader = new TeamCityReader(made.report, stackFrames);
  /** Feeds whole lines, the common case: the process printed them, each ending in a newline. */
  const feed = (...lines: string[]) => reader.feed(lines.map((line) => `${line}\n`).join(''));
  return { ...made, reader, feed };
}

const CLASS_HINT = 'java:suite://com.example.Foo';
const METHOD_HINT = 'java:test://com.example.Foo/test';
const PARAM_HINT = 'java:test://com.example.Foo/paramTest';

describe('which node a runner message is about', () => {
  const cases: {
    name: string;
    attributes: Record<string, string>;
    lands: string | undefined;
  }[] = [
    {
      name: 'a java:test hint is the method',
      attributes: { locationHint: METHOD_HINT },
      lands: 'moduleA/com.example.Foo#test',
    },
    {
      name: 'a java:suite hint is the class',
      attributes: { locationHint: CLASS_HINT },
      lands: 'moduleA/com.example.Foo',
    },
    {
      name: 'a hint for a nested class keeps the binary name the server reported',
      attributes: { locationHint: 'java:test://com.example.Outer$Inner/test' },
      lands: 'moduleA/com.example.Outer$Inner#test',
    },
    {
      name: 'a hint and a node id together are still the node the hint names',
      attributes: { locationHint: METHOD_HINT, nodeId: 'n-1' },
      lands: 'moduleA/com.example.Foo#test',
    },
    {
      name: 'a foreign hint is nobody',
      attributes: { locationHint: 'file:///tmp/Foo.java' },
      lands: undefined,
    },
    {
      name: 'a hint of a class nobody discovered is nobody',
      attributes: { locationHint: 'java:suite://com.example.Unknown' },
      lands: undefined,
    },
    {
      name: 'a message that names nothing at all is nobody',
      attributes: { name: 'somethingElse' },
      lands: undefined,
    },
    {
      name: 'a node id alone, before the run reported it, is nobody',
      attributes: { nodeId: 'n-1' },
      lands: undefined,
    },
  ];

  for (const { name, attributes, lands } of cases) {
    test(name, () => {
      const { feed, calls } = readerFor();

      feed(teamcity('testStarted', attributes));

      assert.deepEqual(calls, lands === undefined ? [] : [`started ${lands}`]);
    });
  }

  test('a node id the run already explained is that node again, hint or no hint', () => {
    const { feed, calls } = readerFor();

    feed(
      teamcity('testStarted', { nodeId: 'n-1', locationHint: METHOD_HINT }),
      teamcity('testFinished', { nodeId: 'n-1' }),
    );

    assert.deepEqual(calls, [
      'started moduleA/com.example.Foo#test',
      'passed moduleA/com.example.Foo#test',
    ]);
  });

  test('a runner that names a node only when it starts it is still understood at the finish', () => {
    const { feed, calls, durations } = readerFor();

    feed(
      teamcity('testStarted', { name: 'test(com.example.Foo)', locationHint: METHOD_HINT }),
      teamcity('testFinished', { name: 'test(com.example.Foo)', duration: '5' }),
    );

    assert.deepEqual(calls, [
      'started moduleA/com.example.Foo#test',
      'passed moduleA/com.example.Foo#test',
    ]);
    assert.equal(durations.get('moduleA/com.example.Foo#test'), 5);
  });

  test('one invocation of a method is its own node, under the method the runner ran', () => {
    const { feed, calls, tree } = readerFor();

    feed(
      teamcity('testSuiteStarted', { nodeId: 'method', locationHint: PARAM_HINT }),
      teamcity('testStarted', {
        nodeId: 'invocation-1',
        parentNodeId: 'method',
        // The invocation of a template repeats the location of the method it runs.
        locationHint: PARAM_HINT,
        name: '[1] one',
      }),
      teamcity('testFinished', { nodeId: 'invocation-1' }),
    );

    assert.deepEqual(calls, ['started moduleA/invocation-1', 'passed moduleA/invocation-1']);
    assert.equal(tree.get('moduleA/invocation-1')?.item.label, '[1] one');
  });

  test('an invocation that reports no location of its own still lands under its parent', () => {
    const { feed, calls } = readerFor();

    feed(
      teamcity('testSuiteStarted', { nodeId: 'method', locationHint: PARAM_HINT }),
      teamcity('testStarted', { nodeId: 'invocation-2', parentNodeId: 'method', name: '[2] two' }),
    );

    assert.deepEqual(calls, ['started moduleA/invocation-2']);
  });

  test('a node under one the run made up nests under it, however deep the runner goes', () => {
    const { feed, calls, tree } = readerFor();

    feed(
      teamcity('testSuiteStarted', { nodeId: 'method', locationHint: PARAM_HINT }),
      teamcity('testSuiteStarted', { nodeId: 'container', parentNodeId: 'method', name: 'rows' }),
      teamcity('testStarted', { nodeId: 'row-1', parentNodeId: 'container', name: 'first row' }),
    );

    assert.deepEqual(calls, ['started moduleA/row-1']);
    const container = tree.get('moduleA/container')?.item;
    assert.equal(container?.children.get('moduleA/row-1'), tree.get('moduleA/row-1')?.item);
  });

  test('a method only the runner knows is created under its class, named as the runner named it', () => {
    const { feed, calls, tree } = readerFor();

    feed(
      teamcity('testSuiteStarted', { nodeId: 'class', locationHint: CLASS_HINT }),
      teamcity('testStarted', {
        nodeId: 'generated-1',
        parentNodeId: 'class',
        name: 'generated(String)',
      }),
    );

    assert.deepEqual(calls, ['started moduleA/generated-1']);
    assert.equal(tree.get('moduleA/generated-1')?.item.label, 'generated(String)');
  });

  test('the invocations of a data provider are nodes beside the method, as the IDE shows them', () => {
    const { feed, calls, tree } = readerFor();

    feed(
      teamcity('testSuiteStarted', { nodeId: 'com.example.Foo', locationHint: CLASS_HINT }),
      teamcity('testStarted', {
        nodeId: 'com.example.Foo/paramTest',
        parentNodeId: 'com.example.Foo',
        locationHint: PARAM_HINT,
        name: 'Foo.paramTest[1]',
      }),
      teamcity('testStarted', {
        nodeId: 'com.example.Foo/paramTest[1]',
        parentNodeId: 'com.example.Foo',
        locationHint: 'java:test://com.example.Foo/paramTest[1]',
        name: 'Foo.paramTest[2] (1)',
      }),
      // A rerun of that node sends the runner's own id back, so the server can run that row alone.
      teamcity('testFinished', { nodeId: 'com.example.Foo/paramTest[1]' }),
    );

    assert.deepEqual(calls, [
      'started moduleA/com.example.Foo#paramTest',
      'started moduleA/com.example.Foo/paramTest[1]',
      'passed moduleA/com.example.Foo/paramTest[1]',
    ]);
    assert.equal(
      tree.get('moduleA/com.example.Foo/paramTest[1]')?.item.label,
      'Foo.paramTest[2] (1)',
    );
  });

  test('a failure the runner reports beside a class is a node of its own under the class', () => {
    // A JUnit 5 @BeforeAll failure comes as "Class Configuration", with the location of the class.
    // The class node keeps the outcome all the same: it rolls up what its children reported.
    const { feed, calls, tree } = readerFor();

    feed(
      teamcity('testSuiteStarted', { nodeId: 'class', locationHint: CLASS_HINT }),
      teamcity('testStarted', {
        nodeId: 'class/configuration',
        parentNodeId: 'class',
        locationHint: CLASS_HINT,
        name: 'Class Configuration',
      }),
    );

    assert.deepEqual(calls, ['started moduleA/class/configuration']);
    assert.equal(tree.get('moduleA/class/configuration')?.item.label, 'Class Configuration');
  });

  test('a run named by a suite file still lands on the classes and methods of the tree', () => {
    // A `testng.xml` run reports the suite and the test of the XML above the class. The tree holds
    // no such node, and needs none: the class and the method are found by their own locations.
    const { feed, calls } = readerFor();

    feed(
      teamcity('testSuiteStarted', {
        nodeId: 'MySuite',
        parentNodeId: '0',
        locationHint: 'file:///p/moduleA/testng.xml',
        name: 'MySuite',
      }),
      teamcity('testSuiteStarted', {
        nodeId: 'com.example.Foo',
        parentNodeId: 'MySuite',
        locationHint: CLASS_HINT,
      }),
      teamcity('testStarted', {
        nodeId: 'com.example.Foo/test',
        parentNodeId: 'com.example.Foo',
        locationHint: METHOD_HINT,
      }),
      teamcity('testSuiteFinished', { nodeId: 'MySuite' }),
      teamcity('testSuiteFinished', { nodeId: 'com.example.Foo' }),
    );

    assert.deepEqual(calls, [
      'started moduleA/com.example.Foo#test',
      'passed moduleA/com.example.Foo',
    ]);
  });

  test('a node whose parent the run never explained has nowhere to go', () => {
    const { feed, calls } = readerFor();

    feed(teamcity('testStarted', { nodeId: 'orphan', parentNodeId: 'nobody' }));

    assert.deepEqual(calls, []);
  });

  test('the node ids of one process mean nothing to the reader of another', () => {
    const { report, feed, calls } = readerFor();
    const other = new TeamCityReader(report);

    feed(teamcity('testStarted', { nodeId: 'n-1', locationHint: METHOD_HINT }));
    other.feed(`${teamcity('testFinished', { nodeId: 'n-1' })}\n`);

    assert.deepEqual(calls, ['started moduleA/com.example.Foo#test']);
  });
});

describe('what a runner message reports', () => {
  test('an assertion failure fails the test, with the stacktrace kept in its text', () => {
    const { feed, calls, messages } = readerFor();

    feed(
      teamcity('testFailed', {
        locationHint: METHOD_HINT,
        message: 'expected: <1> but was: <2>',
        details: 'at com.example.Foo.test(Foo.java:7)',
        expected: '1',
        actual: '2',
      }),
    );

    assert.deepEqual(calls, ['failed moduleA/com.example.Foo#test']);
    const message = messages.get('moduleA/com.example.Foo#test');
    assert.equal(
      message?.message,
      'expected: <1> but was: <2>\nat com.example.Foo.test(Foo.java:7)',
    );
    assert.deepEqual([message?.expectedOutput, message?.actualOutput], ['1', '2']);
  });

  test('an uncaught exception errors the test instead of failing it', () => {
    const { feed, calls } = readerFor();

    feed(teamcity('testFailed', { locationHint: METHOD_HINT, error: 'true', message: 'boom' }));

    assert.deepEqual(calls, ['errored moduleA/com.example.Foo#test']);
  });

  test('a failure that says nothing still says the test failed', () => {
    const { feed, messages } = readerFor();

    feed(teamcity('testFailed', { locationHint: METHOD_HINT }));

    assert.equal(messages.get('moduleA/com.example.Foo#test')?.message, 'Test failed.');
  });

  test('the language reads the frames of the stacktrace, and the first in a known file is marked', () => {
    const { feed, messages } = readerFor((stacktrace, report) => [
      { label: 'org.junit.Assert.fail', line: 3 },
      { label: stacktrace, uri: report.atLocation(CLASS_HINT)?.uri, line: 7 },
    ]);

    feed(
      teamcity('testFailed', { locationHint: METHOD_HINT, message: 'boom', details: 'Foo.java:7' }),
    );

    const message = messages.get('moduleA/com.example.Foo#test');
    assert.deepEqual(
      message?.stackTrace?.map((frame) => frame.label),
      ['org.junit.Assert.fail', 'Foo.java:7'],
    );
    assert.equal(message?.location?.uri.toString(), FOO_FILE);
  });

  test('an ignored test is skipped', () => {
    const { feed, calls } = readerFor();

    feed(teamcity('testIgnored', { locationHint: METHOD_HINT }));

    assert.deepEqual(calls, ['skipped moduleA/com.example.Foo#test']);
  });

  test('a runner that attributes its output is taken at its word', () => {
    const { feed, calls } = readerFor();

    feed(
      teamcity('testStdOut', { locationHint: METHOD_HINT, out: 'to stdout\n' }),
      teamcity('testStdErr', { locationHint: METHOD_HINT, out: 'to stderr\n' }),
    );

    assert.deepEqual(calls, [
      'output moduleA/com.example.Foo#test "to stdout\\r\\n"',
      'output moduleA/com.example.Foo#test "to stderr\\r\\n"',
    ]);
  });

  test('a plain line is what the process printed', () => {
    const { feed, calls } = readerFor();

    feed('Picked up JAVA_TOOL_OPTIONS');

    assert.deepEqual(calls, ['output (run) "Picked up JAVA_TOOL_OPTIONS\\r\\n"']);
  });

  test('a service message about no test is nothing to report', () => {
    const { feed, calls } = readerFor();

    feed(teamcity('buildStatus', { text: 'ok' }));

    assert.deepEqual(calls, []);
  });

  test('a message the runner writes in pieces is not read twice, nor lost when the last one has no newline', () => {
    const { reader, calls } = readerFor();
    const split = teamcity('testFinished', { locationHint: METHOD_HINT });

    reader.feed(split.slice(0, 20));
    reader.feed(split.slice(20));
    assert.deepEqual(calls, []);

    reader.flush();
    assert.deepEqual(calls, ['passed moduleA/com.example.Foo#test']);
  });
});
