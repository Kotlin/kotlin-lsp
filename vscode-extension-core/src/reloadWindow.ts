import { commands, window } from 'vscode';
import { logInfo } from './extension';

const RELOAD_WINDOW_COMMAND = 'workbench.action.reloadWindow';
const RELOAD_WINDOW_ACTION = 'Reload Window';

export async function reloadWindow(): Promise<void> {
  logInfo('Reloading window');
  await commands.executeCommand(RELOAD_WINDOW_COMMAND);
}

export async function promptReloadWindow(message: string): Promise<void> {
  const selectedAction = await window.showInformationMessage(message, RELOAD_WINDOW_ACTION);
  if (selectedAction === RELOAD_WINDOW_ACTION) {
    await reloadWindow();
  }
}
