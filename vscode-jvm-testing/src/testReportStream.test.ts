import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { TestItem, TestMessage } from 'vscode';
import type { TestFailed } from '@jetbrains/vscode-extension-core/testing/serviceMessages';
import { type MessageAttributes, type TestReport, TestReportStream } from './testReportStream';

function makeItem(id: string, children: TestItem[] = []): TestItem {
  return {
    id,
    label: id,
    children: { forEach: (visit: (child: TestItem) => void) => children.forEach(visit) },
  } as unknown as TestItem;
}

function locator(known: readonly TestItem[]) {
  const byName = new Map(known.map((item) => [item.id, item]));
  return (attributes: MessageAttributes): TestItem | undefined => {
    const name = attributes.nodeId ?? attributes.id ?? attributes.locationHint;
    return name === undefined ? undefined : byName.get(name);
  };
}

const plainFailure = (failed: TestFailed): TestMessage =>
  ({
    message:
      [failed.failureMessage, failed.stacktrace].filter(Boolean).join('\n') || 'Test failed.',
  }) as unknown as TestMessage;

function makeStream(options: {
  readonly known?: readonly TestItem[];
  readonly launched?: readonly TestItem[];
  readonly describeFailure?: (failed: TestFailed) => TestMessage;
}): TestReportStream {
  const launched = options.launched ?? [];
  return new TestReportStream({
    launched,
    locate: locator(options.known ?? launched),
    describeFailure: options.describeFailure ?? plainFailure,
  });
}

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

/** Feeds whole lines, the common case: the process printed them, each ending in a newline. */
const feed = (stream: TestReportStream, ...lines: string[]): readonly TestReport[] =>
  stream.feed(lines.map((line) => `${line}\n`).join(''));

/** A report as one readable line, so a test can assert the whole sequence at once. */
function summary(report: TestReport): string {
  switch (report.kind) {
    case 'started':
      return `started ${report.item.id}`;
    case 'finished':
      return `${report.outcome} ${report.item.id}`;
    case 'output':
      return `output ${report.item?.id ?? '(run)'}: ${report.text.trimEnd()}`;
  }
}

const summaries = (reports: readonly TestReport[]): string[] => reports.map(summary);

/** The failure text a report carries, if it carries one. */
function failureText(report: TestReport | undefined): string | undefined {
  const message = report?.kind === 'finished' ? report.message : undefined;
  return typeof message?.message === 'string' ? message.message : undefined;
}

const CLASS_NODE = 'com.example.FooTest';
const methodNode = (method: string) => `${CLASS_NODE}#${method}`;

