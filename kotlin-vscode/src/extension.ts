import { type ExtensionContext } from 'vscode';
import {
  activateExtension,
  deactivateExtension,
  initializeExtension,
  isExternalServerConfigured,
  prepareBundledServerLauncher,
  stopLspClient,
} from '@jetbrains/vscode-extension-core';
import kotlinModule from '@jetbrains/vscode-language-kotlin';
import {
  checkBundledServerEulaAccepted,
  runPolicyGatedActivation,
} from '@jetbrains/intellij-vscode-extension-policy';
import { checkGeoRestricted } from './geoRestriction';

export async function activate(context: ExtensionContext): Promise<void> {
  await initializeExtension(context);
  if (await checkGeoRestricted(context.extension)) return;

  await runPolicyGatedActivation(context, {
    stopServer: stopLspClient,
    usesExternalServer: isExternalServerConfigured(),
    startServer: (options) =>
      activateExtension(context, {
        checkEulaAccepted: (ctx) =>
          checkBundledServerEulaAccepted({
            context: ctx,
            prepareLauncher: prepareBundledServerLauncher,
            options,
          }),
        enableDapServer: true,
        enableDecompiler: true,
        modules: [kotlinModule],
      }),
  });
}

export const deactivate = deactivateExtension;
