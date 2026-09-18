import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { TestController, TestItem, TextDocument, Uri } from 'vscode';
import { JvmTestDiscovery, type JvmTestDiscoveryApi } from './jvmTestDiscovery';
import type { GroupNode, TestTreeGrouping } from './jvmTestGrouping';
import type { JvmTestItemDto } from './jvmTestProtocol';
import { fileScope, moduleScope } from './jvmTestScope';
import { type JvmTestTree, RUNNABLE_TAG, treeId } from './jvmTestTree';
import { makeItem, makeTree } from './jvmTestTreeFixture';

const RANGE = { start: { line: 1, character: 2 }, end: { line: 1, character: 8 } };

const idOfMethod = (fqn: string, method: string): string => `${fqn}#${method}`;

function classDto(moduleName: string | null, fqn: string, uri: string): JvmTestItemDto {
  return {
    id: fqn,
    kind: 'CLASS',
    displayName: fqn.substring(fqn.lastIndexOf('.') + 1),
    uri,
    range: RANGE,
    parentId: null,
    location: `java:suite://${fqn}`,
    className: fqn,
    moduleName,
    runnable: true,
  };
}

function nestedClassDto(
  moduleName: string | null,
  outerFqn: string,
  name: string,
  uri: string,
): JvmTestItemDto {
  return {
    ...classDto(moduleName, `${outerFqn}$${name}`, uri),
    displayName: name,
    parentId: outerFqn,
  };
}

function methodDto(
  moduleName: string | null,
  fqn: string,
  method: string,
  uri: string,
): JvmTestItemDto {
  return {
    id: idOfMethod(fqn, method),
    kind: 'METHOD',
    displayName: method,
    uri,
    range: RANGE,
    parentId: fqn,
    location: `java:test://${fqn}/${method}`,
    className: fqn,
    moduleName,
    runnable: true,
  };
}

/** Ids of the whole tree, depth first, as `parent > child` paths. */
function paths(collection: { forEach(cb: (item: TestItem) => void): void }, prefix = ''): string[] {
  const result: string[] = [];
  collection.forEach((item) => {
    const path = prefix ? `${prefix} > ${item.id}` : item.id;
    result.push(path);
    result.push(...paths(item.children, path));
  });
  return result;
}

const URI_A = 'file:///p/moduleA/src/com/example/SampleTest.java';
const URI_OTHER = 'file:///p/moduleA/src/com/example/OtherTest.java';
const URI_B = 'file:///p/moduleB/src/com/example/SampleTest.java';
const URI_ROOTLESS = 'file:///p/SampleTest.java';

