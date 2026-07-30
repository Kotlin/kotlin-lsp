import {
  commands,
  type Extension,
  type ExtensionContext,
  extensions,
  type MessageItem,
  window,
} from 'vscode';
import { logInfo } from './extension';
import { reloadWindow } from './reloadWindow';

const UNINSTALL_EXTENSION_COMMAND = 'workbench.extensions.uninstallExtension';

const DISMISS_ACTION: MessageItem = { title: 'Dismiss', isCloseAffordance: true };

const TWO_LANGUAGE_SERVERS_WARNING =
  'Running both starts two language servers in one window, duplicating diagnostics, completions, navigation results, and project indexing.';

const RELOAD_NOTE = 'The window reloads once the extension is removed.';

function displayNameOf(extension: Extension<unknown>): string | undefined {
  const displayName: unknown = extension.packageJSON.displayName;
  return typeof displayName === 'string' && displayName.trim() !== '' ? displayName : undefined;
}

/** Adds the id only when both sides share a display name, as the two Kotlin extensions do. */
function describeExtension({
  extension,
  against,
}: {
  extension: Extension<unknown>;
  against: Extension<unknown>;
}): string {
  const displayName = displayNameOf(extension);
  if (displayName === undefined) return `"${extension.id}"`;
  if (displayName !== displayNameOf(against)) return `"${displayName}"`;
  return `"${displayName}" (${extension.id})`;
}

function uninstallAction(extensionName: string): string {
  return `Uninstall ${extensionName}`;
}

interface ConflictNames {
  product: string;
  conflicting: string;
}

interface ConflictScenario {
  warningTitle: (names: ConflictNames) => string;
  uninstallDetail: (names: ConflictNames) => string;
  declineWarning: (names: ConflictNames) => string;
  offersKeepingConflicting?: boolean;
}

const activationBlocked: ConflictScenario = {
  warningTitle: ({ product }) => `An extension incompatible with ${product} is installed.`,
  uninstallDetail: ({ conflicting }) =>
    `${TWO_LANGUAGE_SERVERS_WARNING} The window reloads once ${conflicting} is removed.`,
  declineWarning: ({ product, conflicting }) =>
    `${product} cannot complete activation while ${conflicting} is installed. ${RELOAD_NOTE}`,
};

const alreadyActive: ConflictScenario = {
  warningTitle: ({ product }) => `You are installing an extension that conflicts with ${product}.`,
  uninstallDetail: ({ conflicting }) =>
    `${conflicting} has been installed but is not active yet. ${TWO_LANGUAGE_SERVERS_WARNING} Uninstall the one you do not need. ${RELOAD_NOTE}`,
  declineWarning: ({ conflicting }) =>
    `${conflicting} is not active yet. ${TWO_LANGUAGE_SERVERS_WARNING} ${RELOAD_NOTE}`,
  offersKeepingConflicting: true,
};

interface UninstallChoice {
  label: string;
  extension: Extension<unknown>;
}

export interface ExtensionConflictOptions {
  context: ExtensionContext;
  conflictingExtensionIds: readonly string[];
  /** Never offered as an alternative to keep. */
  deprecatedExtensionIds?: readonly string[];
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
  const { context, conflictingExtensionIds, deprecatedExtensionIds = [] } = options;
  const conflictingIds = new Set(conflictingExtensionIds.map((id) => id.toLowerCase()));
  conflictingIds.delete(context.extension.id.toLowerCase());
  const deprecatedIds = new Set(deprecatedExtensionIds.map((id) => id.toLowerCase()));

  // `prompting` guards re-entry, since `onDidChange` can fire repeatedly while a modal is open.
  // `awaitingReload` is terminal, so the uninstall never runs twice.
  let state: 'idle' | 'prompting' | 'awaitingReload' = 'idle';

  function findConflictingExtension(): Extension<unknown> | undefined {
    return extensions.all.find((extension) => conflictingIds.has(extension.id.toLowerCase()));
  }

  async function uninstall(extension: Extension<unknown>): Promise<void> {
    await commands.executeCommand(UNINSTALL_EXTENSION_COMMAND, extension.id);
    logInfo(`Uninstalled extension '${extension.id}'`);
  }

  async function handleConflict({ scenario }: { scenario: ConflictScenario }): Promise<boolean> {
    if (state !== 'idle') return true;

    const conflictingExtension = findConflictingExtension();
    if (conflictingExtension === undefined) return false;

    state = 'prompting';
    try {
      logInfo(`Detected conflicting extension '${conflictingExtension.id}'`);
      const names: ConflictNames = {
        product: describeExtension({
          extension: context.extension,
          against: conflictingExtension,
        }),
        conflicting: describeExtension({
          extension: conflictingExtension,
          against: context.extension,
        }),
      };

      const choices: UninstallChoice[] = [
        { label: uninstallAction(names.conflicting), extension: conflictingExtension },
      ];
      if (
        scenario.offersKeepingConflicting === true &&
        !deprecatedIds.has(conflictingExtension.id.toLowerCase())
      ) {
        choices.push({ label: uninstallAction(names.product), extension: context.extension });
      }
      const labels = choices.map((choice) => choice.label);
      const chosenFor = (action: string | undefined): UninstallChoice | undefined =>
        choices.find((choice) => choice.label === action);

      // `isCloseAffordance` replaces the "Cancel" button VS Code adds to modal dialogs by default.
      const actions: MessageItem[] = [...labels.map((title) => ({ title })), DISMISS_ACTION];
      const selectedAction = await window.showWarningMessage(
        scenario.warningTitle(names),
        {
          modal: true,
          detail: scenario.uninstallDetail(names),
        },
        ...actions,
      );

      let chosen = chosenFor(selectedAction?.title);

      if (chosen === undefined) {
        logInfo('User dismissed the conflicting extension warning');
        chosen = chosenFor(
          await window.showWarningMessage(scenario.declineWarning(names), ...labels),
        );
      }
      if (chosen === undefined) return true;

      await uninstall(chosen.extension);

      state = 'awaitingReload';
      await reloadWindow();
      return true;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      logInfo(`Handling the conflicting extension failed with error: ${message}`);
      await window.showErrorMessage(`Failed to uninstall the extension: ${message}`);
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
