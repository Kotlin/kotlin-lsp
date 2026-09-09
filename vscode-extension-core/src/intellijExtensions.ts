import * as vscode from 'vscode';
import {
  Command,
  LanguageClient,
  type Location,
  NotificationType,
  RequestType,
} from 'vscode-languageclient/node';
import { getContext } from './extension';
import { registerInitializationOptionsContributor } from './lspClient';
import { changeInvalidatesConflicts } from './showConflictsModel';

type CopyToClipboardParams = { content: string };

const copyToClipboardNotification = new NotificationType<CopyToClipboardParams>(
  'intellij/copyToClipboard',
);

// `uri` names the document the command targets; a client that verifies its selection can drop a mismatched command.
type RunEditorCommandParams = { command: string; arguments?: unknown[]; uri?: string };

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

// `location` is absent when the conflicting code has no document of its own, such as a library element.
type Conflict = { messages: string[]; location?: Location | null };
type ShowConflictsParams = {
  title: string;
  conflicts: Conflict[];
  continueLabel: string;
  cancelLabel: string;
  revealLabel: string;
  documentChangedLabel: string;
};
type ShowConflictsDecision = 'continue' | 'cancel';
type ShowConflictsResult = { decision: ShowConflictsDecision };

const showConflictsRequest = new RequestType<ShowConflictsParams, ShowConflictsResult, void>(
  'intellij/showConflicts',
);

interface ConflictItem extends vscode.QuickPickItem {
  // Set on the two decision items, and absent on a conflict item.
  decision?: ShowConflictsDecision;
  location?: Location | null;
}

function revealConflictButton(tooltip: string): vscode.QuickInputButton {
  return { iconPath: new vscode.ThemeIcon('go-to-file'), tooltip };
}

/**
 * Declares this as a JetBrains client so the server may use custom `intellij/*` protocol
 * extensions (e.g. the `intellij/copyToClipboard` notification handled in this file).
 *
 * It also declares `lazyIntentions`, so the server offers a fix without performing it and performs it only
 * when this client runs the command of the fix. That needs no protocol extension, and the server has a separate
 * option for it, but this client supports both.
 */
