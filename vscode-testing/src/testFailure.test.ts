// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import type { TestMessage, Uri } from 'vscode';
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

  test('a failure with no frames keeps the message and nothing else', () => {
    const message = testFailureMessage({ message: 'boom' });

    assert.equal(message.message, 'boom');
    assert.equal(message.stackTrace, undefined);
    assert.equal(message.location, undefined);
  });
});
