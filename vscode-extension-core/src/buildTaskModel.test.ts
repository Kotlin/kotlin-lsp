import assert from 'node:assert/strict';
import { realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, test } from 'node:test';
import {
  buildToRun,
  createOutputLineSplitter,
  launchBuildTargetOf,
  type RunningBuild,
  needsCmdShell,
  quoteCommandForCmd,
  quoteForCmd,
  runProcess,
  spawnArgumentsFor,
  taskBuildTargetOf,
} from './buildTaskModel';

describe('needsCmdShell', () => {
  test('a batch wrapper needs cmd.exe, which Node will not spawn without a shell', () => {
    assert.equal(needsCmdShell('C:\\p\\gradlew.bat', 'win32'), true);
    assert.equal(needsCmdShell('C:\\p\\mvnw.cmd', 'win32'), true);
    assert.equal(needsCmdShell('C:\\p\\MVNW.CMD', 'win32'), true);
  });

  // Regression: a project with no wrapper falls back to the bare tool name, which on Windows exists only as
  // `gradle.bat` / `mvn.cmd` on PATH. `spawn` does not apply PATHEXT, so this failed with ENOENT and reported
  // "Failed to start the build" for a Maven that was installed and on PATH all along.
  test('a bare tool name needs the shell, because only it applies PATHEXT', () => {
    assert.equal(needsCmdShell('gradle', 'win32'), true);
    assert.equal(needsCmdShell('mvn', 'win32'), true);
  });

  test('an absolute path names the image outright and needs no shell', () => {
    assert.equal(needsCmdShell('C:\\Program Files\\Gradle\\bin\\gradle.exe', 'win32'), false);
    assert.equal(needsCmdShell('\\\\server\\share\\gradle.exe', 'win32'), false);
  });

  test('nothing needs cmd.exe off Windows', () => {
    for (const executable of ['gradle', 'mvn', '/p/gradlew', '/p/gradlew.bat']) {
      assert.equal(needsCmdShell(executable, 'darwin'), false);
      assert.equal(needsCmdShell(executable, 'linux'), false);
    }
  });
});

describe('quoteForCmd', () => {
  test('wraps in double quotes so a path with spaces stays one argument', () => {
    assert.equal(
      quoteForCmd('C:\\Users\\me\\my project\\gradlew.bat'),
      '"C:\\Users\\me\\my project\\gradlew.bat"',
    );
  });

  // Regression: escaping these inside double quotes passed the caret through to the program, so a workspace at
  // 'C:\Users\me\Projects (work)\demo' produced 'Projects ^(work^)' and the build could not find the wrapper.
  const literalInsideQuotes = ['(', ')', '&', '|', '<', '>', '^'];

  for (const char of literalInsideQuotes) {
    test(`leaves ${char} alone, because cmd.exe does not expand it inside quotes`, () => {
      assert.equal(quoteForCmd(`C:\\a ${char} b\\mvnw.cmd`), `"C:\\a ${char} b\\mvnw.cmd"`);
    });
  }

  test('quotes a full path containing parentheses exactly as cmd.exe needs it', () => {
    assert.equal(
      quoteForCmd('C:\\Users\\me\\Projects (work)\\demo\\mvnw.cmd'),
      '"C:\\Users\\me\\Projects (work)\\demo\\mvnw.cmd"',
    );
  });

  // Regression: `!` was escaped as `^!` for a delayed expansion that Node never enables (`cmd /d /s /c`, no
  // `/v:on`). With it off, `!` is already literal and `^` inside quotes is a character rather than an escape, so
  // the "fix" is what broke the path: a workspace at 'C:\a!b' was spawned as 'C:\a^!b\gradlew.bat'.
  test('leaves ! alone, because Node does not enable delayed expansion', () => {
    assert.equal(quoteForCmd('C:\\a!b\\gradlew.bat'), '"C:\\a!b\\gradlew.bat"');
  });

  test('leaves a trailing backslash in place', () => {
    assert.equal(quoteForCmd('C:\\project\\'), '"C:\\project\\"');
  });
});

