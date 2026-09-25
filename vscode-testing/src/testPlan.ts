// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { TestItem, TestItemCollection, TestRunRequest, TestTag, Uri } from 'vscode';
import type { TestRunGroup } from './testLanguage';
import type { TestNodeId, UniqueTestNode } from './testProtocol';
import { ownerOf, type TestTree } from './testTree';

export interface TestLaunchGroup extends TestRunGroup {
  readonly items: readonly TestItem[];
}

export interface TestRunPlan {
  readonly groups: readonly TestLaunchGroup[];
  /** Suites the plan went into without knowing their tests, so it may miss the tests it wants among them. */
  readonly unresolved: readonly TestItem[];
}

interface GroupBuilder {
  readonly moduleName: string | null;
  readonly items: TestItem[];
  readonly testIds: TestNodeId[];
  readonly uniqueIds: UniqueTestNode[];
  readonly uri: Uri;
}

export function planTestRun(
  request: TestRunRequest,
  roots: TestItemCollection,
  tree: TestTree,
  tag: TestTag,
): TestRunPlan {
  const excluded = new Set((request.exclude ?? []).map((item) => item.id));
  const groups = new Map<string, GroupBuilder>();
  const unresolved: TestItem[] = [];

  const holdsExcluded = (item: TestItem): boolean => {
    for (const [, child] of item.children) {
      if (excluded.has(child.id) || holdsExcluded(child)) return true;
    }
    return false;
  };

  const descend = (item: TestItem): void => {
    if (tree.testsUnknown(item)) unresolved.push(item);
    item.children.forEach(visit);
  };

  const visit = (item: TestItem): void => {
    if (excluded.has(item.id)) return;
    const entry = tree.get(item.id);
    if (!entry || holdsExcluded(item)) {
      descend(item);
      return;
    }
    if (!item.uri) return;
    const { moduleName } = ownerOf(entry);
    if (!item.tags.some((carried) => carried.id === tag.id)) {
      descend(item);
      return;
    }
    const key = moduleName ?? '';
    const group = groups.get(key) ?? {
      moduleName: moduleName ?? null,
      items: [],
      testIds: [],
      uniqueIds: [],
      uri: item.uri,
    };
    group.items.push(item);
    switch (entry.origin) {
      case 'discovered':
        group.testIds.push(entry.dto.id);
        break;
      case 'runtime':
        group.uniqueIds.push({ ownerId: entry.owner.id, uniqueId: entry.uniqueId });
        break;
    }
    groups.set(key, group);
  };

  // No `include` means "Run All Tests" from the Test Explorer toolbar: everything in the tree.
  if (request.include) request.include.forEach(visit);
  else roots.forEach(visit);

  return {
    groups: [...groups.values()].map((group) => ({ ...group, name: launchName(group) })),
    unresolved,
  };
}

function launchName({ items, moduleName }: GroupBuilder): string {
  const single = items.length === 1 ? items[0].label : undefined;
  if (single) return `Run ${single}`;
  return `Run ${items.length} tests${moduleName ? ` in ${moduleName}` : ''}`;
}
