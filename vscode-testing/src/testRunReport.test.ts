// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  type CancellationToken,
  type Range,
  type StatementCoverage,
  type TestCoverageCount,
  type TestMessage,
  Uri,
} from 'vscode';
import type { TestFileCoverage } from './testCoverage';
import { GroupReport, type TestRunNode, type TestRunReport } from './testRunReport';
import {
  classDto,
  FOO_FILE,
  makeReport,
  methodDto,
  recordRun,
  withErrorLog,
} from './testRunFixture';

const FOO = 'java:suite://com.example.Foo';
const A = 'java:test://com.example.Foo/a';
const B = 'java:test://com.example.Foo/b';

const FOO_ID = 'moduleA/com.example.Foo';
const A_ID = 'moduleA/com.example.Foo#a';
const B_ID = 'moduleA/com.example.Foo#b';

const at = (report: TestRunReport, location: string): TestRunNode => {
  const node = report.atLocation(location);
  assert.ok(node, `no node at ${location}`);
  return node;
};

const boom = { message: 'boom' };

const text = (message: TestMessage | undefined): string | undefined =>
  typeof message?.message === 'string' ? message.message : undefined;

describe('what a runner reports on a test', () => {
  test('a test starts and passes, taking the time the runner measured', () => {
    const { report, calls, durations } = makeReport();
    const a = at(report, A);

    report.testStarted(a);
    report.passed({ node: a, duration: 12 });

    assert.deepEqual(calls, [`started ${A_ID}`, `passed ${A_ID}`]);
    assert.equal(durations.get(A_ID), 12);
  });

  test('a test the runner timed nothing takes the time the report measured', () => {
    const { report, durations } = makeReport();
    const a = at(report, A);

    report.testStarted(a);
    report.passed({ node: a });

    assert.equal(typeof durations.get(A_ID), 'number');
  });

  test('a test the user asked for starts when the runner starts it, and only once', () => {
    const { report, calls } = makeReport({ launched: ['com.example.Foo#a'] });
    const a = at(report, A);

    report.testStarted(a);
    report.testStarted(a);
    report.passed({ node: a });

    assert.deepEqual(calls, [`started ${A_ID}`, `passed ${A_ID}`]);
  });

  test('an assertion failure fails the test, and a comparison shows both sides', () => {
    const { report, calls, messages } = makeReport();

    report.failed({
      node: at(report, A),
      failure: { message: 'expected: <1> but was: <2>', expected: '1', actual: '2' },
    });

    assert.deepEqual(calls, [`failed ${A_ID}`]);
    const message = messages.get(A_ID);
    assert.equal(text(message), 'expected: <1> but was: <2>');
    assert.deepEqual([message?.expectedOutput, message?.actualOutput], ['1', '2']);
  });

  test('an uncaught exception errors the test instead of failing it', () => {
    const { report, calls } = makeReport();

    report.errored({ node: at(report, A), failure: boom });

    assert.deepEqual(calls, [`errored ${A_ID}`]);
  });

  test('an ignored test is skipped', () => {
    const { report, calls } = makeReport();

    report.skipped(at(report, A));

    assert.deepEqual(calls, [`skipped ${A_ID}`]);
  });

  test('the strictest verdict is the verdict: a failure is not overwritten by the pass that follows it', () => {
    const { report, calls } = makeReport();
    const a = at(report, A);

    report.failed({ node: a, failure: boom });
    report.passed({ node: a });

    assert.deepEqual(calls, [`failed ${A_ID}`]);
  });

  test('a failure that arrives after a pass raises the verdict, and says why', () => {
    const { report, calls, messages } = makeReport();
    const a = at(report, A);

    report.passed({ node: a });
    report.failed({ node: a, failure: boom });

    assert.deepEqual(calls, [`passed ${A_ID}`, `failed ${A_ID}`]);
    assert.equal(text(messages.get(A_ID)), 'boom');
  });

  test('a run whose results all land on the one node it runs fails when one of them failed', () => {
    const { report, calls } = makeReport({ launched: ['com.example.Foo'] });

    report.testStarted(undefined);
    report.passed({ node: undefined });
    report.testStarted(undefined);
    report.failed({ node: undefined, failure: boom });

    assert.deepEqual(calls, [`started ${FOO_ID}`, `passed ${FOO_ID}`, `failed ${FOO_ID}`]);
  });

  test('a result about no node blames nobody when the run is not about one test', () => {
    const { report, calls } = makeReport({ launched: ['com.example.Foo#a', 'com.example.Foo#b'] });

    report.passed({ node: undefined });

    assert.deepEqual(calls, []);
  });

  test('a node another report handed out is nobody here', () => {
    const first = makeReport();
    const { report, calls } = makeReport();

    report.passed({ node: at(first.report, A) });

    assert.deepEqual(calls, []);
  });
});

