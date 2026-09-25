// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { Location, Position, TestMessage, TestMessageStackFrame, type Uri } from 'vscode';

export interface TestFailure {
  readonly message: string;
  readonly expected?: string;
  readonly actual?: string;
  readonly frames?: readonly TestFrame[];
}

export interface TestFrame {
  readonly label: string;
  readonly uri?: Uri;
  readonly line?: number;
}

export function testFailureMessage({
  message: text,
  expected,
  actual,
  frames = [],
}: TestFailure): TestMessage {
  const message =
    expected !== undefined && actual !== undefined
      ? TestMessage.diff(text, expected, actual)
      : new TestMessage(text);

  const stackTrace = frames.map(
    ({ label, uri, line }) =>
      new TestMessageStackFrame(
        label,
        uri,
        line === undefined ? undefined : new Position(line - 1, 0),
      ),
  );
  if (stackTrace.length > 0) message.stackTrace = stackTrace;
  const assertion = assertionLocation(stackTrace);
  if (assertion) message.location = assertion;
  return message;
}

function assertionLocation(frames: readonly TestMessageStackFrame[]): Location | undefined {
  for (const { uri, position } of frames) {
    if (uri && position) return new Location(uri, position);
  }
  return undefined;
}
