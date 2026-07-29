import { commands, type Extension, type ExtensionContext, extensions, window } from 'vscode';
import { logInfo } from './extension';
import { reloadWindow } from './reloadWindow';

const UNINSTALL_EXTENSION_COMMAND = 'workbench.extensions.uninstallExtension';

const UNINSTALL_AND_RELOAD_ACTION = 'Uninstall and Reload Window';

function displayNameOf(extension: Extension<unknown>): string | undefined {
  const displayName: unknown = extension.packageJSON.displayName;
  return typeof displayName === 'string' && displayName.trim() !== '' ? displayName : undefined;
}

/**
 * The id is kept alongside the display name because conflicting extensions may share one — both
 * `jetbrains.kotlin` and `jetbrains.kotlin-server` present themselves as "Kotlin by JetBrains".
 */
function describeExtension(extension: Extension<unknown>): string {
  const displayName = displayNameOf(extension);
  return displayName === undefined ? `"${extension.id}"` : `"${displayName}" (${extension.id})`;
}

interface ConflictScenario {
  warningTitle: string;
  uninstallDetail: (conflictingExtension: string) => string;
  declineWarning: (conflictingExtension: string) => string;
}

export interface ExtensionConflictOptions {
  context: ExtensionContext;
  conflictingExtensionIds: readonly string[];
}

/**
 * Blocks activation while an incompatible extension is installed, and keeps watching for one being
 * installed later. Resolves to `true` when a conflict was found, in which case the caller must not
 * continue activating.
 *
 * We ask the user to reload the window instead of restarting activation ourselves: an uninstalled
 * extension stays in `extensions.all` until the window reloads, so there is no reliable way to
 * observe that the uninstall finished.
 *
 * See `checkLegacyKotlinExtensionConflict` for the Kotlin Server variant; keep the two in sync.
 */
export function registerExtensionConflictHandler(
  options: ExtensionConflictOptions,
): Promise<boolean> {
  const { context, conflictingExtensionIds } = options;
  const conflictingIds = new Set(conflictingExtensionIds.map((id) => id.toLowerCase()));
  conflictingIds.delete(context.extension.id.toLowerCase());
  const productDisplayName = displayNameOf(context.extension) ?? context.extension.id;

  const activationBlocked: ConflictScenario = {
    warningTitle: `An extension incompatible with "${productDisplayName}" is installed.`,
    uninstallDetail: (extension) =>
      `Uninstall the conflicting extension ${extension} and reload the window to activate "${productDisplayName}".`,
    declineWarning: (extension) =>
      `"${productDisplayName}" cannot complete activation while ${extension} is installed.`,
  };

  const alreadyActive: ConflictScenario = {
    warningTitle: `You are installing an extension that conflicts with "${productDisplayName}".`,
    uninstallDetail: (extension) =>
      `Uninstall the conflicting extension ${extension} and reload the window to keep "${productDisplayName}" working correctly.`,
    declineWarning: (extension) =>
      `${extension} may prevent "${productDisplayName}" from working correctly.`,
  };

  // `prompting` guards re-entry, since `onDidChange` can fire repeatedly while a modal is open.
  // `awaitingReload` is terminal, so the uninstall never runs twice.
  let state: 'idle' | 'prompting' | 'awaitingReload' = 'idle';

  function findConflictingExtension(): Extension<unknown> | undefined {
    return extensions.all.find((extension) => conflictingIds.has(extension.id.toLowerCase()));
  }

  async function uninstall(extension: Extension<unknown>): Promise<void> {
    await commands.executeCommand(UNINSTALL_EXTENSION_COMMAND, extension.id);
    logInfo(`Uninstalled conflicting extension '${extension.id}'`);
  }

  async function handleConflict({ scenario }: { scenario: ConflictScenario }): Promise<boolean> {
    if (state !== 'idle') return true;

    const conflictingExtension = findConflictingExtension();
    if (conflictingExtension === undefined) return false;

    state = 'prompting';
    try {
      logInfo(`Detected conflicting extension '${conflictingExtension.id}'`);
      const description = describeExtension(conflictingExtension);

      const selectedAction = await window.showWarningMessage(
        scenario.warningTitle,
        {
          modal: true,
          detail: scenario.uninstallDetail(description),
        },
        UNINSTALL_AND_RELOAD_ACTION,
      );

      if (selectedAction !== UNINSTALL_AND_RELOAD_ACTION) {
        logInfo('User dismissed the conflicting extension warning');
        const reconsidered = await window.showWarningMessage(
          scenario.declineWarning(description),
          UNINSTALL_AND_RELOAD_ACTION,
        );
        if (reconsidered !== UNINSTALL_AND_RELOAD_ACTION) return true;
      }

      await uninstall(conflictingExtension);

      state = 'awaitingReload';
      await reloadWindow();
      return true;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      logInfo(`Handling the conflicting extension failed with error: ${message}`);
      await window.showErrorMessage(`Failed to uninstall the conflicting extension: ${message}`);
      return true;
    } finally {
      if (state === 'prompting') state = 'idle';
    }
  }

  context.subscriptions.push(
    extensions.onDidChange(() => void handleConflict({ scenario: alreadyActive })),
  );
  return handleConflict({ scenario: activationBlocked });
}