describe('what a runner location names', () => {
  test('a discovered node of the module, with its file', () => {
    const { report } = makeReport();

    assert.equal(report.atLocation(FOO)?.uri?.toString(), FOO_FILE);
  });

  test('a node nobody discovered is nothing, so its frames do not navigate', () => {
    const { report } = makeReport();

    assert.equal(report.atLocation('java:suite://org.junit.Assert'), undefined);
  });
});

describe('what a suite ends up showing', () => {
  test('a suite whose tests reported gets no result of its own', () => {
    for (const outcome of ['passed', 'failed', 'skipped'] as const) {
      const { report, calls } = makeReport();
      const foo = at(report, FOO);
      const b = at(report, B);
      report.suiteStarted(foo);
      report.passed({ node: at(report, A) });
      if (outcome === 'passed') report.passed({ node: b });
      if (outcome === 'failed') report.failed({ node: b, failure: boom });
      if (outcome === 'skipped') report.skipped(b);

      report.suiteFinished({ node: foo, duration: 30 });

      assert.deepEqual(calls, [`passed ${A_ID}`, `${outcome} ${B_ID}`], `passed and ${outcome}`);
    }
  });

  test('a suite with nothing to roll up is green rather than left running', () => {
    const { report, calls } = makeReport();

    report.suiteFinished({ node: at(report, FOO) });

    assert.deepEqual(calls, [`passed ${FOO_ID}`]);
  });

  test('a class run without a suite message reports its tests, and the class itself never', () => {
    const { report, calls } = makeReport({ launched: ['com.example.Foo'] });

    report.passed({ node: at(report, A) });
    report.failed({ node: at(report, B), failure: boom });
    report.processExited(0);
    report.conclude();

    assert.deepEqual(calls, [`passed ${A_ID}`, `failed ${B_ID}`]);
  });

  test('a test the runner never ran is skipped, so it does not stay queued', () => {
    const { report, calls } = makeReport({ launched: ['com.example.Foo'] });
    report.passed({ node: at(report, A) });
    report.processExited(0);

    report.conclude();

    assert.deepEqual(calls, [`passed ${A_ID}`, `skipped ${B_ID}`]);
  });
});

describe('who printed a line', () => {
  test('the one running test owns the output, in the line ends a terminal wants', () => {
    const { report, calls } = makeReport();
    report.testStarted(at(report, A));

    report.processOutput('hello from the test');

    assert.deepEqual(calls, [`started ${A_ID}`, `output ${A_ID} "hello from the test\\r\\n"`]);
  });

  test('tests running at once leave the line to the run', () => {
    const { report, calls } = makeReport();
    report.suiteStarted(at(report, FOO));
    report.testStarted(at(report, A));
    report.testStarted(at(report, B));

    report.processOutput('printed by one of them');

    assert.equal(calls.at(-1), 'output (run) "printed by one of them\\r\\n"');
  });

  test('a line printed while only the suite runs belongs to the run', () => {
    // An output message puts the item in the Test Results list, so a suite that owns a line shows
    // as an empty node of its own.
    const { report, calls } = makeReport();
    report.suiteStarted(at(report, FOO));

    report.processOutput('printed by @BeforeClass');

    assert.deepEqual(calls, ['output (run) "printed by @BeforeClass\\r\\n"']);
  });

  test('a line printed before any test starts belongs to the run itself', () => {
    const { report, calls } = makeReport();

    report.processOutput('Picked up JAVA_TOOL_OPTIONS');

    assert.deepEqual(calls, ['output (run) "Picked up JAVA_TOOL_OPTIONS\\r\\n"']);
  });

  test('a line printed after the test finished no longer belongs to it', () => {
    const { report, calls } = makeReport();
    const a = at(report, A);
    report.testStarted(a);
    report.passed({ node: a });

    report.processOutput('printed by the shutdown hook');

    assert.equal(calls.at(-1), 'output (run) "printed by the shutdown hook\\r\\n"');
  });

  test('a runner that does attribute its output is taken at its word', () => {
    const { report, calls } = makeReport();

    report.output({ text: 'first\nsecond\n', node: at(report, A) });

    assert.deepEqual(calls, [`output ${A_ID} "first\\r\\nsecond\\r\\n"`]);
  });

  test('output with no node goes to the run, and no text is no output', () => {
    const { report, calls } = makeReport();

    report.output({ text: 'Compiling...\r\n' });
    report.output({ text: '' });

    assert.deepEqual(calls, ['output (run) "Compiling...\\r\\n"']);
  });
});

