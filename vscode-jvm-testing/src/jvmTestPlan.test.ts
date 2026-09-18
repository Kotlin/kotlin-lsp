import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { TestRunRequest } from 'vscode';
import { leafTests, planTestRun } from './jvmTestPlan';
import type { JvmTestItemDto } from './jvmTestProtocol';
import { fileScope } from './jvmTestScope';
import { treeId } from './jvmTestTree';
import { makeTree } from './jvmTestTreeFixture';

const RANGE = { start: { line: 1, character: 2 }, end: { line: 1, character: 8 } };

function dto(
  overrides: Partial<JvmTestItemDto> & Pick<JvmTestItemDto, 'id' | 'uri'>,
): JvmTestItemDto {
  return {
    kind: 'CLASS',
    displayName: overrides.id,
    range: RANGE,
    parentId: null,
    location: overrides.id,
    className:
      overrides.kind === 'METHOD' && overrides.parentId ? overrides.parentId : overrides.id,
    moduleName: 'moduleA',
    runnable: true,
    ...overrides,
  };
}

const URI_A = 'file:///p/moduleA/src/com/example/OneTest.java';
const URI_A2 = 'file:///p/moduleA/src/com/other/TwoTest.java';
const URI_B = 'file:///p/moduleB/src/com/example/OneTest.java';

const request = (overrides: Partial<TestRunRequest> = {}): TestRunRequest =>
  overrides as TestRunRequest;

/** `moduleA → [ids]`, the shape a reader can check at a glance. */
const summarize = (groups: ReturnType<typeof planTestRun>): string[] =>
  groups.map((g) => `${g.moduleName} → ${g.testIds.join(', ')}`);

describe('planning a run', () => {
  test('with no include takes the whole tree', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree)), [
      'moduleA → com.example.OneTest, com.other.TwoTest',
    ]);
  });

  test('descends through the group nodes a package selection lands on', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);
    const packageNode = controller.items
      .get('module:moduleA')!
      .children.get('package:moduleA/com.example')!;

    const groups = planTestRun(request({ include: [packageNode] }), controller.items, tree);

    assert.deepEqual(summarize(groups), ['moduleA → com.example.OneTest']);
  });

  test('stops at a class, whose selector already covers its methods', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'METHOD',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
      dto({
        id: 'com.example.OneTest#b',
        kind: 'METHOD',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree)), [
      'moduleA → com.example.OneTest',
    ]);
  });

  test('takes the methods that were asked for by name', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'METHOD',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);
    const method = tree.get('moduleA/com.example.OneTest#a')!.item;

    const groups = planTestRun(request({ include: [method] }), controller.items, tree);

    assert.deepEqual(summarize(groups), ['moduleA → com.example.OneTest#a']);
  });

  test('names one invocation by the id its runner gave it, which no FQN can say', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'METHOD',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);
    const invocation = tree.ensureRuntimeItem({
      parent: tree.get(treeId('moduleA', 'com.example.OneTest#a'))!.item,
      nodeId: 'uid-1',
      displayName: '[1] first',
    })!;

    const groups = planTestRun(request({ include: [invocation] }), controller.items, tree);

    assert.deepEqual(summarize(groups), ['moduleA → ']);
    assert.deepEqual(groups[0]!.uniqueIds, [
      { className: 'com.example.OneTest', uniqueId: 'uid-1' },
    ]);
  });

  test('leaves out an excluded subtree', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);
    const excludedPackage = controller.items
      .get('module:moduleA')!
      .children.get('package:moduleA/com.other')!;

    const groups = planTestRun(request({ exclude: [excludedPackage] }), controller.items, tree);

    assert.deepEqual(summarize(groups), ['moduleA → com.example.OneTest']);
  });

  test('names the methods of a class one of whose methods is excluded', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'METHOD',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
      dto({
        id: 'com.example.OneTest#b',
        kind: 'METHOD',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
    ]);
    const method = tree.get('moduleA/com.example.OneTest#b')!.item;

    const groups = planTestRun(request({ exclude: [method] }), controller.items, tree);

    assert.deepEqual(summarize(groups), ['moduleA \u2192 com.example.OneTest#a']);
  });

  test('leaves out an excluded nested class', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      dto({ id: 'com.example.OneTest', uri: URI_A }),
      dto({
        id: 'com.example.OneTest#a',
        kind: 'METHOD',
        parentId: 'com.example.OneTest',
        uri: URI_A,
      }),
      dto({ id: 'com.example.OneTest$Inner', parentId: 'com.example.OneTest', uri: URI_A }),
    ]);
    const nested = tree.get('moduleA/com.example.OneTest$Inner')!.item;

    const groups = planTestRun(request({ exclude: [nested] }), controller.items, tree);

    assert.deepEqual(summarize(groups), ['moduleA \u2192 com.example.OneTest#a']);
  });

  test('splits by module, because a module boundary is a classpath boundary', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_B), [
      dto({ id: 'com.example.OneTest', uri: URI_B, moduleName: 'moduleB' }),
    ]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree)), [
      'moduleA → com.example.OneTest',
      'moduleB → com.example.OneTest',
    ]);
  });

  test('keeps the tests of one module together, whatever framework they belong to', () => {
    // The server splits the request into processes: only it knows the framework of a test.
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A })]);
    tree.sync(fileScope(URI_A2), [dto({ id: 'com.other.TwoTest', uri: URI_A2 })]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree)), [
      'moduleA → com.example.OneTest, com.other.TwoTest',
    ]);
  });

  test('skips an abstract class, because a runner finds no test in it', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [dto({ id: 'com.example.OneTest', uri: URI_A, runnable: false })]);

    assert.deepEqual(planTestRun(request(), controller.items, tree), []);
  });

  test('runs a test of a server that reports no runnable flag', () => {
    const { controller, tree } = makeTree();
    const older = dto({ id: 'com.example.OneTest', uri: URI_A });
    delete older.runnable;
    tree.sync(fileScope(URI_A), [older]);

    assert.deepEqual(summarize(planTestRun(request(), controller.items, tree)), [
      'moduleA \u2192 com.example.OneTest',
    ]);
  });

  test('of a node holding no tests is empty, not an error', () => {
    const { controller, tree } = makeTree();

    assert.deepEqual(planTestRun(request(), controller.items, tree), []);
  });
});

describe('the tests a run marks queued', () => {
  const CLASS_ID = 'com.example.OneTest';
  const classDto = dto({ id: CLASS_ID, uri: URI_A });
  const methodDto = (name: string) =>
    dto({ id: `${CLASS_ID}#${name}`, uri: URI_A, kind: 'METHOD', parentId: CLASS_ID });
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
