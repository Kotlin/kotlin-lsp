import * as vscode from 'vscode';
import * as fs from 'fs';

export function registerDirectoryNavigator(context: vscode.ExtensionContext) {
    const disposable = vscode.commands.registerTextEditorCommand(
        'editor.action.revealDefinition',
        async (textEditor: vscode.TextEditor) => {
            if (textEditor.document.languageId !== 'kotlin') {
                return vscode.commands.executeCommand('vscode.executeDefinitionProvider',
                    textEditor.document.uri, textEditor.selection.active);
            }

            const definitions = await vscode.commands.executeCommand<vscode.Location[]>(
                'vscode.executeDefinitionProvider',
                textEditor.document.uri,
                textEditor.selection.active
            );

            if (!definitions?.length) return;

            for (const def of definitions) {
                try {
                    const isDir = fs.statSync(def.uri.fsPath).isDirectory();
                    if (isDir) {
                        await vscode.commands.executeCommand('workbench.view.explorer');
                        await vscode.commands.executeCommand('revealInExplorer', def.uri);
                        return;
                    }
                } catch {}
            }

            for (const def of definitions) {
                const document = await vscode.workspace.openTextDocument(def.uri);
                await vscode.window.showTextDocument(document, {
                    selection: def.range
                });
            }
        }
    );

    context.subscriptions.push(disposable);
}