describe('the tree', () => {
  test('a class and its methods are grouped under module and package nodes', () => {
    const { controller, tree } = makeTree();

    tree.sync(fileScope(URI_A), [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testTwo', URI_A),
    ]);

    assert.deepEqual(paths(controller.items), [
      'module:moduleA',
      'module:moduleA > package:moduleA/com.example',
      'module:moduleA > package:moduleA/com.example > moduleA/com.example.SampleTest',
      'module:moduleA > package:moduleA/com.example > moduleA/com.example.SampleTest > moduleA/com.example.SampleTest#testOne',
      'module:moduleA > package:moduleA/com.example > moduleA/com.example.SampleTest > moduleA/com.example.SampleTest#testTwo',
    ]);
    const entry = tree.get(treeId('moduleA', 'com.example.SampleTest'));
    assert.equal(entry?.dto.id, 'com.example.SampleTest');
    assert.equal(entry?.dto.className, 'com.example.SampleTest');
    assert.equal(entry?.dto.uri, URI_A);
  });

  test('a class the server sent without a parentId is a root', () => {
    // `explicitNulls = false`: the server drops a null field, so no parentId reaches the client.
    const { controller, tree } = makeTree();
    const dto = classDto('moduleA', 'com.example.SampleTest', URI_A);
    delete dto.parentId;

    tree.sync(fileScope(URI_A), [dto]);

    assert.deepEqual(paths(controller.items), [
      'module:moduleA',
      'module:moduleA > package:moduleA/com.example',
      'module:moduleA > package:moduleA/com.example > moduleA/com.example.SampleTest',
    ]);
  });

  test('a nested class hangs under the class that holds it, not under the package', () => {
    const { controller, tree } = makeTree();
    const outer = 'com.example.SampleTest';
    const inner = `${outer}$Inner`;

    tree.sync(fileScope(URI_A), [
      classDto('moduleA', outer, URI_A),
      methodDto('moduleA', outer, 'testOne', URI_A),
      nestedClassDto('moduleA', outer, 'Inner', URI_A),
      methodDto('moduleA', inner, 'testTwo', URI_A),
    ]);

    const pkg = `module:moduleA > package:moduleA/com.example`;
    assert.deepEqual(paths(controller.items), [
      'module:moduleA',
      pkg,
      `${pkg} > moduleA/${outer}`,
      `${pkg} > moduleA/${outer} > moduleA/${outer}#testOne`,
      `${pkg} > moduleA/${outer} > moduleA/${inner}`,
      `${pkg} > moduleA/${outer} > moduleA/${inner} > moduleA/${inner}#testTwo`,
    ]);
    assert.equal(tree.get(`moduleA/${inner}`)?.item.label, 'Inner');
  });

  test('group nodes carry the runnable tag, or the Run profile refuses to run a package', () => {
    // Both run profiles are tag-filtered (`jvmTestController.ts`), and VS Code only offers a
    // profile on items carrying its tag — an untagged package node simply has no Run action.
    const { controller, tree } = makeTree();

    tree.sync(fileScope(URI_A), [classDto('moduleA', 'com.example.SampleTest', URI_A)]);

    const moduleNode = controller.items.get('module:moduleA')!;
    assert.deepEqual(moduleNode.tags, [RUNNABLE_TAG]);
    assert.deepEqual(moduleNode.children.get('package:moduleA/com.example')?.tags, [RUNNABLE_TAG]);
  });

  test('a class the runner cannot instantiate carries no runnable tag', () => {
    const { tree } = makeTree();

    tree.sync(fileScope(URI_A), [
      { ...classDto('moduleA', 'com.example.SampleTest', URI_A), runnable: false },
    ]);

    assert.deepEqual(tree.get('moduleA/com.example.SampleTest')?.item.tags, []);
  });

  test('a test of a server that reports no runnable flag keeps the tag', () => {
    const { tree } = makeTree();
    const older = classDto('moduleA', 'com.example.SampleTest', URI_A);
    delete older.runnable;

    tree.sync(fileScope(URI_A), [older]);

    assert.deepEqual(tree.get('moduleA/com.example.SampleTest')?.item.tags, [RUNNABLE_TAG]);
  });

  test('a class in the default package of a module-less file goes to the top level', () => {
    const { controller, tree } = makeTree();

    tree.sync(fileScope(URI_ROOTLESS), [classDto(null, 'SampleTest', URI_ROOTLESS)]);

    assert.deepEqual(paths(controller.items), ['package:/', 'package:/ > /SampleTest']);
    assert.equal(controller.items.get('package:/')?.label, '(default package)');
  });

  test('the same test class in two modules gives two independent nodes', () => {
    const { controller, tree } = makeTree();

    tree.sync(fileScope(URI_A), [classDto('moduleA', 'com.example.SampleTest', URI_A)]);
    tree.sync(fileScope(URI_B), [classDto('moduleB', 'com.example.SampleTest', URI_B)]);

    assert.deepEqual(paths(controller.items), [
      'module:moduleA',
      'module:moduleA > package:moduleA/com.example',
      'module:moduleA > package:moduleA/com.example > moduleA/com.example.SampleTest',
      'module:moduleB',
      'module:moduleB > package:moduleB/com.example',
      'module:moduleB > package:moduleB/com.example > moduleB/com.example.SampleTest',
    ]);
    assert.notEqual(
      tree.get('moduleA/com.example.SampleTest')?.item,
      tree.get('moduleB/com.example.SampleTest')?.item,
    );
  });

  test('a class is marked as resolvable so expanding it can fetch its methods', () => {
    const { tree } = makeTree();

    tree.sync(fileScope(URI_A), [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
    ]);

    assert.equal(tree.get('moduleA/com.example.SampleTest')?.item.canResolveChildren, true);
    assert.equal(
      tree.get('moduleA/com.example.SampleTest#testOne')?.item.canResolveChildren,
      undefined,
    );
  });

  test('re-syncing a file keeps the very same TestItem', () => {
    // VS Code compares TestItems by reference, and a run in progress may still hold the old object.
    const { tree } = makeTree();
    const dtos = [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
    ];
    tree.sync(fileScope(URI_A), dtos);
    const classItem = tree.get('moduleA/com.example.SampleTest')?.item;
    const methodItem = tree.get('moduleA/com.example.SampleTest#testOne')?.item;

    tree.sync(fileScope(URI_A), dtos);

    assert.equal(tree.get('moduleA/com.example.SampleTest')?.item, classItem);
    assert.equal(tree.get('moduleA/com.example.SampleTest#testOne')?.item, methodItem);
  });

  test('a method that is gone from the class disappears from the tree', () => {
    const { tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testTwo', URI_A),
    ]);

    tree.sync(fileScope(URI_A), [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
    ]);

    const classItem = tree.get('moduleA/com.example.SampleTest')?.item;
    assert.deepEqual(paths(classItem!.children), ['moduleA/com.example.SampleTest#testOne']);
    assert.equal(tree.get('moduleA/com.example.SampleTest#testTwo'), undefined);
  });

  test('a scope that does not cover methods keeps the ones already resolved', () => {
    const { tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
    ]);

    tree.sync(moduleScope('moduleA'), [classDto('moduleA', 'com.example.SampleTest', URI_A)]);

    const classItem = tree.get('moduleA/com.example.SampleTest')?.item;
    assert.deepEqual(paths(classItem!.children), ['moduleA/com.example.SampleTest#testOne']);
  });

  test('removing the last class of a module drops its package and module nodes', () => {
    const { controller, tree } = makeTree();
    tree.sync(fileScope(URI_A), [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
    ]);
    tree.sync(fileScope(URI_B), [classDto('moduleB', 'com.example.SampleTest', URI_B)]);

    tree.sync(fileScope(URI_A), []);

    assert.deepEqual(paths(controller.items), [
      'module:moduleB',
      'module:moduleB > package:moduleB/com.example',
      'module:moduleB > package:moduleB/com.example > moduleB/com.example.SampleTest',
    ]);
    // The class going away takes its methods with it.
    assert.equal(tree.get('moduleA/com.example.SampleTest'), undefined);
    assert.equal(tree.get('moduleA/com.example.SampleTest#testOne'), undefined);
  });

  test('a test that moved to another file is no longer covered by the old file', () => {
    const { tree } = makeTree();
    const moved = 'file:///p/moduleA/src/com/example/MovedTest.java';
    tree.sync(fileScope(URI_A), [classDto('moduleA', 'com.example.SampleTest', URI_A)]);

    tree.sync(fileScope(moved), [classDto('moduleA', 'com.example.SampleTest', moved)]);
    // The old file reporting nothing must not take the test with it — it lives in `moved` now.
    tree.sync(fileScope(URI_A), []);

    assert.equal(tree.get('moduleA/com.example.SampleTest')?.dto.uri, moved);
  });

  test('an inherited method survives a scan of the file it is written in', () => {
    const { tree } = makeTree();
    const base = 'file:///p/moduleA/src/com/example/BaseTest.java';
    tree.sync(fileScope(URI_A), [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', base),
    ]);

    tree.sync(fileScope(base), [classDto('moduleA', 'com.example.BaseTest', base)]);

    assert.equal(tree.get('moduleA/com.example.SampleTest#testOne')?.dto.uri, base);
  });

  test('a module scope prunes only its own classes, not tests outside any module', () => {
    const { tree } = makeTree();
    tree.sync(fileScope(URI_ROOTLESS), [classDto(null, 'SampleTest', URI_ROOTLESS)]);
    tree.sync(moduleScope('moduleA'), [classDto('moduleA', 'com.example.SampleTest', URI_A)]);

    tree.sync(moduleScope('moduleA'), []);

    assert.equal(tree.get('moduleA/com.example.SampleTest'), undefined);
    assert.equal(tree.get('/SampleTest')?.dto.id, 'SampleTest');
  });
});

