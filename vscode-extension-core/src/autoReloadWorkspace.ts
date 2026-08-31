import { type ExtensionContext, workspace } from 'vscode';
import { isBuildFilePath } from './buildFiles';
import { reloadWorkspace } from './extension';

/**
 * Poor man's version without any content diff checking to transparently reload the workspace
 * whenever a build descriptor (Maven, Gradle, or Bazel) is saved.
 *
 * This covers only build systems whose settings files are listed in `buildFiles.ts`. A build system can
 * instead declare its settings files server-side on its `WorkspaceImporter`, and the server then
 * watches them itself and reloads without this listener. Do not list such a file here as well: the reload
 * below is a manual one and is not coalesced with the server's automatic reload, so every save would run two
 * imports and show a reload notification.
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
