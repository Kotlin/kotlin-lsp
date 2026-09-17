import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, test } from 'node:test';
import { BUILD_TASK_LABEL, BUILD_TASK_TYPE } from './buildTaskModel';

/**
 * The build task is half code and half manifest: this package registers a task provider for
 * `contributes.taskDefinitions`, and the launch snippets in the products' package.json reference the task by the
 * label VS Code derives from its type and name. Nothing else checks that the two halves still agree — a renamed
 * constant or an edited snippet string breaks a launch at runtime and passes every other test.
 *
 * The source manifest and one product manifest are read: `intellij-vscode/check-metadata-sync.mjs` copies
 * `debuggers` (which carries the snippets) and `taskDefinitions` from the source into the products' manifests, and
 * fails when they differ, so the source stands for every copy. A debugger only a product contributes, such as the
 * Bazel one (the Kotlin server has no Bazel import), is read from that product's manifest.
 */
const SOURCE_MANIFEST = '../../kotlin-vscode/package.json';
const INTELLIJ_MANIFEST = '../../../intellij-vscode/intellij-server/package.json';

/** The configuration types a build tool launches, as `dap.ts` registers them: one type per tool. */
const BUILD_TOOL_DEBUG_TYPES = ['intellij_gradle', 'intellij_bazel'];

interface TaskDefinition {
  type?: string;
  properties?: Record<string, unknown>;
}

interface Snippet {
  label?: string;
  body?: { type?: string; request?: string; preLaunchTask?: string };
}

interface Debugger {
  type: string;
  configurationSnippets?: Snippet[];
}

interface Manifest {
  contributes: {
    taskDefinitions?: TaskDefinition[];
    debuggers?: Debugger[];
  };
}

const readManifest = (path: string): Manifest =>
  JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8')) as Manifest;

const manifest = readManifest(SOURCE_MANIFEST);
const intellijManifest = readManifest(INTELLIJ_MANIFEST);

// Snippets of every debugger either manifest contributes, each debugger type once: the synced ones are identical in
// both, and a product-only one appears in one.
const debuggers = [manifest, intellijManifest]
  .flatMap((candidate) => candidate.contributes.debuggers ?? [])
  .filter((entry, index, all) => all.findIndex((other) => other.type === entry.type) === index);
const snippets = debuggers.flatMap((entry) =>
  (entry.configurationSnippets ?? []).map((snippet) => ({ entry, snippet })),
);

describe('build task contribution', () => {
  test('the task type this code registers is contributed', () => {
    const types = (manifest.contributes.taskDefinitions ?? []).map((definition) => definition.type);
    assert.ok(
      types.includes(BUILD_TASK_TYPE),
      `contributes.taskDefinitions has ${JSON.stringify(types)}, so a task of type ` +
        `"${BUILD_TASK_TYPE}" cannot be referenced from tasks.json or launch.json`,
    );
  });

  // A build compiles a *module*, and the task names one with a path. `mainClass` is the launch side's way of naming a
  // target — a launch configuration is read before its variables are substituted, so a class name is all it can be
  // identified by — and offering it here too only bought a task a `resolveClassDocument` round-trip that can fail on a
  // stale FQN. Anything the schema stops offering, `taskBuildTargetOf` also stops reading.
  test('the build task is targeted by path, not by class name', () => {
    const definition = (manifest.contributes.taskDefinitions ?? []).find(
      (candidate) => candidate.type === BUILD_TASK_TYPE,
    );
    assert.deepEqual(Object.keys(definition?.properties ?? {}), ['file']);
  });

  test('every snippet that pre-launches a build names the task this code provides', () => {
    const referenced = snippets
      .map(({ snippet }) => snippet.body?.preLaunchTask)
      .filter((task): task is string => task !== undefined);
    assert.notEqual(referenced.length, 0, 'no snippet references a build at all');
    for (const task of referenced) {
      assert.equal(
        task,
        BUILD_TASK_LABEL,
        `a snippet's "preLaunchTask" is ${JSON.stringify(task)}, which no provided task matches`,
      );
    }
  });

  // Creating a JVM launch configuration compiles before running, without the user wiring anything up: `dap.ts`
  // resolves that launch's own build before the task runs. A snippet shipped without the task would launch whatever
  // happened to be compiled last, which is the failure this default exists to prevent.
  test('the JVM launch snippet builds before launching', () => {
    const jvmLaunches = snippets.filter(
      ({ entry, snippet }) => entry.type === 'intellij_jvm' && snippet.body?.request === 'launch',
    );
    assert.notEqual(jvmLaunches.length, 0, 'no intellij_jvm launch snippet to check');
    for (const { snippet } of jvmLaunches) {
      assert.equal(
        snippet.body?.preLaunchTask,
        BUILD_TASK_LABEL,
        `the snippet ${JSON.stringify(snippet.label)} creates a launch that compiles nothing first`,
      );
    }
  });

  // The other side of that default: a build-tool launch *is* its build — the tool compiles the module on the way to
  // running it — so a build task in front of it would compile the same sources twice per launch.
  for (const type of BUILD_TOOL_DEBUG_TYPES) {
    test(`the ${type} launch snippet has no build task, because it compiles as it runs`, () => {
      const launches = snippets.filter(({ entry }) => entry.type === type);
      assert.notEqual(launches.length, 0, `no ${type} snippet to check`);
      for (const { snippet } of launches) {
        assert.equal(
          snippet.body?.preLaunchTask,
          undefined,
          `the snippet ${JSON.stringify(snippet.label)} builds before a launch that builds by itself`,
        );
      }
    });
  }

  // An attach session has nothing to compile: the program it attaches to is already running.
  test('no attach snippet builds anything', () => {
    for (const { snippet } of snippets.filter(
      ({ snippet }) => snippet.body?.request === 'attach',
    )) {
      assert.equal(snippet.body?.preLaunchTask, undefined);
    }
  });
});
