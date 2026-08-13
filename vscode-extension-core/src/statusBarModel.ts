export interface StatusBarAction {
  label: string;
  command: string;
}

export interface StatusBarContributionPresentation {
  text: string;
  tooltip: string;
  isProblem: boolean;
}

export interface StatusBarContribution {
  actions: StatusBarAction[];
  title?: string;
  runningText?: string;
  stoppedText?: string;
  presentation?: StatusBarContributionPresentation;
}

export type LspStatus = 'running' | 'starting' | 'stopped';

export interface LatestSnapshotTracker<T> {
  publish(snapshot: T): void;
  refresh(load: () => Promise<T>): Promise<void>;
}

export interface BlockedWorkspaceFolder {
  folderUri: string;
  reason: string;
  candidates: string[];
  dismissed: boolean;
}

export interface WorkspaceImportStatus {
  blockedFolders: BlockedWorkspaceFolder[];
}

export interface BuildToolConflictState {
  blocked: boolean;
  promptDismissed: boolean;
}

export function buildToolConflictState(status: WorkspaceImportStatus): BuildToolConflictState {
  const conflicts = status.blockedFolders.filter(
    (folder) => folder.reason === 'ambiguousBuildSystem',
  );
  return {
    blocked: conflicts.length > 0,
    promptDismissed: conflicts.some((folder) => folder.dismissed),
  };
}

/** Seeds state from a loaded snapshot only when no live snapshot has been published. */
export function createLatestSnapshotTracker<T>(
  apply: (snapshot: T) => void,
): LatestSnapshotTracker<T> {
  let publishedRevision = 0;
  return {
    publish(snapshot): void {
      publishedRevision++;
      apply(snapshot);
    },
    async refresh(load): Promise<void> {
      const snapshot = await load();
      if (publishedRevision === 0) apply(snapshot);
    },
  };
}

export function effectiveLspStatus(clientStatus: LspStatus, startPending: boolean): LspStatus {
  return clientStatus === 'stopped' && startPending ? 'starting' : clientStatus;
}

export interface StatusTooltipContent {
  heading: string;
  detail?: string;
  actions: readonly StatusBarAction[];
  enabledCommands: string[];
}

export function statusActions(
  contribution: StatusBarContribution | undefined,
  lspActionsAvailable: boolean,
  lspActions: readonly StatusBarAction[],
  workspaceActions: readonly StatusBarAction[] = [],
): StatusBarAction[] {
  return [
    ...(contribution?.actions ?? []),
    ...workspaceActions,
    ...(lspActionsAvailable ? lspActions : []),
  ];
}

export interface StatusBarState {
  workspaceImportBlocked?: boolean;
}

export function computeStatusText(
  clientState: LspStatus,
  title: string,
  contribution: StatusBarContribution | undefined,
  state: StatusBarState = {},
): string {
  const presentation = contribution?.presentation;
  if (clientState === 'starting') return `$(loading~spin) ${title}`;
  if (presentation?.isProblem === true) return `${presentation.text} ${title}`;
  if (clientState === 'running' && state.workspaceImportBlocked === true) {
    return `${contribution?.stoppedText ?? '$(circle-slash)'} ${title}`;
  }
  return clientState === 'running'
    ? `${presentation?.text ?? contribution?.runningText ?? '$(check)'} ${title}`
    : `${contribution?.stoppedText ?? '$(stop)'} ${title}`;
}

export function computeStatusTooltipContent(
  clientState: LspStatus,
  title: string,
  contribution: StatusBarContribution | undefined,
  actions: readonly StatusBarAction[],
  state: StatusBarState = {},
): StatusTooltipContent {
  const contributionProblem = contribution?.presentation?.isProblem === true;
  const workspaceImportBlocked =
    clientState === 'running' && state.workspaceImportBlocked === true && !contributionProblem;
  return {
    heading: `**${title}**&nbsp;&nbsp;${workspaceImportBlocked ? '$(circle-slash) Build tool selection required' : statusStateText(clientState)}`,
    ...(workspaceImportBlocked
      ? { detail: 'Project import will not start until you select a build tool.' }
      : contribution?.presentation === undefined
        ? {}
        : { detail: contribution.presentation.tooltip }),
    actions,
    enabledCommands: actions.map((action) => action.command),
  };
}

export function statusBarCommand(
  actions: readonly StatusBarAction[],
  command: string,
): string | undefined {
  return actions.length === 0 ? undefined : command;
}

export function shouldShowStatusBar(
  contribution: StatusBarContribution | undefined,
  actions: readonly StatusBarAction[],
): boolean {
  return contribution !== undefined || actions.length > 0;
}

function statusStateText(clientState: LspStatus): string {
  switch (clientState) {
    case 'running':
      return '$(check) Running';
    case 'starting':
      return '$(loading~spin) Starting';
    case 'stopped':
      return '$(stop) Stopped';
  }
}

export async function pickStatusAction(
  actions: readonly StatusBarAction[],
  title: string,
  showQuickPick: (
    actions: readonly StatusBarAction[],
    options: { placeHolder: string },
  ) => PromiseLike<StatusBarAction | undefined>,
): Promise<StatusBarAction | undefined> {
  if (actions.length === 0) return undefined;
  return showQuickPick(actions, { placeHolder: `${title} actions` });
}
