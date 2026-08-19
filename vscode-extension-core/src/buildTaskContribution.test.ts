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
 * Only the source manifest is read: `intellij-vscode/check-metadata-sync.mjs` copies `debuggers` (which carries the
 * snippets) and `taskDefinitions` into the other products' manifests, and fails when they differ, so checking one
 * checks all of them.
 */
const SOURCE_MANIFEST = '../../kotlin-vscode/package.json';

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

const manifest = JSON.parse(readFileSync(new URL(SOURCE_MANIFEST, import.meta.url), 'utf8')) as {
  contributes: {
    taskDefinitions?: TaskDefinition[];
    debuggers?: Debugger[];
  };
};

const debuggers = manifest.contributes.debuggers ?? [];
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

  // The other side of that default: a Gradle launch *is* its build — the task it runs compiles the module on the way
  // to running it — so a build task in front of it would compile the same source set twice per launch.
  test('the Gradle launch snippet has no build task, because it compiles as it runs', () => {
    const gradleLaunches = snippets.filter(({ entry }) => entry.type === 'intellij_gradle');
    assert.notEqual(gradleLaunches.length, 0, 'no intellij_gradle snippet to check');
    for (const { snippet } of gradleLaunches) {
      assert.equal(snippet.body?.preLaunchTask, undefined);
    }
  });

  // An attach session has nothing to compile: the program it attaches to is already running.
  test('no attach snippet builds anything', () => {
    for (const { snippet } of snippets.filter(
      ({ snippet }) => snippet.body?.request === 'attach',
    )) {
      assert.equal(snippet.body?.preLaunchTask, undefined);
    }
  });
});
