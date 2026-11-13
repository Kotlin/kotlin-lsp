import type { ClientCapabilities, StaticFeature } from 'vscode-languageclient/node';

export function createIntellijExtensionsClientFeature(): StaticFeature {
  return {
    fillClientCapabilities(capabilities: ClientCapabilities): void {
      const experimental = capabilities.experimental as Record<string, unknown> | undefined;
      capabilities.experimental = {
        ...experimental,
        conflictView: true,
        actionChooser: true,
        copyToClipboard: true,
        runEditorCommand: true,
        editOptions: true,
      };
    },
    initialize(): void {},
    getState: () => ({ kind: 'static' }),
    clear(): void {},
  };
}
