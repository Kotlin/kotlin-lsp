import * as vscode from 'vscode';
import { State } from 'vscode-languageclient/node';
import { getContext } from './extension';
import {
  getLspClient,
  isLspClientStartPending,
  packageJson,
  subscribeToClientEvent,
} from './lspClient';
import {
  computeStatusTooltipContent,
  computeStatusText,
  effectiveLspStatus,
  pickStatusAction,
  shouldShowStatusBar,
  statusBarCommand,
  statusActions as resolveStatusActions,
  type LspStatus,
  type StatusBarAction,
  type StatusBarContribution,
} from './statusBarModel';

export type {
  StatusBarAction,
  StatusBarContribution,
  StatusBarContributionPresentation,
} from './statusBarModel';

const STATUS_MENU_COMMAND = 'jetbrains.kotlin.showLspStatusMenu';

export interface StatusBarContributionRegistration extends vscode.Disposable {
  update(contribution: StatusBarContribution): void;
}

function lspActions(): StatusBarAction[] {
  return [
    { label: '$(sync) Restart Language Server', command: 'jetbrains.kotlin.restartLsp' },
    {
      label: '$(clear-all) Clear Caches and Restart Language Server',
      command: 'jetbrains.kotlin.clearCachesAndRestartLsp',
    },
  ];
}

function statusActions(): StatusBarAction[] {
  return resolveStatusActions(contribution, lspActionsAvailable, lspActions());
}

function productTitle(): string {
  if (contribution?.title !== undefined) return contribution.title;
  const name = packageJson()?.displayName;
  return name ?? 'IntelliJ';
}

let statusBarItem: vscode.StatusBarItem | undefined;
let buildStatusBarItem: vscode.StatusBarItem | undefined;
let contribution: StatusBarContribution | undefined;
let lspActionsAvailable = false;

export function setLspActionsAvailable(available: boolean): void {
  lspActionsAvailable = available;
  updateLspStatusBar();
}

export function registerStatusBarContribution(
  initial: StatusBarContribution,
): StatusBarContributionRegistration {
  contribution = initial;
  updateLspStatusBar();
  return {
    update(updated): void {
      if (contribution !== initial) return;
      initial.presentation = updated.presentation;
      initial.actions = updated.actions;
      initial.title = updated.title;
      initial.runningText = updated.runningText;
      initial.stoppedText = updated.stoppedText;
      updateLspStatusBar();
    },
    dispose(): void {
      if (contribution !== initial) return;
      contribution = undefined;
      updateLspStatusBar();
    },
  };
}

export function registerStatusBarItem() {
  lspActionsAvailable = false;
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  statusBarItem.text = productTitle();
  updateLspStatusBar();

  // Keep build failures separate from the LSP-state item.
  buildStatusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 99);
  buildStatusBarItem.command = 'jetbrains.showBuildLog';

  getContext().subscriptions.push(
    statusBarItem,
    buildStatusBarItem,
    vscode.commands.registerCommand(STATUS_MENU_COMMAND, showLspStatusMenu),
  );
  subscribeToClientEvent(() => updateLspStatusBar());
}

export function setBuildError(tool: string): void {
  if (!buildStatusBarItem) return;
  buildStatusBarItem.text = `$(warning) ${tool}: Build Error`;
  buildStatusBarItem.tooltip = 'Click to open the build log';
  buildStatusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
  buildStatusBarItem.show();
}

export function clearBuildError(): void {
  buildStatusBarItem?.hide();
}

export function updateLspStatusBar() {
  if (!statusBarItem) return;
  const actions = statusActions();
  statusBarItem.tooltip = computeTooltip(actions);
  statusBarItem.text = computeText();
  statusBarItem.command = statusBarCommand(actions, STATUS_MENU_COMMAND);
  if (shouldShowStatusBar(contribution, actions)) {
    statusBarItem.show();
  } else {
    statusBarItem.hide();
  }
}

function computeTooltip(actions: readonly StatusBarAction[]): vscode.MarkdownString {
  // Shown on hover; clicking the item opens the same actions as a QuickPick (STATUS_MENU_COMMAND).
  const text = new vscode.MarkdownString();
  text.supportThemeIcons = true;

  const content = computeStatusTooltipContent(lspStatus(), productTitle(), contribution, actions);
  text.isTrusted = { enabledCommands: content.enabledCommands };
  text.appendMarkdown(content.heading);
  if (content.detail !== undefined) {
    text.appendMarkdown('\n\n');
    text.appendText(content.detail);
  }
  for (const action of content.actions) {
    text.appendMarkdown(`\n\n[${action.label}](command:${action.command})`);
  }
  return text;
}

function computeText(): string {
  return computeStatusText(lspStatus(), productTitle(), contribution);
}

function lspStatus(): LspStatus {
  const clientStatus: LspStatus = (() => {
    switch (getLspClient()?.state ?? State.Stopped) {
      case State.Running:
        return 'running';
      case State.Starting:
        return 'starting';
      default:
        return 'stopped';
    }
  })();
  return effectiveLspStatus(clientStatus, isLspClientStartPending());
}

async function showLspStatusMenu(): Promise<void> {
  const actions = statusActions();
  const picked = await pickStatusAction(actions, productTitle(), (items, options) =>
    vscode.window.showQuickPick(items, options),
  );
  if (picked) {
    await vscode.commands.executeCommand(picked.command);
  }
}
