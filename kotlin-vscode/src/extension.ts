import { type ExtensionContext } from 'vscode';
import {
  activateExtension,
  checkGeoRestricted,
  deactivateExtension,
  initializeExtension,
  isExternalServerConfigured,
  prepareBundledServerLauncher,
  registerKotlinExtensionConflictHandler,
  registerStatusBarContribution,
  stopLspClient,
  withLspClientStartPending,
} from '@jetbrains/vscode-extension-core';
import kotlinModule from '@jetbrains/vscode-language-kotlin';
import {
  checkBundledServerEulaAccepted,
  getExtensionStatusBarTitle,
  runPolicyGatedActivation,
} from '@jetbrains/intellij-vscode-extension-policy';

export async function activate(context: ExtensionContext): Promise<void> {
  const geoRestricted = await checkGeoRestricted(context.extension);
  if (geoRestricted) return;

  const conflictFound = await registerKotlinExtensionConflictHandler(context);
  if (conflictFound) return;

  await initializeExtension(context);

  await withLspClientStartPending(() =>
    runPolicyGatedActivation(context, {
      registerStatusBarContribution,
      statusBarTitle: getExtensionStatusBarTitle(context),
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
    }),
  );
}

export const deactivate = deactivateExtension;
