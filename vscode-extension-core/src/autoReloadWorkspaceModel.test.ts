import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  type WorkspaceReloadMode,
  type WorkspaceReloadPromptAction,
  WorkspaceReloadOnSaveHandler,
} from './autoReloadWorkspaceModel';

interface HandlerState {
  reloads: number;
  prompts: number;
  markedProblems: number;
  clearedProblems: number;
  savedModes: WorkspaceReloadMode[];
}

function createHandler(action: WorkspaceReloadPromptAction | undefined): {
  handler: WorkspaceReloadOnSaveHandler;
  state: HandlerState;
} {
  const state: HandlerState = {
    reloads: 0,
    prompts: 0,
    markedProblems: 0,
    clearedProblems: 0,
    savedModes: [],
  };
  return {
    handler: new WorkspaceReloadOnSaveHandler({
      reloadWorkspace: async () => {
        state.reloads++;
      },
      showPrompt: async () => {
        state.prompts++;
        return action;
      },
    }),
    state,
  };
}

async function handleSave(
  handler: WorkspaceReloadOnSaveHandler,
  state: HandlerState,
  mode: WorkspaceReloadMode,
): Promise<void> {
  await handler.handleBuildFileSave({
    mode,
    setMode: async (newMode) => {
      state.savedModes.push(newMode);
    },
    markReloadRequired: () => {
      state.markedProblems++;
    },
    clearDisabledReloadProblems: () => {
      state.clearedProblems++;
    },
  });
}

describe('workspace reload on build file save', () => {
  test('reloads without a prompt in always mode', async () => {
    const { handler, state } = createHandler(undefined);

    await handleSave(handler, state, 'always');

    assert.deepEqual(state, {
      reloads: 1,
      prompts: 0,
      markedProblems: 0,
      clearedProblems: 0,
      savedModes: [],
    });
  });

  test('does nothing in never mode', async () => {
    const { handler, state } = createHandler(undefined);

    await handleSave(handler, state, 'never');

    assert.deepEqual(state, {
      reloads: 0,
      prompts: 0,
      markedProblems: 0,
      clearedProblems: 1,
      savedModes: [],
    });
  });

  test('reloads once when the user selects Yes', async () => {
    const { handler, state } = createHandler('yes');

    await handleSave(handler, state, 'prompt');

    assert.deepEqual(state, {
      reloads: 1,
      prompts: 1,
      markedProblems: 1,
      clearedProblems: 0,
      savedModes: [],
    });
  });

  test('keeps the problem when the user dismisses the prompt', async () => {
    const { handler, state } = createHandler(undefined);

    await handleSave(handler, state, 'prompt');

    assert.deepEqual(state, {
      reloads: 0,
      prompts: 1,
      markedProblems: 1,
      clearedProblems: 0,
      savedModes: [],
    });
  });

  test('shows a new prompt after the previous prompt is dismissed', async () => {
    const { handler, state } = createHandler(undefined);

    await handleSave(handler, state, 'prompt');
    await handleSave(handler, state, 'prompt');

    assert.equal(state.prompts, 2);
    assert.equal(state.markedProblems, 2);
  });

  test('enables automatic reload and reloads when the user selects Always', async () => {
    const { handler, state } = createHandler('always');

    await handleSave(handler, state, 'prompt');

    assert.deepEqual(state, {
      reloads: 1,
      prompts: 1,
      markedProblems: 1,
      clearedProblems: 0,
      savedModes: ['always'],
    });
  });

  test('disables reload when the user selects Never', async () => {
    const { handler, state } = createHandler('never');

    await handleSave(handler, state, 'prompt');

    assert.deepEqual(state, {
      reloads: 0,
      prompts: 1,
      markedProblems: 1,
      clearedProblems: 1,
      savedModes: ['never'],
    });
  });

  test('shows only one prompt while a prompt is open', async () => {
    let resolvePrompt: ((action: WorkspaceReloadPromptAction) => void) | undefined;
    const prompt = new Promise<WorkspaceReloadPromptAction>((resolve) => {
      resolvePrompt = resolve;
    });
    let prompts = 0;
    let reloads = 0;
    const handler = new WorkspaceReloadOnSaveHandler({
      reloadWorkspace: async () => {
        reloads++;
      },
      showPrompt: async () => {
        prompts++;
        return await prompt;
      },
    });
    const setMode = async (): Promise<void> => {};
    let markedProblems = 0;
    const markReloadRequired = (): void => {
      markedProblems++;
    };
    const clearDisabledReloadProblems = (): void => {};

    const firstSave = handler.handleBuildFileSave({
      mode: 'prompt',
      setMode,
      markReloadRequired,
      clearDisabledReloadProblems,
    });
    const secondSave = handler.handleBuildFileSave({
      mode: 'prompt',
      setMode,
      markReloadRequired,
      clearDisabledReloadProblems,
    });
    assert.equal(markedProblems, 2);
    assert.equal(prompts, 1);

    resolvePrompt?.('yes');
    await Promise.all([firstSave, secondSave]);
    assert.equal(reloads, 1);
  });
});
