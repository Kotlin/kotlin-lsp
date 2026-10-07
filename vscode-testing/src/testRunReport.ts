// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { type TestItem, TestMessage, type TestRun, type Uri } from 'vscode';
import { LanguageFileCoverage, type TestFileCoverage } from './testCoverage';
import { type TestFailure, testFailureMessage } from './testFailure';
import { leafTests } from './testItems';
import type { TestLaunchGroup } from './testPlan';
import type { TestTree } from './testTree';

export interface TestRunNode {
  label: string;
  readonly uri: Uri | undefined;
}

export interface TestResult {
  readonly node: TestRunNode | undefined;
  readonly duration?: number;
}

export interface TestFailureResult extends TestResult {
  readonly failure: TestFailure;
}

export interface TestRunReport {
  atLocation(location: string): TestRunNode | undefined;
  runtimeChild(child: {
    readonly parent: TestRunNode;
    readonly uniqueId: string;
    readonly label: string;
  }): TestRunNode | undefined;
  suiteStarted(node: TestRunNode | undefined): void;
  testStarted(node: TestRunNode | undefined): void;
  passed(result: TestResult): void;
  failed(result: TestFailureResult): void;
  errored(result: TestFailureResult): void;
  skipped(node: TestRunNode | undefined): void;
  suiteFinished(result: TestResult): void;
  output(output: { readonly text: string; readonly node?: TestRunNode }): void;
  processOutput(line: string): void;
  processExited(exitCode: number | undefined): void;
  coverage(file: TestFileCoverage): void;
}

const SEVERITY = { skipped: 0, passed: 1, failed: 2, errored: 3 } as const;

