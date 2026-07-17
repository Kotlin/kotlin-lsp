import * as vscode from 'vscode';
import { LanguageClient, NotificationType } from 'vscode-languageclient/node';
import { getContext } from './extension';
import { registerInitializationOptionsContributor } from './lspClient';

type CopyToClipboardParams = { content: string };

const copyToClipboardNotification = new NotificationType<CopyToClipboardParams>(
  'intellij/copyToClipboard',
);

type ChooseActionMenuEntry = { index: number; name: string };
type ShowChooseActionMenuParams = {
  sessionId: number;
  title: string;
  entries: ChooseActionMenuEntry[];
};

const chooseActionMenuNotification = new NotificationType<ShowChooseActionMenuParams>(
  'intellij/chooseAction',
);

interface ChooseActionMenuItem extends vscode.QuickPickItem {
  index: number;
}

/**
 * Declares this as a JetBrains client so the server may use custom `intellij/*` protocol
 * extensions (e.g. the `intellij/copyToClipboard` notification handled in this file).
 */
export function registerIntellijExtensionsInitOption(): void {
  registerInitializationOptionsContributor(() => ({ intellijExtensions: true }));
}

/**
 * Handles the `intellij/copyToClipboard` server notification (used by the ModCommand
 * `ModCopyToClipboard`), writing the supplied text to the system clipboard.
 */
export function registerCopyToClipboardHandler(client: LanguageClient): void {
  const subscription = client.onNotification(
    copyToClipboardNotification,
    (p) => void vscode.env.clipboard.writeText(p.content),
  );
  getContext().subscriptions.push(subscription);
}

/**
 * Handles the `intellij/chooseAction` server notification (used by the ModCommand `ModChooseAction`),
 * showing a QuickPick menu of the offered actions. When the user picks one, the chosen action is executed
 * back on the server via the `chooseModCommandAction` command; that action may itself yield another
 * `ModChooseAction`, in which case the server sends a follow-up notification and another menu is shown.
 */
export function registerChooseActionMenuHandler(client: LanguageClient): void {
  const subscription = client.onNotification(chooseActionMenuNotification, (params) => {
    void showChooseActionMenu(client, params);
  });
  getContext().subscriptions.push(subscription);
}

async function showChooseActionMenu(
  client: LanguageClient,
  params: ShowChooseActionMenuParams,
): Promise<void> {
  const items: ChooseActionMenuItem[] = params.entries.map((entry) => ({
    label: entry.name,
    index: entry.index,
  }));
  const picked = await vscode.window.showQuickPick(items, { placeHolder: params.title });
  if (picked) {
    await client.sendRequest('workspace/executeCommand', {
      command: 'chooseModCommandAction',
      arguments: [params.sessionId, picked.index],
    });
  }
}
