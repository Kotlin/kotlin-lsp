import type { JvmTestItemDto } from './jvmTestProtocol';

export interface DiscoveryScope {
  readonly key: string;
  readonly coversMethods: boolean;
}

/** One file, as reported by `discoverTestsInFile`: its classes and all of their methods. */
export const fileScope = (uri: string): DiscoveryScope => ({
  key: `file:${uri}`,
  coversMethods: true,
});

/** One module, as reported by `discoverTestsInModule`: its test classes, without their methods. */
export const moduleScope = (moduleName: string): DiscoveryScope => ({
  key: `module:${moduleName}`,
  coversMethods: false,
});

/**
 * Keys the item is indexed under — one per scope that covers it. A file outside any module gets no
 * module key, which is exactly why a module scan can never prune such a test.
 */
export const scopeKeysOf = (dto: JvmTestItemDto): string[] => [
  fileScope(dto.uri).key,
  ...(dto.moduleName ? [moduleScope(dto.moduleName).key] : []),
];
