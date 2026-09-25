// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  Position,
  Range,
  type TestController,
  type TestItem,
  type TestItemCollection,
  Uri,
} from 'vscode';
import { type GroupNode, groupPath, moduleGroup, moduleNameOf } from './testGrouping';
import type { TestProfileTags } from './testProfileTags';
import type { TestItemDto } from './testProtocol';
import { type DiscoveryScope, scopeKeysOf } from './testScope';

export const treeId = (moduleName: string | null | undefined, testId: string): string =>
  `${moduleName ?? ''}/${testId}`;

export interface DiscoveredTestEntry {
  readonly origin: 'discovered';
  readonly item: TestItem;
  readonly dto: TestItemDto;
}

export interface RuntimeTestEntry {
  readonly origin: 'runtime';
  readonly item: TestItem;
  readonly owner: TestItemDto;
  readonly uniqueId: string;
}

export type TestEntry = DiscoveredTestEntry | RuntimeTestEntry;

export function ownerOf(entry: TestEntry): TestItemDto {
  switch (entry.origin) {
    case 'discovered':
      return entry.dto;
    case 'runtime':
      return entry.owner;
  }
}

export class TestTree {
  private readonly entries = new Map<string, TestEntry>();
  /** Scope key → tree ids covered by it, so a sync never has to walk the whole tree. */
  private readonly idsByScope = new MultiMap<string, string>();
  /** What a runner reports for a node → the node, so a run needs no id format to find it. */
  private readonly byLocation = new Map<string, DiscoveredTestEntry>();

  constructor(
    private readonly controller: TestController,
    private readonly tags: TestProfileTags,
  ) {}

  get(id: string): TestEntry | undefined {
    return this.entries.get(id);
  }

  /** A suite whose tests are still to be discovered: a module scan reports suites alone. */
  testsUnknown(item: TestItem): boolean {
    const entry = this.entries.get(item.id);
    if (entry?.origin !== 'discovered' || entry.dto.kind !== 'SUITE') return false;
    // A nested suite is a child too, and a runtime one came from a run: neither is a test discovery reported.
    return ![...item.children].some(([, child]) => {
      const childEntry = this.entries.get(child.id);
      return childEntry?.origin === 'discovered' && childEntry.dto.kind === 'TEST';
    });
  }

  itemAtLocation(options: { moduleName: string | null; location: string }): TestItem | undefined {
    return this.byLocation.get(treeId(options.moduleName, options.location))?.item;
  }

  ensureRuntimeItem(options: {
    parent: TestItem;
    nodeId: string;
    displayName: string;
  }): TestItem | undefined {
    const { nodeId, displayName } = options;
    const parent = this.entries.get(options.parent.id);
    if (!parent) return undefined;
    const owner = ownerOf(parent);
    const id = treeId(owner.moduleName, nodeId);
    const existing = this.entries.get(id);
    if (existing) return existing.item;

    // No range: the runner reports what ran, not where it is written.
    const item = this.controller.createTestItem(id, displayName, Uri.parse(owner.uri));
    item.tags = this.tags.ofNode(owner);
    // The runner reports the invocations of a test in order, and a label sorts `[10]` before `[2]`.
    item.sortText = String(parent.item.children.size).padStart(6, '0');
    parent.item.children.add(item);
    this.link(id, { origin: 'runtime', item, owner, uniqueId: nodeId });
    return item;
  }

  forgetRuntimeChildren(item: TestItem): void {
    const doomed: string[] = [];
    const collect = (parent: TestItem): void =>
      parent.children.forEach((child) => {
        if (this.entries.get(child.id)?.origin === 'runtime') doomed.push(child.id);
        else collect(child);
      });
    collect(item);
    for (const id of doomed) this.remove(id);
  }

  syncModules(moduleNames: readonly string[]): void {
    const present = new Set(moduleNames);
    const gone: string[] = [];
    this.controller.items.forEach((item) => {
      const moduleName = moduleNameOf(item.id);
      if (moduleName !== undefined && !present.has(moduleName) && item.children.size === 0) {
        gone.push(item.id);
      }
    });
    for (const id of gone) this.controller.items.delete(id);
    for (const moduleName of moduleNames) this.ensureGroupPath([moduleGroup(moduleName)]);
  }

  /** Modules the tree currently holds tests of — a workspace pass prunes the ones that are gone. */
  knownModules(): Set<string> {
    const names = new Set<string>();
    for (const entry of this.entries.values()) {
      const { moduleName } = ownerOf(entry);
      if (moduleName) names.add(moduleName);
    }
    return names;
  }

  /** Makes [scope] hold exactly [dtos]: merges them in, then drops whatever the scope held before and they don't. */
  sync(scope: DiscoveryScope, dtos: readonly TestItemDto[]): void {
    const merged = new Set<string>();
    for (const dto of dtos) {
      const id = this.mergeOne(dto);
      if (id) merged.add(id);
    }
    // Collected before removing anything: removal mutates the very set being read.
    // Only a discovered node is indexed by scope: a runtime one is no news by its absence (see RuntimeTestEntry).
    const stale = [...this.idsByScope.get(scope.key)].filter((id) => {
      if (merged.has(id)) return false;
      const entry = this.entries.get(id);
      // Tests survive a scope that never reported any (see DiscoveryScope.coversTests).
      return scope.coversTests || (entry?.origin === 'discovered' && entry.dto.kind === 'SUITE');
    });
    for (const id of stale) this.remove(id);
  }