export function registerIntellijExtensionsInitOption(): void {
  registerInitializationOptionsContributor(() => ({
    intellijExtensions: true,
    lazyIntentions: true,
  }));
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

/**
 * Handles the `intellij/showConflicts` server request (used by the ModCommand `ModShowConflicts`), which asks
 * the user to confirm a fix that reports problems. The server holds the edits of the fix and applies them only
 * after a `continue` answer, so a rejected fix changes nothing.
 *
 * The answer must always be a decision. A thrown error would reach the server as a request failure, which it
 * treats as a cancel, but that would also log noise, so a closed picker answers `cancel` instead.
 *
 * An edit which lands while the question is open answers `cancel` too, because it makes the edits the server
 * holds stale. See [changeInvalidatesConflicts].
 */
export function registerShowConflictsHandler(client: LanguageClient): void {
  const subscription = client.onRequest(showConflictsRequest, (params) => showConflicts(params));
  getContext().subscriptions.push(subscription);
}

/**
 * One row per conflict. `label` names the place, and `detail` holds the whole message under it.
 *
 * A `QuickPickItem` renders one line of `label` and one line of `detail`, and cuts what does not fit. Only
 * `detail` carries a tooltip which shows its whole text, so the message goes there and needs no breaking. The
 * label answers what the message cannot: which file and line the conflict belongs to.
 */
function conflictRows(params: ShowConflictsParams): ConflictItem[] {
  return params.conflicts.flatMap((conflict) => {
    const message = conflictMessage(conflict);
    if (message === '') return [];

    const location = conflict.location;
    return [
      {
        // Without a location there is nothing to name, so the label falls back to a placeholder. The message
        // stays in `detail` either way, which is the only field with a tooltip, so all of it stays readable.
        label: location ? conflictLocationLabel(location) : '-',
        detail: '- ' + message,
        location,
        buttons: location ? [revealConflictButton(params.revealLabel)] : undefined,
      },
    ];
  });
}

/**
 * The name of the file of [location] and the line in it, as the label of its row.
 *
 * The name alone, not the path: a path can take the whole width and push out the part which identifies the
 * place. Two files of the same name therefore read alike, and the reveal button still opens the right one.
 */
function conflictLocationLabel(location: Location): string {
  // A URI path always separates with a slash, and `Uri.path` is already decoded.
  const uriPath = vscode.Uri.parse(location.uri).path;
  const fileName = uriPath.slice(uriPath.lastIndexOf('/') + 1);
  return `${fileName}:${location.range.start.line + 1}`;
}

/**
 * The whole message of one conflict on one line. The platform can report several messages for one element, and
 * a message comes from HTML, so it can hold a line break of its own. A row renders no line break, so each of
 * them becomes a space.
 */
function conflictMessage(conflict: Conflict): string {
  return conflict.messages
    .flatMap((message) => message.split('\n'))
    .map((line) => line.trim())
    .filter((line) => line !== '')
    .join(' ');
}

/**
 * Shows one row per conflict, so the user can read each of them and open its code, and answers the decision the
 * user picks.
 *
 * An edit of a file cancels instead, because it makes the edits the server holds stale. `ignoreFocusOut` keeps
 * the picker open when the focus goes back to the editor, so an edit can arrive at any time: the user reveals a
 * conflict and types, a formatter runs on a save, or another tool writes the file.
 */
function showConflicts(params: ShowConflictsParams): Promise<ShowConflictsResult> {
  const conflictItems = conflictRows(params);
  const decisionItems: ConflictItem[] = [
    { label: '', kind: vscode.QuickPickItemKind.Separator },
    { label: params.continueLabel, decision: 'continue' },
    { label: params.cancelLabel, decision: 'cancel' },
  ];

  return new Promise((resolve) => {
    const picker = vscode.window.createQuickPick<ConflictItem>();
    let answered = false;

    // The editor delivers a change event only after this setup, so `finish` is already defined by then.
    const changeSubscription = vscode.workspace.onDidChangeTextDocument((event) => {
      if (!changeInvalidatesConflicts(event)) return;

      // The picker only closes, so the message is what tells the user why the fix stopped.
      void vscode.window.showWarningMessage(params.documentChangedLabel);
      finish('cancel');
    });

    const finish = (decision: ShowConflictsDecision) => {
      if (answered) return;

      answered = true;
      // The edits of a confirmed fix arrive as a change event of their own, and they must not reach the
      // listener. `onDidHide` runs later than this, so it is too late to drop the listener there.
      changeSubscription.dispose();
      resolve({ decision });
      picker.hide();
    };

    picker.title = params.title;
    picker.items = [...conflictItems, ...decisionItems];
    // Half of the text of a conflict sits in `detail`, so a filter which ignored it would miss a match.
    picker.matchOnDetail = true;
    // Revealing a conflict moves the focus to the editor, and the picker has to survive that.
    picker.ignoreFocusOut = true;

    picker.onDidAccept(() => {
      const item = picker.selectedItems[0];
      if (item?.decision) {
        finish(item.decision);
        return;
      }
      // A conflict row is not an answer, so accepting it opens its code and keeps the picker open.
      void revealConflict(item?.location);
    });
    picker.onDidTriggerItemButton((event) => {
      void revealConflict(event.item.location);
    });
    picker.onDidHide(() => {
      // The user dismissed the picker, which is a refusal to continue.
      finish('cancel');
      picker.dispose();
    });

    picker.show();
  });
}

/** Opens [location] and selects its range, so the user can see the code the conflict belongs to. */
async function revealConflict(location: Location | null | undefined): Promise<void> {
  if (!location) return;

  try {
    const document = await vscode.workspace.openTextDocument(vscode.Uri.parse(location.uri));
    const selection = new vscode.Range(
      new vscode.Position(location.range.start.line, location.range.start.character),
      new vscode.Position(location.range.end.line, location.range.end.character),
    );
    await vscode.window.showTextDocument(document, { selection, preserveFocus: false });
  } catch {
    // The file may be gone by now. The picker stays open, so the user can still decide.
  }
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
