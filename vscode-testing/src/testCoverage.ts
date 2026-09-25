// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  BranchCoverage,
  type CancellationToken,
  DeclarationCoverage,
  FileCoverage,
  type FileCoverageDetail,
  type Range,
  StatementCoverage,
  TestCoverageCount,
  type Uri,
} from 'vscode';

export interface TestFileCoverage {
  readonly uri: Uri;
  readonly statements: CoverageCount;
  readonly branches?: CoverageCount;
  readonly declarations?: CoverageCount;
  details(token: CancellationToken): Promise<readonly TestCoverageDetail[]>;
}

export interface CoverageCount {
  readonly covered: number;
  readonly total: number;
}

export type TestCoverageDetail =
  | { readonly kind: 'statement'; readonly range: Range; readonly executed: number }
  | {
      readonly kind: 'branch';
      readonly range: Range;
      readonly branches: readonly { readonly executed: number }[];
    }
  | {
      readonly kind: 'declaration';
      readonly name: string;
      readonly range: Range;
      readonly executed: number;
    };

export class LanguageFileCoverage extends FileCoverage {
  constructor(private readonly source: TestFileCoverage) {
    super(
      source.uri,
      countOf(source.statements),
      source.branches && countOf(source.branches),
      source.declarations && countOf(source.declarations),
    );
  }

  async details(token: CancellationToken): Promise<FileCoverageDetail[]> {
    return (await this.source.details(token)).map(detailOf);
  }
}

const countOf = ({ covered, total }: CoverageCount): TestCoverageCount =>
  new TestCoverageCount(covered, total);

function detailOf(detail: TestCoverageDetail): FileCoverageDetail {
  switch (detail.kind) {
    case 'statement':
      return new StatementCoverage(detail.executed, detail.range);
    case 'branch':
      return new StatementCoverage(
        detail.branches.reduce((sum, branch) => sum + branch.executed, 0),
        detail.range,
        detail.branches.map((branch) => new BranchCoverage(branch.executed)),
      );
    case 'declaration':
      return new DeclarationCoverage(detail.name, detail.executed, detail.range);
  }
}
