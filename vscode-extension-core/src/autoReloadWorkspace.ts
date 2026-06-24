import { type ExtensionContext, workspace } from 'vscode';
import { isBuildFilePath } from './buildFiles';
import { reloadWorkspace } from './extension';

/**
 * Poor man's version without any content diff checking to transparently reload the workspace
 * whenever a build descriptor (Maven, Gradle, or Bazel) is saved.
 *
 */
export function registerAutoReloadWorkspace(context: ExtensionContext): void {
  context.subscriptions.push(
    workspace.onDidSaveTextDocument(async (document) => {
      if (isBuildFilePath(document.uri.fsPath)) {
        await reloadWorkspace();
      }
    }),
  );
}
