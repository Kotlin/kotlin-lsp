import { randomBytes } from 'node:crypto';
import * as vscode from 'vscode';
import {
  type CancellationToken,
  LanguageClient,
  RequestType,
  State,
} from 'vscode-languageclient/node';
import {
  type EditOptionsParams,
  type EditOptionsResult,
  type OptionValue,
  parseEditOptionsMessage,
  renderEditOptionsHtml,
} from './editOptionsModel';
import { getContext } from './extension';
import { subscribeToClientEvent } from './lspClient';

const editOptionsRequest = new RequestType<EditOptionsParams, EditOptionsResult, void>(
  'intellij/editOptions',
);

/**
 * Handles the `intellij/editOptions` server request (used by the ModCommand `ModEditOptions`), which asks the user to
 * edit the options of a form. The server computes the next command from the answer, so a cancelled form changes
 * nothing.
 *
 * The answer must always be a result. A closed panel answers a cancel, and so does a stopped client. When a value is
 * not valid, the server sends the request again with the values of the user and the messages.
 */
export function registerEditOptionsHandler(client: LanguageClient): void {
  const subscription = client.onRequest(editOptionsRequest, (params, token) =>
    showEditOptions(params, token),
  );
  getContext().subscriptions.push(subscription);
}

/**
 * Shows the form in a webview panel beside the editor, and answers the values that the user submits. When the form
 * closes, the editor that was active before the form gets the focus again. The answer goes to the server after that,
 * so the next command of the server does not compete with the focus change.
 */
function showEditOptions(
  params: EditOptionsParams,
  token: CancellationToken,
): Promise<EditOptionsResult> {
  const editor = vscode.window.activeTextEditor;
  return new Promise((resolve) => {
    const panel = vscode.window.createWebviewPanel(
      'intellij.editOptions',
      params.title,
      { viewColumn: vscode.ViewColumn.Beside, preserveFocus: false },
      { enableScripts: true, localResourceRoots: [], retainContextWhenHidden: true },
    );
    let answered = false;
    const disposables: vscode.Disposable[] = [];

    const finish = (values: Record<string, OptionValue> | undefined) => {
      if (answered) return;

      answered = true;
      for (const disposable of disposables) disposable.dispose();
      panel.dispose();
      const result = values ? { values } : {};
      void focusEditor(editor).finally(() => resolve(result));
    };

    disposables.push(
      token.onCancellationRequested(() => finish(undefined)),
      subscribeToClientEvent((_, stateChange) => {
        if (stateChange.newState !== State.Running) finish(undefined);
      }),
      panel.webview.onDidReceiveMessage((message: unknown) => {
        const parsed = parseEditOptionsMessage(message);
        if (!parsed) return;
        finish(parsed.type === 'submit' ? parsed.values : undefined);
      }),
      panel.onDidDispose(() => finish(undefined)),
    );

    panel.webview.html = renderEditOptionsHtml(params, {
      nonce: randomBytes(16).toString('base64'),
    });
  });
}

/** Gives the focus to [editor] again, with its selection. A closed document gets no focus. */
async function focusEditor(editor: vscode.TextEditor | undefined): Promise<void> {
  if (!editor || editor.document.isClosed) return;

  try {
    await vscode.window.showTextDocument(editor.document, {
      viewColumn: editor.viewColumn,
      selection: editor.selection,
      preserveFocus: false,
    });
  } catch {
    // The focus change is a convenience. A failure must not keep the answer from the server.
  }
}
