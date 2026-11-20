// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { CancellationToken, TestRun, Uri } from 'vscode';
import type {
  TestFileCoverage,
  TestRunGroup,
  TestRunInput,
  TestRunReport,
} from '@jetbrains/vscode-testing';
import { fileCoverageOf, JvmTestCoverages } from './jvmTestCoverage';
import type { JvmFileCoverage, JvmTestCoverageRequest, JvmTestLaunch } from './jvmTestProtocol';

const rangeAt = (line: number, start: number, end: number) => ({
  start: { line, character: start },
  end: { line, character: end },
});

const FILE: JvmFileCoverage = {
  uri: 'file:///project/src/app/Calculator.java',
  lines: [
    { range: rangeAt(4, 8, 24), covered: true, branches: 2, coveredBranches: 1 },
    { range: rangeAt(5, 12, 22), covered: false, branches: 0, coveredBranches: 0 },
    { range: rangeAt(7, 8, 17), covered: true, branches: 0, coveredBranches: 0 },
    { range: rangeAt(8, 8, 30), covered: true, branches: 2, coveredBranches: 0 },
  ],
  methods: [
    { name: 'sign', range: rangeAt(3, 15, 19), covered: true },
    { name: 'unused', range: rangeAt(10, 15, 21), covered: false },
  ],
};

const TOKEN = {} as CancellationToken;

describe('fileCoverageOf', () => {
  test('counts the covered lines, branches and methods of the file', () => {
    const coverage = fileCoverageOf(FILE);

    assert.equal(coverage.uri.toString(), FILE.uri);
    assert.deepEqual(coverage.statements, { covered: 3, total: 4 });
    assert.deepEqual(coverage.branches, { covered: 1, total: 4 });
    assert.deepEqual(coverage.declarations, { covered: 1, total: 2 });
  });

  // Without the count, the Testing view shows no branch column for the file.
  test('gives no branch count to a file without branches', () => {
    const coverage = fileCoverageOf({ ...FILE, lines: FILE.lines.slice(1, 3) });

    assert.equal('branches' in coverage, false);
  });

  // A number would show as a count of executions, and the server has no count.
  // A line can run without a branch taken to its end, as a lambda that the line only creates.
  test('makes a line with branches one detail with its own state and a state for each branch', async () => {
    const details = await fileCoverageOf(FILE).details(TOKEN);

    // The stub of `Range` keeps no line, so the test compares the other fields.
    assert.deepEqual(
      details.map((detail) => ({ ...detail, range: undefined })),
      [
        {
          kind: 'branch',
          range: undefined,
          executed: true,
          branches: [{ executed: true }, { executed: false }],
        },
        { kind: 'statement', range: undefined, executed: false },
        { kind: 'statement', range: undefined, executed: true },
        {
          kind: 'branch',
          range: undefined,
          executed: true,
          branches: [{ executed: false }, { executed: false }],
        },
        { kind: 'declaration', range: undefined, name: 'sign', executed: true },
        { kind: 'declaration', range: undefined, name: 'unused', executed: false },
      ],
    );
  });
});

function uriOf(path: string): Uri {
  return { toString: () => `file://${path}` } as unknown as Uri;
}

