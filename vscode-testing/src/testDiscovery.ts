// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  type TestItem,
  type TestItemCollection,
  type TestRunRequest,
  type TestTag,
  type TextDocument,
  Uri,
} from 'vscode';
import { getLspClient } from '@jetbrains/vscode-extension-core';
import { moduleNameOf } from './testGrouping';
import type { TestDiscovery } from './testLanguage';
import { planTestRun, type TestLaunchGroup } from './testPlan';
import { fileScope, moduleScope } from './testScope';
import type { TestTree } from './testTree';
import { WorkspaceImportStateRequest } from './workspaceImport';

const DISCOVERY_CONCURRENCY = 4;

/** What discovery needs of the running server beyond the language's own answers. */
export interface DiscoveryServer {
  /** Whether an import cycle is running, and so the workspace has no modules to report yet. */
  importInProgress(): Promise<boolean>;
}

/** The running language server, or undefined while it is down. */
export function lspDiscoveryServer(): DiscoveryServer | undefined {
  const client = getLspClient();
  if (!client) return undefined;
  return {
    importInProgress: async () =>
      (await client.sendRequest(WorkspaceImportStateRequest, {})).phase === 'IN_PROGRESS',
  };
}

export class TestTreeDiscovery {
  private workspacePass: Promise<void> = Promise.resolve();
  private readonly scannedModules = new Set<string>();

  constructor(
    private readonly tree: TestTree,
    private readonly discovery: TestDiscovery,
    private readonly server: () => DiscoveryServer | undefined,
  ) {}

  refreshFile(document: TextDocument | Uri): Promise<void> {
    if ('languageId' in document && !this.discovery.languageIds.has(document.languageId)) {
      return Promise.resolve();
    }
    return this.syncFile('languageId' in document ? document.uri : document);
  }

  resolve(item: TestItem): Promise<void> {
    const moduleName = moduleNameOf(item.id);
    if (moduleName !== undefined) return this.resolveModule(moduleName);
    // Only module and suite nodes are marked resolvable, and a suite always carries a file.
    if (!item.uri) return Promise.resolve();
    return this.refreshFile(item.uri);
  }

  /** The launches of a run, planned only once the tests they choose from are discovered. */
  async planRun(
    request: TestRunRequest,
    roots: TestItemCollection,
    tag: TestTag,
  ): Promise<readonly TestLaunchGroup[]> {
    // A module holds no test until it is scanned, and a run of one has to find them.
    await this.resolveModules(request.include ?? [...roots].map(([, item]) => item));
    let plan = planTestRun(request, roots, this.tree, tag);
    // A module scan reports suites alone, and a profile that picks tests by tag finds them only among the tests.
    if (plan.unresolved.length > 0) {
      await this.resolveTests(plan.unresolved);
      plan = planTestRun(request, roots, this.tree, tag);
    }
    // A run marks each test queued, and a suite taken whole may not know its tests yet.
    await this.resolveTests(plan.groups.flatMap((group) => group.items));
    return plan.groups;
  }

  resolveModules(items: readonly TestItem[]): Promise<void> {
    const names = items
      .map((item) => moduleNameOf(item.id))
      .filter((name): name is string => name !== undefined && !this.scannedModules.has(name));
    return mapConcurrent(names, DISCOVERY_CONCURRENCY, (name) => this.resolveModule(name));
  }

  private async resolveModule(moduleName: string): Promise<void> {
    if (!this.server()) return;
    try {
      this.tree.sync(moduleScope(moduleName), await this.discovery.testsInModule(moduleName));
      this.scannedModules.add(moduleName);
    } catch (e) {
      console.error(`[test] Test discovery failed for module '${moduleName}'`, e);
    }
  }

  async resolveTests(items: readonly TestItem[]): Promise<void> {
    // One file answers for every suite in it.
    const files = new Set<string>();
    for (const item of items) {
      const entry = this.tree.get(item.id);
      if (entry?.origin === 'discovered' && this.tree.testsUnknown(item)) files.add(entry.dto.uri);
    }
    await mapConcurrent([...files], DISCOVERY_CONCURRENCY, (uri) => this.syncFile(Uri.parse(uri)));
  }

  private async syncFile(uri: Uri): Promise<void> {
    if (!this.server()) return;
    try {
      this.tree.sync(fileScope(uri.toString()), await this.discovery.testsInFile(uri));
    } catch (e) {
      console.error(`[test] Test discovery failed for ${uri}`, e);
    }
  }

  forgetFile(uri: Uri): void {
    this.tree.sync(fileScope(uri.toString()), []);
  }

  refreshWorkspace(): Promise<void> {
    return (this.workspacePass = this.workspacePass
      .catch(() => {})
      .then(() => this.discoverWorkspace()));
  }

  private async discoverWorkspace(): Promise<void> {
    const server = this.server();
    if (!server) return;
    // A pass during an import sees a workspace without modules, and would prune the tree down to it.
    // The pass that matters runs on the import's own "finished" notification.
    if (await importInProgress(server)) {
      console.log('[test] workspace discovery skipped: the workspace import is still running');
      return;
    }
    const startedAt = Date.now();
    let modules: string[];
    try {
      modules = await this.discovery.modules();
    } catch (e) {
      console.error('[test] Failed to list modules for test discovery', e);
      return;
    }

    this.scannedModules.clear();
    // A module that left the workspace keeps neither its tests nor its node.
    const present = new Set(modules);
    for (const moduleName of this.tree.knownModules()) {
      if (!present.has(moduleName)) this.tree.sync(moduleScope(moduleName), []);
    }
    this.tree.syncModules(modules);
    console.log(
      `[test] workspace discovery: ${modules.length} module(s) in ${Date.now() - startedAt}ms`,
    );
  }
}

/** A server that cannot say counts as "not importing": a broken answer must not stop discovery for good. */
async function importInProgress(server: DiscoveryServer): Promise<boolean> {
  try {
    return await server.importInProgress();
  } catch (e) {
    console.error('[test] Failed to ask whether the workspace import is running', e);
    return false;
  }
}

async function mapConcurrent<T>(
  items: readonly T[],
  limit: number,
  fn: (item: T) => Promise<void>,
): Promise<void> {
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < items.length) await fn(items[next++]);
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
}
