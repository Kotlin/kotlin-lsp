// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

/**
 * Typed model of TeamCity Service Messages (`##teamcity[name key='value' ...]`) — the wire format
 * classic IntelliJ's SMTestRunner uses for test results, for every language (JVM, Python, JS, Go,
 * ...), not just JVM/JUnit. Ported from the real JetBrains library
 * (`jetbrains.buildServer.messages.serviceMessages`, `org.jetbrains.teamcity:serviceMessages`):
 * `MessageWithAttributes`/`BaseTestMessage`/`BaseTestSuiteMessage`/`TestStarted`/`TestFinished`/
 * `TestFailed`/`TestIgnored`/`TestStdOut`/`TestStdErr`/`TestSuiteStarted`/`TestSuiteFinished`, plus
 * the `error` attribute used by IDEA's own generic converter
 * (`OutputToGeneralTestEventsConverter.ATTR_KEY_TEST_ERROR`) to distinguish an assertion failure
 * from an uncaught exception. `testStdOut`/`testStdErr` both carry their text in the `out`
 * attribute — the message *name* is what says which stream it is, not the attribute key.
 */
export interface MessageWithAttributes {
  readonly name: string;
  readonly attributes: Readonly<Record<string, string>>;
  /** Set for the `##teamcity[name 'argument']` form; unset for the attribute-map form. */
  readonly argument?: string;
}

export interface TestStarted extends MessageWithAttributes {
  readonly name: 'testStarted';
  readonly testName: string;
}

export interface TestFinished extends MessageWithAttributes {
  readonly name: 'testFinished';
  readonly testName: string;
  readonly testDuration?: number;
}

export interface TestFailed extends MessageWithAttributes {
  readonly name: 'testFailed';
  readonly testName: string;
  /** True for an uncaught exception; false for an assertion failure. */
  readonly error: boolean;
  readonly failureMessage?: string;
  readonly stacktrace?: string;
  readonly actual?: string;
  readonly expected?: string;
  readonly testDuration?: number;
}

export interface TestIgnored extends MessageWithAttributes {
  readonly name: 'testIgnored';
  readonly testName: string;
  readonly ignoreComment?: string;
}

export interface TestStdOut extends MessageWithAttributes {
  readonly name: 'testStdOut';
  readonly testName: string;
  readonly stdOut: string;
}

export interface TestStdErr extends MessageWithAttributes {
  readonly name: 'testStdErr';
  readonly testName: string;
  readonly stdErr: string;
}

export interface TestSuiteStarted extends MessageWithAttributes {
  readonly name: 'testSuiteStarted';
  readonly suiteName: string;
}

export interface TestSuiteFinished extends MessageWithAttributes {
  readonly name: 'testSuiteFinished';
  readonly suiteName: string;
  /** Not part of the base protocol (suites have no getter for it upstream) — some runners (e.g. JUnit5 RT) report it anyway. */
  readonly testDuration?: number;
}

// `MessageWithAttributes` is deliberately NOT a member here (only the 8 known variants are): a
// member with a plain `string` name would make every other member's `name` compatible with it too,
// which defeats `switch (message.name)` narrowing for all of them, everywhere this type is consumed
// (TS has no way to type "any string except these literals" — confirmed, not a guess). Any other/
// custom message name still parses (see `toServiceMessage`'s `default` case below), just not as a
// distinct member of this union — that's the one place, not every consumer's switch, that trusts it.
export type ServiceMessage =
  | TestStarted
  | TestFinished
  | TestFailed
  | TestIgnored
  | TestStdOut
  | TestStdErr
  | TestSuiteStarted
  | TestSuiteFinished;

const MESSAGE_START = '##teamcity[';
const MESSAGE_END = ']';

const ESCAPE_TABLE: Readonly<Record<string, string>> = {
  n: '\n',
  r: '\r',
  b: '\b',
  x: '\u0085', // next-line character
  l: '\u2028', // line-separator character
  p: '\u2029', // paragraph-separator character
  '|': '|',
  "'": "'",
  '[': '[',
  ']': ']',
};

function isWhitespace(ch: string | undefined): boolean {
  return ch === ' ' || ch === '\t' || ch === '\f' || ch === '\v';
}

function isHexDigit(ch: string): boolean {
  return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
}

function parseDuration(raw: string | undefined): number | undefined {
  const value = raw === undefined ? NaN : Number(raw);
  return Number.isFinite(value) ? value : undefined;
}

/** Narrows a parsed `name` + attribute map into the matching `ServiceMessage` shape (all our known test-message types are always sent in this attribute form, never as a quoted argument). */
function toServiceMessage(name: string, attributes: Record<string, string>): ServiceMessage {
  switch (name) {
    case 'testStarted':
      return { name, attributes, testName: attributes.name };
    case 'testFinished':
      return {
        name,
        attributes,
        testName: attributes.name,
        testDuration: parseDuration(attributes.duration),
      };
    case 'testFailed':
      return {
        name,
        attributes,
        testName: attributes.name,
        error: attributes.error === 'true',
        failureMessage: attributes.message,
        stacktrace: attributes.details,
        actual: attributes.actual,
        expected: attributes.expected,
        testDuration: parseDuration(attributes.duration),
      };
    case 'testIgnored':
      return { name, attributes, testName: attributes.name, ignoreComment: attributes.message };
    case 'testStdOut':
      return { name, attributes, testName: attributes.name, stdOut: attributes.out ?? '' };
    case 'testStdErr':
      return { name, attributes, testName: attributes.name, stdErr: attributes.out ?? '' };
    case 'testSuiteStarted':
      return { name, attributes, suiteName: attributes.name };
    case 'testSuiteFinished':
      return {
        name,
        attributes,
        suiteName: attributes.name,
        testDuration: parseDuration(attributes.duration),
      };
    default:
      // Not one of our known variants — still a valid message (e.g. `buildStatus`), just not
      // structurally distinct from them at the type level (see the comment on `ServiceMessage`).
      return { name, attributes } as ServiceMessage;
  }
}

