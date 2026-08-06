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
): StatusBarAction[] {
  return [...(contribution?.actions ?? []), ...(lspActionsAvailable ? lspActions : [])];
}

export function computeStatusText(
  clientState: LspStatus,
  title: string,
  contribution: StatusBarContribution | undefined,
): string {
  const presentation = contribution?.presentation;
  if (clientState === 'starting') return `$(loading~spin) ${title}`;
  if (presentation?.isProblem === true) return `${presentation.text} ${title}`;
  return clientState === 'running'
    ? `${presentation?.text ?? contribution?.runningText ?? '$(check)'} ${title}`
    : `${contribution?.stoppedText ?? '$(stop)'} ${title}`;
}

export function computeStatusTooltipContent(
  clientState: LspStatus,
  title: string,
  contribution: StatusBarContribution | undefined,
  actions: readonly StatusBarAction[],
): StatusTooltipContent {
  return {
    heading: `**${title}**&nbsp;&nbsp;${statusStateText(clientState)}`,
    ...(contribution?.presentation === undefined
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
