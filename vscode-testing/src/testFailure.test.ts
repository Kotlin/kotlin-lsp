// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { MarkdownString, type TestMessage, type Uri } from 'vscode';
import { testFailureMessage, type TestFrame } from './testFailure';

const FOO_FILE = 'file:///p/src/com/example/FooTest.java';

/**
 * Which line the failure is marked on. The stub keeps the `Position` handed to `new Location(...)` as
 * the location's `range`, where real VS Code widens it to an empty `Range` at that position.
 */
const markedLine = (message: TestMessage): number | undefined =>
  (message.location?.range as unknown as { line?: number } | undefined)?.line;

/** Real enough for these tests: the code under test only carries a uri around. */
const fakeUri = (value: string): Uri => ({ toString: () => value }) as unknown as Uri;

const markdownOf = (message: TestMessage): string => {
  assert.ok(message.message instanceof MarkdownString, 'the message is markdown');
  assert.deepEqual(message.message.isTrusted, {
    enabledCommands: ['jetbrains.stackTrace.navigate'],
  });
  return message.message.value;
};

/** The trace lines the `command:` link on a markdown line hands to the command. */
const traceLinkedBy = (line: string): string[] => {
  const link = /\]\(command:jetbrains\.stackTrace\.navigate\?([^()]*)\)$/.exec(line);
  assert.ok(link, `a link on: ${line}`);
  const [trace] = JSON.parse(decodeURIComponent(link[1])) as [string[]];
  return trace;
};

const FRAMES: TestFrame[] = [
  { label: 'org.junit.jupiter.api.AssertionUtils.fail', line: 38 },
  { label: 'com.example.FooTest.testEquals', uri: fakeUri(FOO_FILE), line: 42 },
  { label: 'java.base/java.lang.reflect.Method.invoke', line: 580 },
];

describe('what VS Code shows for a failure', () => {
  test('a comparison failure shows expected and actual side by side', () => {
    const message = testFailureMessage({
      message: 'expected: <a> but was: <b>',
      expected: 'a',
      actual: 'b',
    });

    assert.equal(message.expectedOutput, 'a');
    assert.equal(message.actualOutput, 'b');
    assert.equal(message.message, 'expected: <a> but was: <b>');
  });

  test('a failure that is not a comparison has nothing to diff', () => {
    const message = testFailureMessage({ message: 'boom' });

    assert.equal(message.expectedOutput, undefined);
    assert.equal(message.actualOutput, undefined);
  });

  test('the failure is marked on the line the assertion is on, not on the framework above it', () => {
    const message = testFailureMessage({ message: 'boom', frames: FRAMES });

    assert.equal(message.location?.uri.toString(), FOO_FILE);
    // VS Code counts lines from 0, a stacktrace from 1.
    assert.equal(markedLine(message), 41);
  });

  test('every frame is listed, and only a frame in a file we know navigates', () => {
    const message = testFailureMessage({ message: 'boom', frames: FRAMES });

    assert.deepEqual(
      message.stackTrace?.map((frame) => [frame.label, frame.uri?.toString()]),
      [
        ['org.junit.jupiter.api.AssertionUtils.fail', undefined],
        ['com.example.FooTest.testEquals', FOO_FILE],
        ['java.base/java.lang.reflect.Method.invoke', undefined],
      ],
    );
  });

  test('a frame with no line is listed, but has no line to navigate to', () => {
    const message = testFailureMessage({
      message: 'boom',
      frames: [{ label: 'com.example.FooTest.setUp', uri: fakeUri(FOO_FILE) }],
    });

    assert.deepEqual(
      message.stackTrace?.map((frame) => [frame.label, frame.position?.line]),
      [['com.example.FooTest.setUp', undefined]],
    );
    // There is nowhere in the file to mark the failure on.
    assert.equal(message.location, undefined);
  });

  test('the lines of the linked frames link to the code, the other lines stay text', () => {
    const message = testFailureMessage({
      message:
        'java.lang.RuntimeException: <boom>\n\tat org.example.Main.callThrow(Main (1).java:20)\n\tat java.base/java.lang.Thread.run(Thread.java:1583)',
      frames: [
        { label: 'org.example.Main.callThrow', linked: true },
        { label: 'java.base/java.lang.Thread.run' },
      ],
    });

    const lines = markdownOf(message).split('\\\n');
    assert.equal(lines[0], 'java\\.lang\\.RuntimeException: \\<boom\\>');
    assert.match(
      lines[1],
      /^&nbsp;&nbsp;&nbsp;&nbsp;\[at org\\\.example\\\.Main\\\.callThrow\\\(Main \\\(1\\\)\\\.java:20\\\)\]\(command:/,
    );
    assert.equal(
      lines[2],
      '&nbsp;&nbsp;&nbsp;&nbsp;at java\\.base/java\\.lang\\.Thread\\.run\\(Thread\\.java:1583\\)',
    );
  });

  test('a link carries the frame and the line above it, which the server refines the frame by', () => {
    const message = testFailureMessage({
      message:
        'java.lang.NullPointerException\n\tat demo.Service.length(Service.java:5)\n\tat demo.Main.main(Main.java:6)',
      frames: [
        { label: 'demo.Service.length', linked: true },
        { label: 'demo.Main.main', linked: true },
      ],
    });

    const lines = markdownOf(message).split('\\\n');
    assert.deepEqual(traceLinkedBy(lines[1]), [
      'java.lang.NullPointerException',
      '\tat demo.Service.length(Service.java:5)',
    ]);
    assert.deepEqual(traceLinkedBy(lines[2]), [
      '\tat demo.Service.length(Service.java:5)',
      '\tat demo.Main.main(Main.java:6)',
    ]);
  });

  test('a frame on the first line of the message links to itself alone', () => {
    const message = testFailureMessage({
      message: 'at demo.Main.main(Main.java:6)',
      frames: [{ label: 'demo.Main.main', linked: true }],
    });

    assert.deepEqual(traceLinkedBy(markdownOf(message)), ['at demo.Main.main(Main.java:6)']);
  });

  test('a message that ends with a line break does not end with a backslash', () => {
    const message = testFailureMessage({
      message: 'boom\n\tat demo.Main.main(Main.java:6)\n',
      frames: [{ label: 'demo.Main.main', linked: true }],
    });

    assert.match(markdownOf(message), /\)$/);
  });

  test('each line of a recursive method links to its own line', () => {
    const message = testFailureMessage({
      message: 'boom\n\tat demo.Main.fact(Main.java:5)\n\tat demo.Main.fact(Main.java:7)',
      frames: [
        { label: 'demo.Main.fact', linked: true },
        { label: 'demo.Main.fact', linked: true },
      ],
    });

    const lines = markdownOf(message).split('\\\n');
    assert.equal(traceLinkedBy(lines[1]).at(-1), '\tat demo.Main.fact(Main.java:5)');
    assert.equal(traceLinkedBy(lines[2]).at(-1), '\tat demo.Main.fact(Main.java:7)');
  });

  test('a failure whose frames are not linked keeps its message as text', () => {
    const text = 'boom\n\tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:38)';
    const message = testFailureMessage({
      message: text,
      frames: [
        { label: 'org.junit.jupiter.api.AssertionUtils.fail', uri: fakeUri(FOO_FILE), line: 38 },
      ],
    });

    assert.equal(message.message, text);
  });

  test('a failure with no frames keeps the message and nothing else', () => {
    const message = testFailureMessage({ message: 'boom' });

    assert.equal(message.message, 'boom');
    assert.equal(message.stackTrace, undefined);
    assert.equal(message.location, undefined);
  });
});
