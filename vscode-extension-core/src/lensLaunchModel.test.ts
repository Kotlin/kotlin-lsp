import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { lensLaunchDecision } from './lensLaunchModel';

const typeByTool = { gradle: 'intellij_gradle', bazel: 'intellij_bazel' };
const jvmType = 'intellij_jvm';

describe('lensLaunchDecision', () => {
  test('a named tool launches through its own configuration type', () => {
    assert.deepEqual(lensLaunchDecision({ tool: 'bazel' }, typeByTool, jvmType), {
      type: 'intellij_bazel',
    });
  });

  test('a tool without a configuration type falls back to the JVM', () => {
    assert.deepEqual(lensLaunchDecision({ tool: 'maven' }, typeByTool, jvmType), {
      type: jvmType,
    });
  });

  // Regression: the lens read only `tool`, so a refused Bazel launch ran as a plain JVM program and the reason
  // the server gave was never shown.
  test('a refusal with a reason stops the launch and carries the reason', () => {
    assert.deepEqual(
      lensLaunchDecision({ reason: 'The Bazel release is unknown; re-import the project' }, typeByTool, jvmType),
      { refused: 'The Bazel release is unknown; re-import the project' },
    );
  });

  test('no tool and no reason means no tool launches this module, so the JVM does', () => {
    assert.deepEqual(lensLaunchDecision({}, typeByTool, jvmType), { type: jvmType });
  });

  test('a failure to ask keeps the JVM fallback', () => {
    assert.deepEqual(lensLaunchDecision(undefined, typeByTool, jvmType), { type: jvmType });
  });
});
