import {
  ConfigurationTarget,
  Diagnostic,
  type DiagnosticCollection,
  DiagnosticSeverity,
  type Event,
  type ExtensionContext,
  languages,
  Range,
  type Uri,
  window,
  workspace,
} from 'vscode';
import { isBuildFilePath } from './buildFiles';
import {
  WORKSPACE_RELOAD_SETTING,
  type WorkspaceReloadMode,
  WorkspaceReloadOnSaveHandler,
  type WorkspaceReloadPromptAction,
} from './autoReloadWorkspaceModel';

const RELOAD_ACTION = 'Yes';
const ALWAYS_ACTION = 'Always';
const NEVER_ACTION = 'Never';

interface AutoReloadWorkspaceOptions {
  reloadWorkspace: () => Promise<void>;
  onDidReloadWorkspace: Event<void>;
}

async function showReloadPrompt(): Promise<WorkspaceReloadPromptAction | undefined> {
  const action = await window.showInformationMessage(
    'A build file was modified. Do you want to reload the workspace?',
    RELOAD_ACTION,
    ALWAYS_ACTION,
    NEVER_ACTION,
  );
  return promptAction(action);
}

function promptAction(action: string | undefined): WorkspaceReloadPromptAction | undefined {
  if (action === RELOAD_ACTION) return 'yes';
  if (action === ALWAYS_ACTION) return 'always';
  if (action === NEVER_ACTION) return 'never';
  return undefined;
}

function workspaceReloadMode(): WorkspaceReloadMode {
  return workspace.getConfiguration().get<WorkspaceReloadMode>(WORKSPACE_RELOAD_SETTING, 'always');
}

async function setWorkspaceReloadMode(mode: WorkspaceReloadMode): Promise<void> {
  const configuration = workspace.getConfiguration();
  const inspection = configuration.inspect<WorkspaceReloadMode>(WORKSPACE_RELOAD_SETTING);
  const target =
    inspection?.workspaceValue === undefined
      ? ConfigurationTarget.Global
      : ConfigurationTarget.Workspace;
  await configuration.update(WORKSPACE_RELOAD_SETTING, mode, target);
}

function clearDisabledReloadProblems(diagnostics: DiagnosticCollection): void {
  if (workspaceReloadMode() === 'never') diagnostics.clear();
}

function markReloadRequired(diagnostics: DiagnosticCollection, resource: Uri): void {
  const diagnostic = new Diagnostic(
    new Range(0, 0, 0, 0),
    'The build file changed. Reload the workspace to apply the change.',
    DiagnosticSeverity.Information,
  );
  diagnostic.source = 'IntelliJ';
  diagnostics.set(resource, [diagnostic]);
}

/**
 * Reloads the workspace or prompts the user whenever a build descriptor (Maven, Gradle, or Bazel)
 * is saved. The configured mode controls the behavior.
 *
 * This covers only build systems whose settings files are listed in `buildFiles.ts`. A build system can
 * instead declare its settings files server-side on its `WorkspaceImporter`, and the server then
 * watches them itself and reloads without this listener. Do not list such a file here as well: the reload
 * below is a manual one and is not coalesced with the server's automatic reload, so every save would run two
 * imports and show a reload notification.
 */
export function registerAutoReloadWorkspace(
  context: ExtensionContext,
  { reloadWorkspace, onDidReloadWorkspace }: AutoReloadWorkspaceOptions,
): void {
  const diagnostics = languages.createDiagnosticCollection('intellij-build-file-reload');
  const reloadOnSaveHandler = new WorkspaceReloadOnSaveHandler({
    reloadWorkspace,
    showPrompt: showReloadPrompt,
  });
  context.subscriptions.push(
    diagnostics,
    onDidReloadWorkspace(() => diagnostics.clear()),
    workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration(WORKSPACE_RELOAD_SETTING)) {
        clearDisabledReloadProblems(diagnostics);
      }
    }),
    workspace.onDidSaveTextDocument(async (document) => {
      if (!isBuildFilePath(document.uri.fsPath)) return;

      await reloadOnSaveHandler.handleBuildFileSave({
        mode: workspaceReloadMode(),
        setMode: setWorkspaceReloadMode,
        markReloadRequired: () => markReloadRequired(diagnostics, document.uri),
        clearDisabledReloadProblems: () => clearDisabledReloadProblems(diagnostics),
      });
    }),
  );
}
