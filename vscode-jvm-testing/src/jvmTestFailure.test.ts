import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { TestMessage, Uri } from 'vscode';
import type { TestFailed } from '@jetbrains/vscode-extension-core/testing/serviceMessages';
import { jvmFailureMessage } from './jvmTestFailure';

const FOO_FILE = 'file:///p/src/com/example/FooTest.java';

/**
 * Which line the failure is marked on. The stub keeps the `Position` handed to `new Location(...)` as
 * the location's `range`, where real VS Code widens it to an empty `Range` at that position.
 */
const markedLine = (message: TestMessage): number | undefined =>
  (message.location?.range as unknown as { line?: number } | undefined)?.line;

/** Real enough for these tests: the code under test only carries a uri around. */
const fakeUri = (value: string): Uri => ({ toString: () => value }) as unknown as Uri;

/** Stands in for the test tree: it knows the file of the test classes discovery found, nothing else. */
const fileOfClass = (className: string): Uri | undefined =>
  className === 'com.example.FooTest' ? fakeUri(FOO_FILE) : undefined;

function failed(attributes: Partial<TestFailed>): TestFailed {
  return { name: 'testFailed', attributes: {}, testName: 'test()', error: false, ...attributes };
}

const STACKTRACE = [
  'org.opentest4j.AssertionFailedError: expected: <a> but was: <b>',
  '\tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:38)',
  '\tat com.example.FooTest.testEquals(FooTest.java:42)',
  '\tat java.base/java.lang.reflect.Method.invoke(Method.java:580)',
].join('\n');

describe('what VS Code shows for a failure', () => {
  test('a comparison failure shows expected and actual side by side', () => {
    const message = jvmFailureMessage(
      failed({ failureMessage: 'expected: <a> but was: <b>', expected: 'a', actual: 'b' }),
      fileOfClass,
    );

    assert.equal(message.expectedOutput, 'a');
    assert.equal(message.actualOutput, 'b');
    assert.equal(message.message, 'expected: <a> but was: <b>');
  });

  test('a failure that is not a comparison has nothing to diff', () => {
    const message = jvmFailureMessage(failed({ failureMessage: 'boom' }), fileOfClass);

    assert.equal(message.expectedOutput, undefined);
    assert.equal(message.actualOutput, undefined);
  });

  test('the failure is marked on the line the assertion is on, not on the framework above it', () => {
    const message = jvmFailureMessage(
      failed({ failureMessage: 'boom', stacktrace: STACKTRACE }),
      fileOfClass,
    );

    assert.equal(message.location?.uri.toString(), FOO_FILE);
    // VS Code counts lines from 0, a stacktrace from 1.
    assert.equal(markedLine(message), 41);
  });

  test('every frame is listed, and only a frame in a file we know navigates', () => {
    const message = jvmFailureMessage(
      failed({ failureMessage: 'boom', stacktrace: STACKTRACE }),
      fileOfClass,
    );

    assert.deepEqual(
      message.stackTrace?.map((frame) => [frame.label, frame.uri?.toString()]),
      [
        ['org.junit.jupiter.api.AssertionUtils.fail', undefined],
        ['com.example.FooTest.testEquals', FOO_FILE],
        ['java.base/java.lang.reflect.Method.invoke', undefined],
      ],
    );
  });

  test('a nested, anonymous, or lambda class is looked for in the file of the class it is in', () => {
    const message = jvmFailureMessage(
      failed({
        failureMessage: 'boom',
        stacktrace: '\tat com.example.FooTest$Nested$1.lambda$run$0(FooTest.java:17)',
      }),
      fileOfClass,
    );

    assert.equal(message.location?.uri.toString(), FOO_FILE);
    assert.equal(markedLine(message), 16);
  });

  test('a frame with no source line is listed, but has no line to navigate to', () => {
    const message = jvmFailureMessage(
      failed({
        failureMessage: 'boom',
        stacktrace: '\tat com.example.FooTest.setUp(Native Method)',
      }),
      fileOfClass,
    );

    assert.deepEqual(
      message.stackTrace?.map((frame) => [frame.label, frame.position?.line]),
      [['com.example.FooTest.setUp', undefined]],
    );
    // A native frame names no line, so there is nowhere in the file to mark the failure on.
    assert.equal(message.location, undefined);
  });

  test('a line that is not a frame is not one', () => {
    const message = jvmFailureMessage(
      failed({
        failureMessage: 'boom',
        stacktrace: [
          'java.lang.IllegalStateException: no db',
          'Caused by: java.lang.NullPointerException',
          '\t... 23 more',
        ].join('\n'),
      }),
      fileOfClass,
    );

    assert.equal(message.stackTrace, undefined);
  });

  test('a failure with no stacktrace at all keeps the message and nothing else', () => {
    const message = jvmFailureMessage(failed({ failureMessage: 'boom' }), fileOfClass);

    assert.equal(message.message, 'boom');
    assert.equal(message.stackTrace, undefined);
    assert.equal(message.location, undefined);
  });

  test('the message keeps the stacktrace text, so it is readable even without the frames', () => {
    const message = jvmFailureMessage(
      failed({ failureMessage: 'boom', stacktrace: STACKTRACE }),
      fileOfClass,
    );

    assert.equal(message.message, `boom\n${STACKTRACE}`);
  });
});
