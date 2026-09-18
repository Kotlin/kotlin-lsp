import type { TestRun } from 'vscode';
import {
  type TestReport,
  TestReportStream,
  type TestReportStreamOptions,
} from './testReportStream';

export interface TestRunReporterOptions extends TestReportStreamOptions {
  readonly run: TestRun;
}

export class TestRunReporter {
  private readonly stream: TestReportStream;

  constructor(private readonly options: TestRunReporterOptions) {
    this.stream = new TestReportStream(options);
  }

  readonly feed = (chunk: string): void => {
    applyReports(this.options.run, this.stream.feed(chunk));
  };

  finish(exitCode: number | undefined): void {
    applyReports(this.options.run, this.stream.finish(exitCode));
  }
}

function applyReports(run: TestRun, reports: readonly TestReport[]): void {
  for (const report of reports) {
    switch (report.kind) {
      case 'started':
        run.started(report.item);
        break;
      case 'output':
        // VS Code's test output channel is a terminal, and wants CRLF.
        run.appendOutput(report.text.replace(/\r?\n/g, '\r\n'), undefined, report.item);
        break;
      case 'finished': {
        const { item, message, duration } = report;
        switch (report.outcome) {
          case 'passed':
            run.passed(item, duration);
            break;
          case 'skipped':
            run.skipped(item);
            break;
          case 'failed':
            run.failed(item, message ?? [], duration);
            break;
          case 'errored':
            run.errored(item, message ?? [], duration);
            break;
        }
        break;
      }
    }
  }
}
