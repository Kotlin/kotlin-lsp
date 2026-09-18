import type { TestItem, TextDocument, Uri } from 'vscode';
import { getLspClient, sendLspCommand } from '@jetbrains/vscode-extension-core';
import { moduleNameOf } from './jvmTestGrouping';
import { JvmTestCommands, type JvmTestItemDto } from './jvmTestProtocol';
import { fileScope, moduleScope } from './jvmTestScope';
import type { JvmTestTree } from './jvmTestTree';
import { WorkspaceImportStateRequest } from './workspaceImport';

const DISCOVERY_CONCURRENCY = 4;

export interface JvmTestDiscoveryApi {
  testsInFile(uri: string): Promise<JvmTestItemDto[]>;

  testModules(): Promise<string[]>;

  testsInModule(moduleName: string): Promise<JvmTestItemDto[]>;

  /** Whether an import cycle is running, and so the workspace has no modules to report yet. */
  importInProgress(): Promise<boolean>;
}

/** The running language server, or undefined while it is down. */
export function lspDiscoveryApi(): JvmTestDiscoveryApi | undefined {
  const client = getLspClient();
  if (!client) return undefined;
  return {
    testsInFile: (uri) =>
      sendLspCommand<JvmTestItemDto[]>(client, JvmTestCommands.discoverTestsInFile, [{ uri }]),
    testModules: () => sendLspCommand<string[]>(client, JvmTestCommands.discoverTestModules, []),
    testsInModule: (moduleName) =>
      sendLspCommand<JvmTestItemDto[]>(client, JvmTestCommands.discoverTestsInModule, [moduleName]),
    importInProgress: async () =>
      (await client.sendRequest(WorkspaceImportStateRequest, {})).phase === 'IN_PROGRESS',
  };
}

export class JvmTestDiscovery {
  private workspacePass: Promise<void> = Promise.resolve();
  private readonly scannedModules = new Set<string>();

  constructor(
    private readonly tree: JvmTestTree,
    private readonly api: () => JvmTestDiscoveryApi | undefined = lspDiscoveryApi,
  ) {}

  refreshFile(document: TextDocument | Uri): Promise<void> {
    if ('languageId' in document && document.languageId !== 'java') return Promise.resolve();
    return this.syncFile(('languageId' in document ? document.uri : document).toString());
  }

  resolve(item: TestItem): Promise<void> {
    const moduleName = moduleNameOf(item.id);
    if (moduleName !== undefined) return this.resolveModule(moduleName);
    // Only module and class nodes are marked resolvable, and a class always carries a file.
    if (!item.uri) return Promise.resolve();
    return this.refreshFile(item.uri);
  }

  resolveModules(items: readonly TestItem[]): Promise<void> {
    const names = items
      .map((item) => moduleNameOf(item.id))
      .filter((name): name is string => name !== undefined && !this.scannedModules.has(name));
    return mapConcurrent(names, DISCOVERY_CONCURRENCY, (name) => this.resolveModule(name));
  }

  private async resolveModule(moduleName: string): Promise<void> {
    const api = this.api();
    if (!api) return;
    try {
      this.tree.sync(moduleScope(moduleName), await api.testsInModule(moduleName));
      this.scannedModules.add(moduleName);
    } catch (e) {
      console.error(`[jvmTest] Test discovery failed for module '${moduleName}'`, e);
    }
  }

  async resolveMethods(items: readonly TestItem[]): Promise<void> {
    // One file answers for every class in it, and only a class that has no methods yet is worth asking about.
    // A nested class is a child too, so counting children would take a class with one for resolved.
    const files = new Set<string>();
    for (const item of items) {
      const dto = this.tree.get(item.id)?.dto;
      if (dto?.kind !== 'CLASS') continue;
      const resolved = [...item.children].some(([, child]) => {
        // A runtime node came from a run, so the methods of the class are still unknown.
        const entry = this.tree.get(child.id);
        return entry?.dto.kind === 'METHOD' && entry.origin === 'discovered';
      });
      if (!resolved) files.add(dto.uri);
    }
    await mapConcurrent([...files], DISCOVERY_CONCURRENCY, (uri) => this.syncFile(uri));
  }

  private async syncFile(uri: string): Promise<void> {
    const api = this.api();
    if (!api) return;
    try {
      this.tree.sync(fileScope(uri), await api.testsInFile(uri));
    } catch (e) {
      console.error(`[jvmTest] Test discovery failed for ${uri}`, e);
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
    const api = this.api();
    if (!api) return;
    // A pass during an import sees a workspace without modules, and would prune the tree down to it.
    // The pass that matters runs on the import's own "finished" notification.
    if (await importInProgress(api)) {
      console.log('[jvmTest] workspace discovery skipped: the workspace import is still running');
      return;
    }
    const startedAt = Date.now();
    let modules: string[];
    try {
      modules = await api.testModules();
    } catch (e) {
      console.error('[jvmTest] Failed to list modules for test discovery', e);
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
      `[jvmTest] workspace discovery: ${modules.length} module(s) in ${Date.now() - startedAt}ms`,
    );
  }
}

/** A server that cannot say counts as "not importing": a broken answer must not stop discovery for good. */
async function importInProgress(api: JvmTestDiscoveryApi): Promise<boolean> {
  try {
    return await api.importInProgress();
  } catch (e) {
    console.error('[jvmTest] Failed to ask whether the workspace import is running', e);
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
