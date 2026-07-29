import { commands, window } from 'vscode';
import { logInfo } from './extension';

const RELOAD_WINDOW_COMMAND = 'workbench.action.reloadWindow';
const RELOAD_WINDOW_ACTION = 'Reload Window';

export async function reloadWindow(): Promise<void> {
  logInfo('Reloading window');
  await commands.executeCommand(RELOAD_WINDOW_COMMAND);
}

export interface PromptReloadWindowOptions {
  message: string;
  modal?: boolean;
  /** Rendered by modal dialogs only; VS Code ignores it for notifications. */
  detail?: string;
  dismissedMessage?: string;
}

export async function promptReloadWindow({
  message,
  modal = true,
  detail,
  dismissedMessage,
}: PromptReloadWindowOptions): Promise<boolean> {
  let selectedAction = await window.showInformationMessage(
    message,
    {
      modal,
      detail,
    },
    RELOAD_WINDOW_ACTION,
  );

  if (selectedAction !== RELOAD_WINDOW_ACTION && dismissedMessage !== undefined) {
    selectedAction = await window.showInformationMessage(dismissedMessage, RELOAD_WINDOW_ACTION);
  }

  if (selectedAction !== RELOAD_WINDOW_ACTION) return false;

  await reloadWindow();
  return true;
}
