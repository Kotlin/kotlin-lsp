// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { rm } from 'node:fs/promises';
import { Range, Uri } from 'vscode';
import { getOutputChannel } from '@jetbrains/vscode-extension-core';
import { errorMessage } from '@jetbrains/vscode-extension-core/build';
import type {
  TestCoverageDetail,
  TestFileCoverage,
  TestRunGroup,
  TestRunInput,
} from '@jetbrains/vscode-testing';
import type { JvmFileCoverage, JvmTestCoverageRequest, JvmTestLaunch } from './jvmTestProtocol';

export interface JvmTestCoveragesOptions {
  load: (request: JvmTestCoverageRequest) => Promise<JvmFileCoverage[]>;
  /** Overridden in tests, where there is no data file. */
  remove?: (file: string) => Promise<void>;
  /** Overridden in tests, where there is no output channel. */
  log?: (message: string) => void;
}

/**
 * Loads the coverage of the groups of one test run. Two groups can run the same source file, and VS Code keeps
 * the last report of a file. So a group loads the data of the groups before it too, and the data files stay
 * until the run finishes.
 */
export class JvmTestCoverages {
  private readonly dataFiles = new Map<TestRunGroup, readonly string[]>();
  private loaded: readonly string[] = [];
  private readonly load: JvmTestCoveragesOptions['load'];
  private readonly remove: (file: string) => Promise<void>;
  private readonly log: (message: string) => void;

  constructor(options: JvmTestCoveragesOptions) {
    this.load = options.load;
    this.remove = options.remove ?? ((file) => rm(file, { force: true }));
    this.log = options.log ?? ((message) => getOutputChannel().appendLine(message));
  }

  /** Keeps the data files that [launches] of [group] write. */
  launched(group: TestRunGroup, launches: readonly JvmTestLaunch[]): void {
    this.dataFiles.set(
      group,
      launches.flatMap((launch) => launch.coverageData ?? []),
    );
  }

  /** Reports the coverage of the group. A failure goes to the test results, and the tests keep their verdicts. */
  async report({ group, report, token }: TestRunInput): Promise<void> {
    const dataFiles = this.dataFiles.get(group) ?? [];
    if (dataFiles.length === 0 || token.isCancellationRequested) return;
    const all = [...this.loaded, ...dataFiles];
    let files: JvmFileCoverage[];
    try {
      files = await this.load({ dataFiles: all, file: group.uri.toString() });
    } catch (e) {
      const reason = `The coverage could not load: ${errorMessage(e)}.`;
      report.output({ text: `${reason}\n` });
      this.log(`[jvmTest] ${reason}`);
      return;
    }
    this.loaded = all;
    for (const file of files) report.coverage(fileCoverageOf(file));
  }

  /** Deletes the data files of the run. */
  async finished(): Promise<void> {
    for (const file of [...this.dataFiles.values()].flat()) {
      try {
        await this.remove(file);
      } catch (e) {
        this.log(`[jvmTest] The coverage data file ${file} stays: ${errorMessage(e)}.`);
      }
    }
  }
}

/** The coverage of [file] for the Testing view. The server counts no executions, so a detail tells only whether it ran. */
export function fileCoverageOf(file: JvmFileCoverage): TestFileCoverage {
  const lines = file.lines.map((line): TestCoverageDetail =>
    line.branches > 0
      ? {
          kind: 'branch',
          range: rangeOf(line.range),
          executed: line.covered,
          branches: Array.from({ length: line.branches }, (_, index) => ({
            executed: index < line.coveredBranches,
          })),
        }
      : { kind: 'statement', range: rangeOf(line.range), executed: line.covered },
  );
  const methods = file.methods.map((method): TestCoverageDetail => ({
    kind: 'declaration',
    name: method.name,
    range: rangeOf(method.range),
    executed: method.covered,
  }));
  const branches = file.lines.reduce((sum, line) => sum + line.branches, 0);
  return {
    uri: Uri.parse(file.uri),
    statements: { covered: file.lines.filter((line) => line.covered).length, total: lines.length },
    ...(branches > 0 && {
      branches: {
        covered: file.lines.reduce((sum, line) => sum + line.coveredBranches, 0),
        total: branches,
      },
    }),
    declarations: {
      covered: file.methods.filter((method) => method.covered).length,
      total: methods.length,
    },
    details: () => Promise.resolve([...lines, ...methods]),
  };
}

const rangeOf = ({ start, end }: JvmFileCoverage['lines'][number]['range']): Range =>
  new Range(start.line, start.character, end.line, end.character);
