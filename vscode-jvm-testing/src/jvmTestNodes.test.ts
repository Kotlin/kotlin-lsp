import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { Uri } from 'vscode';
import { jvmTestNodes } from './jvmTestNodes';
import type { JvmTestLaunchGroup } from './jvmTestPlan';
import type { JvmTestItemDto } from './jvmTestProtocol';
import { fileScope } from './jvmTestScope';
import { makeTree } from './jvmTestTreeFixture';

const MODULE = 'moduleA';
const FOO_FILE = 'file:///p/moduleA/src/com/example/Foo.java';
const RANGE = { start: { line: 1, character: 2 }, end: { line: 1, character: 8 } };

const classDto = (fqn: string): JvmTestItemDto => ({
  id: fqn,
  kind: 'CLASS',
  displayName: fqn,
  uri: FOO_FILE,
  range: RANGE,
  parentId: null,
  location: `java:suite://${fqn}`,
  className: fqn,
  moduleName: MODULE,
  runnable: true,
});

const methodDto = (fqn: string, method: string): JvmTestItemDto => ({
  ...classDto(fqn),
  id: `${fqn}#${method}`,
  kind: 'METHOD',
  displayName: method,
  parentId: fqn,
  location: `java:test://${fqn}/${method}`,
});

/** The classes and methods discovery found, seen the way one run of them sees them. */
function nodesFor() {
  const { tree } = makeTree();
  tree.sync(fileScope(FOO_FILE), [
    classDto('com.example.Foo'),
    methodDto('com.example.Foo', 'test'),
    methodDto('com.example.Foo', 'paramTest'),
    classDto('com.example.Outer$Inner'),
    methodDto('com.example.Outer$Inner', 'test'),
  ]);
  const group = {
    moduleName: MODULE,
    items: [],
    testIds: [],
    uniqueIds: [],
    uri: Uri.parse(FOO_FILE),
  } satisfies JvmTestLaunchGroup;
  return jvmTestNodes(tree, group);
}

const CLASS_HINT = 'java:suite://com.example.Foo';
const METHOD_HINT = 'java:test://com.example.Foo/test';

