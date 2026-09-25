// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { getLspClient, sendLspCommand } from '@jetbrains/vscode-extension-core';
import type { TestDiscovery } from './testLanguage';
import type { TestDiscoveryCommands, TestItemDto } from './testProtocol';

export interface LspDiscoveryOptions {
  readonly commands: TestDiscoveryCommands;
  readonly languageIds: ReadonlySet<string>;
  readonly sourceGlob: string;
}

export function lspDiscovery({
  commands,
  languageIds,
  sourceGlob,
}: LspDiscoveryOptions): TestDiscovery {
  return {
    languageIds,
    sourceGlob,
    supportedBy: (capabilities) =>
      capabilities.executeCommandProvider?.commands.includes(commands.discoverTestsInFile) ?? false,
    testsInFile: (uri) =>
      sendLspCommand<TestItemDto[]>(runningClient(), commands.discoverTestsInFile, [
        { uri: uri.toString() },
      ]),
    modules: () => sendLspCommand<string[]>(runningClient(), commands.discoverTestModules, []),
    testsInModule: (moduleName) =>
      sendLspCommand<TestItemDto[]>(runningClient(), commands.discoverTestsInModule, [moduleName]),
  };
}

function runningClient() {
  const client = getLspClient();
  if (!client) throw new Error('The language server is not running');
  return client;
}
