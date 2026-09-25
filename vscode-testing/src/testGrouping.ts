// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { TestItemDto } from './testProtocol';

export interface GroupNode {
  readonly id: string;
  readonly label: string;
}

const MODULE_ID_PREFIX = 'module:';
const GROUP_ID_PREFIX = 'group:';

export const moduleGroup = (moduleName: string): GroupNode => ({
  id: `${MODULE_ID_PREFIX}${moduleName}`,
  label: moduleName,
});

/** The module a group node stands for, or undefined for any other node. */
export const moduleNameOf = (id: string): string | undefined =>
  id.startsWith(MODULE_ID_PREFIX) ? id.substring(MODULE_ID_PREFIX.length) : undefined;

export function groupPath(dto: TestItemDto): GroupNode[] {
  let path = encodeURIComponent(dto.moduleName ?? '');
  const groups = dto.groups.map((group): GroupNode => {
    path = `${path}/${encodeURIComponent(group.id)}`;
    return { id: `${GROUP_ID_PREFIX}${path}`, label: group.label };
  });
  // A file outside any module has no module node to sit under, so its groups go to the top level.
  if (!dto.moduleName) return groups;
  return [moduleGroup(dto.moduleName), ...groups];
}