describe('what a test process asks for', () => {
  test('a test the runner announces starts and passes', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testStarted', { nodeId: methodNode('a'), name: 'a()' }),
      teamcity('testFinished', { nodeId: methodNode('a'), name: 'a()', duration: '12' }),
    );

    assert.deepEqual(summaries(reports), [
      'started com.example.FooTest#a',
      'passed com.example.FooTest#a',
    ]);
    assert.equal(reports[1].kind === 'finished' && reports[1].duration, 12);
  });

  test('a test the user asked for starts when the runner starts it, and only then', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ launched: [item] });

    const reports = feed(
      stream,
      teamcity('testStarted', { nodeId: methodNode('a') }),
      teamcity('testStarted', { nodeId: methodNode('a') }),
      teamcity('testFinished', { nodeId: methodNode('a') }),
    );

    assert.deepEqual(summaries(reports), [
      'started com.example.FooTest#a',
      'passed com.example.FooTest#a',
    ]);
  });

  test('an assertion failure fails the test, carrying what the language made of it', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testFailed', {
        nodeId: methodNode('a'),
        message: 'expected: <1> but was: <2>',
        details: 'at com.example.FooTest.a(FooTest.java:7)',
      }),
    );

    assert.deepEqual(summaries(reports), ['failed com.example.FooTest#a']);
    assert.equal(
      failureText(reports[0]),
      'expected: <1> but was: <2>\nat com.example.FooTest.a(FooTest.java:7)',
    );
  });

  test('an uncaught exception errors the test instead of failing it', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testFailed', { nodeId: methodNode('a'), error: 'true', message: 'boom' }),
    );

    assert.deepEqual(summaries(reports), ['errored com.example.FooTest#a']);
  });

  test('an ignored test is skipped', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    assert.deepEqual(
      summaries(feed(stream, teamcity('testIgnored', { nodeId: methodNode('a') }))),
      ['skipped com.example.FooTest#a'],
    );
  });

  test('the strictest verdict is the verdict: a failure is not overwritten by the finish that follows it', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testFailed', { nodeId: methodNode('a'), message: 'boom' }),
      teamcity('testFinished', { nodeId: methodNode('a') }),
    );

    assert.deepEqual(summaries(reports), ['failed com.example.FooTest#a']);
  });

  test('a failure that arrives after a pass raises the verdict, and says why', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testFinished', { nodeId: methodNode('a') }),
      teamcity('testFailed', { nodeId: methodNode('a'), message: 'boom' }),
    );

    assert.deepEqual(summaries(reports), [
      'passed com.example.FooTest#a',
      'failed com.example.FooTest#a',
    ]);
    assert.equal(failureText(reports[1]), 'boom');
  });

  test('a run whose results all land on one node fails when one of them failed', () => {
    const item = makeItem('com.example.AllTests');
    const stream = makeStream({ launched: [item], known: [] });

    const reports = feed(
      stream,
      teamcity('testStarted', { nodeId: '[engine:junit-vintage]/[test:passes(FirstTest)]' }),
      teamcity('testFinished', { nodeId: '[engine:junit-vintage]/[test:passes(FirstTest)]' }),
      teamcity('testStarted', { nodeId: '[engine:junit-vintage]/[test:fails(SecondTest)]' }),
      teamcity('testFailed', {
        nodeId: '[engine:junit-vintage]/[test:fails(SecondTest)]',
        message: 'boom',
      }),
    );

    assert.deepEqual(summaries(reports), [
      'started com.example.AllTests',
      'passed com.example.AllTests',
      'failed com.example.AllTests',
    ]);
  });

  test('a runner that names a node only when it starts it is still understood at the finish', () => {
    const item = makeItem('java:test://com.example.FooTest/a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testStarted', {
        name: 'a(com.example.FooTest)',
        locationHint: 'java:test://com.example.FooTest/a',
      }),
      teamcity('testFinished', { name: 'a(com.example.FooTest)', duration: '5' }),
    );

    assert.deepEqual(summaries(reports), [
      'started java:test://com.example.FooTest/a',
      'passed java:test://com.example.FooTest/a',
    ]);
  });

  test('a message about a node nobody knows lands on the single test that was launched', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ launched: [item], known: [] });

    const reports = feed(stream, teamcity('testFinished', { nodeId: methodNode('somethingElse') }));

    assert.deepEqual(summaries(reports), ['passed com.example.FooTest#a']);
  });

  test('a message about a node nobody knows blames nobody when the run is not about one test', () => {
    const stream = makeStream({ launched: [makeItem('a'), makeItem('b')], known: [] });

    assert.deepEqual(
      summaries(feed(stream, teamcity('testFinished', { nodeId: methodNode('x') }))),
      [],
    );
  });

  test('a message the runner writes in pieces is not read twice, nor lost when the last one has no newline', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const split = teamcity('testFinished', { nodeId: methodNode('a') });
    assert.deepEqual(summaries(stream.feed(split.slice(0, 20))), []);
    assert.deepEqual(summaries(stream.feed(split.slice(20))), []);
    assert.deepEqual(summaries(stream.finish(0)), ['passed com.example.FooTest#a']);
  });
});