describe('JvmTestCoverages', () => {
  const APP = '/project/app/test/AppTest.java';
  const LIB = '/project/lib/test/LibTest.java';

  let requests: JvmTestCoverageRequest[];
  let reported: string[];
  let output: string[];
  let log: string[];
  let removed: string[];

  function coveragesOf(
    options: {
      load?: () => Promise<JvmFileCoverage[]>;
      remove?: (file: string) => Promise<void>;
    } = {},
  ) {
    const { load = () => Promise.resolve([FILE]), remove = () => Promise.resolve() } = options;
    requests = [];
    reported = [];
    output = [];
    log = [];
    removed = [];
    return new JvmTestCoverages({
      load: (request) => {
        requests.push(request);
        return load();
      },
      remove: (file) => {
        removed.push(file);
        return remove(file);
      },
      log: (message) => log.push(message),
    });
  }

  function launchOf(coverageData: string): JvmTestLaunch {
    return { mainClass: 'Main', args: [], runtimeClasspath: [], coverageData };
  }

  /** The input of the group of [path], after its launches got [dataFiles] from the server. */
  function launchedInput(
    coverages: JvmTestCoverages,
    path: string,
    dataFiles: string[],
    cancelled = false,
  ): TestRunInput {
    const report = {
      output: ({ text }: { text: string }) => output.push(text),
      coverage: (file: TestFileCoverage) => reported.push(file.uri.toString()),
    } as unknown as TestRunReport;
    const group = { uri: uriOf(path) } as TestRunGroup;
    coverages.launched(group, dataFiles.map(launchOf));
    return {
      group,
      report,
      token: { isCancellationRequested: cancelled } as CancellationToken,
      run: {} as TestRun,
    };
  }

  test('reports the files that the server reads from the data of a group', async () => {
    const coverages = coveragesOf();

    await coverages.report(launchedInput(coverages, APP, ['/tmp/app.exec']));

    assert.deepEqual(requests, [{ dataFiles: ['/tmp/app.exec'], file: `file://${APP}` }]);
    assert.deepEqual(reported, [FILE.uri]);
  });

  // Two groups can run the same source file, and VS Code keeps the last report of a file.
  test('loads the data of the earlier groups of the run together with the data of a group', async () => {
    const coverages = coveragesOf();

    await coverages.report(launchedInput(coverages, APP, ['/tmp/app.exec']));
    await coverages.report(launchedInput(coverages, LIB, ['/tmp/lib.exec']));

    assert.deepEqual(requests[1], {
      dataFiles: ['/tmp/app.exec', '/tmp/lib.exec'],
      file: `file://${LIB}`,
    });
  });

  // The tests of the group ran, and a rejection would give each of them an error.
  test('tells the reason in the test results when the server cannot load the coverage', async () => {
    const coverages = coveragesOf({
      load: () => Promise.reject(new Error('Invalid execution data file')),
    });

    await coverages.report(launchedInput(coverages, APP, ['/tmp/app.exec']));

    assert.deepEqual(reported, []);
    assert.deepEqual(output, ['The coverage could not load: Invalid execution data file.\n']);
    assert.deepEqual(log, ['[jvmTest] The coverage could not load: Invalid execution data file.']);
  });

  test('leaves out the data that did not load when it loads the next group', async () => {
    let fail = true;
    const coverages = coveragesOf({
      load: () =>
        fail ? Promise.reject(new Error('Invalid execution data file')) : Promise.resolve([FILE]),
    });

    await coverages.report(launchedInput(coverages, APP, ['/tmp/app.exec']));
    fail = false;
    await coverages.report(launchedInput(coverages, LIB, ['/tmp/lib.exec']));

    assert.deepEqual(requests[1].dataFiles, ['/tmp/lib.exec']);
    assert.deepEqual(reported, [FILE.uri]);
  });

  test('asks the server nothing for a group without data, and nothing after a cancellation', async () => {
    const coverages = coveragesOf();

    await coverages.report(launchedInput(coverages, APP, []));
    await coverages.report(launchedInput(coverages, APP, ['/tmp/app.exec'], true));

    assert.deepEqual(requests, []);
  });

  // A later group reads the data of the groups before it, so no file goes before the last group.
  // The launches of a group get their files before the build, so a group that did not run has files too.
  test('deletes the data files of each group when the run finishes, and none before', async () => {
    const coverages = coveragesOf();
    await coverages.report(launchedInput(coverages, APP, ['/tmp/app.exec']));
    launchedInput(coverages, LIB, ['/tmp/lib.exec']);
    assert.deepEqual(removed, []);

    await coverages.finished();

    assert.deepEqual(removed, ['/tmp/app.exec', '/tmp/lib.exec']);
  });

  test('writes a data file that it cannot delete to the log, and deletes the others', async () => {
    const coverages = coveragesOf({
      remove: (file) =>
        file === '/tmp/app.exec' ? Promise.reject(new Error('EBUSY')) : Promise.resolve(),
    });
    launchedInput(coverages, APP, ['/tmp/app.exec']);
    launchedInput(coverages, LIB, ['/tmp/lib.exec']);

    await coverages.finished();

    assert.deepEqual(removed, ['/tmp/app.exec', '/tmp/lib.exec']);
    assert.deepEqual(log, ['[jvmTest] The coverage data file /tmp/app.exec stays: EBUSY.']);
  });
});
