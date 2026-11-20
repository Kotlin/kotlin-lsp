import { type ExtensionContext } from 'vscode';
import {
  activateExtension,
  checkGeoRestricted,
  deactivateExtension,
  type ExtensionTestApi,
  extensionTestApi,
  getOutputChannel,
  initializeExtension,
  isExternalServerConfigured,
  prepareBundledServerLauncher,
  registerKotlinExtensionConflictHandler,
  registerStatusBarContribution,
  stopLspClient,
  withLspClientStartPending,
} from '@jetbrains/vscode-extension-core';
import jvmTestingModule from '@jetbrains/vscode-jvm-testing';
import kotlinModule from '@jetbrains/vscode-language-kotlin';
import {
  checkBundledServerEulaAccepted,
  getExtensionStatusBarTitle,
  runPolicyGatedActivation,
} from '@jetbrains/intellij-vscode-extension-policy';

/** Returns the test API in the test mode, and `undefined` in all other modes. */
export async function activate(context: ExtensionContext): Promise<ExtensionTestApi | undefined> {
  const geoRestricted = await checkGeoRestricted(context.extension);
  if (geoRestricted) return undefined;

  const conflictFound = await registerKotlinExtensionConflictHandler(context);
  if (conflictFound) return undefined;

  await initializeExtension(context);

  await withLspClientStartPending(() =>
    runPolicyGatedActivation(context, {
      registerStatusBarContribution,
      statusBarTitle: getExtensionStatusBarTitle(context),
      showOutput: () => getOutputChannel().show(false),
      stopServer: stopLspClient,
      usesExternalServer: isExternalServerConfigured(),
      startServer: (serverOptions) =>
        activateExtension(context, {
          checkEulaAccepted: (ctx) =>
            checkBundledServerEulaAccepted({
              context: ctx,
              prepareLauncher: prepareBundledServerLauncher,
              options: serverOptions,
            }),
          enableDapServer: true,
          enableDecompiler: true,
          modules: [kotlinModule, jvmTestingModule],
          onServerRestartStateChanged: serverOptions?.onServerRestartStateChanged,
        }),
    }),
  );
  return extensionTestApi(context);
}

export const deactivate = deactivateExtension;
