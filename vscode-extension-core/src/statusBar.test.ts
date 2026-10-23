import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  buildToolConflictState,
  computeStatusTooltipContent,
  computeStatusText,
  createLatestSnapshotTracker,
  effectiveLspStatus,
  pickStatusAction,
  shouldShowStatusBar,
  statusBarCommand,
  statusActions,
  type StatusBarAction,
  type StatusBarContribution,
  type StatusBarState,
} from './statusBarModel';

const restartActions: StatusBarAction[] = [
  { label: 'Restart Language Server', command: 'restart' },
  { label: 'Clear Caches and Restart Language Server', command: 'clearAndRestart' },
];

describe('latest snapshot tracker', () => {
  it('applies a loaded snapshot when no live snapshot supersedes it', async () => {
    const applied: string[] = [];
    const tracker = createLatestSnapshotTracker<string>((snapshot) => applied.push(snapshot));

    await tracker.refresh(async () => 'persisted');

    assert.deepEqual(applied, ['persisted']);
  });

  it('does not let a stale loaded snapshot overwrite a live snapshot published during refresh', async () => {
    const applied: string[] = [];
    let finishLoad: ((snapshot: string) => void) | undefined;
    const load = new Promise<string>((resolve) => {
      finishLoad = resolve;
    });
    const tracker = createLatestSnapshotTracker<string>((snapshot) => applied.push(snapshot));
    const refresh = tracker.refresh(() => load);

    tracker.publish('live');
    finishLoad?.('persisted');
    await refresh;

    assert.deepEqual(applied, ['live']);
  });

  it('does not let a loaded seed overwrite a live snapshot published before refresh', async () => {
    const applied: string[] = [];
    const tracker = createLatestSnapshotTracker<string>((snapshot) => applied.push(snapshot));

    tracker.publish('live');
    await tracker.refresh(async () => 'persisted');

    assert.deepEqual(applied, ['live']);
  });
});

describe('workspace import status', () => {
  it('marks import as blocked while the ambiguous-build-system chooser is open', () => {
    assert.deepEqual(
      buildToolConflictState({
        blockedFolders: [
          {
            folderUri: 'file:///workspace',
            reason: 'ambiguousBuildSystem',
            candidates: ['gradle', 'maven'],
            dismissed: false,
          },
        ],
      }),
      { blocked: true, promptDismissed: false },
    );
  });

  it('offers recovery after the ambiguous-build-system chooser is dismissed', () => {
    assert.deepEqual(
      buildToolConflictState({
        blockedFolders: [
          {
            folderUri: 'file:///workspace',
            reason: 'ambiguousBuildSystem',
            candidates: ['gradle', 'maven'],
            dismissed: true,
          },
        ],
      }),
      { blocked: true, promptDismissed: true },
    );
  });

  it('ignores other blocked import reasons', () => {
    assert.deepEqual(
      buildToolConflictState({
        blockedFolders: [
          {
            folderUri: 'file:///workspace',
            reason: 'noBuildSystemFound',
            candidates: [],
            dismissed: true,
          },
        ],
      }),
      { blocked: false, promptDismissed: false },
    );
  });
});

