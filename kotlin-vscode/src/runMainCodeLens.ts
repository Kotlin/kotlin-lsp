import * as vscode from 'vscode';

const DEBUG_TYPE = 'intellij_debugger';
const RUN_COMMAND = 'intellij_debugger.runMain';

interface RunArgs {
  mainClass: string;
  uri?: string;
  noDebug?: boolean;
}

export function registerRunMainCodeLens(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.commands.registerCommand(RUN_COMMAND, async (arg: RunArgs) => {
      const folder = vscode.window.activeTextEditor
        ? vscode.workspace.getWorkspaceFolder(vscode.window.activeTextEditor.document.uri)
        : vscode.workspace.workspaceFolders?.[0];
      const config: vscode.DebugConfiguration = {
        type: DEBUG_TYPE,
        request: 'launch',
        name: arg.mainClass.split('.').pop() ?? 'Run main',
        mainClass: arg.mainClass,
      };
      if (arg.uri) config.file = vscode.Uri.parse(arg.uri).fsPath;
      await vscode.debug.startDebugging(folder, config, { noDebug: arg.noDebug ?? false });
    }),
  );
}