describe('a node the runner reported but discovery never did', () => {
  const CLASS_ID = 'com.example.SampleTest';
  const TEST_ID = idOfMethod(CLASS_ID, 'testOne');
  /** The runner's own name for the node, which the client never reads. */
  const NODE_ID = 'runner-node-1';

  /** A collapsed class: the module scan reported it without methods. */
  function treeWithCollapsedClass() {
    const made = makeTree();
    made.tree.sync(moduleScope('moduleA'), [classDto('moduleA', CLASS_ID, URI_A)]);
    return made;
  }

  const itemOf = (tree: JvmTestTree, id: string): TestItem => tree.get(treeId('moduleA', id))!.item;

  const runtimeItem = (
    tree: JvmTestTree,
    parentId: string,
    nodeId = NODE_ID,
    label = 'testOne()',
  ) => tree.ensureRuntimeItem({ parent: itemOf(tree, parentId), nodeId, displayName: label });

  test('is created under its class, inheriting everything it takes to launch it', () => {
    const { controller, tree } = treeWithCollapsedClass();

    const item = runtimeItem(tree, CLASS_ID);

    assert.equal(item?.label, 'testOne()');
    assert.equal(paths(controller.items).at(-1)?.endsWith(`> moduleA/${NODE_ID}`), true);
    const dto = tree.get(treeId('moduleA', NODE_ID))?.dto;
    assert.deepEqual(
      { kind: dto?.kind, parentId: dto?.parentId, className: dto?.className, uri: dto?.uri },
      { kind: 'METHOD', parentId: CLASS_ID, className: CLASS_ID, uri: URI_A },
    );
    // Only a discovered node has a location, so this one is found by the runner's id alone.
    assert.equal(dto?.location, undefined);
    assert.equal(tree.get(treeId('moduleA', NODE_ID))?.uniqueId, NODE_ID);
  });

  test('is returned as is when asked for twice, so a rerun does not duplicate it', () => {
    const { tree } = treeWithCollapsedClass();

    assert.equal(runtimeItem(tree, CLASS_ID), runtimeItem(tree, CLASS_ID));
  });

  test('is an invocation under its method, in the order the runner reported it', () => {
    const { tree } = treeWithCollapsedClass();
    tree.sync(fileScope(URI_A), [
      classDto('moduleA', CLASS_ID, URI_A),
      methodDto('moduleA', CLASS_ID, 'testOne', URI_A),
    ]);

    const second = runtimeItem(tree, TEST_ID, 'uid-10', '[10] ten');
    const first = runtimeItem(tree, TEST_ID, 'uid-2', '[2] two');

    assert.deepEqual(
      [...itemOf(tree, TEST_ID).children].map(([, child]) => child.id),
      ['moduleA/uid-10', 'moduleA/uid-2'],
    );
    assert.equal(second!.sortText! < first!.sortText!, true);
    assert.equal(tree.get(first!.id)?.uniqueId, 'uid-2');
  });

  test('has nowhere to go when the node it hangs under is not in the tree', () => {
    const { tree } = makeTree();

    const stray = makeItem('moduleA/gone', 'gone');
    assert.equal(
      tree.ensureRuntimeItem({ parent: stray, nodeId: NODE_ID, displayName: 'testOne()' }),
      undefined,
    );
  });

  test('outlives a discovery of its own file, which never knew it existed', () => {
    const { tree } = treeWithCollapsedClass();
    runtimeItem(tree, CLASS_ID);

    tree.sync(fileScope(URI_A), [
      classDto('moduleA', CLASS_ID, URI_A),
      methodDto('moduleA', CLASS_ID, 'testTwo', URI_A),
    ]);

    assert.notEqual(tree.get(treeId('moduleA', NODE_ID)), undefined);
    assert.notEqual(tree.get(treeId('moduleA', idOfMethod(CLASS_ID, 'testTwo'))), undefined);
  });

  test('still goes when the class it hangs under does', () => {
    const { tree } = treeWithCollapsedClass();
    runtimeItem(tree, CLASS_ID);

    tree.sync(moduleScope('moduleA'), []);

    assert.equal(tree.get(treeId('moduleA', NODE_ID)), undefined);
  });

  test('is cleared by the next run of the node above it, not by discovery', () => {
    // Otherwise a parameterized test that went from three rows of data to two keeps a phantom third.
    const { tree } = treeWithCollapsedClass();
    const classItem = itemOf(tree, CLASS_ID);
    runtimeItem(tree, CLASS_ID);

    tree.forgetRuntimeChildren(classItem);

    assert.equal(tree.get(treeId('moduleA', NODE_ID)), undefined);
    assert.deepEqual(paths(classItem.children), []);
  });

  test('is cleared even when it hangs below a discovered method', () => {
    const { tree } = treeWithCollapsedClass();
    tree.sync(fileScope(URI_A), [
      classDto('moduleA', CLASS_ID, URI_A),
      methodDto('moduleA', CLASS_ID, 'testOne', URI_A),
    ]);
    const classItem = itemOf(tree, CLASS_ID);
    runtimeItem(tree, TEST_ID, 'uid-1', '[1] first');

    tree.forgetRuntimeChildren(classItem);

    assert.equal(tree.get(treeId('moduleA', 'uid-1')), undefined);
    // The discovered method it hung under is not the run's to remove.
    assert.notEqual(tree.get(treeId('moduleA', TEST_ID)), undefined);
  });

  test('survives a module pass, which never claims to know any methods', () => {
    const { tree } = treeWithCollapsedClass();
    runtimeItem(tree, CLASS_ID);

    tree.sync(moduleScope('moduleA'), [classDto('moduleA', CLASS_ID, URI_A)]);

    assert.notEqual(tree.get(treeId('moduleA', NODE_ID)), undefined);
  });
});