describe('a run that ends without saying why', () => {
  const single = () => makeReport({ launched: ['com.example.Foo#a'] });

  test('a test the runner never mentioned is logged', () => {
    const { report } = single();
    report.processExited(1);

    assert.deepEqual(withErrorLog(() => report.conclude()).logged, [
      `[test] No result for '${A_ID}'.`,
    ]);
  });

  test('a test the runner never mentioned errors with the exit code', () => {
    const { report, calls, messages } = single();
    report.processExited(1);

    withErrorLog(() => report.conclude());

    assert.deepEqual(calls, [`errored ${A_ID}`]);
    assert.equal(text(messages.get(A_ID)), 'Process exited with code 1.');
  });

  test('a process that exits cleanly skips the test it never mentioned', () => {
    const { report, calls } = single();
    report.processExited(0);

    withErrorLog(() => report.conclude());

    assert.deepEqual(calls, [`skipped ${A_ID}`]);
  });

  test('a process that exits cleanly without a word about a class skips the class and its tests', () => {
    const { report, calls } = makeReport({ launched: ['com.example.Foo'] });
    report.processExited(0);

    withErrorLog(() => report.conclude());

    assert.deepEqual(calls, [`skipped ${FOO_ID}`, `skipped ${A_ID}`, `skipped ${B_ID}`]);
  });

  test('the first process that failed says how the group went', () => {
    const { report, messages } = single();
    report.processExited(0);
    report.processExited(3);
    report.processExited(0);

    withErrorLog(() => report.conclude());

    assert.equal(text(messages.get(A_ID)), 'Process exited with code 3.');
  });

  test('a process killed without an exit code still says so', () => {
    const { report, messages } = single();
    report.processExited(undefined);

    withErrorLog(() => report.conclude());

    assert.equal(text(messages.get(A_ID)), 'Process exited with code unknown.');
  });

  test('a group whose process never started does not pass', () => {
    const { report, calls } = single();

    withErrorLog(() => report.conclude());

    assert.deepEqual(calls, [`errored ${A_ID}`]);
  });

  test('a failure the runner blamed on nobody is why the tests failed, and is shown as output', () => {
    const { report, calls, messages } = makeReport({
      launched: ['com.example.Foo#a', 'com.example.Foo#b'],
    });

    report.failed({
      node: undefined,
      failure: { message: 'java.lang.NoClassDefFoundError: org/junit/Test\n' },
    });
    assert.deepEqual(calls, [
      'output (run) "java.lang.NoClassDefFoundError: org/junit/Test\\r\\n"',
    ]);

    report.processExited(1);
    withErrorLog(() => report.conclude());
    assert.equal(
      text(messages.get(A_ID)),
      'Process exited with code 1.\n\njava.lang.NoClassDefFoundError: org/junit/Test',
    );
  });

  test('with no such failure, the last of the output stands in for it', () => {
    const { report, messages } = single();
    report.processOutput('Error: Could not find or load main class');
    report.processOutput('Caused by: ClassNotFoundException');
    report.processExited(1);

    withErrorLog(() => report.conclude());

    assert.equal(
      text(messages.get(A_ID)),
      'Process exited with code 1.\n\nLast output before the process exited:\n' +
        'Error: Could not find or load main class\nCaused by: ClassNotFoundException',
    );
  });

  test('a failure nobody owned is the failure of the one test that was launched', () => {
    const { report, calls, messages } = single();

    report.failed({ node: undefined, failure: { message: 'initializationError' } });

    assert.deepEqual(calls, [`failed ${A_ID}`]);
    assert.equal(text(messages.get(A_ID)), 'initializationError');
  });

  test('a failure with nothing to show leaves nothing behind', () => {
    const { report, calls } = makeReport({ launched: ['com.example.Foo#a', 'com.example.Foo#b'] });

    report.errored({ node: undefined, failure: { message: '  \n' } });

    assert.deepEqual(calls, []);
  });
});

