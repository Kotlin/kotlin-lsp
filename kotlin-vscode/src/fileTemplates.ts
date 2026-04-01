import {ExtensionContext, SnippetString, Uri, window, workspace} from 'vscode';
import {getLspClient} from './lspClient';

export function registerFileTemplates(context: ExtensionContext): void {
    context.subscriptions.push(
            workspace.onDidCreateFiles(async (event) => {
                if (event.files.length === 1) {
                    await handleFileCreated(event.files[0]);
                }
            })
    );
}

async function handleFileCreated(fileUri: Uri): Promise<void> {
    try {
        const client = getLspClient();
        if (client === undefined || client.initializeResult === undefined) return;
        const document = await workspace.openTextDocument(fileUri);
        if (document.getText()) return;
        await window.showTextDocument(document);

        const languageId = document.languageId;
        const config = workspace.getConfiguration('jetbrains.templates', fileUri);
        const templates = config.get<object>(languageId);
        if (templates === undefined) return;

        const templateKeys = Object.keys(templates);
        if (templateKeys.length === 0) return;

        const selectedTemplate = (templateKeys.length === 1)
                ? templateKeys[0]
                : await window.showQuickPick(templateKeys, {
                    placeHolder: 'Select a file template'
                });

        if (!selectedTemplate) return;

        const template = (templates as any)[selectedTemplate];

        const content = await client.sendRequest('workspace/executeCommand', {
            command: 'interpolateFileTemplate',
            arguments: [fileUri.toString(), template]
        });
        if (content && typeof content === 'string') {
            const contentWithFixedCaret = content.replace('|', '$0');
            const textEditor = await window.showTextDocument(document);
            await textEditor.insertSnippet(new SnippetString(contentWithFixedCaret));
        }
    } catch (error) {
        console.error('Error handling file creation:', error);
    }
}
