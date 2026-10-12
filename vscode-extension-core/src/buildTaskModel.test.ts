import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { BuildOutputRouter, buildTaskExitCode, taskBuildTargetOf } from './buildTaskModel';

describe('BuildOutputRouter', () => {
  test('delivers a line to the build that listens for its id, and drops the rest', () => {
    const router = new BuildOutputRouter();
    const a = router.newBuildId();
    const b = router.newBuildId();
    assert.notEqual(a, b);
    const seen: string[] = [];
    const stop = router.listen(a, (output) => seen.push(output.line));
    router.deliver({ buildId: a, category: 'stdout', line: 'one' });
    router.deliver({ buildId: b, category: 'stdout', line: 'other' });
    stop();
    router.deliver({ buildId: a, category: 'stdout', line: 'late' });
    assert.deepEqual(seen, ['one']);
  });
});

describe('buildTaskExitCode', () => {
  test('the exit code of the tool is the exit code of the task', () => {
    assert.equal(buildTaskExitCode({ exitCode: 3 }), 3);
  });

  // "Nothing to build" is not a failure: a task referenced from tasks.json must not fail a workflow because the
  // project has no build tool.
  test('nothing to build ends the task with 0', () => {
    assert.equal(buildTaskExitCode({ reason: 'no build tool' }), 0);
  });
});

describe('taskBuildTargetOf', () => {
  test('a file named in the task definition is the target', () => {
    assert.deepEqual(
      taskBuildTargetOf({ type: 'intellij_build', file: '/p/app/src/main/java/App.java' }),
      { kind: 'file', path: '/p/app/src/main/java/App.java' },
    );
  });

  // The task the extension provides has no target at all, and that is the everyday case: the Run/Debug lens hands
  // its build over out of band, and a user-authored task with nothing named falls back to the active editor.
  test('a definition that names nothing has no target', () => {
    assert.equal(taskBuildTargetOf({ type: 'intellij_build' }), undefined);
    assert.equal(taskBuildTargetOf(undefined), undefined);
  });

  // A task compiles a module, and a path names one without asking the server to place a class first. The class form
  // belongs to launch configurations, which cannot use a path; a task that spells one is naming a property the
  // contributed schema does not offer, and VS Code flags it there.
  test('a class name is not a task target, the schema does not offer one', () => {
    assert.equal(
      taskBuildTargetOf({ type: 'intellij_build', mainClass: 'com.example.App' }),
      undefined,
    );
  });

  // A task definition is user JSON. Ignoring an unusable value rather than trusting it keeps a typo from resolving
  // a build for `[object Object]` — and ignoring it rather than throwing keeps it from failing the launch, which is
  // this task's rule everywhere: only a build that ran and failed stops one.
  test('a value that is not a non-blank string is ignored', () => {
    assert.equal(taskBuildTargetOf({ type: 'intellij_build', file: '' }), undefined);
    assert.equal(taskBuildTargetOf({ type: 'intellij_build', file: '   ' }), undefined);
    assert.equal(taskBuildTargetOf({ type: 'intellij_build', file: null }), undefined);
    assert.equal(taskBuildTargetOf({ type: 'intellij_build', file: ['/p/App.java'] }), undefined);
  });

  test('surrounding whitespace is not part of the target', () => {
    assert.deepEqual(taskBuildTargetOf({ type: 'intellij_build', file: ' /p/App.java ' }), {
      kind: 'file',
      path: '/p/App.java',
    });
  });

  // VS Code substitutes a task definition's variables before the task runs, so `${file}` reaches this as a path. One
  // that still reads as a variable is one VS Code did not recognize, and resolving a module for a file literally
  // named `${file}` would compile something arbitrary or nothing.
  test('a value left as an unsubstituted variable is not a target', () => {
    assert.equal(taskBuildTargetOf({ type: 'intellij_build', file: '${file}' }), undefined);
  });
});
