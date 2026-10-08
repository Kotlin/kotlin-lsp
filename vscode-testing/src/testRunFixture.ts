// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { FileCoverage, TestItem, TestMessage, TestRun } from 'vscode';
import type { TestLaunchGroup } from './testPlan';
import type { TestItemDto, TestNodeId } from './testProtocol';
import { GroupReport } from './testRunReport';
import { fileScope } from './testScope';
import { makeTree } from './testTreeFixture';

export const MODULE = 'moduleA';
export const FOO_FILE = 'file:///p/moduleA/src/com/example/Foo.java';
const RANGE = { start: { line: 1, character: 2 }, end: { line: 1, character: 8 } };

export const classDto = (fqn: string): TestItemDto => ({
  id: fqn as TestNodeId,
  kind: 'SUITE',
  displayName: fqn,
  uri: FOO_FILE,
  range: RANGE,
  parentId: null,
  location: `java:suite://${fqn}`,
  moduleName: MODULE,
  groups: [],
});

export const methodDto = (fqn: string, method: string): TestItemDto => ({
  ...classDto(fqn),
  id: `${fqn}#${method}` as TestNodeId,
  kind: 'TEST',
  displayName: method,
  parentId: fqn as TestNodeId,
  location: `java:test://${fqn}/${method}`,
});

export interface RecordedRun {
  readonly run: TestRun;
  readonly calls: string[];
  readonly messages: Map<string, TestMessage>;
  readonly durations: Map<string, number | undefined>;
  readonly coverage: FileCoverage[];
}

export function recordRun(): RecordedRun {
  const calls: string[] = [];
  const messages = new Map<string, TestMessage>();
  const durations = new Map<string, number | undefined>();
  const coverage: FileCoverage[] = [];
  const concluded =
    (call: string) =>
    (item: TestItem, message?: TestMessage | readonly TestMessage[], duration?: number) => {
      calls.push(`${call} ${item.id}`);
      if (message && !Array.isArray(message)) messages.set(item.id, message as TestMessage);
      durations.set(item.id, duration);
    };
  const run = {
    enqueued: (item: TestItem) => calls.push(`enqueued ${item.id}`),
    started: (item: TestItem) => calls.push(`started ${item.id}`),
    passed: (item: TestItem, duration?: number) => {
      calls.push(`passed ${item.id}`);
      durations.set(item.id, duration);
    },
    skipped: (item: TestItem) => calls.push(`skipped ${item.id}`),
    failed: concluded('failed'),
    errored: concluded('errored'),
    appendOutput: (text: string, _location: unknown, item?: TestItem) =>
      calls.push(`output ${item?.id ?? '(run)'} ${JSON.stringify(text)}`),
    addCoverage: (file: FileCoverage) => coverage.push(file),
  } as unknown as TestRun;
  return { run, calls, messages, durations, coverage };
}

export function makeReport({
  dtos = [
    classDto('com.example.Foo'),
    methodDto('com.example.Foo', 'a'),
    methodDto('com.example.Foo', 'b'),
  ],
  launched = [],
}: { readonly dtos?: readonly TestItemDto[]; readonly launched?: readonly string[] } = {}) {
  const { tree } = makeTree();
  tree.sync(fileScope(FOO_FILE), [...dtos]);
  const items = launched.map((id) => tree.get(`${MODULE}/${id}`)!.item);
  const group = {
    moduleName: MODULE,
    items,
    testIds: [],
    uniqueIds: [],
    uri: items[0]?.uri ?? tree.get(`${MODULE}/${dtos[0].id}`)!.item.uri!,
    name: 'Run',
  } satisfies TestLaunchGroup;
  const recorded = recordRun();
  const report = new GroupReport({ run: recorded.run, tree, group });
  return { ...recorded, report, tree, group };
}

export function withErrorLog<T>(body: () => T): { readonly result: T; readonly logged: string[] } {
  const logged: string[] = [];
  const error = console.error;
  console.error = (...args: unknown[]) => void logged.push(args.map(String).join(' '));
  try {
    return { result: body(), logged };
  } finally {
    console.error = error;
  }
}
