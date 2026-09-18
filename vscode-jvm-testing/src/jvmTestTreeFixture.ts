import type { TestController, TestItem, Uri } from 'vscode';
import type { TestTreeGrouping } from './jvmTestGrouping';
import { JvmTestTree } from './jvmTestTree';

export function makeCollection(owner: TestItem | undefined) {
  const items = new Map<string, TestItem>();
  return {
    get size() {
      return items.size;
    },
    add(item: TestItem) {
      (item as { parent?: TestItem }).parent = owner;
      items.set(item.id, item);
    },
    delete(id: string) {
      items.delete(id);
    },
    replace(list: TestItem[]) {
      items.clear();
      for (const item of list) items.set(item.id, item);
    },
    forEach(callback: (item: TestItem) => void) {
      for (const item of items.values()) callback(item);
    },
    get(id: string) {
      return items.get(id);
    },
    [Symbol.iterator](): Iterator<[string, TestItem]> {
      return items.entries();
    },
  };
}

export function makeItem(id: string, label: string, uri?: Uri): TestItem {
  const item = { id, label, uri } as unknown as TestItem;
  (item as { children: unknown }).children = makeCollection(item);
  return item;
}

export function makeTree(grouping?: TestTreeGrouping) {
  const controller = {
    createTestItem: (id: string, label: string, uri?: Uri) => makeItem(id, label, uri),
  } as unknown as TestController;
  (controller as { items: unknown }).items = makeCollection(undefined);
  return { controller, tree: new JvmTestTree(controller, grouping) };
}