describe('quoteCommandForCmd', () => {
  // Regression (LSP-1716): the *command name* got the quotes of an argument, which is a different job. For a
  // project with no wrapper, the server falls back to a bare `mvn.cmd` or `gradle.bat`. cmd.exe reads a quoted
  // bare name as a file specification, so it does not search PATH and it does not set `%~dp0`. Both launchers
  // find their installation through `%~dp0`, so every wrapper-less build on Windows failed.
  test('leaves the bare PATH fallback unquoted, so cmd.exe looks it up as a typed name', () => {
    assert.equal(quoteCommandForCmd('mvn.cmd'), 'mvn.cmd');
    assert.equal(quoteCommandForCmd('gradle.bat'), 'gradle.bat');
    assert.equal(quoteCommandForCmd('mvn'), 'mvn');
  });

  // The case that quotes exist for. It is also why a project with a wrapper always worked.
  test('quotes a wrapper path, which is where the spaces are', () => {
    assert.equal(
      quoteCommandForCmd('C:\\Users\\me\\my project\\mvnw.cmd'),
      '"C:\\Users\\me\\my project\\mvnw.cmd"',
    );
    assert.equal(quoteCommandForCmd('C:\\p\\gradlew.bat'), '"C:\\p\\gradlew.bat"');
  });

  test('quotes anything else that names a path rather than a command to look up', () => {
    assert.equal(quoteCommandForCmd('.\\mvnw.cmd'), '".\\mvnw.cmd"');
    assert.equal(
      quoteCommandForCmd('\\\\server\\share\\gradlew.bat'),
      '"\\\\server\\share\\gradlew.bat"',
    );
    // Drive-relative is still a path. cmd.exe would not look up its leading `C:` on PATH either.
    assert.equal(quoteCommandForCmd('C:mvnw.cmd'), '"C:mvnw.cmd"');
    assert.equal(quoteCommandForCmd('/usr/local/bin/mvn'), '"/usr/local/bin/mvn"');
  });

  // The server produces no such name. But a name without quotes gives cmd.exe the leading word to run as some
  // *other* command. That is worse than to not find this one.
  test('quotes a name that is not a path but that cmd.exe would otherwise split', () => {
    assert.equal(quoteCommandForCmd('my tool.cmd'), '"my tool.cmd"');
    assert.equal(quoteCommandForCmd('tool&other.cmd'), '"tool&other.cmd"');
    // Quotes do not stop the expansion. They only stop the split of the result.
    assert.equal(quoteCommandForCmd('%TOOL%.cmd'), '"%TOOL%.cmd"');
  });

  // `!` is already literal, because Node spawns cmd.exe without `/v:on`. That is the reason `quoteForCmd` gives
  // for no escape. Quotes here would only stop the PATH lookup that this function exists to allow.
  test('leaves a name holding `!` bare, because delayed expansion is off', () => {
    assert.equal(quoteCommandForCmd('hi!.cmd'), 'hi!.cmd');
  });
});

describe('spawnArgumentsFor', () => {
  // Regression (LSP-1716): the fix is the quoter that the *name* gets, so a test must read the pair and not only
  // each quoter. The suites above pin what `quoteCommandForCmd` returns. They also passed while the spawn still
  // called `quoteForCmd` on the name, which is the bug of this ticket.
  test('quotes the arguments but not the bare PATH fallback, on Windows', () => {
    assert.deepEqual(spawnArgumentsFor(['mvn.cmd', '-B', 'compile'], 'win32'), {
      command: 'mvn.cmd',
      args: ['"-B"', '"compile"'],
      shell: true,
    });
  });

  test('quotes a wrapper path as well as its arguments, on Windows', () => {
    assert.deepEqual(spawnArgumentsFor(['C:\\my project\\mvnw.cmd', 'clean package'], 'win32'), {
      command: '"C:\\my project\\mvnw.cmd"',
      args: ['"clean package"'],
      shell: true,
    });
  });

  // An image named by a path needs no shell, so nothing gets quotes. Node gives the arguments to the process.
  test('spawns a path directly on Windows, without a shell and without quoting', () => {
    assert.deepEqual(spawnArgumentsFor(['C:\\tools\\mvn.exe', 'a b'], 'win32'), {
      command: 'C:\\tools\\mvn.exe',
      args: ['a b'],
      shell: false,
    });
  });

  test('passes every part through untouched off Windows, where there is no cmd.exe', () => {
    for (const platform of ['darwin', 'linux']) {
      assert.deepEqual(
        spawnArgumentsFor(['mvn', '-B', 'clean package'], platform),
        { command: 'mvn', args: ['-B', 'clean package'], shell: false },
        `wrong arguments on ${platform}`,
      );
    }
  });
});

