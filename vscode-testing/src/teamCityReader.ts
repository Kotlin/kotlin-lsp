// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  type ParsedOutput,
  type ServiceMessage,
  ServiceMessageStream,
  type TestFailed,
} from './serviceMessages';
import type { TestFailure, TestFrame } from './testFailure';
import type { TestRunNode, TestRunReport } from './testRunReport';

type MessageAttributes = Readonly<Record<string, string>>;

export type StackFrameParser = (stacktrace: string, report: TestRunReport) => readonly TestFrame[];

export class TeamCityReader {
  private readonly lines = new ServiceMessageStream();
  private readonly nodesById = new Map<string, TestRunNode>();
  private readonly takenLocations = new Set<string>();
  private readonly nodesByName = new Map<string, TestRunNode>();
  private readonly configurations = new Map<string, MessageAttributes>();

  constructor(
    private readonly report: TestRunReport,
    private readonly stackFrames: StackFrameParser = () => [],
  ) {}

  readonly feed = (chunk: string): void => this.read(this.lines.feed(chunk));

  flush(): void {
    this.read(this.lines.flush());
  }

  private read(output: ParsedOutput): void {
    for (const entry of output) {
      if (typeof entry === 'string') {
        this.report.processOutput(entry);
        continue;
      }
      try {
        this.handle(entry);
      } catch (e) {
        console.error(`[test] Failed to handle service message '${entry.name}'`, e);
      }
    }
  }

  private handle(message: ServiceMessage): void {
    const { attributes } = message;
    switch (message.name) {
      case 'testStarted':
        if (this.holdsConfiguration(attributes)) break;
        this.report.testStarted(this.started(attributes));
        break;
      case 'testSuiteStarted':
        this.report.suiteStarted(this.started(attributes));
        break;
      case 'testFinished':
        if (this.endsConfiguration(attributes)) break;
        this.report.passed({ node: this.named(attributes), duration: message.testDuration });
        break;
      case 'testFailed': {
        this.startFailedConfiguration(attributes);
        const result = {
          node: this.named(attributes),
          failure: this.failureOf(message),
          duration: message.testDuration,
        };
        if (message.error) this.report.errored(result);
        else this.report.failed(result);
        break;
      }
      case 'testIgnored':
        this.report.skipped(this.named(attributes));
        break;
      case 'testSuiteFinished':
        this.report.suiteFinished({ node: this.named(attributes), duration: message.testDuration });
        break;
      case 'testStdOut':
        this.report.output({ text: message.stdOut, node: this.named(attributes) });
        break;
      case 'testStdErr':
        this.report.output({ text: message.stdErr, node: this.named(attributes) });
        break;
      default:
        // Another kind of service message (`buildStatus`, ...): nothing about a test node.
        break;
    }
  }

  private holdsConfiguration(attributes: MessageAttributes): boolean {
    if (attributes.config !== 'true' || attributes.nodeId === undefined) return false;
    this.configurations.set(attributes.nodeId, attributes);
    return true;
  }

  private endsConfiguration({ nodeId }: MessageAttributes): boolean {
    return nodeId !== undefined && this.configurations.delete(nodeId);
  }

  private startFailedConfiguration({ nodeId }: MessageAttributes): void {
    const started = nodeId === undefined ? undefined : this.configurations.get(nodeId);
    if (!started) return;
    this.endsConfiguration(started);
    this.report.testStarted(this.started(started));
  }

  private failureOf({ failureMessage, stacktrace, expected, actual }: TestFailed): TestFailure {
    return {
      message: [failureMessage, stacktrace].filter(Boolean).join('\n') || 'Test failed.',
      expected,
      actual,
      frames: stacktrace ? this.stackFrames(stacktrace, this.report) : [],
    };
  }

  /** A runner may name a node only when it starts it, and by its name alone after that. */
  private started(attributes: MessageAttributes): TestRunNode | undefined {
    const node = this.named(attributes);
    if (node && attributes.name) this.nodesByName.set(attributes.name, node);
    return node;
  }

  private named(attributes: MessageAttributes): TestRunNode | undefined {
    const name = attributes.name;
    return this.locate(attributes) ?? (name ? this.nodesByName.get(name) : undefined);
  }

  private locate(attributes: MessageAttributes): TestRunNode | undefined {
    const nodeId = attributes.nodeId ?? attributes.id;
    const known = nodeId === undefined ? undefined : this.nodesById.get(nodeId);
    if (known) return known;

    const location = attributes.locationHint;
    const taken =
      nodeId !== undefined && location !== undefined && this.takenLocations.has(location);
    if (location !== undefined && !taken) {
      const node = this.report.atLocation(location);
      if (node) {
        if (nodeId !== undefined) this.takenLocations.add(location);
        return this.remember(nodeId, node);
      }
    }

    if (nodeId === undefined) return undefined;
    const parentNodeId = attributes.parentNodeId;
    const parent = parentNodeId === undefined ? undefined : this.nodesById.get(parentNodeId);
    if (!parent) return undefined;
    const node = this.report.runtimeChild({
      parent,
      uniqueId: nodeId,
      label: attributes.name ?? nodeId,
    });
    return node && this.remember(nodeId, node);
  }

  private remember(nodeId: string | undefined, node: TestRunNode): TestRunNode {
    if (nodeId !== undefined) this.nodesById.set(nodeId, node);
    return node;
  }
}
