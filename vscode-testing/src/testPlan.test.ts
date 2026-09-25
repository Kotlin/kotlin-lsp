// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { type TestRunRequest, TestTag } from 'vscode';
import { leafTests } from './testItems';
import { planTestRun, type TestRunPlan } from './testPlan';
import type { TestItemDto, TestNodeId } from './testProtocol';
import { fileScope, moduleScope } from './testScope';
import { treeId } from './testTree';
import { makeTree, PROFILES } from './testTreeFixture';

const RANGE = { start: { line: 1, character: 2 }, end: { line: 1, character: 8 } };

type DtoOverrides = Omit<Partial<TestItemDto>, 'id' | 'parentId'> & {
  readonly id: string;
  readonly uri: string;
  readonly parentId?: string;
};

function dto({ id, parentId, ...overrides }: DtoOverrides): TestItemDto {
  const packageName = id.substring(0, Math.max(id.lastIndexOf('.'), 0));
  return {
    kind: 'SUITE',
    displayName: id,
    range: RANGE,
    location: id,
    moduleName: 'moduleA',
    groups: [{ id: packageName, label: packageName || '(default package)' }],
    ...overrides,
    id: id as TestNodeId,
    parentId: parentId === undefined ? null : (parentId as TestNodeId),
  };
}

const URI_A = 'file:///p/moduleA/src/com/example/OneTest.java';
const URI_A2 = 'file:///p/moduleA/src/com/other/TwoTest.java';
const URI_B = 'file:///p/moduleB/src/com/example/OneTest.java';

const request = (overrides: Partial<TestRunRequest> = {}): TestRunRequest =>
  overrides as TestRunRequest;

const RUN = new TestTag(PROFILES.run.id);
const FUZZ = new TestTag(PROFILES.fuzz.id);

/** `moduleA → [ids]`, the shape a reader can check at a glance. */
const summarize = ({ groups }: TestRunPlan): string[] =>
  groups.map((g) => `${g.moduleName} → ${g.testIds.join(', ')}`);