  private mergeOne(dto: TestItemDto): string | undefined {
    const id = treeId(dto.moduleName, dto.id);
    const container =
      dto.kind === 'SUITE' && !dto.parentId
        ? this.ensureGroupPath(groupPath(dto))
        : this.entries.get(treeId(dto.moduleName, dto.parentId ?? ''))?.item.children;
    // A test or a nested suite whose parent isn't in this batch (and isn't already known) has nowhere to go.
    if (!container) return undefined;

    const item =
      this.entries.get(id)?.item ??
      this.controller.createTestItem(id, dto.displayName, Uri.parse(dto.uri));
    item.label = dto.displayName;
    item.range = new Range(
      new Position(dto.range.start.line, dto.range.start.character),
      new Position(dto.range.end.line, dto.range.end.character),
    );
    item.tags = this.tags.ofNode(dto);
    if (dto.kind === 'SUITE') item.canResolveChildren = true;
    container.add(item);
    this.link(id, { origin: 'discovered', item, dto });
    return id;
  }

  /** Detaches a test and everything under it, then folds up the group nodes it emptied. */
  private remove(id: string): void {
    const entry = this.entries.get(id);
    if (!entry) return;
    // Unlink before pruning: pruneUpwards tells a group node from a test node by asking `entries`.
    this.unlinkSubtree(id);
    const parent = entry.item.parent;
    if (parent) parent.children.delete(id);
    else this.controller.items.delete(id);
    this.pruneUpwards(parent);
  }

  private link(id: string, entry: TestEntry): void {
    // A test that moved to another file (or module) must not stay indexed under the old one.
    const previous = this.entries.get(id);
    if (previous) this.unindex(id, previous);
    this.entries.set(id, entry);
    if (entry.origin === 'runtime') return;
    for (const key of this.scopeKeys(entry.dto)) this.idsByScope.add(key, id);
    const { location, moduleName } = entry.dto;
    if (location) this.byLocation.set(treeId(moduleName, location), entry);
  }

  private unlinkSubtree(id: string): void {
    const entry = this.entries.get(id);
    if (!entry) return;
    entry.item.children.forEach((child) => this.unlinkSubtree(child.id));
    this.entries.delete(id);
    this.unindex(id, entry);
  }

  private unindex(id: string, entry: TestEntry): void {
    if (entry.origin === 'runtime') return;
    for (const key of this.scopeKeys(entry.dto)) this.idsByScope.delete(key, id);
    const { location, moduleName } = entry.dto;
    if (location) this.byLocation.delete(treeId(moduleName, location));
  }

  /**
   * A test inherited from a base suite belongs to the file of the suite that owns it, not to the file
   * it is written in: a scan of the base suite's file never reports it, and would take it for stale.
   */
  private scopeKeys(dto: TestItemDto): string[] {
    const parent = !dto.parentId
      ? undefined
      : this.entries.get(treeId(dto.moduleName, dto.parentId));
    const owner = parent && ownerOf(parent).uri;
    return scopeKeysOf(owner === undefined || owner === dto.uri ? dto : { ...dto, uri: owner });
  }

  private ensureGroupPath(groups: readonly GroupNode[]): TestItemCollection {
    let container = this.controller.items;
    for (const group of groups) {
      const existing = container.get(group.id);
      if (existing) {
        container = existing.children;
        continue;
      }
      const node = this.controller.createTestItem(group.id, group.label);
      node.tags = this.tags.ofGroup;
      node.canResolveChildren = moduleNameOf(group.id) !== undefined;
      container.add(node);
      container = node.children;
    }
    return container;
  }

  private pruneUpwards(from: TestItem | undefined): void {
    let node = from;
    while (node && node.children.size === 0 && !this.entries.has(node.id)) {
      const parent = node.parent;
      if (parent) parent.children.delete(node.id);
      else this.controller.items.delete(node.id);
      node = parent;
    }
  }
}

const NO_VALUES: ReadonlySet<never> = new Set();

/** Key → set of values, dropping a key as soon as its last value goes. */
class MultiMap<K, V> {
  private readonly buckets = new Map<K, Set<V>>();

  get(key: K): ReadonlySet<V> {
    return this.buckets.get(key) ?? NO_VALUES;
  }

  add(key: K, value: V): void {
    let bucket = this.buckets.get(key);
    if (!bucket) this.buckets.set(key, (bucket = new Set()));
    bucket.add(value);
  }

  delete(key: K, value: V): void {
    const bucket = this.buckets.get(key);
    if (!bucket) return;
    bucket.delete(value);
    if (bucket.size === 0) this.buckets.delete(key);
  }
}