describe('createOutputLineSplitter', () => {
  const collect = () => {
    const lines: string[] = [];
    return { lines, splitter: createOutputLineSplitter((line) => lines.push(line)) };
  };

  test('emits one line per terminator', () => {
    const { lines, splitter } = collect();
    splitter.push('first\nsecond\n');
    assert.deepEqual(lines, ['first', 'second']);
  });

  // Regression: splitting each chunk on its own yields a trailing '' for a chunk ending in a newline, which the
  // caller turns into an extra blank line — so a multi-chunk build log rendered double-spaced.
  test('does not emit a blank line for a chunk that ends in a terminator', () => {
    const { lines, splitter } = collect();
    splitter.push('first\n');
    splitter.push('second\n');
    assert.deepEqual(lines, ['first', 'second']);
  });

  test('joins a line that straddles two chunks', () => {
    const { lines, splitter } = collect();
    splitter.push('> Task :app:comp');
    assert.deepEqual(lines, [], 'an unterminated line must be held back');
    splitter.push('ileJava\n');
    assert.deepEqual(lines, ['> Task :app:compileJava']);
  });

  test('strips the carriage return of a CRLF stream', () => {
    const { lines, splitter } = collect();
    splitter.push('BUILD SUCCESSFUL\r\n');
    assert.deepEqual(lines, ['BUILD SUCCESSFUL']);
  });

  test('keeps genuinely blank lines', () => {
    const { lines, splitter } = collect();
    splitter.push('a\n\nb\n');
    assert.deepEqual(lines, ['a', '', 'b']);
  });

  test('flush emits a last line with no terminator', () => {
    const { lines, splitter } = collect();
    splitter.push('no newline at the end');
    splitter.flush();
    assert.deepEqual(lines, ['no newline at the end']);
  });

  test('flush emits nothing when everything was already terminated', () => {
    const { lines, splitter } = collect();
    splitter.push('done\n');
    splitter.flush();
    splitter.flush();
    assert.deepEqual(lines, ['done']);
  });
});

