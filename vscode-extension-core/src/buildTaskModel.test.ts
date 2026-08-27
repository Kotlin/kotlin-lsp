import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  buildToRun,
  createOutputLineSplitter,
  launchBuildTargetOf,
  needsCmdShell,
  quoteCommandForCmd,
  quoteForCmd,
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
  // Regression (LSP-1716): the *command name* was quoted like an argument, which is a different job. The server's
  // PATH fallback for a project with no wrapper is a bare `mvn.cmd` / `gradle.bat`, and cmd.exe does not resolve a
  // quoted bare name the way it resolves a typed one — it takes the quoted text for a file specification, so it
  // neither finds the tool on PATH nor, when it does, gives the batch file its own `%~dp0`. Both Gradle's and
  // Maven's launchers locate their installation through `%~dp0`, so they exited 1 having printed nothing about it,
  // and every build in a wrapper-less project on Windows failed.
  test('leaves the bare PATH fallback unquoted, so cmd.exe looks it up as a typed name', () => {
    assert.equal(quoteCommandForCmd('mvn.cmd'), 'mvn.cmd');
    assert.equal(quoteCommandForCmd('gradle.bat'), 'gradle.bat');
    assert.equal(quoteCommandForCmd('mvn'), 'mvn');
  });

  // The case quoting exists for, and the one that made wrapper-shipping projects work all along.
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
    // Drive-relative: still a path, and one whose leading `C:` cmd.exe would not look up on PATH either.
    assert.equal(quoteCommandForCmd('C:mvnw.cmd'), '"C:mvnw.cmd"');
    assert.equal(quoteCommandForCmd('/usr/local/bin/mvn'), '"/usr/local/bin/mvn"');
  });

  // Unreachable for the names the server produces, but leaving such a name unquoted would hand cmd.exe some *other*
  // command — the leading word — to run, which is worse than failing to find this one.
  test('quotes a name that is not a path but that cmd.exe would otherwise split', () => {
    assert.equal(quoteCommandForCmd('my tool.cmd'), '"my tool.cmd"');
    assert.equal(quoteCommandForCmd('tool&other.cmd'), '"tool&other.cmd"');
    // Quotes do not stop the expansion, only the split of whatever it expands to.
    assert.equal(quoteCommandForCmd('%TOOL%.cmd'), '"%TOOL%.cmd"');
  });

  // `!` is literal already, because Node spawns cmd.exe without `/v:on` — the reason `quoteForCmd` documents for
  // not escaping it. Quoting the name here would only stop the PATH lookup that this function exists to allow.
  test('leaves a name holding `!` bare, because delayed expansion is off', () => {
    assert.equal(quoteCommandForCmd('hi!.cmd'), 'hi!.cmd');
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
