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

  it('places workspace actions before LSP actions', () => {
    const resolveImport = { label: 'Choose Build Tool…', command: 'reloadWorkspace' };
    assert.deepEqual(statusActions(undefined, true, restartActions, [resolveImport]), [
      resolveImport,
      ...restartActions,
    ]);
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

  it('hides a healthy contribution detail after LSP startup fails', () => {
    const content = computeStatusTooltipContent(
      'stopped',
      'Java and Kotlin',
      {
        actions: [],
        presentation: {
          text: '$(loading~spin)',
          tooltip: 'IntelliJ license service is starting.',
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

  it('warns that project import waits for build tool selection', () => {
    const content = computeStatusTooltipContent('running', 'Java and Kotlin', contribution, [], {
      workspaceImportBlocked: true,
    });
    assert.equal(
      content.heading,
      '**Java and Kotlin**&nbsp;&nbsp;$(circle-slash) Build tool selection required',
    );
    assert.equal(content.detail, 'Project import will not start until you select a build tool.');
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