// Grouping is a strategy, so the tree must not assume the two levels the default one happens to build.
/** Three levels deep, so pruning has more than the default's module/package to fold up. */
const nestedGrouping: TestTreeGrouping = {
  groupsFor: (dto): GroupNode[] => [
    { id: 'root', label: 'All' },
    { id: `by-module:${dto.moduleName}`, label: dto.moduleName ?? '(none)' },
    { id: `by-file:${dto.uri}`, label: dto.uri },
  ],
};

describe('a custom grouping', () => {
  test('decides the shape of the tree', () => {
    const { controller, tree } = makeTree(nestedGrouping);

    tree.sync(fileScope(URI_A), [
      classDto('moduleA', 'com.example.SampleTest', URI_A),
      methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
    ]);

    assert.deepEqual(paths(controller.items), [
      'root',
      'root > by-module:moduleA',
      `root > by-module:moduleA > by-file:${URI_A}`,
      `root > by-module:moduleA > by-file:${URI_A} > moduleA/com.example.SampleTest`,
      `root > by-module:moduleA > by-file:${URI_A} > moduleA/com.example.SampleTest > moduleA/com.example.SampleTest#testOne`,
    ]);
  });

  test('has its empty group nodes folded up at any depth', () => {
    const { controller, tree } = makeTree(nestedGrouping);
    tree.sync(fileScope(URI_A), [classDto('moduleA', 'com.example.SampleTest', URI_A)]);

    tree.sync(fileScope(URI_A), []);

    assert.deepEqual(paths(controller.items), []);
  });
});

