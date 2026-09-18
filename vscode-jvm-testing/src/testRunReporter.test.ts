import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { TestItem, TestMessage, TestRun } from 'vscode';
import { TestRunReporter } from './testRunReporter';

const makeItem = (id: string, children: TestItem[] = []): TestItem =>
  ({
    id,
    label: id,
    children: { forEach: (visit: (child: TestItem) => void) => children.forEach(visit) },
  }) as unknown as TestItem;

/** What the failure amounts to for a test: its text, or `[]` for a node with nothing of its own to say. */
function shown(messages: TestMessage | readonly TestMessage[]): string {
  if (Array.isArray(messages)) return '[]';
  const { message } = messages as TestMessage;
  return typeof message === 'string' ? message : message.value;
}

/** Records what the reporter does to the run, in order, as `<call> <node> <detail>`. */
function makeRun(): { readonly run: TestRun; readonly calls: string[] } {
  const calls: string[] = [];
  const run = {
    started: (item: TestItem) => calls.push(`started ${item.id}`),
    passed: (item: TestItem) => calls.push(`passed ${item.id}`),
    skipped: (item: TestItem) => calls.push(`skipped ${item.id}`),
    failed: (item: TestItem, message: TestMessage | readonly TestMessage[]) =>
      calls.push(`failed ${item.id} ${shown(message)}`),
    errored: (item: TestItem, message: TestMessage | readonly TestMessage[]) =>
      calls.push(`errored ${item.id} ${shown(message)}`),
    appendOutput: (text: string, _location: unknown, item?: TestItem) =>
      calls.push(`output ${item?.id ?? '(run)'} ${JSON.stringify(text)}`),
  } as unknown as TestRun;
  return { run, calls };
}

function makeReporter(launched: readonly TestItem[], known: readonly TestItem[] = launched) {
  const { run, calls } = makeRun();
  const locate = (attributes: Readonly<Record<string, string>>): TestItem | undefined =>
    known.find((item) => item.id === attributes.name);
  const reporter = new TestRunReporter({
    run,
    launched,
    locate,
    describeFailure: (failed) =>
      ({ message: failed.failureMessage ?? 'Test failed.' }) as unknown as TestMessage,
  });
  return { reporter, calls };
}

const line = (name: string, attributes: string): string => `##teamcity[${name} ${attributes}]\n`;

describe('carrying out what a test process asks for', () => {
  test('nothing is reported until the runner says something', () => {
    const { calls } = makeReporter([makeItem('a'), makeItem('b')]);

    assert.deepEqual(calls, []);
  });

  test('each outcome becomes the call VS Code expects for it', () => {
    const items = ['pass', 'fail', 'crash', 'ignore'].map((id) => makeItem(id));
    const { reporter, calls } = makeReporter(items);

    reporter.feed(
      line('testFinished', "name='pass'") +
        line('testFailed', "name='fail' message='expected 1'") +
        line('testFailed', "name='crash' error='true' message='boom'") +
        line('testIgnored', "name='ignore'"),
    );

    assert.deepEqual(calls, [
      'passed pass',
      'failed fail expected 1',
      'errored crash boom',
      'skipped ignore',
    ]);
  });

  test('a suite is left to its tests: the failure is theirs, and the suite gets no result', () => {
    const child = makeItem('a');
    const suite = makeItem('suite', [child]);
    const { reporter, calls } = makeReporter([suite], [suite, child]);

    reporter.feed(line('testFailed', "name='a' message='boom'"));
    reporter.finish(0);

    assert.deepEqual(calls, ['failed a boom']);
  });

  test('a test of a suite the runner never ran is skipped rather than left queued', () => {
    const children = [makeItem('a'), makeItem('b')];
    const suite = makeItem('suite', children);
    const { reporter, calls } = makeReporter([suite], [suite, ...children]);

    reporter.feed(line('testFinished', "name='a'"));
    reporter.finish(0);

    assert.deepEqual(calls, ['passed a', 'skipped b']);
  });

  test('output reaches the terminal with the line ends a terminal wants, under the test that printed it', () => {
    const item = makeItem('a');
    const { reporter, calls } = makeReporter([item]);

    reporter.feed(line('testStarted', "name='a'") + 'first\nsecond\n');

    assert.deepEqual(calls, ['started a', 'output a "first\\r\\n"', 'output a "second\\r\\n"']);
  });

  test('the process exiting is what answers for a test the runner never mentioned', () => {
    const item = makeItem('a');
    const { reporter, calls } = makeReporter([item]);
    const error = console.error;
    console.error = () => {};

    try {
      reporter.finish(1);
    } finally {
      console.error = error;
    }

    assert.deepEqual(calls, ['errored a Process exited with code 1.']);
  });
});
