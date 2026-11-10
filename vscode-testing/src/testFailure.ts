// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  Location,
  MarkdownString,
  Position,
  TestMessage,
  TestMessageStackFrame,
  type Uri,
} from 'vscode';
import { NAVIGATE_TO_FRAME_COMMAND } from '@jetbrains/vscode-extension-core/stackTraceNavigation';

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
  /** Whether the line of the frame in the message links to the code; the server resolves it when it is followed. */
  readonly linked?: boolean;
}

export function testFailureMessage({
  message: text,
  expected,
  actual,
  frames = [],
}: TestFailure): TestMessage {
  const linkedText = linkedFrames(text, frames);
  const message =
    expected !== undefined && actual !== undefined
      ? TestMessage.diff(linkedText, expected, actual)
      : new TestMessage(linkedText);

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

/**
 * The text with each line of a linked frame linked. The frames come in the order of their lines at the end of the
 * text, so they are matched from the end: a recursive method has several lines of the same frame. A link carries the
 * line above the frame too: the server refines a frame by it, the call of the method of the frame above.
 */
function linkedFrames(text: string, frames: readonly TestFrame[]): string | MarkdownString {
  // A line break with no line after it is not a break in markdown: its backslash would stay in the text.
  const lines = text.trimEnd().split(/\r?\n/);
  const linkedLines = new Set<number>();
  let frameIndex = frames.length - 1;
  for (let lineIndex = lines.length - 1; lineIndex >= 0 && frameIndex >= 0; lineIndex--) {
    if (!lines[lineIndex].includes(frames[frameIndex].label)) continue;
    if (frames[frameIndex--].linked) linkedLines.add(lineIndex);
  }
  if (linkedLines.size === 0) return text;

  const markdown = new MarkdownString(
    lines
      .map((line, lineIndex) => {
        if (!linkedLines.has(lineIndex)) return escaped(line);
        const indent = /^\s*/.exec(line)![0];
        const trace = lines.slice(Math.max(0, lineIndex - 1), lineIndex + 1);
        const args = encodeURIComponent(JSON.stringify([trace]))
          .replace(/\(/g, '%28')
          .replace(/\)/g, '%29');
        return `${escaped(indent)}[${escaped(line.slice(indent.length))}](command:${NAVIGATE_TO_FRAME_COMMAND}?${args})`;
      })
      .join('\\\n'),
  );
  markdown.isTrusted = { enabledCommands: [NAVIGATE_TO_FRAME_COMMAND] };
  return markdown;
}

/** The line as plain text in markdown; the indentation is kept, markdown drops leading whitespace. */
function escaped(line: string): string {
  return line
    .replace(/[\\`*_{}[\]()#+\-.!<>|~&]/g, '\\$&')
    .replace(/^[ \t]+/, (indent) =>
      indent.replace(/\t/g, '&nbsp;'.repeat(4)).replace(/ /g, '&nbsp;'),
    );
}