// --- discovery: the real tree, against a server that answers the three discovery commands

/** The discovery commands, each defaulting to "nothing found" so a test only spells out what it cares about. */
function fakeServer(overrides: Partial<JvmTestDiscoveryApi> = {}): JvmTestDiscoveryApi {
  return {
    testsInFile: async () => [],
    testModules: async () => [],
    testsInModule: async () => [],
    importInProgress: async () => false,
    ...overrides,
  };
}

function makeDiscovery(server: JvmTestDiscoveryApi) {
  const { controller, tree } = makeTree();
  return { controller, tree, discovery: new JvmTestDiscovery(tree, () => server) };
}

/** What VS Code does when the user expands a module node: its classes are scanned only then. */
async function expandModule(
  controller: TestController,
  discovery: JvmTestDiscovery,
  moduleName: string,
): Promise<void> {
  const item = controller.items.get(`module:${moduleName}`);
  assert.ok(item, `the tree has no node for module '${moduleName}'`);
  assert.equal(item.canResolveChildren, true, 'a module node must be expandable');
  await discovery.resolve(item);
}

function javaDocument(uri: string): TextDocument {
  return { languageId: 'java', uri: fakeUri(uri) } as unknown as TextDocument;
}

function fakeUri(uri: string): Uri {
  return { toString: () => uri } as unknown as Uri;
}

/** Silences the `console.error` a deliberately failing request logs, so test output stays readable. */
async function withoutErrorLog(body: () => Promise<void>): Promise<void> {
  const original = console.error;
  console.error = () => {};
  try {
    await body();
  } finally {
    console.error = original;
  }
}