describe('what a suite ends up showing', () => {
  const suiteWith = (...outcomes: readonly ('passed' | 'failed' | 'skipped')[]) => {
    const children = outcomes.map((_, index) => makeItem(`com.example.FooTest#t${index}`));
    const suite = makeItem('com.example.FooTest', children);
    const stream = makeStream({ known: [suite, ...children] });
    const lines = [teamcity('testSuiteStarted', { nodeId: CLASS_NODE })];
    outcomes.forEach((outcome, index) => {
      const nodeId = methodNode(`t${index}`);
      lines.push(teamcity('testStarted', { nodeId }));
      if (outcome === 'passed') lines.push(teamcity('testFinished', { nodeId }));
      if (outcome === 'failed') lines.push(teamcity('testFailed', { nodeId, message: 'boom' }));
      if (outcome === 'skipped') lines.push(teamcity('testIgnored', { nodeId }));
    });
    lines.push(teamcity('testSuiteFinished', { nodeId: CLASS_NODE, duration: '30' }));
    return { suite, reports: feed(stream, ...lines) };
  };

  const suiteReport = (reports: readonly TestReport[], suite: TestItem) =>
    reports.find((report) => report.kind === 'finished' && report.item === suite);

  test('a suite whose tests reported gets no result of its own', () => {
    for (const outcome of ['passed', 'failed', 'skipped'] as const) {
      const { suite, reports } = suiteWith('passed', outcome);

      assert.equal(suiteReport(reports, suite), undefined, `passed and ${outcome}`);
    }
  });

  test('the tests of a suite report themselves, and a failing one keeps its failure text', () => {
    const { reports } = suiteWith('passed', 'failed');

    assert.deepEqual(summaries(reports), [
      'started com.example.FooTest#t0',
      'passed com.example.FooTest#t0',
      'started com.example.FooTest#t1',
      'failed com.example.FooTest#t1',
    ]);
    assert.equal(failureText(reports.at(-1)), 'boom');
  });

  test('a suite with nothing to roll up is green rather than left running', () => {
    const suite = makeItem('com.example.FooTest');
    const stream = makeStream({ known: [suite] });

    assert.deepEqual(
      summaries(feed(stream, teamcity('testSuiteFinished', { nodeId: CLASS_NODE }))),
      ['passed com.example.FooTest'],
    );
  });

  test('a class run without a suite message reports its tests, and the class itself never', () => {
    const children = [makeItem('com.example.FooTest#a'), makeItem('com.example.FooTest#b')];
    const suite = makeItem('com.example.FooTest', children);
    const stream = makeStream({ launched: [suite], known: [suite, ...children] });

    const reports = [
      ...feed(
        stream,
        teamcity('testFinished', { nodeId: methodNode('a') }),
        teamcity('testFailed', { nodeId: methodNode('b'), message: 'boom' }),
      ),
      ...stream.finish(0),
    ];

    assert.deepEqual(summaries(reports), [
      'passed com.example.FooTest#a',
      'failed com.example.FooTest#b',
    ]);
  });

  test('a test the runner never ran is skipped, so it does not stay queued', () => {
    const children = [makeItem('com.example.FooTest#a'), makeItem('com.example.FooTest#b')];
    const suite = makeItem('com.example.FooTest', children);
    const stream = makeStream({ launched: [suite], known: [suite, ...children] });

    feed(stream, teamcity('testFinished', { nodeId: methodNode('a') }));

    assert.deepEqual(summaries(stream.finish(0)), ['skipped com.example.FooTest#b']);
  });
});

describe('who printed a line', () => {
  test('the one running test owns the output', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testStarted', { nodeId: methodNode('a') }),
      'hello from the test',
    );

    assert.deepEqual(summaries(reports), [
      'started com.example.FooTest#a',
      'output com.example.FooTest#a: hello from the test',
    ]);
  });

  test('tests running at once leave the line to the suite around them', () => {
    const children = [makeItem('com.example.FooTest#a'), makeItem('com.example.FooTest#b')];
    const suite = makeItem('com.example.FooTest', children);
    const stream = makeStream({ known: [suite, ...children] });

    const reports = feed(
      stream,
      teamcity('testSuiteStarted', { nodeId: CLASS_NODE }),
      teamcity('testStarted', { nodeId: methodNode('a') }),
      teamcity('testStarted', { nodeId: methodNode('b') }),
      'printed by one of them',
    );

    assert.equal(summaries(reports).at(-1), 'output com.example.FooTest: printed by one of them');
  });

  test('a line printed before any test starts belongs to the run itself', () => {
    const stream = makeStream({ known: [] });

    assert.deepEqual(summaries(feed(stream, 'Picked up JAVA_TOOL_OPTIONS')), [
      'output (run): Picked up JAVA_TOOL_OPTIONS',
    ]);
  });

  test('a line printed after the test finished no longer belongs to it', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testStarted', { nodeId: methodNode('a') }),
      teamcity('testFinished', { nodeId: methodNode('a') }),
      'printed by the shutdown hook',
    );

    assert.equal(summaries(reports).at(-1), 'output (run): printed by the shutdown hook');
  });

  test('a runner that does attribute its output is taken at its word', () => {
    const item = makeItem('com.example.FooTest#a');
    const stream = makeStream({ known: [item] });

    const reports = feed(
      stream,
      teamcity('testStdErr', { nodeId: methodNode('a'), out: 'to stderr\n' }),
    );

    assert.deepEqual(summaries(reports), ['output com.example.FooTest#a: to stderr']);
  });
});

