// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

export type TestNodeId = string & { readonly __testNodeId: unique symbol };

export type TestNodeKind = 'SUITE' | 'TEST';

export interface TestGroup {
  readonly id: string;
  readonly label: string;
}

export interface TestItemDto {
  id: TestNodeId;
  kind: TestNodeKind;
  displayName: string;
  uri: string;
  range: {
    start: { line: number; character: number };
    end: { line: number; character: number };
  };
  parentId?: TestNodeId | null;
  location?: string;
  moduleName?: string | null;
  groups: TestGroup[];
  tags?: string[];
}

export interface UniqueTestNode {
  readonly ownerId: TestNodeId;
  readonly uniqueId: string;
}

export interface TestDiscoveryCommands {
  readonly discoverTestsInFile: string;
  readonly discoverTestModules: string;
  readonly discoverTestsInModule: string;
}