describe('status bar actions', () => {
  it('keeps restart actions available without a live client once LSP actions are enabled', () => {
    assert.deepEqual(statusActions(undefined, true, restartActions), restartActions);
  });

  it('withholds restart actions until LSP actions are enabled', () => {
    assert.deepEqual(statusActions(undefined, false, restartActions), []);
  });

  it('places contribution actions before LSP actions', () => {
    const setupAction = { label: 'Complete Setup', command: 'setup' };
    assert.deepEqual(statusActions({ actions: [setupAction] }, true, restartActions), [
      setupAction,
      ...restartActions,
    ]);
  });

  it('places workspace actions before contribution and LSP actions', () => {
    const resolveImport = { label: 'Choose Build Tool…', command: 'reloadWorkspace' };
    const setupAction = { label: 'Complete Setup', command: 'setup' };
    assert.deepEqual(
      statusActions({ actions: [setupAction] }, true, restartActions, [resolveImport]),
      [resolveImport, setupAction, ...restartActions],
    );
  });

  it('does not open an empty quick pick before actions are registered', async () => {
    let opened = false;
    const picked = await pickStatusAction([], 'Kotlin', async () => {
      opened = true;
      return undefined;
    });

    assert.equal(picked, undefined);
    assert.equal(opened, false);
  });

  it('disables the status bar command when there are no actions', () => {
    assert.equal(statusBarCommand([], 'showMenu'), undefined);
    assert.equal(statusBarCommand(restartActions, 'showMenu'), 'showMenu');
  });

  it('hides an unconfigured status bar but shows contributions and LSP actions', () => {
    assert.equal(shouldShowStatusBar(undefined, []), false);
    assert.equal(shouldShowStatusBar({ actions: [] }, []), true);
    assert.equal(shouldShowStatusBar(undefined, restartActions), true);
  });

  it('builds tooltip trust and actions from the same resolved action list', () => {
    const content = computeStatusTooltipContent(
      'stopped',
      'Kotlin',
      {
        actions: [],
        presentation: { text: 'icon', tooltip: 'Setup required', isProblem: true },
      },
      restartActions,
    );
    assert.equal(content.heading, '**Kotlin**&nbsp;&nbsp;$(stop) Stopped');
    assert.equal(content.detail, 'Setup required');
    assert.deepEqual(content.actions, restartActions);
    assert.deepEqual(content.enabledCommands, ['restart', 'clearAndRestart']);
  });
});