describe('which node a runner message is about', () => {
  const cases: {
    name: string;
    attributes: Record<string, string>;
    lands: string | undefined;
  }[] = [
    {
      name: 'a java:test hint is the method',
      attributes: { locationHint: METHOD_HINT },
      lands: 'moduleA/com.example.Foo#test',
    },
    {
      name: 'a java:suite hint is the class',
      attributes: { locationHint: CLASS_HINT },
      lands: 'moduleA/com.example.Foo',
    },
    {
      name: 'a hint for a nested class keeps the binary name the server reported',
      attributes: { locationHint: 'java:test://com.example.Outer$Inner/test' },
      lands: 'moduleA/com.example.Outer$Inner#test',
    },
    {
      name: 'a hint and a node id together are still the node the hint names',
      attributes: { locationHint: METHOD_HINT, nodeId: 'n-1' },
      lands: 'moduleA/com.example.Foo#test',
    },
    {
      name: 'a foreign hint is nobody',
      attributes: { locationHint: 'file:///tmp/Foo.java' },
      lands: undefined,
    },
    {
      name: 'a hint of a class nobody discovered is nobody',
      attributes: { locationHint: 'java:suite://com.example.Unknown' },
      lands: undefined,
    },
    {
      name: 'a message that names nothing at all is nobody',
      attributes: { name: 'somethingElse' },
      lands: undefined,
    },
    {
      name: 'a node id alone, before the run reported it, is nobody',
      attributes: { nodeId: 'n-1' },
      lands: undefined,
    },
  ];

  for (const { name, attributes, lands } of cases) {
    test(name, () => {
      assert.equal(nodesFor().locate(attributes)?.id, lands);
    });
  }

  test('a node id the run already explained is that node again, hint or no hint', () => {
    const nodes = nodesFor();

    const started = nodes.locate({ nodeId: 'n-1', locationHint: METHOD_HINT });
    const output = nodes.locate({ nodeId: 'n-1' });

    assert.equal(started?.id, 'moduleA/com.example.Foo#test');
    assert.equal(output, started);
  });

  test('one invocation of a method is its own node, under the method the runner ran', () => {
    const nodes = nodesFor();
    nodes.locate({ nodeId: 'method', locationHint: 'java:test://com.example.Foo/paramTest' });

    const first = nodes.locate({
      nodeId: 'invocation-1',
      parentNodeId: 'method',
      // The invocation of a template repeats the location of the method it runs.
      locationHint: 'java:test://com.example.Foo/paramTest',
      name: '[1] one',
    });

    assert.equal(first?.id, 'moduleA/invocation-1');
    assert.equal(first?.label, '[1] one');
    assert.equal(nodes.locate({ nodeId: 'invocation-1' }), first);
  });

  test('an invocation that reports no location of its own still lands under its parent', () => {
    const nodes = nodesFor();
    nodes.locate({ nodeId: 'method', locationHint: 'java:test://com.example.Foo/paramTest' });

    const item = nodes.locate({ nodeId: 'invocation-2', parentNodeId: 'method', name: '[2] two' });

    assert.equal(item?.id, 'moduleA/invocation-2');
  });

  test('a node under one the run made up nests under it, however deep the runner goes', () => {
    const nodes = nodesFor();
    nodes.locate({ nodeId: 'method', locationHint: 'java:test://com.example.Foo/paramTest' });
    nodes.locate({ nodeId: 'container', parentNodeId: 'method', name: 'rows' });

    const leaf = nodes.locate({ nodeId: 'row-1', parentNodeId: 'container', name: 'first row' });

    assert.equal(leaf?.id, 'moduleA/row-1');
    assert.equal(nodes.locate({ nodeId: 'container' })?.children.get('moduleA/row-1'), leaf);
  });

  test('a method only the runner knows is created under its class, named as the runner named it', () => {
    const nodes = nodesFor();
    nodes.locate({ nodeId: 'class', locationHint: CLASS_HINT });

    const item = nodes.locate({
      nodeId: 'generated-1',
      parentNodeId: 'class',
      name: 'generated(String)',
    });

    assert.equal(item?.id, 'moduleA/generated-1');
    assert.equal(item?.label, 'generated(String)');
  });

  test('the invocations of a data provider are nodes beside the method, as the IDE shows them', () => {
    const nodes = nodesFor();
    nodes.locate({ nodeId: 'com.example.Foo', locationHint: CLASS_HINT });

    const first = nodes.locate({
      nodeId: 'com.example.Foo/paramTest',
      parentNodeId: 'com.example.Foo',
      locationHint: 'java:test://com.example.Foo/paramTest',
      name: 'Foo.paramTest[1]',
    });
    const second = nodes.locate({
      nodeId: 'com.example.Foo/paramTest[1]',
      parentNodeId: 'com.example.Foo',
      locationHint: 'java:test://com.example.Foo/paramTest[1]',
      name: 'Foo.paramTest[2] (1)',
    });

    assert.equal(first?.id, 'moduleA/com.example.Foo#paramTest');
    assert.equal(second?.id, 'moduleA/com.example.Foo/paramTest[1]');
    assert.equal(second?.label, 'Foo.paramTest[2] (1)');
    // A rerun of that node sends the runner's own id back, so the server can run that row alone.
    assert.equal(nodes.locate({ nodeId: 'com.example.Foo/paramTest[1]' }), second);
  });

  test('a failure the runner reports beside a class is a node of its own under the class', () => {
    // A JUnit 5 @BeforeAll failure comes as "Class Configuration", with the location of the class.
    // The class node keeps the outcome all the same: it rolls up what its children reported.
    const nodes = nodesFor();
    nodes.locate({ nodeId: 'class', locationHint: CLASS_HINT });

    const item = nodes.locate({
      nodeId: 'class/configuration',
      parentNodeId: 'class',
      locationHint: CLASS_HINT,
      name: 'Class Configuration',
    });

    assert.equal(item?.id, 'moduleA/class/configuration');
    assert.equal(item?.label, 'Class Configuration');
  });

  test('a run named by a suite file still lands on the classes and methods of the tree', () => {
    // A `testng.xml` run reports the suite and the test of the XML above the class. The tree holds
    // no such node, and needs none: the class and the method are found by their own locations.
    const nodes = nodesFor();

    const suite = nodes.locate({
      nodeId: 'MySuite',
      parentNodeId: '0',
      locationHint: 'file:///p/moduleA/testng.xml',
      name: 'MySuite',
    });
    const classItem = nodes.locate({
      nodeId: 'com.example.Foo',
      parentNodeId: 'MySuite',
      locationHint: CLASS_HINT,
    });
    const method = nodes.locate({
      nodeId: 'com.example.Foo/test',
      parentNodeId: 'com.example.Foo',
      locationHint: METHOD_HINT,
    });

    assert.equal(suite, undefined);
    assert.equal(classItem?.id, 'moduleA/com.example.Foo');
    assert.equal(method?.id, 'moduleA/com.example.Foo#test');
  });

  test('a node whose parent the run never explained has nowhere to go', () => {
    const nodes = nodesFor();

    assert.equal(nodes.locate({ nodeId: 'orphan', parentNodeId: 'nobody' }), undefined);
  });
});

describe('where a class is written', () => {
  test('a discovered class answers with its file', () => {
    assert.equal(nodesFor().fileOf('com.example.Foo')?.toString(), FOO_FILE);
  });

  test('a class nobody discovered answers with nothing, so its frames do not navigate', () => {
    assert.equal(nodesFor().fileOf('org.junit.Assert'), undefined);
  });
});
