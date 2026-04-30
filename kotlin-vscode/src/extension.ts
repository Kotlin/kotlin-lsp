import { type ExtensionContext } from 'vscode';
import { activateExtension } from '@jetbrains/vscode-extension-core';
import kotlinModule from '@jetbrains/vscode-language-kotlin';
import { checkGeoRestricted } from './geoRestriction';
import { registerRunMainCodeLens } from './runMainCodeLens';

export async function activate(context: ExtensionContext): Promise<void> {
  await activateExtension(context, {
    checkGeoRestricted,
    enableDapServer: true,
    enableDecompiler: true,
    modules: [kotlinModule, registerRunMainCodeLens],
  });
}
