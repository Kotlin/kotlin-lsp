import type { TestItem, TestItemCollection, TestRunRequest, Uri } from 'vscode';
import type { JvmUniqueTestNode } from './jvmTestProtocol';
import type { JvmTestTree } from './jvmTestTree';

/**
 * Everything one run of one module will run. The server splits it into processes: one runner takes
 * the tests of one framework only, and only the server knows which framework a test belongs to.
 */
export interface JvmTestLaunchGroup {
  readonly moduleName: string | null;
  /** The nodes the run was asked for — what to mark started, and to conclude by exit code if the runner said nothing. */
  readonly items: TestItem[];
  /** The ids the server gave the discovered nodes, as `resolveTestLaunch` takes them back. */
  readonly testIds: string[];
  /** The nodes the runner named itself: one invocation of a method. */
  readonly uniqueIds: JvmUniqueTestNode[];
  /** Any file of the group, enough to resolve the module's classpath and its workspace folder. */
  readonly uri: Uri;
}

export function planTestRun(
  request: TestRunRequest,
  roots: TestItemCollection,
  tree: JvmTestTree,
): JvmTestLaunchGroup[] {
  const excluded = new Set((request.exclude ?? []).map((item) => item.id));
  const groups = new Map<string, JvmTestLaunchGroup>();

  const holdsExcluded = (item: TestItem): boolean => {
    for (const [, child] of item.children) {
      if (excluded.has(child.id) || holdsExcluded(child)) return true;
    }
    return false;
  };

  const visit = (item: TestItem): void => {
    if (excluded.has(item.id)) return;
    const entry = tree.get(item.id);
    const dto = entry?.dto;
    if (!dto || holdsExcluded(item)) {
      item.children.forEach(visit);
      return;
    }
    // A node the server cannot run is not worth asking about, and every test node has a file.
    if (!item.uri || dto.runnable === false) return;
    const key = dto.moduleName ?? '';
    const group = groups.get(key) ?? {
      moduleName: dto.moduleName ?? null,
      items: [],
      testIds: [],
      uniqueIds: [],
      uri: item.uri,
    };
    group.items.push(item);
    if (entry?.uniqueId) {
      group.uniqueIds.push({ className: dto.className, uniqueId: entry.uniqueId });
    } else {
      group.testIds.push(dto.id);
    }
    groups.set(key, group);
  };

  // No `include` means "Run All Tests" from the Test Explorer toolbar: everything in the tree.
  if (request.include) request.include.forEach(visit);
  else roots.forEach(visit);

  return [...groups.values()];
}

/** The nodes a run reports a result on: the tests under [item], or [item] itself when it holds none. */
export function leafTests(item: TestItem): TestItem[] {
  const leaves: TestItem[] = [];
  const visit = (node: TestItem): void => {
    let childless = true;
    node.children.forEach((child) => {
      childless = false;
      visit(child);
    });
    if (childless) leaves.push(node);
  };
  visit(item);
  return leaves;
}
