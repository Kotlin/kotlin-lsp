import { type TestItem, TestMessage } from 'vscode';
import {
  type ParsedOutput,
  type ServiceMessage,
  ServiceMessageStream,
  type TestFailed,
} from '@jetbrains/vscode-extension-core/testing/serviceMessages';
import { leafTests } from './jvmTestPlan';

export type MessageAttributes = Readonly<Record<string, string>>;
const SEVERITY = { skipped: 0, passed: 1, failed: 2, errored: 3 } as const;

export type TestOutcome = keyof typeof SEVERITY;
export type TestReport = StartedTestReport | OutputTestReport | FinishedTestReport;

type StartedTestReport = { readonly kind: 'started'; readonly item: TestItem };
type OutputTestReport = {
  readonly kind: 'output';
  readonly text: string;
  readonly item?: TestItem;
};
type FinishedTestReport = {
  readonly kind: 'finished';
  readonly item: TestItem;
  readonly outcome: TestOutcome;
  readonly message?: TestMessage;
  readonly duration?: number;
};

const OUTPUT_TAIL_LINES = 20;

type NodeKind = 'requested' | 'suite' | 'test';

interface OpenNode {
  kind: NodeKind;
  readonly startedAt: number;
}

interface ConcludedNode {
  readonly outcome: TestOutcome;
  readonly elapsed: number | undefined;
}

class RunState {
  private readonly open = new Map<TestItem, OpenNode>();
  private readonly concluded = new Map<TestItem, ConcludedNode>();
  private readonly names = new Map<string, TestItem>();
  private readonly tail: string[] = [];
  private unowned: string | undefined;

  start(item: TestItem, kind: NodeKind, name: string | undefined): boolean {
    if (name) this.names.set(name, item);
    if (this.concluded.has(item)) return false;
    const already = this.open.get(item);
    if (already) {
      if (already.kind !== 'requested') return false;
      already.kind = kind;
      return true;
    }
    this.open.set(item, { kind, startedAt: Date.now() });
    return true;
  }

  conclude(item: TestItem, outcome: TestOutcome): boolean {
    const concluded = this.concluded.get(item);
    if (concluded && SEVERITY[outcome] <= SEVERITY[concluded.outcome]) return false;
    const started = this.open.get(item);
    this.open.delete(item);
    this.concluded.set(item, {
      outcome,
      elapsed:
        concluded?.elapsed ?? (started === undefined ? undefined : Date.now() - started.startedAt),
    });
    return true;
  }

  outcomeOf(item: TestItem): TestOutcome | undefined {
    return this.concluded.get(item)?.outcome;
  }

  /** How long the node ran, as measured here — what to report when the runner timed nothing itself. */
  elapsedOf(item: TestItem): number | undefined {
    return this.concluded.get(item)?.elapsed;
  }

  itemNamed(name: string): TestItem | undefined {
    return this.names.get(name);
  }

  rollUp(item: TestItem): TestOutcome | undefined {
    let result: TestOutcome | undefined;
    item.children.forEach((child) => {
      const outcome = this.concluded.get(child)?.outcome;
      if (outcome && (result === undefined || SEVERITY[outcome] > SEVERITY[result]))
        result = outcome;
    });
    return result;
  }

  outputOwner(): TestItem | undefined {
    let tests = 0;
    let test: TestItem | undefined;
    let suite: TestItem | undefined;
    for (const [item, node] of this.open) {
      if (node.kind === 'test') {
        tests++;
        test = item;
      } else if (node.kind === 'suite') {
        suite = item;
      }
    }
    return tests === 1 ? test : suite;
  }

  /** Keeps the last lines the process printed, the only explanation a test it never mentioned can get. */
  note(line: string): void {
    if (this.tail.push(line) > OUTPUT_TAIL_LINES) this.tail.shift();
  }

  blame(failure: string): void {
    this.unowned = failure;
  }

  get silenceReason(): string | undefined {
    if (this.unowned) return this.unowned;
    const tail = this.tail.join('\n').trim();
    return tail ? `Last output before the process exited:\n${tail}` : undefined;
  }
}

export interface TestReportStreamOptions {
  readonly launched: readonly TestItem[];
  readonly locate: (attributes: MessageAttributes) => TestItem | undefined;
  readonly describeFailure: (failed: TestFailed) => TestMessage;
}

export class TestReportStream {
  private readonly lines = new ServiceMessageStream();
  private readonly state = new RunState();
  private messageCount = 0;
  private readonly fallback: TestItem | undefined;

  constructor(private readonly options: TestReportStreamOptions) {
    for (const item of options.launched) this.state.start(item, 'requested', undefined);
    this.fallback = options.launched.length === 1 ? options.launched[0] : undefined;
  }

  feed(chunk: string): readonly TestReport[] {
    return this.reportsFor(this.lines.feed(chunk));
  }

  finish(exitCode: number | undefined): readonly TestReport[] {
    const reports = [...this.reportsFor(this.lines.flush())];
    for (const item of this.options.launched) {
      this.conclude(item, exitCode, reports);
      // The run marked every test of [item] queued, so one the runner never ran must not stay that way.
      for (const test of leafTests(item)) {
        if (test === item || this.state.outcomeOf(test) !== undefined) continue;
        this.state.conclude(test, 'skipped');
        reports.push({ kind: 'finished', item: test, outcome: 'skipped' });
      }
    }
    return reports;
  }

