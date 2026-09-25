// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { TestController, TestItem, Uri } from 'vscode';
import type { TestProfile } from './testLanguage';
import { TestProfileTags } from './testProfileTags';
import { TestTree } from './testTree';

export const PROFILES = {
  run: { id: 'run', label: 'Run', button: 'run', nodes: 'any' },
  fuzz: { id: 'fuzz', label: 'Fuzz', button: 'run', nodes: 'tagged' },
} as const satisfies Record<string, TestProfile>;

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

export function makeTree() {
  const controller = {
    createTestItem: (id: string, label: string, uri?: Uri) => makeItem(id, label, uri),
  } as unknown as TestController;
  (controller as { items: unknown }).items = makeCollection(undefined);
  const tags = new TestProfileTags(Object.values(PROFILES));
  return { controller, tags, tree: new TestTree(controller, tags) };
}