describe('discovery of one file', () => {
  test('puts what the server reported into the tree', async () => {
    const { tree, discovery } = makeDiscovery(
      fakeServer({
        testsInFile: async () => [
          classDto('moduleA', 'com.example.SampleTest', URI_A),
          methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
        ],
      }),
    );

    await discovery.refreshFile(javaDocument(URI_A));

    assert.equal(tree.get('moduleA/com.example.SampleTest')?.dto.uri, URI_A);
    assert.equal(tree.get('moduleA/com.example.SampleTest#testOne')?.dto.displayName, 'testOne');
  });

  test('leaves the file as it was when the request fails', async () => {
    let failing = false;
    const { tree, discovery } = makeDiscovery(
      fakeServer({
        testsInFile: async () => {
          if (failing) throw new Error('server is busy');
          return [classDto('moduleA', 'com.example.SampleTest', URI_A)];
        },
      }),
    );
    await discovery.refreshFile(javaDocument(URI_A));

    failing = true;
    await withoutErrorLog(() => discovery.refreshFile(javaDocument(URI_A)));

    assert.ok(
      tree.get('moduleA/com.example.SampleTest'),
      'a failed re-scan must not empty the file',
    );
  });

  test('happens for a Kotlin document too', async () => {
    let asked = false;
    const { discovery } = makeDiscovery(
      fakeServer({
        testsInFile: async () => {
          asked = true;
          return [];
        },
      }),
    );

    await discovery.refreshFile({
      languageId: 'kotlin',
      uri: fakeUri('file:///p/SampleTest.kt'),
    } as unknown as TextDocument);

    assert.equal(asked, true);
  });

  test('does not happen at all for a document of another language', async () => {
    let asked = false;
    const { controller, discovery } = makeDiscovery(
      fakeServer({
        testsInFile: async () => {
          asked = true;
          return [];
        },
      }),
    );

    await discovery.refreshFile({
      languageId: 'json',
      uri: fakeUri('file:///p/package.json'),
    } as unknown as TextDocument);

    assert.equal(asked, false);
    assert.deepEqual(paths(controller.items), []);
  });

  test('fills in the methods of a class the user expanded', async () => {
    const { controller, tree, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => ['moduleA'],
        testsInModule: async () => [classDto('moduleA', 'com.example.SampleTest', URI_A)],
        testsInFile: async () => [
          classDto('moduleA', 'com.example.SampleTest', URI_A),
          methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
        ],
      }),
    );
    // A module scan reports classes only, so the class starts out childless.
    await discovery.refreshWorkspace();
    await expandModule(controller, discovery, 'moduleA');
    const classItem = tree.get('moduleA/com.example.SampleTest')!.item;
    assert.equal(classItem.children.size, 0);

    await discovery.resolve(classItem);

    assert.deepEqual(paths(classItem.children), ['moduleA/com.example.SampleTest#testOne']);
  });

  test('drops the tests of a file that is gone', async () => {
    const { controller, discovery } = makeDiscovery(
      fakeServer({
        testsInFile: async () => [classDto('moduleA', 'com.example.SampleTest', URI_A)],
      }),
    );
    await discovery.refreshFile(javaDocument(URI_A));

    discovery.forgetFile(fakeUri(URI_A));

    assert.deepEqual(paths(controller.items), []);
  });
});

describe('discovery before a run', () => {
  /** A module scan reports classes only, which is how a class comes to be run without its methods. */
  async function classesOfModuleA(asked: string[]) {
    const { controller, tree, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => ['moduleA'],
        testsInModule: async () => [
          classDto('moduleA', 'com.example.SampleTest', URI_A),
          classDto('moduleA', 'com.example.OtherTest', URI_OTHER),
        ],
        testsInFile: async (uri) => {
          asked.push(uri);
          const fqn = uri === URI_A ? 'com.example.SampleTest' : 'com.example.OtherTest';
          return [classDto('moduleA', fqn, uri), methodDto('moduleA', fqn, 'testOne', uri)];
        },
      }),
    );
    await discovery.refreshWorkspace();
    await expandModule(controller, discovery, 'moduleA');
    return { tree, discovery };
  }

  test('fills in the methods of every class about to run', async () => {
    const asked: string[] = [];
    const { tree, discovery } = await classesOfModuleA(asked);
    const running = [
      tree.get('moduleA/com.example.SampleTest')!.item,
      tree.get('moduleA/com.example.OtherTest')!.item,
    ];

    await discovery.resolveMethods(running);

    assert.deepEqual(paths(running[0].children), ['moduleA/com.example.SampleTest#testOne']);
    assert.deepEqual(paths(running[1].children), ['moduleA/com.example.OtherTest#testOne']);
    assert.deepEqual([...asked].sort(), [URI_A, URI_OTHER].sort());
  });

  test('asks about a file once, however many of its classes run', async () => {
    const asked: string[] = [];
    const nested = 'com.example.SampleTest$Inner';
    const { controller, tree, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => ['moduleA'],
        testsInModule: async () => [
          classDto('moduleA', 'com.example.SampleTest', URI_A),
          nestedClassDto('moduleA', 'com.example.SampleTest', 'Inner', URI_A),
        ],
        testsInFile: async (uri) => {
          asked.push(uri);
          return [
            classDto('moduleA', 'com.example.SampleTest', URI_A),
            methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
            nestedClassDto('moduleA', 'com.example.SampleTest', 'Inner', URI_A),
            methodDto('moduleA', nested, 'testTwo', URI_A),
          ];
        },
      }),
    );
    await discovery.refreshWorkspace();
    await expandModule(controller, discovery, 'moduleA');

    await discovery.resolveMethods([
      tree.get('moduleA/com.example.SampleTest')!.item,
      tree.get(`moduleA/${nested}`)!.item,
    ]);

    assert.deepEqual(asked, [URI_A]);
    assert.deepEqual(paths(tree.get(`moduleA/${nested}`)!.item.children), [
      `moduleA/${nested}#testTwo`,
    ]);
  });

  test('asks about a class whose only method came from a run, not from discovery', async () => {
    const asked: string[] = [];
    const { tree, discovery } = makeDiscovery(
      fakeServer({
        testsInFile: async (uri) => {
          asked.push(uri);
          return [
            classDto('moduleA', 'com.example.SampleTest', URI_A),
            methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
          ];
        },
      }),
    );
    tree.sync(moduleScope('moduleA'), [classDto('moduleA', 'com.example.SampleTest', URI_A)]);
    const testId = idOfMethod('com.example.SampleTest', 'testOne');
    tree.ensureRuntimeItem({
      parent: tree.get('moduleA/com.example.SampleTest')!.item,
      nodeId: 'runner-node-1',
      displayName: 'testOne()',
    });

    await discovery.resolveMethods([tree.get('moduleA/com.example.SampleTest')!.item]);

    assert.deepEqual(asked, [URI_A]);
    assert.equal(tree.get(`moduleA/${testId}`)?.origin, 'discovered');
  });

  test('leaves alone a class that has its methods, and a single method', async () => {
    const asked: string[] = [];
    const { tree, discovery } = makeDiscovery(
      fakeServer({
        testsInFile: async (uri) => {
          asked.push(uri);
          return [
            classDto('moduleA', 'com.example.SampleTest', URI_A),
            methodDto('moduleA', 'com.example.SampleTest', 'testOne', URI_A),
          ];
        },
      }),
    );
    await discovery.refreshFile(javaDocument(URI_A));
    asked.length = 0;

    await discovery.resolveMethods([
      tree.get('moduleA/com.example.SampleTest')!.item,
      tree.get('moduleA/com.example.SampleTest#testOne')!.item,
    ]);

    assert.deepEqual(asked, []);
  });
});