  private conclude(item: TestItem, exitCode: number | undefined, reports: TestReport[]): void {
    if (this.state.outcomeOf(item) !== undefined) return;
    const rolled = this.state.rollUp(item);
    if (rolled !== undefined) {
      this.state.conclude(item, rolled);
      return;
    }
    console.error(
      `[jvmTest] No result for '${item.id}' (${this.messageCount} service message(s) processed).`,
    );
    this.state.conclude(item, exitCode === 0 ? 'passed' : 'errored');
    reports.push(
      exitCode === 0
        ? { kind: 'finished', item, outcome: 'passed' }
        : { kind: 'finished', item, outcome: 'errored', message: this.exitFailure(exitCode) },
    );
  }

  private exitFailure(exitCode: number | undefined): TestMessage {
    const exit = `Process exited with code ${exitCode ?? 'unknown'}.`;
    const reason = this.state.silenceReason;
    return new TestMessage(reason ? `${exit}\n\n${reason}` : exit);
  }

  private reportsFor(output: ParsedOutput): readonly TestReport[] {
    const reports: TestReport[] = [];
    for (const entry of output) {
      if (typeof entry === 'string') {
        this.state.note(entry);
        reports.push({ kind: 'output', text: `${entry}\n`, item: this.state.outputOwner() });
        continue;
      }
      this.messageCount++;
      try {
        const report = this.reportFor(entry);
        if (report) reports.push(report);
      } catch (e) {
        console.error(`[jvmTest] Failed to handle service message '${entry.name}'`, e);
      }
    }
    return reports;
  }

  private reportFor(message: ServiceMessage): TestReport | undefined {
    switch (message.name) {
      case 'testStarted':
        return this.started(message.attributes, 'test');
      case 'testSuiteStarted':
        return this.started(message.attributes, 'suite');
      case 'testFinished':
        return this.finished(message.attributes, {
          outcome: 'passed',
          duration: message.testDuration,
        });
      case 'testFailed':
        return this.finished(message.attributes, {
          outcome: message.error ? 'errored' : 'failed',
          message: this.options.describeFailure(message),
          duration: message.testDuration,
        });
      case 'testIgnored':
        return this.finished(message.attributes, { outcome: 'skipped' });
      case 'testSuiteFinished':
        return this.suiteFinished(message.attributes, message.testDuration);
      case 'testStdOut':
        return this.attributedOutput(message.attributes, message.stdOut);
      case 'testStdErr':
        return this.attributedOutput(message.attributes, message.stdErr);
      default:
        // Another kind of service message (`buildStatus`, ...): nothing about a test node.
        return undefined;
    }
  }

  private started(attributes: MessageAttributes, kind: NodeKind): TestReport | undefined {
    const item = this.resolve(attributes);
    if (!item || !this.state.start(item, kind, attributes.name)) return undefined;
    return kind === 'suite' ? undefined : { kind: 'started', item };
  }

  private finished(
    attributes: MessageAttributes,
    verdict: {
      readonly outcome: TestOutcome;
      readonly message?: TestMessage;
      readonly duration?: number;
    },
  ): TestReport | undefined {
    const item = this.resolve(attributes);
    if (!item) return this.unownedFailure(verdict.message);
    if (!this.state.conclude(item, verdict.outcome)) return undefined;
    return {
      kind: 'finished',
      item,
      outcome: verdict.outcome,
      message: verdict.message,
      duration: verdict.duration ?? this.state.elapsedOf(item),
    };
  }

  private suiteFinished(
    attributes: MessageAttributes,
    reportedDuration: number | undefined,
  ): TestReport | undefined {
    const item = this.resolve(attributes);
    if (!item || this.state.outcomeOf(item) !== undefined) return undefined;
    const rolled = this.state.rollUp(item);
    this.state.conclude(item, rolled ?? 'passed');
    if (rolled !== undefined) return undefined;
    return {
      kind: 'finished',
      item,
      outcome: 'passed',
      duration: reportedDuration ?? this.state.elapsedOf(item),
    };
  }

  private attributedOutput(attributes: MessageAttributes, text: string): TestReport | undefined {
    return text ? { kind: 'output', text, item: this.named(attributes) } : undefined;
  }

  /** Keeps a failure nobody owned as the reason a silent test failed, and shows it in the output. */
  private unownedFailure(message: TestMessage | undefined): TestReport | undefined {
    const text = message === undefined ? '' : textOf(message).trim();
    if (!text) return undefined;
    this.state.blame(text);
    return { kind: 'output', text: `${text}\n`, item: undefined };
  }

  /** The node the runner named, as far as this run knows it. */
  private named(attributes: MessageAttributes): TestItem | undefined {
    const name = attributes.name;
    return this.options.locate(attributes) ?? (name ? this.state.itemNamed(name) : undefined);
  }

  private resolve(attributes: MessageAttributes): TestItem | undefined {
    return this.named(attributes) ?? this.fallback;
  }
}

const textOf = (message: TestMessage): string =>
  typeof message.message === 'string' ? message.message : message.message.value;
