import * as vscode from 'vscode';
import { LanguageClient, NotificationType } from 'vscode-languageclient/node';
import { getContext } from './extension';
import { registerInitializationOptionsContributor } from './lspClient';

type CopyToClipboardParams = { content: string };

const copyToClipboardNotification = new NotificationType<CopyToClipboardParams>(
  'intellij/copyToClipboard',
);

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
