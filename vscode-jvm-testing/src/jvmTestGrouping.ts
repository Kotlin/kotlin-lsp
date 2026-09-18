import type { JvmTestItemDto } from './jvmTestProtocol';

export interface GroupNode {
  readonly id: string;
  readonly label: string;
}

export interface TestTreeGrouping {
  groupsFor(dto: JvmTestItemDto): readonly GroupNode[];
}

const MODULE_ID_PREFIX = 'module:';
const PACKAGE_ID_PREFIX = 'package:';
const DEFAULT_PACKAGE_LABEL = '(default package)';

export const moduleGroup = (moduleName: string): GroupNode => ({
  id: `${MODULE_ID_PREFIX}${moduleName}`,
  label: moduleName,
});

/** The module a group node stands for, or undefined for any other node. */
export const moduleNameOf = (id: string): string | undefined =>
  id.startsWith(MODULE_ID_PREFIX) ? id.substring(MODULE_ID_PREFIX.length) : undefined;

/** `module → package → class → method`, the shape vscode-java-test and the IDE both use. */
export const moduleAndPackageGrouping: TestTreeGrouping = {
  groupsFor(dto: JvmTestItemDto): readonly GroupNode[] {
    const lastDot = dto.className.lastIndexOf('.');
    const packageName = lastDot === -1 ? '' : dto.className.substring(0, lastDot);
    const packageNode: GroupNode = {
      id: `${PACKAGE_ID_PREFIX}${dto.moduleName ?? ''}/${packageName}`,
      label: packageName || DEFAULT_PACKAGE_LABEL,
    };
    // A file outside any module has no module node to sit under, so its package goes to the top level.
    if (!dto.moduleName) return [packageNode];
    return [moduleGroup(dto.moduleName), packageNode];
  },
};
