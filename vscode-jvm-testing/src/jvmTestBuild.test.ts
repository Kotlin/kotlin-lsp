import assert from 'node:assert/strict';
import { beforeEach, describe, test } from 'node:test';
import type { CancellationToken, TestRun, Uri } from 'vscode';
import type { ResolvedBuildCommand } from '@jetbrains/vscode-extension-core/build';
import { JvmTestBuilds } from './jvmTestBuild';

const MAVEN_BUILD: ResolvedBuildCommand = {
  supported: true,
  tool: 'maven',
  cwd: '/p',
  command: ['mvn', '-pl', ':app', '-am', 'test-compile'],
};

function uriOf(path: string): Uri {
  return { toString: () => `file://${path}` } as unknown as Uri;
}

function tokenOf(cancelled = false): CancellationToken {
  return {
    isCancellationRequested: cancelled,
    onCancellationRequested: () => ({ dispose() {} }),
  } as unknown as CancellationToken;
}

describe('JvmTestBuilds', () => {
  let output: string[];
  let log: string[];
  let run: TestRun;

  beforeEach(() => {
    output = [];
    log = [];
    run = { appendOutput: (text: string) => output.push(text) } as unknown as TestRun;
  });

  test('runs the build the server resolved and reports it built', async () => {
    const spawned: string[][] = [];
    const builds = new JvmTestBuilds({
      run,
      log: (message) => log.push(message),
      resolve: () => Promise.resolve(MAVEN_BUILD),
      spawn: (build, line) => {
        spawned.push(build.command);
        line('BUILD SUCCESS');
        return Promise.resolve(0);
      },
    });

    assert.equal(await builds.ensureBuilt(uriOf('/p/app/AppTest.java'), tokenOf()), 'built');
    assert.deepEqual(spawned, [['mvn', '-pl', ':app', '-am', 'test-compile']]);
    assert.deepEqual(output, ['BUILD SUCCESS\r\n']);
  });

  // Regression: the test build spawned the wrapper with the host environment only, so a Gradle project whose
  // import ran on a compatible JDK still built on the `java` from the PATH and failed on a JDK Gradle cannot run on.
  test('spawns the build with the environment the server chose for the tool', async () => {
    const environments: (Record<string, string> | undefined)[] = [];
    const builds = new JvmTestBuilds({
      run,
      log: (message) => log.push(message),
      resolve: () =>
        Promise.resolve({
          supported: true,
          tool: 'gradle',
          cwd: '/p',
          command: ['/p/gradlew', ':testClasses'],
          env: { JAVA_HOME: '/jdks/jbr-25' },
        }),
      spawn: (build) => {
        environments.push(build.env);
        return Promise.resolve(0);
      },
    });

    assert.equal(await builds.ensureBuilt(uriOf('/p/src/test/AppTest.kt'), tokenOf()), 'built');
    assert.deepEqual(environments, [{ JAVA_HOME: '/jdks/jbr-25' }]);
  });

  // Two frameworks in one module are two launch groups, and both resolve the same command.
  test('compiles once for two groups that resolve the same command', async () => {
    let spawns = 0;
    const builds = new JvmTestBuilds({
      run,
      log: (message) => log.push(message),
      resolve: () => Promise.resolve(MAVEN_BUILD),
      spawn: () => {
        spawns += 1;
        return Promise.resolve(0);
      },
    });

    await builds.ensureBuilt(uriOf('/p/app/JUnitTest.java'), tokenOf());
    const second = await builds.ensureBuilt(uriOf('/p/app/TestNgTest.java'), tokenOf());

    assert.equal(second, 'built');
    assert.equal(spawns, 1);
  });

  test('a failed build is reported as failed, so the caller can refuse to launch', async () => {
    const builds = new JvmTestBuilds({
      run,
      log: (message) => log.push(message),
      resolve: () => Promise.resolve(MAVEN_BUILD),
      spawn: (_build, line) => {
        line('AppTest.java:[7,5] cannot find symbol');
        return Promise.resolve(1);
      },
    });

    assert.equal(await builds.ensureBuilt(uriOf('/p/app/AppTest.java'), tokenOf()), 'failed');
    assert.ok(output.join('').includes('cannot find symbol'), output.join(''));
  });

  // A project no build tool can compile — JPS, or an imported workspace.json. The run still has to happen.
  test('says why nothing was compiled, and lets the run go ahead', async () => {
    let spawned = false;
    const builds = new JvmTestBuilds({
      run,
      log: (message) => log.push(message),
      resolve: () =>
        Promise.resolve({ supported: false, reason: 'No build tool can compile this module.' }),
      spawn: () => {
        spawned = true;
        return Promise.resolve(0);
      },
    });

    assert.equal(await builds.ensureBuilt(uriOf('/p/app/AppTest.java'), tokenOf()), 'skipped');
    assert.equal(spawned, false);
    assert.ok(output.join('').includes('No build tool can compile this module.'), output.join(''));
    assert.ok(output.join('').includes('already compiled'), output.join(''));
    assert.equal(log.length, 1);
  });

  // Only a build that ran and failed stops a launch; a question we could not ask does not.
  test('a resolve that throws skips the build instead of failing the run', async () => {
    const builds = new JvmTestBuilds({
      run,
      log: (message) => log.push(message),
      resolve: () => Promise.reject(new Error('IntelliJ LSP is not running')),
      spawn: () => Promise.resolve(0),
    });

    assert.equal(await builds.ensureBuilt(uriOf('/p/app/AppTest.java'), tokenOf()), 'skipped');
    assert.ok(output.join('').includes('IntelliJ LSP is not running'), output.join(''));
  });

  test('a cancelled run neither asks the server nor compiles', async () => {
    let asked = false;
    const builds = new JvmTestBuilds({
      run,
      log: (message) => log.push(message),
      resolve: () => {
        asked = true;
        return Promise.resolve(MAVEN_BUILD);
      },
      spawn: () => Promise.resolve(0),
    });

    assert.equal(await builds.ensureBuilt(uriOf('/p/app/AppTest.java'), tokenOf(true)), 'skipped');
    assert.equal(asked, false);
    assert.deepEqual(output, []);
  });
});
