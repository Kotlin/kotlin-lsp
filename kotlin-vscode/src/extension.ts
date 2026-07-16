import { type ExtensionContext } from 'vscode';
import {
  activateExtension,
  deactivateExtension,
  isExternalServerConfigured,
  stopLspClient,
} from '@jetbrains/vscode-extension-core';
import kotlinModule from '@jetbrains/vscode-language-kotlin';
import {
  checkEulaAccepted,
  runPolicyGatedActivation,
} from '@jetbrains/intellij-vscode-extension-policy';
import { checkGeoRestricted } from './geoRestriction';

export async function activate(context: ExtensionContext): Promise<void> {
  if (await checkGeoRestricted(context.extension)) return;

  await runPolicyGatedActivation(context, {
    stopServer: stopLspClient,
    usesExternalServer: isExternalServerConfigured(),
    startServer: (options) =>
      activateExtension(context, {
        checkEulaAccepted: (ctx) => checkEulaAccepted(ctx, undefined, options),
        enableDapServer: true,
        enableDecompiler: true,
        modules: [kotlinModule],
      }),
  });
}

export const deactivate = deactivateExtension;
