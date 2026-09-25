// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { TestItemDto } from './testProtocol';

export interface DiscoveryScope {
  readonly key: string;
  readonly coversTests: boolean;
}

/** One file, as reported by `discoverTestsInFile`: its suites and all of their tests. */
export const fileScope = (uri: string): DiscoveryScope => ({
  key: `file:${uri}`,
  coversTests: true,
});

/** One module, as reported by `discoverTestsInModule`: its suites, without their tests. */
export const moduleScope = (moduleName: string): DiscoveryScope => ({
  key: `module:${moduleName}`,
  coversTests: false,
});

/**
 * Keys the item is indexed under — one per scope that covers it. A file outside any module gets no
 * module key, which is exactly why a module scan can never prune such a test.
 */
export const scopeKeysOf = (dto: TestItemDto): string[] => [
  fileScope(dto.uri).key,
  ...(dto.moduleName ? [moduleScope(dto.moduleName).key] : []),
];
