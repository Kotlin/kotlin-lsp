import * as vscode from 'vscode';
import { Command, LanguageClient, NotificationType } from 'vscode-languageclient/node';
import { getContext } from './extension';
import { registerInitializationOptionsContributor } from './lspClient';

type CopyToClipboardParams = { content: string };

const copyToClipboardNotification = new NotificationType<CopyToClipboardParams>(
  'intellij/copyToClipboard',
);

type RunEditorCommandParams = { command: string; arguments?: unknown[] };

const runEditorCommandNotification = new NotificationType<RunEditorCommandParams>(
  'intellij/runEditorCommand',
);

type ChooseActionMenuEntry = { name: string; command: Command };
type ShowChooseActionMenuParams = {
  title: string;
  entries: ChooseActionMenuEntry[];
};

const chooseActionMenuNotification = new NotificationType<ShowChooseActionMenuParams>(
  'intellij/chooseAction',
);

interface ChooseActionMenuItem extends vscode.QuickPickItem {
  command: Command;
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
 * Handles the `intellij/runEditorCommand` server notification, running the requested editor command as if the
 * user had invoked it. Some ModCommands drive the editor UI instead of changing the document — starting an
 * inline rename (`editor.action.rename`) — and LSP cannot express that: a server can neither send
 * `workspace/executeCommand` to a client nor start such a session itself. Live templates need no such command:
 * they travel as a `SnippetTextEdit`, which is standard LSP.
 */
export function registerRunEditorCommandHandler(client: LanguageClient): void {
  const subscription = client.onNotification(runEditorCommandNotification, (p) => {
    void Promise.resolve(vscode.commands.executeCommand(p.command, ...(p.arguments ?? []))).then(
      undefined,
      () => {},
    );
  });
  getContext().subscriptions.push(subscription);
}

/**
 * Handles the `intellij/chooseAction` server notification (used by the ModCommand `ModChooseAction`),
 * showing a QuickPick menu of the offered actions. When the user picks one, the server runs the chosen action
 * through the command the entry carries; that action may itself yield another `ModChooseAction`, in which case
 * the server sends a follow-up notification and another menu is shown.
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
    command: entry.command,
  }));
  const picked = await vscode.window.showQuickPick(items, { placeHolder: params.title });
  if (picked) {
    await client.sendRequest('workspace/executeCommand', {
      command: picked.command.command,
      arguments: picked.command.arguments,
    });
  }
}