describe('buildToRun', () => {
  test('a supported answer with a command is the build to run', () => {
    assert.deepEqual(
      buildToRun({ supported: true, tool: 'maven', cwd: '/p', command: ['mvn', 'compile'] }),
      {
        tool: 'maven',
        cwd: '/p',
        command: ['mvn', 'compile'],
      },
    );
  });

  // Regression: the Run/Debug lens injected the build task unconditionally, so a launch with nothing to compile
  // opened a terminal only to print that into it. Nothing to run means no task at all.
  test('a module no tool can name has nothing to run', () => {
    assert.equal(
      buildToRun({
        supported: false,
        tool: 'gradle',
        reason: 'Could not determine the Gradle project of module …',
      }),
      undefined,
    );
  });

  test('a project with no build tool has nothing to run', () => {
    assert.equal(buildToRun({ supported: false, reason: 'no build tool detected' }), undefined);
  });

  // A command that builds nothing would report success while compiling nothing, so it is not a build.
  test('a supported answer with an empty command has nothing to run', () => {
    assert.equal(buildToRun({ supported: true, tool: 'gradle', command: [] }), undefined);
    assert.equal(buildToRun({ supported: true, tool: 'gradle' }), undefined);
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

describe('launchBuildTargetOf', () => {
  test('a class named in the configuration is the target', () => {
    assert.deepEqual(
      launchBuildTargetOf({ type: 'intellij_jvm', mainClass: 'com.example.app.App' }),
      {
        kind: 'mainClass',
        mainClass: 'com.example.app.App',
      },
    );
  });

  // Same precedence as the launch's own target: a path names one document, a class name has to be looked up and can
  // be declared in more than one file.
  test('a file wins over a class, as it does for the launch itself', () => {
    assert.deepEqual(
      launchBuildTargetOf({
        type: 'intellij_jvm',
        file: '/p/App.java',
        mainClass: 'com.example.Other',
      }),
      { kind: 'file', path: '/p/App.java' },
    );
  });

  test('a configuration that names nothing has no target', () => {
    assert.equal(launchBuildTargetOf({ type: 'intellij_jvm' }), undefined);
    assert.equal(launchBuildTargetOf(undefined), undefined);
  });

  test('a value that is not a non-blank string is ignored', () => {
    assert.equal(launchBuildTargetOf({ type: 'intellij_jvm', mainClass: '' }), undefined);
    assert.equal(launchBuildTargetOf({ type: 'intellij_jvm', mainClass: 42 }), undefined);
    assert.equal(launchBuildTargetOf({ type: 'intellij_jvm', file: null }), undefined);
  });

  test('an unusable file falls through to the class rather than losing the target', () => {
    assert.deepEqual(
      launchBuildTargetOf({ type: 'intellij_jvm', file: '  ', mainClass: 'com.example.App' }),
      { kind: 'mainClass', mainClass: 'com.example.App' },
    );
  });

  // A launch configuration is read before VS Code substitutes variables — the only point at which its build can
  // still reach the task — so `${file}` arrives literally. Resolving a module for a path spelled `${file}` would
  // compile something arbitrary, or report that there is nothing to build for a file that does exist.
  test('a value with an unsubstituted variable is not a target', () => {
    assert.equal(launchBuildTargetOf({ type: 'intellij_jvm', file: '${file}' }), undefined);
    assert.equal(
      launchBuildTargetOf({
        type: 'intellij_jvm',
        file: '${workspaceFolder}/app/src/main/java/App.java',
      }),
      undefined,
    );
  });

  // The class name of such a configuration needs no substitution, so it is what identifies the launch instead —
  // which is what makes a plain `{"mainClass": …, "preLaunchTask": "intellij: build"}` compile its own module.
  test('the class name identifies a launch whose file is still a variable', () => {
    assert.deepEqual(
      launchBuildTargetOf({
        type: 'intellij_jvm',
        file: '${file}',
        mainClass: 'com.example.app.App',
      }),
      { kind: 'mainClass', mainClass: 'com.example.app.App' },
    );
  });
});

describe('runProcess', () => {
  // A build reports only lines, so a test reads lines. The task only echoes `tool`, so it stays fixed here.
  const run = (options: {
    command: string[];
    cwd?: string;
    running?: RunningBuild;
  }): Promise<{ lines: string[]; codes: number[] }> => {
    const lines: string[] = [];
    const codes: number[] = [];
    const running = options.running ?? {};
    return runProcess({
      tool: 'maven',
      command: options.command,
      cwd: options.cwd,
      line: (text) => lines.push(text),
      close: (exitCode) => codes.push(exitCode),
      running,
    }).then(() => ({ lines, codes }));
  };

  /** A child that is the node of this process, so the suite needs no installed tool to run a build. */
  const node = (script: string): string[] => [process.execPath, '-e', script];

  /**
   * An executable that does not exist, written as a *path* so `spawn` fails the same way on every platform.
   *
   * A bare name would not do that. `needsCmdShell` sends a bare name through cmd.exe on Windows. cmd.exe starts
   * correctly and exits non-zero for a command it cannot find, so there is no `error` event to test.
   */
  const missingExecutable = join(tmpdir(), 'definitely-not-a-build-tool-4e7c1');

  test('echoes what it is about to run, so a failure names the command', async () => {
    const { lines } = await run({ command: node('') });
    assert.equal(lines[0], `Building with maven: ${node('').join(' ')}`);
  });

  test('a build that exits 0 finishes successfully', async () => {
    const { lines, codes } = await run({ command: node('') });
    assert.deepEqual(codes, [0]);
    assert.equal(lines.at(-1), 'Build finished successfully.');
  });

  test('a build that exits non-zero reports that code, which is what stops the launch', async () => {
    const { lines, codes } = await run({ command: node('process.exit(3)') });
    assert.deepEqual(codes, [3]);
    assert.equal(lines.at(-1), 'Build failed (exit code 3).');
  });

  test('output from both streams is reported, including a last line with no terminator', async () => {
    const { lines } = await run({
      command: node(
        'process.stdout.write("compiling\\n"); process.stderr.write("a warning\\nno newline")',
      ),
    });
    assert.ok(lines.includes('compiling'), `stdout line missing from ${JSON.stringify(lines)}`);
    assert.ok(lines.includes('a warning'), `stderr line missing from ${JSON.stringify(lines)}`);
    assert.ok(
      lines.includes('no newline'),
      `unterminated tail missing from ${JSON.stringify(lines)}`,
    );
  });

  test('runs in the cwd it is given, which is the build tool root and not the editor process', async () => {
    const { lines } = await run({
      command: node('process.stdout.write("cwd=" + process.cwd())'),
      cwd: tmpdir(),
    });
    const reported = lines.find((l) => l.startsWith('cwd='))?.slice('cwd='.length);
    assert.ok(reported, `the child did not report its cwd: ${JSON.stringify(lines)}`);
    // Compared through `realpath`, because macOS gives /var/folders/... for a /private/var/... directory.
    assert.equal(realpathSync(reported), realpathSync(tmpdir()));
  });

  // Regression (LSP-1716): `spawn` reports a rejected argument with a *throw* and not with an `error` event.
  // Without the guard, that rejected a promise which nobody awaits, so the task never closed. The terminal stayed
  // open on a build that never reports, and the launch behind it waited on a task that cannot finish.
  test('a spawn that throws is a failed build, not a task that never closes', async () => {
    const { lines, codes } = await run({
      // `spawn` rejects a `cwd` of the wrong type before there is a child to carry an `error` event. It stands
      // in for the malformed server response, which is the only way to reach this. See `runProcess`.
      command: node(''),
      cwd: 42 as unknown as string,
    });
    assert.deepEqual(codes, [1], 'the build has to report, or the task hangs');
    assert.ok(
      lines.at(-1)?.startsWith('Failed to start the build: '),
      `no failure reported in ${JSON.stringify(lines)}`,
    );
  });

  // The same guard, reached the other way. `buildToRun` drops an empty command, so only a malformed server
  // response gets here. `spawnArgumentsFor` then hands `spawn` a command name of `undefined`, which it rejects
  // with a throw. Without the guard the task never closes.
  test('an empty command is a failed build, not a task that never closes', async () => {
    const { lines, codes } = await run({ command: [] });
    assert.deepEqual(codes, [1], 'the build has to report, or the task hangs');
    assert.ok(
      lines.at(-1)?.startsWith('Failed to start the build: '),
      `no failure reported in ${JSON.stringify(lines)}`,
    );
  });

  test('an executable that does not exist is a failed build too, via the error event', async () => {
    const { lines, codes } = await run({ command: [missingExecutable] });
    assert.deepEqual(codes, [1]);
    assert.ok(
      lines.some((l) => l.startsWith('Failed to start the build: ')),
      `no failure reported in ${JSON.stringify(lines)}`,
    );
  });

  // Node emits *both* `error` and `close` for an executable that it cannot spawn. To report twice writes a
  // second "Build failed" into a pseudoterminal that VS Code has already closed.
  test('reports exactly once even when the failure arrives as two events', async () => {
    const { codes } = await run({ command: [missingExecutable] });
    assert.equal(codes.length, 1, `closed ${codes.length} times: ${JSON.stringify(codes)}`);
  });

  test('a build cancelled before it was spawned is killed rather than left running', async () => {
    // The `close` of the pseudoterminal can land while the build command still resolves. So `runProcess` can
    // reach the spawn already cancelled, and that handler had no child to kill.
    const running: RunningBuild = { cancelled: true };
    const { codes } = await run({ command: node('setTimeout(() => {}, 60_000)'), running });
    assert.equal(codes.length, 1);
    assert.notEqual(codes[0], 0, 'a killed build must not read as a successful one');
  });

  test('clears the child once it has ended, so nothing later kills a dead pid', async () => {
    const running: RunningBuild = {};
    await run({ command: node(''), running });
    assert.equal(running.child, undefined);
  });

  // A build that never started must clear the child too. `error` reports the failure, and the `close` after it
  // arrives once the task is already done. To clear only in `close` left a dead child for the task to kill.
  test('clears the child when the build failed to start, so nothing kills a pid that never was', async () => {
    const running: RunningBuild = {};
    await run({ command: [missingExecutable], running });
    assert.equal(running.child, undefined);
  });
});
