import {commands, type ExtensionContext, extensions, window} from 'vscode';
import {logInfo} from './extension';

const KOTLIN_SERVER_EXTENSION_ID = 'jetbrains.kotlin-server';
const LEGACY_KOTLIN_EXTENSION_ID = 'jetbrains.kotlin';

const UNINSTALL_EXTENSION_COMMAND = 'workbench.extensions.uninstallExtension';
const RELOAD_WINDOW_COMMAND = 'workbench.action.reloadWindow';

const UNINSTALL_EXTENSION_ACTION_PREFIX = 'Uninstall';
const RELOAD_WINDOW_ACTION = 'Reload Window';

/**
 * Checks whether Kotlin Server should be blocked because the legacy Kotlin extension is installed.
 *
 * Return `true` iff there is a legacy Kotlin extension is detected, and the initialization
 * should be stopped to avoid confusion.
 *
 * Side effect: when a conflict is detected, a warning notification is shown. If the user chooses
 * the uninstall action, the legacy extension is uninstalled and a reload prompt is shown.
 *
 * The UI flow is handled asynchronously; callers must not continue activation when this function returns `true`.
 */
export function checkLegacyKotlinExtensionConflict(context: ExtensionContext): boolean {
    const currentExtensionId = context.extension.id.toLowerCase();

    // sanity check to prevent this code from running on legacy versions of extension
    if (currentExtensionId !== KOTLIN_SERVER_EXTENSION_ID) {
        return false;
    }

    const legacyExtension = extensions.getExtension(LEGACY_KOTLIN_EXTENSION_ID);

    if (!legacyExtension) {
        return false;
    }

    logInfo(`Detected conflicting extension '${legacyExtension.id}' while activating '${currentExtensionId}'`);

    // we do not wait until user decides what to do; we return from the function right away
    // to signal that the legacy extension conflict is detected
    void handleLegacyKotlinExtensionConflict(currentExtensionId, legacyExtension.id);

    return true;
}

/**
 * Important note: we propose to reload the whole window instead of just restarting the extension host.
 * This is because the "removed" extension would still be present in the installed extensions,
 * and there is no reliable way to detect that the extension has been uninstalled.
 *
 * Reloading the window works much more reliably in this regard.
 */
async function handleLegacyKotlinExtensionConflict(currentExtensionId: string, legacyExtensionId: string): Promise<void> {
    try {
        const uninstallExtensionAction = `${UNINSTALL_EXTENSION_ACTION_PREFIX} ${legacyExtensionId}`;
        const selectedAction = await window.showWarningMessage(
                `Extension '${legacyExtensionId}' is installed and conflicts with '${currentExtensionId}'. Uninstall '${legacyExtensionId}' to avoid conflicts.`,
                uninstallExtensionAction,
        );

        if (selectedAction !== uninstallExtensionAction) {
            logInfo(`User dismissed conflicting extension warning for '${legacyExtensionId}' while activating '${currentExtensionId}'`);
            return;
        }

        await commands.executeCommand(UNINSTALL_EXTENSION_COMMAND, legacyExtensionId);
        logInfo(`Uninstalled conflicting extension '${legacyExtensionId}' while activating '${currentExtensionId}'`);

        const reloadWindowAction = await window.showInformationMessage(
                `Extension '${legacyExtensionId}' was uninstalled. Reload Window to finish enabling '${currentExtensionId}'.`,
                RELOAD_WINDOW_ACTION,
        );

        if (reloadWindowAction === RELOAD_WINDOW_ACTION) {
            logInfo(`Reloading window after uninstalling '${legacyExtensionId}' for '${currentExtensionId}'`);
            await commands.executeCommand(RELOAD_WINDOW_COMMAND);
        }
    } catch (error) {
        logInfo(`Handling conflicting extension '${legacyExtensionId}' for '${currentExtensionId}' failed with error: ${error}`);
    }
}
