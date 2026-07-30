import { commands, type Extension, type ExtensionContext, extensions, window } from 'vscode';
import { logInfo } from './extension';
import { reloadWindow } from './reloadWindow';

const UNINSTALL_EXTENSION_COMMAND = 'workbench.extensions.uninstallExtension';

const UNINSTALL_AND_RELOAD_ACTION = 'Uninstall and Reload Window';

function displayNameOf(extension: Extension<unknown>): string | undefined {
  const displayName: unknown = extension.packageJSON.displayName;
  return typeof displayName === 'string' && displayName.trim() !== '' ? displayName : undefined;
}

function describeExtension(extension: Extension<unknown>): string {
  const displayName = displayNameOf(extension);
  return displayName === undefined ? `"${extension.id}"` : `"${displayName}" (${extension.id})`;
}

/**
 * Falls back to the id when both sides share a display name — `jetbrains.kotlin` and
 * `jetbrains.kotlin-server` both present themselves as "Kotlin by JetBrains".
 */
function describeProduct(product: Extension<unknown>, conflicting: Extension<unknown>): string {
  const displayName = displayNameOf(product);
  if (displayName === undefined) return `"${product.id}"`;
  if (displayName !== displayNameOf(conflicting)) return `"${displayName}"`;
  return `"${displayName}" (${product.id})`;
}

interface ConflictNames {
  product: string;
  conflicting: string;
}

interface ConflictScenario {
  warningTitle: (names: ConflictNames) => string;
  uninstallDetail: (names: ConflictNames) => string;
  declineWarning: (names: ConflictNames) => string;
}

const activationBlocked: ConflictScenario = {
  warningTitle: ({ product }) => `An extension incompatible with ${product} is installed.`,
  uninstallDetail: ({ product, conflicting }) =>
    `Uninstall the conflicting extension ${conflicting} and reload the window to activate ${product}.`,
  declineWarning: ({ product, conflicting }) =>
    `${product} cannot complete activation while ${conflicting} is installed.`,
};

const alreadyActive: ConflictScenario = {
  warningTitle: ({ product }) => `You are installing an extension that conflicts with ${product}.`,
  uninstallDetail: ({ product, conflicting }) =>
    `${conflicting} has been installed but is not active yet. Uninstall it and reload the window to keep ${product} working correctly.`,
  declineWarning: ({ product, conflicting }) =>
    `${conflicting} is not active yet, and will stop ${product} from working correctly once it starts.`,
};

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
 */
export function registerExtensionConflictHandler(
  options: ExtensionConflictOptions,
): Promise<boolean> {
  const { context, conflictingExtensionIds } = options;
  const conflictingIds = new Set(conflictingExtensionIds.map((id) => id.toLowerCase()));
  conflictingIds.delete(context.extension.id.toLowerCase());

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
      const names: ConflictNames = {
        product: describeProduct(context.extension, conflictingExtension),
        conflicting: describeExtension(conflictingExtension),
      };

      const selectedAction = await window.showWarningMessage(
        scenario.warningTitle(names),
        {
          modal: true,
          detail: scenario.uninstallDetail(names),
        },
        UNINSTALL_AND_RELOAD_ACTION,
      );

      if (selectedAction !== UNINSTALL_AND_RELOAD_ACTION) {
        logInfo('User dismissed the conflicting extension warning');
        const reconsidered = await window.showWarningMessage(
          scenario.declineWarning(names),
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
