import { Location, Position, TestMessage, TestMessageStackFrame, type Uri } from 'vscode';
import type { TestFailed } from '@jetbrains/vscode-extension-core/testing/serviceMessages';

type FileOfClass = (className: string) => Uri | undefined;

// `\tat com.example.Foo.bar(Foo.java:42)`, `at Foo.bar(Native Method)`, `at java.base/java.util.X.y(X.java:1)`.
// A Kotlin test named in backticks keeps its spaces: `at com.example.FooTest.adds two numbers(FooTest.kt:12)`.
// The source is the last parenthesized group, so the method name may hold any character but a parenthesis pair.
const FRAME = /^\s*at\s+(\S.*)\(([^()]*)\)\s*$/;
const SOURCE_LINE = /:(\d+)\s*$/;

export function jvmFailureMessage(failed: TestFailed, fileOfClass: FileOfClass): TestMessage {
  const text =
    [failed.failureMessage, failed.stacktrace].filter(Boolean).join('\n') || 'Test failed.';
  const message =
    failed.expected !== undefined && failed.actual !== undefined
      ? TestMessage.diff(text, failed.expected, failed.actual)
      : new TestMessage(text);

  const frames = stackFrames(failed.stacktrace, fileOfClass);
  if (frames.length > 0) message.stackTrace = frames;
  const assertion = assertionLocation(frames);
  if (assertion) message.location = assertion;
  return message;
}

function stackFrames(
  stacktrace: string | undefined,
  fileOfClass: FileOfClass,
): TestMessageStackFrame[] {
  const frames: TestMessageStackFrame[] = [];
  for (const line of stacktrace?.split('\n') ?? []) {
    const frame = FRAME.exec(line);
    if (!frame) continue;
    const [, reference, source] = frame;
    const sourceLine = SOURCE_LINE.exec(source);
    frames.push(
      new TestMessageStackFrame(
        reference,
        fileOfFrame(reference, fileOfClass),
        // VS Code counts lines from 0, a stacktrace from 1; a native frame names no line at all.
        sourceLine ? new Position(Number(sourceLine[1]) - 1, 0) : undefined,
      ),
    );
  }
  return frames;
}

function assertionLocation(frames: readonly TestMessageStackFrame[]): Location | undefined {
  for (const { uri, position } of frames) {
    if (uri && position) return new Location(uri, position);
  }
  return undefined;
}

function fileOfFrame(reference: string, fileOfClass: FileOfClass): Uri | undefined {
  const module = reference.indexOf('/');
  const qualifiedMethod = module === -1 ? reference : reference.slice(module + 1);
  const method = qualifiedMethod.lastIndexOf('.');
  let className = method > 0 ? qualifiedMethod.slice(0, method) : undefined;
  while (className) {
    const uri = fileOfClass(className);
    if (uri) return uri;
    const nested = className.lastIndexOf('$');
    className = nested > 0 ? className.slice(0, nested) : undefined;
  }
  return undefined;
}
