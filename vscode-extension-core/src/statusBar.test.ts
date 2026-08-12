import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  computeStatusTooltipContent,
  computeStatusText,
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
