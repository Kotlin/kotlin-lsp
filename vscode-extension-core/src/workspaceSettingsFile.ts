import * as path from 'node:path';

/**
 * Whether [fsPath] is a `.vscode/settings.json` of a workspace folder or of a subproject: the file
 * the `intellij.projects` configuration lives in.
 */
export function isWorkspaceSettingsPath(fsPath: string): boolean {
  return (
    path.basename(fsPath) === 'settings.json' && path.basename(path.dirname(fsPath)) === '.vscode'
  );
}