type TestOutcome = keyof typeof SEVERITY;

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
  private readonly tail: string[] = [];
  private unowned: string | undefined;

  start(item: TestItem, kind: NodeKind): boolean {
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

  elapsedOf(item: TestItem): number | undefined {
    return this.concluded.get(item)?.elapsed;
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
    let test: TestItem | undefined = undefined;
    for (const [item, node] of this.open) {
      if (node.kind !== 'test') continue;
      if (test !== undefined) return undefined;
      test = item;
    }
    return test;
  }

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

export interface GroupReportOptions {
  readonly run: TestRun;
  readonly tree: TestTree;
  readonly group: TestLaunchGroup;
}

export class GroupReport implements TestRunReport {
  private readonly state = new RunState();
  private readonly items = new Map<TestRunNode, TestItem>();
  private readonly launched: readonly TestItem[];
  private readonly fallback: TestItem | undefined;
  private began = false;
  private exited = false;
  private exitCode: number | undefined;

  constructor(private readonly options: GroupReportOptions) {
    this.launched = options.group.items;
    for (const item of this.launched) this.state.start(item, 'requested');
    this.fallback = this.launched.length === 1 ? this.launched[0] : undefined;
  }

  atLocation(location: string): TestRunNode | undefined {
    const { tree, group } = this.options;
    return this.handOut(tree.itemAtLocation({ moduleName: group.moduleName, location }));
  }

  runtimeChild(child: {
    readonly parent: TestRunNode;
    readonly uniqueId: string;
    readonly label: string;
  }): TestRunNode | undefined {
    this.begin();
    const parent = this.items.get(child.parent);
    if (!parent) return undefined;
    return this.handOut(
      this.options.tree.ensureRuntimeItem({
        parent,
        nodeId: child.uniqueId,
        displayName: child.label,
      }),
    );
  }

  suiteStarted(node: TestRunNode | undefined): void {
    this.start(node, 'suite');
  }

  testStarted(node: TestRunNode | undefined): void {
    const item = this.start(node, 'test');
    if (item) this.options.run.started(item);
  }

  passed({ node, duration }: TestResult): void {
    const item = this.verdict(node, 'passed');
    if (item) this.options.run.passed(item, duration ?? this.state.elapsedOf(item));
  }

  failed(result: TestFailureResult): void {
    this.failure(result, 'failed');
  }

  errored(result: TestFailureResult): void {
    this.failure(result, 'errored');
  }

  skipped(node: TestRunNode | undefined): void {
    const item = this.verdict(node, 'skipped');
    if (item) this.options.run.skipped(item);
  }

  suiteFinished({ node, duration }: TestResult): void {
    const item = this.resolve(node);
    if (!item || this.state.outcomeOf(item) !== undefined) return;
    const rolled = this.state.rollUp(item);
    this.state.conclude(item, rolled ?? 'passed');
    if (rolled === undefined) this.options.run.passed(item, duration ?? this.state.elapsedOf(item));
  }

  output({ text, node }: { readonly text: string; readonly node?: TestRunNode }): void {
    if (text) this.append(text, node && this.items.get(node));
  }

  processOutput(line: string): void {
    this.begin();
    this.state.note(line);
    this.append(`${line}\n`, this.state.outputOwner());
  }

  processExited(exitCode: number | undefined): void {
    this.begin();
    if (!this.exited || this.exitCode === 0) this.exitCode = exitCode;
    this.exited = true;
  }

  coverage(file: TestFileCoverage): void {
    this.options.run.addCoverage(new LanguageFileCoverage(file));
  }

  conclude(): void {
    const { run } = this.options;
    for (const item of this.launched) {
      this.concludeLaunched(item);
      for (const test of leafTests(item)) {
        if (test === item || this.state.outcomeOf(test) !== undefined) continue;
        this.state.conclude(test, 'skipped');
        run.skipped(test);
      }
    }
  }

  private concludeLaunched(item: TestItem): void {
    if (this.state.outcomeOf(item) !== undefined) return;
    const rolled = this.state.rollUp(item);
    if (rolled !== undefined) {
      this.state.conclude(item, rolled);
      return;
    }
    console.error(`[test] No result for '${item.id}'.`);
    if (this.exitCode === 0) {
      this.state.conclude(item, 'skipped');
      this.options.run.skipped(item);
    } else {
      this.state.conclude(item, 'errored');
      this.options.run.errored(item, this.exitFailure());
    }
  }

  private exitFailure(): TestMessage {
    const exit = `Process exited with code ${this.exitCode ?? 'unknown'}.`;
    const reason = this.state.silenceReason;
    return new TestMessage(reason ? `${exit}\n\n${reason}` : exit);
  }

  private begin(): void {
    if (this.began) return;
    this.began = true;
    for (const item of this.launched) this.options.tree.forgetRuntimeChildren(item);
  }

  private handOut(item: TestItem | undefined): TestRunNode | undefined {
    if (item) this.items.set(item, item);
    return item;
  }

  private resolve(node: TestRunNode | undefined): TestItem | undefined {
    this.begin();
    return (node && this.items.get(node)) ?? this.fallback;
  }

  private start(node: TestRunNode | undefined, kind: NodeKind): TestItem | undefined {
    const item = this.resolve(node);
    return item && this.state.start(item, kind) ? item : undefined;
  }

  private verdict(node: TestRunNode | undefined, outcome: TestOutcome): TestItem | undefined {
    const item = this.resolve(node);
    return item && this.state.conclude(item, outcome) ? item : undefined;
  }

  private failure(result: TestFailureResult, outcome: 'failed' | 'errored'): void {
    const item = this.resolve(result.node);
    if (!item) {
      this.blame(result.failure);
      return;
    }
    if (!this.state.conclude(item, outcome)) return;
    const message = testFailureMessage(result.failure);
    const duration = result.duration ?? this.state.elapsedOf(item);
    switch (outcome) {
      case 'failed':
        this.options.run.failed(item, message, duration);
        break;
      case 'errored':
        this.options.run.errored(item, message, duration);
        break;
    }
  }

  private blame(failure: TestFailure): void {
    const text = failure.message.trim();
    if (!text) return;
    this.state.blame(text);
    this.append(`${text}\n`, undefined);
  }

  private append(text: string, item: TestItem | undefined): void {
    this.options.run.appendOutput(text.replace(/\r?\n/g, '\r\n'), undefined, item);
  }
}