describe('status bar text', () => {
  const contribution: StatusBarContribution = {
    actions: [],
    runningText: '$(jetbrains-ij)',
    stoppedText: '$(jetbrains-ij-crossed)',
    presentation: {
      text: '$(jetbrains-ij)',
      tooltip: 'License is active',
      isProblem: false,
    },
  };

  it('shows the stopped problem icon when LSP is stopped even if licensing is healthy', () => {
    assert.equal(
      computeStatusText('stopped', 'Java and Kotlin', contribution),
      '$(jetbrains-ij-crossed) Java and Kotlin',
    );
  });

  it('lets a contributed problem override a running LSP', () => {
    const problem = {
      ...contribution,
      presentation: {
        text: '$(jetbrains-ij-crossed)',
        tooltip: 'Setup is incomplete',
        isProblem: true,
      },
    };
    assert.equal(
      computeStatusText('running', 'Java and Kotlin', problem),
      '$(jetbrains-ij-crossed) Java and Kotlin',
    );
  });

  it('shows the license state instead of Running for an unlicensed server', () => {
    const unlicensed = {
      ...contribution,
      presentation: {
        text: '$(jetbrains-ij-crossed)',
        tooltip: 'Activate a license to access all extension features',
        isProblem: true,
        statusText: 'No active license',
      },
    };
    const content = computeStatusTooltipContent('running', 'Java and Kotlin', unlicensed, []);

    assert.equal(content.heading, '**Java and Kotlin**&nbsp;&nbsp;No active license');
    assert.equal(content.detail, 'Activate a license to access all extension features');
    assert.equal(
      computeStatusText('running', 'Java and Kotlin', unlicensed),
      '$(jetbrains-ij-crossed) Java and Kotlin',
    );
  });

  it('hides a healthy contribution detail after LSP startup fails', () => {
    const content = computeStatusTooltipContent(
      'stopped',
      'Java and Kotlin',
      {
        actions: [],
        presentation: {
          text: '$(loading~spin)',
          tooltip: 'License service is starting…',
          isProblem: false,
        },
      },
      restartActions,
    );

    assert.equal(content.heading, '**Java and Kotlin**&nbsp;&nbsp;$(stop) Stopped');
    assert.equal(content.detail, undefined);
  });

  it('keeps a contributed problem visible while LSP is stopped', () => {
    const content = computeStatusTooltipContent(
      'stopped',
      'Java and Kotlin',
      {
        actions: [],
        presentation: {
          text: '$(jetbrains-ij-crossed)',
          tooltip: 'Setup is incomplete',
          isProblem: true,
        },
      },
      restartActions,
    );

    assert.equal(content.detail, 'Setup is incomplete');
  });

  it('uses the spinner while LSP is starting', () => {
    assert.equal(computeStatusText('starting', 'Kotlin', undefined), '$(loading~spin) Kotlin');
  });

  it('keeps the spinner while LSP is starting even if a contribution is not ready', () => {
    const problem = {
      ...contribution,
      presentation: {
        text: '$(jetbrains-ij-crossed)',
        tooltip: 'License service is starting',
        isProblem: true,
      },
    };
    assert.equal(
      computeStatusText('starting', 'Java and Kotlin', problem),
      '$(loading~spin) Java and Kotlin',
    );
  });

  it('uses default icons without a contribution', () => {
    assert.equal(computeStatusText('running', 'Kotlin', undefined), '$(check) Kotlin');
    assert.equal(computeStatusText('stopped', 'Kotlin', undefined), '$(stop) Kotlin');
  });

  it('prefers healthy presentation text to runningText while LSP is running', () => {
    assert.equal(
      computeStatusText('running', 'Java and Kotlin', contribution),
      '$(jetbrains-ij) Java and Kotlin',
    );
  });

  it('uses the crossed product icon while build tool selection is required', () => {
    assert.equal(
      computeStatusText('running', 'Java and Kotlin', contribution, {
        workspaceImportBlocked: true,
      }),
      '$(jetbrains-ij-crossed) Java and Kotlin',
    );
  });

  it('keeps a contributed problem ahead of a blocked project import', () => {
    const problem = {
      ...contribution,
      presentation: {
        text: '$(warning)',
        tooltip: 'Setup is incomplete',
        isProblem: true,
      },
    };
    assert.equal(
      computeStatusText('running', 'Java and Kotlin', problem, {
        workspaceImportBlocked: true,
      }),
      '$(warning) Java and Kotlin',
    );
  });

  it('asks the user to choose a build tool for project import', () => {
    const content = computeStatusTooltipContent('running', 'Java and Kotlin', contribution, [], {
      workspaceImportBlocked: true,
    });
    assert.equal(
      content.heading,
      '**Java and Kotlin**&nbsp;&nbsp;$(circle-slash) Build tool required',
    );
    assert.equal(content.detail, 'Choose a build tool to start project import.');
  });
});