/** Scans a single `##teamcity[...]` body left-to-right, tracking its own position — mirrors the real `ServiceMessage`. */
class ServiceMessageParser {
  private pos = 0;

  private constructor(private readonly text: string) {}

  static parse(line: string): ServiceMessage | undefined {
    const trimmed = line.trim();
    if (!trimmed.startsWith(MESSAGE_START) || !trimmed.endsWith(MESSAGE_END)) return undefined;
    const body = trimmed.slice(MESSAGE_START.length, -MESSAGE_END.length).trim();
    return new ServiceMessageParser(body).parse();
  }

  private parse(): ServiceMessage | undefined {
    const name = this.name();
    if (!name) return undefined;
    this.skipWhitespace();

    if (this.text[this.pos] === "'") {
      const argument = this.value();
      // The quoted-argument form is used by generic/other messages (e.g. `buildStatus 'SUCCESS'`),
      // never by any of our 8 known variants — see the comment on `ServiceMessage`.
      return (
        argument === undefined ? { name, attributes: {} } : { name, attributes: {}, argument }
      ) as ServiceMessage;
    }
    return toServiceMessage(name, this.attributes());
  }

  private name(): string {
    const start = this.pos;
    while (this.pos < this.text.length && !isWhitespace(this.text[this.pos])) this.pos++;
    return this.text.slice(start, this.pos);
  }

  /** Parses `key1='value1' key2='value2' ...`, stopping (and keeping what parsed so far) at the first malformed pair. */
  private attributes(): Record<string, string> {
    const attributes: Record<string, string> = {};
    this.skipWhitespace();
    while (this.pos < this.text.length) {
      const key = this.attributeName();
      if (!key || this.text[this.pos] !== '=') return attributes;
      this.pos++;
      this.skipWhitespace();
      if (this.text[this.pos] !== "'") return attributes;

      const value = this.value();
      if (value === undefined) return attributes;
      attributes[key] = value;
      this.skipWhitespace();
    }
    return attributes;
  }

  private attributeName(): string {
    const start = this.pos;
    while (this.pos < this.text.length && this.text[this.pos] !== '=') this.pos++;
    return this.text.slice(start, this.pos).trim();
  }

  private skipWhitespace(): void {
    while (isWhitespace(this.text[this.pos])) this.pos++;
  }

  /** Assumes the current character is the opening `'`; leaves `pos` right after the closing `'`. */
  private value(): string | undefined {
    this.pos++; // opening quote
    let value = '';
    while (this.pos < this.text.length) {
      const char = this.text[this.pos];
      if (char === "'") {
        this.pos++;
        return value;
      }
      if (char === '|') {
        const escaped = this.parseEscape();
        if (escaped !== undefined) {
          value += escaped;
          continue;
        }
      }
      value += char;
      this.pos++;
    }
    return undefined; // unterminated value — malformed/truncated line
  }

  /**
   * Assumes the current character is `|`. On a recognized escape, returns the decoded character and
   * advances past it; otherwise returns `undefined` and leaves `pos` on the `|` (kept as a literal).
   */
  private parseEscape(): string | undefined {
    const marker = this.text[this.pos + 1];
    const simple = ESCAPE_TABLE[marker];
    if (simple !== undefined) {
      this.pos += 2;
      return simple;
    }

    if (marker === '0' && this.text[this.pos + 2] === 'x') {
      const hex = this.text.slice(this.pos + 3, this.pos + 7);
      if (hex.length === 4 && hex.split('').every(isHexDigit)) {
        this.pos += 7;
        return String.fromCharCode(Number.parseInt(hex, 16));
      }
    }
    return undefined;
  }
}

/** Companion namespace for the `ServiceMessage` type — `ServiceMessage.parse(line)`, same shape as the Java original's `ServiceMessage.parse(text)`. */
export const ServiceMessage = {
  parse(line: string): ServiceMessage | undefined {
    return ServiceMessageParser.parse(line);
  },
};

/** Buffers process output across chunks (which can split a `##teamcity[...]` line at any byte offset) and parses complete lines as they become available. */
export class ServiceMessageStream {
  private pending = '';

  feed(chunk: string) {
    this.pending += chunk;
    const rawLines = this.pending.split(/\r\n|\r|\n/);
    this.pending = rawLines.pop() ?? '';
    return parseLines(rawLines);
  }

  /** Parses whatever's left in the buffer as a final, newline-less line. Call once the process has exited so its last message (e.g. a closing `testFailed`) isn't silently dropped. */
  flush() {
    const remainder = this.pending;
    this.pending = '';
    return remainder
      ? parseLines([remainder])
      : { messages: [] as ServiceMessage[], lines: [] as string[] };
  }
}

function parseLines(rawLines: string[]) {
  const messages: ServiceMessage[] = [];
  const lines: string[] = [];
  for (const line of rawLines) {
    const message = ServiceMessage.parse(line);
    if (message) {
      messages.push(message);
    } else {
      lines.push(line);
    }
  }
  return { messages, lines };
}
