import { type ExtensionContext } from 'vscode';
import { activateExtension } from '@jetbrains/vscode-extension-core';
import kotlinModule from '@jetbrains/vscode-language-kotlin';
import { checkGeoRestricted } from './geoRestriction';

export async function activate(context: ExtensionContext): Promise<void> {
  await activateExtension(context, {
    checkGeoRestricted,
    enableDapServer: true,
    enableDecompiler: true,
    modules: [kotlinModule],
  });
}
