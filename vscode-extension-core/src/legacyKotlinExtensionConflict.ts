import { commands, type ExtensionContext, extensions, window } from 'vscode';
import { logInfo } from './extension';
import { promptReloadWindow } from './reloadWindow';
import { registerExtensionConflictHandler } from './extensionConflict';

const KOTLIN_SERVER_EXTENSION_ID = 'jetbrains.kotlin-server';
const LEGACY_KOTLIN_EXTENSION_ID = 'jetbrains.kotlin';

const UNINSTALL_EXTENSION_COMMAND = 'workbench.extensions.uninstallExtension';

const UNINSTALL_OUTDATED_EXTENSION_ACTION = 'Uninstall outdated extension';

export function registerKotlinExtensionConflictHandler(
  context: ExtensionContext,
): Promise<boolean> {
  return registerExtensionConflictHandler({
    context,
    conflictingExtensionIds: [KOTLIN_SERVER_EXTENSION_ID, LEGACY_KOTLIN_EXTENSION_ID],
  });
}

/**
 * Checks whether Kotlin Server should be blocked because the legacy Kotlin extension is installed.
 *
 * Return `true` iff there is a legacy Kotlin extension is detected, and the initialization
 * should be stopped to avoid confusion.
 *
 * Side effect: when a conflict is detected, a modal warning dialog is shown. If the user chooses
 * the uninstall action, the legacy extension is uninstalled and a reload prompt is shown.
 *
 * The UI flow is handled asynchronously; callers must not continue activation when this function returns `true`.
 *
 * See `registerExtensionConflictHandler` for the parameterized variant; keep the two in sync.
 */
export function checkLegacyKotlinExtensionConflict(context: ExtensionContext): boolean {
  const currentExtensionId = context.extension.id.toLowerCase();

  // sanity check to prevent this code from running on legacy versions of extension
  if (currentExtensionId !== KOTLIN_SERVER_EXTENSION_ID) {
    return false;
  }

  const legacyExtension = extensions.getExtension(LEGACY_KOTLIN_EXTENSION_ID);

  if (!legacyExtension) {
    // no legacy extension found, hence no conflict
    return false;
  }

  logInfo(
    `Detected conflicting extension '${LEGACY_KOTLIN_EXTENSION_ID}' while activating '${KOTLIN_SERVER_EXTENSION_ID}'`,
  );

  // we do not wait until user decides what to do; we return from the function right away
  // to signal that the legacy extension conflict is detected
  void handleLegacyKotlinExtensionConflict();

  return true;
}

/**
 * Important note: we propose to reload the whole window instead of just restarting the extension host.
 * This is because the "removed" extension would still be present in the installed extensions,
 * and there is no reliable way to detect that the extension has been uninstalled.
 *
 * Reloading the window works much more reliably in this regard.
 */
async function handleLegacyKotlinExtensionConflict(): Promise<void> {
  try {
    const selectedAction = await window.showWarningMessage(
      'Conflicting "Kotlin by JetBrains" extensions detected.',
      {
        modal: true,
        detail: `Uninstall the outdated extension "${LEGACY_KOTLIN_EXTENSION_ID}" to avoid conflicts with the new extension "${KOTLIN_SERVER_EXTENSION_ID}".`,
      },
      UNINSTALL_OUTDATED_EXTENSION_ACTION,
    );

    if (selectedAction !== UNINSTALL_OUTDATED_EXTENSION_ACTION) {
      logInfo(
        `User dismissed conflicting extension warning for '${LEGACY_KOTLIN_EXTENSION_ID}' while activating '${KOTLIN_SERVER_EXTENSION_ID}'`,
      );
      await window.showWarningMessage(
        `"Kotlin by JetBrains" cannot complete activation while the outdated extension "${LEGACY_KOTLIN_EXTENSION_ID}" is still installed.`,
      );
      return;
    }

    await commands.executeCommand(UNINSTALL_EXTENSION_COMMAND, LEGACY_KOTLIN_EXTENSION_ID);
    logInfo(
      `Uninstalled conflicting extension '${LEGACY_KOTLIN_EXTENSION_ID}' while activating '${KOTLIN_SERVER_EXTENSION_ID}'`,
    );

    const reloaded = await promptReloadWindow({
      message: 'The outdated "Kotlin by JetBrains" extension was uninstalled.',
      detail: `Reload the window to finish switching to the new extension "${KOTLIN_SERVER_EXTENSION_ID}".`,
      dismissedMessage: 'Reload the window to complete activation of "Kotlin by JetBrains".',
    });

    if (!reloaded) {
      logInfo(
        `User dismissed reload prompt after uninstalling '${LEGACY_KOTLIN_EXTENSION_ID}' for '${KOTLIN_SERVER_EXTENSION_ID}'`,
      );
    }
  } catch (error) {
    logInfo(
      `Handling conflicting extension '${LEGACY_KOTLIN_EXTENSION_ID}' for '${KOTLIN_SERVER_EXTENSION_ID}' failed with error: ${error}`,
    );
  }
}