describe('a run that ends without saying why', () => {
  const launched = () => makeItem('com.example.FooTest#a');

  /** A test left unreported is a diagnostic, which the tests read rather than print. */
  function finish(stream: TestReportStream, exitCode: number | undefined) {
    const logged: string[] = [];
    const error = console.error;
    console.error = (...args: unknown[]) => void logged.push(args.map(String).join(' '));
    try {
      return { reports: stream.finish(exitCode), logged };
    } finally {
      console.error = error;
    }
  }

  test('a test the runner never mentioned is logged, with how much the runner did say', () => {
    const stream = makeStream({ launched: [launched()], known: [] });

    feed(stream, teamcity('buildStatus', { text: 'ok' }));

    assert.deepEqual(finish(stream, 1).logged, [
      "[jvmTest] No result for 'com.example.FooTest#a' (1 service message(s) processed).",
    ]);
  });

  test('a test the runner never mentioned errors with the exit code', () => {
    const item = launched();
    const stream = makeStream({ launched: [item], known: [] });

    const { reports } = finish(stream, 1);

    assert.deepEqual(summaries(reports), ['errored com.example.FooTest#a']);
    assert.equal(failureText(reports[0]), 'Process exited with code 1.');
  });

  test('a process that exits cleanly leaves its unmentioned test passing', () => {
    const item = launched();
    const stream = makeStream({ launched: [item], known: [] });

    const { reports } = finish(stream, 0);

    assert.deepEqual(summaries(reports), ['passed com.example.FooTest#a']);
    assert.equal(failureText(reports[0]), undefined);
  });

  test('a failure the runner blamed on nobody is why the test failed, and is shown as output', () => {
    const item = makeItem('com.example.FooTest');
    const other = makeItem('com.example.BarTest');
    const stream = makeStream({ launched: [item, other], known: [] });

    const shown = feed(
      stream,
      teamcity('testFailed', {
        name: 'initializationError',
        message: 'java.lang.NoClassDefFoundError: org/junit/Test',
      }),
    );
    assert.deepEqual(summaries(shown), [
      'output (run): java.lang.NoClassDefFoundError: org/junit/Test',
    ]);

    assert.equal(
      failureText(finish(stream, 1).reports[0]),
      'Process exited with code 1.\n\njava.lang.NoClassDefFoundError: org/junit/Test',
    );
  });

  test('with no such failure, the last of the output stands in for it', () => {
    const item = launched();
    const stream = makeStream({ launched: [item], known: [] });

    feed(stream, 'Error: Could not find or load main class', 'Caused by: ClassNotFoundException');

    assert.equal(
      failureText(finish(stream, 1).reports[0]),
      'Process exited with code 1.\n\nLast output before the process exited:\n' +
        'Error: Could not find or load main class\nCaused by: ClassNotFoundException',
    );
  });

  test('a failure nobody owned is the failure of the one test that was launched', () => {
    const stream = makeStream({ launched: [launched()], known: [] });

    const reports = feed(stream, teamcity('testFailed', { name: 'initializationError' }));

    assert.deepEqual(summaries(reports), ['failed com.example.FooTest#a']);
    assert.equal(failureText(reports[0]), 'Test failed.');
  });

  test('a failure with nothing to show leaves nothing behind', () => {
    const stream = makeStream({
      launched: [makeItem('a'), makeItem('b')],
      known: [],
      describeFailure: () => ({ message: '' }) as unknown as TestMessage,
    });

    assert.deepEqual(summaries(feed(stream, teamcity('testFailed', { name: 'unknown' }))), []);
  });

  test('a process killed without an exit code still says so', () => {
    const item = launched();
    const stream = makeStream({ launched: [item], known: [] });

    assert.equal(
      failureText(finish(stream, undefined).reports[0]),
      'Process exited with code unknown.',
    );
  });
});