describe('discovery of the whole workspace', () => {
  test('reports the modules without scanning a single one of them', async () => {
    const scanned: string[] = [];
    const { controller, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => ['moduleA', 'moduleB'],
        testsInModule: async (name) => {
          scanned.push(name);
          return [];
        },
      }),
    );

    await discovery.refreshWorkspace();

    assert.deepEqual(paths(controller.items), ['module:moduleA', 'module:moduleB']);
    assert.deepEqual(scanned, [], 'a workspace pass must scan no module');
  });

  test('fills a module the user expanded, and only that one', async () => {
    const { controller, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => ['moduleA', 'moduleB'],
        testsInModule: async (name) => [
          classDto(name, `com.example.${name}Test`, name === 'moduleA' ? URI_A : URI_B),
        ],
      }),
    );
    await discovery.refreshWorkspace();

    await expandModule(controller, discovery, 'moduleA');

    assert.deepEqual(paths(controller.items), [
      'module:moduleA',
      'module:moduleA > package:moduleA/com.example',
      'module:moduleA > package:moduleA/com.example > moduleA/com.example.moduleATest',
      'module:moduleB',
    ]);
  });

  test('keeps the tests of a module whose scan failed, and updates the rest', async () => {
    let brokenFails = false;
    const { controller, tree, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => ['broken', 'moduleB'],
        testsInModule: async (name) => {
          if (name === 'broken') {
            if (brokenFails) throw new Error('indexing');
            return [classDto('broken', 'com.example.BrokenTest', URI_A)];
          }
          return [classDto('moduleB', 'com.example.SampleTest', URI_B)];
        },
      }),
    );
    await discovery.refreshWorkspace();
    await expandModule(controller, discovery, 'broken');

    brokenFails = true;
    await discovery.refreshWorkspace();
    await withoutErrorLog(() => expandModule(controller, discovery, 'broken'));
    await expandModule(controller, discovery, 'moduleB');

    assert.ok(tree.get('broken/com.example.BrokenTest'), 'a failed module must not lose its tests');
    assert.ok(tree.get('moduleB/com.example.SampleTest'));
  });

  test('drops the tests and the node of a module that left the workspace', async () => {
    let modules = ['moduleA', 'moduleB'];
    const { controller, tree, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => modules,
        testsInModule: async (name) => [
          classDto(name, 'com.example.SampleTest', name === 'moduleA' ? URI_A : URI_B),
        ],
      }),
    );
    await discovery.refreshWorkspace();
    await expandModule(controller, discovery, 'moduleA');
    await expandModule(controller, discovery, 'moduleB');

    modules = ['moduleA'];
    await discovery.refreshWorkspace();

    assert.ok(tree.get('moduleA/com.example.SampleTest'));
    assert.equal(tree.get('moduleB/com.example.SampleTest'), undefined);
    assert.equal(controller.items.get('module:moduleB'), undefined);
  });

  test('changes nothing when the module list itself fails', async () => {
    let listFails = false;
    const { controller, tree, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => {
          if (listFails) throw new Error('no project yet');
          return ['moduleA'];
        },
        testsInModule: async () => [classDto('moduleA', 'com.example.SampleTest', URI_A)],
      }),
    );
    await discovery.refreshWorkspace();
    await expandModule(controller, discovery, 'moduleA');

    listFails = true;
    await withoutErrorLog(() => discovery.refreshWorkspace());

    assert.ok(
      tree.get('moduleA/com.example.SampleTest'),
      'a failed pass must not prune everything',
    );
  });

  test('scans every module of a whole-workspace run once, a bounded number at a time', async () => {
    const modules = Array.from({ length: 10 }, (_, index) => `module${index}`);
    const scanned: string[] = [];
    let inFlight = 0;
    let peak = 0;
    const { controller, discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => modules,
        testsInModule: async (name) => {
          peak = Math.max(peak, ++inFlight);
          await new Promise((resolve) => setTimeout(resolve, 1));
          inFlight--;
          scanned.push(name);
          return [];
        },
      }),
    );
    await discovery.refreshWorkspace();
    const roots = [...controller.items].map(([, item]) => item);

    await discovery.resolveModules(roots);
    await discovery.resolveModules(roots);

    assert.deepEqual([...scanned].sort(), [...modules].sort());
    assert.ok(peak > 1, `expected concurrent requests, saw a peak of ${peak}`);
    assert.ok(peak <= 4, `expected at most 4 concurrent requests, saw ${peak}`);
  });

  test('waits for the workspace import instead of pruning the tree down to no modules', async () => {
    let importing = true;
    const asked: string[] = [];
    const { controller, tree, discovery } = makeDiscovery(
      fakeServer({
        importInProgress: async () => importing,
        testModules: async () => {
          asked.push('modules');
          return ['moduleA'];
        },
        testsInModule: async () => [classDto('moduleA', 'com.example.SampleTest', URI_A)],
      }),
    );

    await discovery.refreshWorkspace();
    assert.deepEqual(asked, [], 'the server must not be scanned while it is still importing');

    // What the import's own notification triggers once the cycle ends.
    importing = false;
    await discovery.refreshWorkspace();
    await expandModule(controller, discovery, 'moduleA');

    assert.ok(tree.get('moduleA/com.example.SampleTest'));
  });

  test('keeps the tests found from open files while the import is still running', async () => {
    const { tree, discovery } = makeDiscovery(
      fakeServer({
        importInProgress: async () => true,
        testsInFile: async () => [classDto(null, 'com.example.SampleTest', URI_A)],
      }),
    );
    await discovery.refreshFile(javaDocument(URI_A));

    await discovery.refreshWorkspace();

    assert.ok(tree.get('/com.example.SampleTest'), 'a skipped pass must prune nothing');
  });

  test('scans the workspace when the server cannot say whether it is importing', async () => {
    const { controller, tree, discovery } = makeDiscovery(
      fakeServer({
        importInProgress: async () => {
          throw new Error('unknown method');
        },
        testModules: async () => ['moduleA'],
        testsInModule: async () => [classDto('moduleA', 'com.example.SampleTest', URI_A)],
      }),
    );

    await withoutErrorLog(() => discovery.refreshWorkspace());
    await expandModule(controller, discovery, 'moduleA');

    assert.ok(tree.get('moduleA/com.example.SampleTest'));
  });

  test('runs overlapping passes one after another', async () => {
    // Two passes at once would each prune against their own view of what exists.
    let running = 0;
    const { discovery } = makeDiscovery(
      fakeServer({
        testModules: async () => {
          assert.equal(running, 0, 'a second pass started while the first was still running');
          running++;
          await new Promise((resolve) => setTimeout(resolve, 1));
          running--;
          return [];
        },
      }),
    );

    await Promise.all([discovery.refreshWorkspace(), discovery.refreshWorkspace()]);
  });
});

test('nothing is discovered while the language server is down', async () => {
  const { controller, tree } = makeTree();
  const discovery = new JvmTestDiscovery(tree, () => undefined);

  await discovery.refreshFile(javaDocument(URI_A));
  await discovery.refreshWorkspace();

  assert.deepEqual(paths(controller.items), []);
});