describe('planning a run', () => {
  test('with no include takes the whole tree', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree, RUN)), [
      'moduleA → com.example.OneTest, com.other.TwoTest',
    ]);
  });

  test('descends through the group nodes a package selection lands on', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);
    const packageNode = controller.items
      .get('module:moduleA')!
      .children.get('group:moduleA/com.example')!;

    const plan = planTestRun(request({ include: [packageNode] }), controller.items, tree, RUN);

    assert.deepEqual(summarize(plan), ['moduleA → com.example.OneTest']);
  });

  test('stops at a class, whose selector already covers its methods', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
      dto({
        id: 'com.example.OneTest#b',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree, RUN)), [
      'moduleA → com.example.OneTest',
    ]);
  });

  test('takes the methods that were asked for by name', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);
    const method = tree.get('moduleA/com.example.OneTest#a')!.item;

    const plan = planTestRun(request({ include: [method] }), controller.items, tree, RUN);

    assert.deepEqual(summarize(plan), ['moduleA → com.example.OneTest#a']);
  });

  test('names one invocation by the id its runner gave it, which no FQN can say', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);
    const invocation = tree.ensureRuntimeItem({
      parent: tree.get(treeId('moduleA', 'com.example.OneTest#a'))!.item,
      nodeId: 'uid-1',
      displayName: '[1] first',
    })!;

    const plan = planTestRun(request({ include: [invocation] }), controller.items, tree, RUN);

    assert.deepEqual(summarize(plan), ['moduleA → ']);
    assert.deepEqual(plan.groups[0]!.uniqueIds, [
      { ownerId: 'com.example.OneTest#a', uniqueId: 'uid-1' },
    ]);
  });

  test('leaves out an excluded subtree', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);
    const excludedPackage = controller.items
      .get('module:moduleA')!
      .children.get('group:moduleA/com.other')!;

    const plan = planTestRun(request({ exclude: [excludedPackage] }), controller.items, tree, RUN);

    assert.deepEqual(summarize(plan), ['moduleA → com.example.OneTest']);
  });

  test('names the methods of a class one of whose methods is excluded', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
      dto({
        id: 'com.example.OneTest#b',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);
    const method = tree.get('moduleA/com.example.OneTest#b')!.item;

    const plan = planTestRun(request({ exclude: [method] }), controller.items, tree, RUN);

    assert.deepEqual(summarize(plan), ['moduleA \u2192 com.example.OneTest#a']);
  });

  test('leaves out an excluded nested class', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
      dto({ id: 'com.example.OneTest$Inner', parentId: 'com.example.OneTest', uri: URI_A }),
    ]);
    const nested = tree.get('moduleA/com.example.OneTest$Inner')!.item;

    const plan = planTestRun(request({ exclude: [nested] }), controller.items, tree, RUN);

    assert.deepEqual(summarize(plan), ['moduleA \u2192 com.example.OneTest#a']);
  });

  test('splits by module, because a module boundary is a classpath boundary', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_B), [
      dto({ id: 'com.example.OneTest', uri: URI_B, moduleName: 'moduleB' }),
    ]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree, RUN)), [
      'moduleA → com.example.OneTest',
      'moduleB → com.example.OneTest',
    ]);
  });

  test('keeps the tests of one module together, whatever framework they belong to', () => {
    // The server splits the request into processes: only it knows the framework of a test.
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree, RUN)), [
      'moduleA → com.example.OneTest, com.other.TwoTest',
    ]);
  });

  test('by a profile for tagged nodes takes only those, under a class it cannot run', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
        tags: [PROFILES.fuzz.id],
      }),
      dto({
        id: 'com.example.OneTest#b',
        kind: 'TEST',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree, FUZZ)), [
      'moduleA → com.example.OneTest#a',
    ]);
  });

  test('names the launch after the single test it runs', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);

    assert.deepEqual(
      planTestRun(request(), controller.items, tree, RUN).groups.map((group) => group.name),
      ['Run com.example.OneTest'],
    );
  });

  test('names the launch after how many tests of which module it runs', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);

    assert.deepEqual(
      planTestRun(request(), controller.items, tree, RUN).groups.map((group) => group.name),
      ['Run 2 tests in moduleA'],
    );
  });

  test('of a node holding no tests is empty, not an error', () => {
    const { controller, tree } = makeTree();

    assert.deepEqual(planTestRun(request(), controller.items, tree, RUN).groups, []);
  });

  test('by a profile for tagged nodes names a suite whose tests it could not see', () => {
    const { controller, tree } = makeTree();
    tree.sync(moduleScope('moduleA'), [dto({ id: 'com.example.OneTest', uri: URI_A })]);

    const plan = planTestRun(request(), controller.items, tree, FUZZ);

    assert.deepEqual(plan.groups, []);
    assert.deepEqual(
      plan.unresolved.map((item) => item.id),
      ['moduleA/com.example.OneTest'],
    );
  });

  test('by a profile for any node takes a suite whose tests are unknown whole', () => {
    const { controller, tree } = makeTree();
    tree.sync(moduleScope('moduleA'), [dto({ id: 'com.example.OneTest', uri: URI_A })]);

    const plan = planTestRun(request(), controller.items, tree, RUN);

    assert.deepEqual(summarize(plan), ['moduleA → com.example.OneTest']);
    assert.deepEqual(plan.unresolved, []);
  });
});

describe('the tests a run marks queued', () => {
  const CLASS_ID = 'com.example.OneTest';
  const classDto = dto({ id: CLASS_ID, uri: URI_A });
  const methodDto = (name: string) =>
    dto({ id: `${CLASS_ID}#${name}`, uri: URI_A, kind: 'TEST', parentId: CLASS_ID });
  const classItem = (tree: ReturnType<typeof makeTree>['tree']) =>
    tree.get(treeId('moduleA', CLASS_ID))!.item;

  test('are the methods of a class that has them', () => {
    const { tree } = makeTree();
    tree.sync(fileScope(URI_A), [classDto, methodDto('a'), methodDto('b')]);

    assert.deepEqual(
      leafTests(classItem(tree)).map((test) => test.label),
      [`${CLASS_ID}#a`, `${CLASS_ID}#b`],
    );
  });

  test('are the class itself while its methods are unknown', () => {
    const { tree } = makeTree();
    tree.sync(fileScope(URI_A), [classDto]);

    assert.deepEqual(leafTests(classItem(tree)), [classItem(tree)]);
  });
});