describe('the nodes a runner finds while running', () => {
  const PARAM = 'java:test://com.example.Foo/param';
  const dtos = [classDto('com.example.Foo'), methodDto('com.example.Foo', 'param')];

  test('are created under the node they run under, named as the runner named them', () => {
    const { report, tree } = makeReport({ dtos });

    const child = report.runtimeChild({
      parent: at(report, PARAM),
      uniqueId: 'inv-1',
      label: '[1] one',
    });
    report.passed({ node: child });

    assert.equal(tree.get('moduleA/inv-1')?.item.label, '[1] one');
    assert.equal(child?.label, '[1] one');
  });

  test('have no parent to go under when it is not a node of this run', () => {
    const { report } = makeReport({ dtos });

    assert.equal(
      report.runtimeChild({
        parent: { label: 'x', uri: undefined },
        uniqueId: 'inv-1',
        label: 'x',
      }),
      undefined,
    );
  });

  test('of an earlier run go once the new run reports, and stay when it never got that far', () => {
    const earlier = makeReport({ dtos, launched: ['com.example.Foo#param'] });
    earlier.report.runtimeChild({
      parent: at(earlier.report, PARAM),
      uniqueId: 'inv-1',
      label: '[1]',
    });
    const { tree, group } = earlier;

    // A build that failed: the run concludes without a word from the runner.
    const failedBuild = new GroupReport({ run: recordRun().run, tree, group });
    withErrorLog(() => failedBuild.conclude());
    assert.ok(tree.get('moduleA/inv-1'), 'a run that never ran must keep what the last one found');

    const next = new GroupReport({ run: recordRun().run, tree, group });
    next.testStarted(next.atLocation(PARAM));
    assert.equal(tree.get('moduleA/inv-1'), undefined);
  });
});

describe('coverage', () => {
  const RANGE = { start: { line: 0, character: 0 } } as unknown as Range;
  const file: TestFileCoverage = {
    uri: Uri.parse(FOO_FILE),
    statements: { covered: 3, total: 4 },
    branches: { covered: 1, total: 2 },
    details: async () => [
      { kind: 'statement', range: RANGE, executed: 2 },
      {
        kind: 'branch',
        range: RANGE,
        branches: [{ executed: 1 }, { executed: 0 }, { executed: 4 }],
      },
      { kind: 'declaration', name: 'a', range: RANGE, executed: 1 },
    ],
  };

  test('of a file reaches the run with its totals', () => {
    const { report, coverage } = makeReport();

    report.coverage(file);

    assert.equal(coverage.length, 1);
    assert.equal(coverage[0].uri.toString(), FOO_FILE);
    const count = (value: TestCoverageCount | undefined) => value && [value.covered, value.total];
    assert.deepEqual(
      [
        count(coverage[0].statementCoverage),
        count(coverage[0].branchCoverage),
        count(coverage[0].declarationCoverage),
      ],
      [[3, 4], [1, 2], undefined],
    );
  });

  test('of a file loads its lines only when asked, a branch as part of its statement', async () => {
    const { report, coverage } = makeReport();
    report.coverage(file);

    const loaded = coverage[0] as unknown as {
      details(token: CancellationToken): Promise<StatementCoverage[]>;
    };
    const details = await loaded.details({} as CancellationToken);

    assert.deepEqual(
      details.map((detail) => [
        detail.constructor.name,
        detail.executed,
        detail.branches?.map((branch) => branch.executed),
      ]),
      [
        ['StatementCoverage', 2, []],
        ['StatementCoverage', 5, [1, 0, 4]],
        ['DeclarationCoverage', 1, undefined],
      ],
    );
  });
});
