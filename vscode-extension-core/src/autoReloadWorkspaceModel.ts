export const WORKSPACE_RELOAD_SETTING = 'intellij.workspace.reloadOnBuildFileSave';

export type WorkspaceReloadMode = 'always' | 'prompt' | 'never';
export type WorkspaceReloadPromptAction = 'yes' | 'always' | 'never';

interface WorkspaceReloadOnSaveHandlerOptions {
  reloadWorkspace: () => Promise<void>;
  showPrompt: () => Promise<WorkspaceReloadPromptAction | undefined>;
}

interface BuildFileSaveOptions {
  mode: WorkspaceReloadMode;
  setMode: (mode: WorkspaceReloadMode) => Promise<void>;
  markReloadRequired: () => void;
  clearDisabledReloadProblems: () => void;
}

export class WorkspaceReloadOnSaveHandler {
  readonly #reloadWorkspace: () => Promise<void>;
  readonly #showPrompt: () => Promise<WorkspaceReloadPromptAction | undefined>;
  #pendingPrompt: Promise<void> | undefined;

  constructor({ reloadWorkspace, showPrompt }: WorkspaceReloadOnSaveHandlerOptions) {
    this.#reloadWorkspace = reloadWorkspace;
    this.#showPrompt = showPrompt;
  }

  async handleBuildFileSave({
    mode,
    setMode,
    markReloadRequired,
    clearDisabledReloadProblems,
  }: BuildFileSaveOptions): Promise<void> {
    if (mode === 'always') {
      await this.#reloadWorkspace();
      return;
    }
    if (mode === 'never') {
      clearDisabledReloadProblems();
      return;
    }

    markReloadRequired();
    if (this.#pendingPrompt !== undefined) return;

    this.#pendingPrompt = this.#handlePrompt(setMode, clearDisabledReloadProblems).finally(() => {
      this.#pendingPrompt = undefined;
    });
    await this.#pendingPrompt;
  }

  async #handlePrompt(
    setMode: (mode: WorkspaceReloadMode) => Promise<void>,
    clearDisabledReloadProblems: () => void,
  ): Promise<void> {
    const action = await this.#showPrompt();
    if (action === 'yes') {
      await this.#reloadWorkspace();
    } else if (action === 'always') {
      await setMode('always');
      await this.#reloadWorkspace();
    } else if (action === 'never') {
      await setMode('never');
      clearDisabledReloadProblems();
    }
  }
}