describe('status bar suffix', () => {
  const state: StatusBarState = { suffix: { text: '1.27.3', tooltip: '/sdk/go1.27.3' } };
  const licensed: StatusBarContribution = {
    title: 'IntelliJ',
    actions: [{ label: 'Manage License', command: 'license' }],
    runningText: '$(jetbrains-ij)',
    stoppedText: '$(jetbrains-ij-crossed)',
    presentation: { text: '$(jetbrains-ij)', tooltip: 'License is active', isProblem: false },
  };

  it('adds the SDK to the displayed text while keeping the product heading and menu', async () => {
    assert.equal(
      computeStatusText('running', 'Go LSP', undefined, state),
      '$(check) Go LSP · 1.27.3',
    );
    const tooltip = computeStatusTooltipContent(
      'running',
      'Go LSP',
      undefined,
      restartActions,
      state,
    );
    assert.equal(tooltip.heading, '**Go LSP**&nbsp;&nbsp;$(check) Running');
    assert.equal(tooltip.detail, '/sdk/go1.27.3');
    assert.deepEqual(tooltip.actions, restartActions);

    await pickStatusAction(tooltip.actions, 'Go LSP', async (_actions, options) => {
      assert.equal(options.placeHolder, 'Go LSP actions');
      return undefined;
    });
  });

  it('keeps active licensing branding, details, and actions alongside the SDK', () => {
    assert.equal(
      computeStatusText('running', 'IntelliJ', licensed, state),
      '$(jetbrains-ij) IntelliJ · 1.27.3',
    );
    const actions = statusActions(licensed, true, restartActions);
    const tooltip = computeStatusTooltipContent('running', 'IntelliJ', licensed, actions, state);
    assert.equal(tooltip.detail, 'License is active\n\n/sdk/go1.27.3');
    assert.deepEqual(tooltip.actions, [...licensed.actions, ...restartActions]);
  });

  it('hides the SDK during licensing problems and restores it after recovery', () => {
    const problem: StatusBarContribution = {
      ...licensed,
      presentation: { text: '$(warning)', tooltip: 'License required', isProblem: true },
    };
    for (const clientState of ['starting', 'running', 'stopped'] as const) {
      assert.ok(!computeStatusText(clientState, 'IntelliJ', problem, state).includes('1.27.3'));
      assert.equal(
        computeStatusTooltipContent(clientState, 'IntelliJ', problem, restartActions, state).detail,
        'License required',
      );
    }
    assert.equal(
      computeStatusText('running', 'IntelliJ', licensed, state),
      '$(jetbrains-ij) IntelliJ · 1.27.3',
    );
  });

  it('hides the SDK while a build tool is required', () => {
    const blocked = { ...state, workspaceImportBlocked: true };
    assert.equal(
      computeStatusText('running', 'IntelliJ', licensed, blocked),
      '$(jetbrains-ij-crossed) IntelliJ',
    );
    const tooltip = computeStatusTooltipContent('running', 'IntelliJ', licensed, [], blocked);
    assert.equal(tooltip.heading, '**IntelliJ**&nbsp;&nbsp;$(circle-slash) Build tool required');
    assert.equal(tooltip.detail, 'Choose a build tool to start project import.');
  });

  it('keeps the cached SDK during restart and hides its path after stopping', () => {
    assert.equal(
      computeStatusText('starting', 'Go LSP', undefined, state),
      '$(loading~spin) Go LSP · 1.27.3',
    );
    assert.equal(
      computeStatusTooltipContent('starting', 'Go LSP', undefined, [], state).detail,
      '/sdk/go1.27.3',
    );
    assert.equal(
      computeStatusText('stopped', 'Go LSP', undefined, state),
      '$(stop) Go LSP · 1.27.3',
    );
    assert.equal(
      computeStatusTooltipContent('stopped', 'Go LSP', undefined, [], state).detail,
      undefined,
    );
  });

  it('shows a version without an SDK path', () => {
    const versionOnly = { suffix: { text: '1.27.3' } };
    assert.equal(
      computeStatusText('running', 'Go LSP', undefined, versionOnly),
      '$(check) Go LSP · 1.27.3',
    );
    assert.equal(
      computeStatusTooltipContent('running', 'Go LSP', undefined, [], versionOnly).detail,
      undefined,
    );
  });

  it('clears both the version and path when the source clears the suffix', () => {
    const cleared = { ...state, suffix: undefined };
    assert.equal(computeStatusText('running', 'Go LSP', undefined, cleared), '$(check) Go LSP');
    assert.equal(
      computeStatusTooltipContent('running', 'Go LSP', undefined, [], cleared).detail,
      undefined,
    );
  });
});

describe('effective LSP status', () => {
  it('treats a pending start without a live client as starting', () => {
    assert.equal(effectiveLspStatus('stopped', true), 'starting');
  });

  it('preserves settled client states', () => {
    assert.equal(effectiveLspStatus('stopped', false), 'stopped');
    assert.equal(effectiveLspStatus('starting', false), 'starting');
    assert.equal(effectiveLspStatus('running', true), 'running');
  });
});
