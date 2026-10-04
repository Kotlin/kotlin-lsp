import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, test } from 'node:test';
import { BUILD_TASK_TYPE } from './buildTaskModel';

/**
 * The build task is half code and half manifest: this package registers a task provider for
 * `contributes.taskDefinitions`, and the products' package.json contributes the definition and the launch snippets.
 * Nothing else checks that the two halves still agree.
 *
 * The source manifest and one product manifest are read: `intellij-vscode/check-metadata-sync.mjs` copies
 * `debuggers` (which carries the snippets) and `taskDefinitions` from the source into the products' manifests, and
 * fails when they differ, so the source stands for every copy. A debugger only a product contributes, such as the
 * Bazel one (the Kotlin server has no Bazel import), is read from that product's manifest.
 */
const SOURCE_MANIFEST = '../../kotlin-vscode/package.json';
const INTELLIJ_MANIFEST = '../../../intellij-vscode/intellij-server/package.json';

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

  // The server builds as part of every launch, so no snippet references the task: a `preLaunchTask` would compile
  // the same sources twice per launch.
  test('no launch snippet references the build task', () => {
    assert.notEqual(snippets.length, 0, 'no snippet to check');
    for (const { snippet } of snippets) {
      assert.equal(
        snippet.body?.preLaunchTask,
        undefined,
        `the snippet ${JSON.stringify(snippet.label)} builds before a launch that builds by itself`,
      );
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
