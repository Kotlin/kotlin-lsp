import * as vscode from "vscode"
import {CancellationToken, commands, ExtensionContext, languages, TextDocumentContentProvider, Uri, workspace} from "vscode"

interface DecompiledDocumentContent {
    code: string;
    language: string;
}

type DocumentContent = DecompiledDocumentContent | 'error during decompilation';

/**
 * Registers jar/jrt decompilers for given files.
 * 
 * The algorithm is as follows:
 * 1. When a file is opened, check if it is a jar/jrt file.
 * 2. Check if the file is already decompiled, it's cached in `openedDecompiledDocuments`. Reuse it if so.
 * 3. If not, call the decompile command and get the decompiled code. Add it to the `openedDecompiledDocuments` map and to the `documentsAwaitingToChangeTheirLanguage`.
 * 4. After that, on a document open, take this document from the `documentsAwaitingToChangeTheirLanguage`, set the language to the decompiled language.
 * 5. This will trigger the `onDidCloseTextDocument` event, which will remove the document from the `documentsAwaitingToChangeTheirLanguage`.
 * 6. Then the `onDidOpenTextDocument` event will be triggered, but the `documentsAwaitingToChangeTheirLanguage` will be empty, so do nothing.
 * 7 On the real close of the document, remove it from the `openedDecompiledDocuments` map.
 * 8. Additionally, the internal vscode document cache will be invalidated when the ` onDidChange ` event is fired when LSP-158 is implemented.
 */
export function registerDecompiler(context: ExtensionContext) {
    const openedDecompiledDocuments = new Map<string, DocumentContent>();
    const openDocumentsDisposer = {
        dispose() {
            openedDecompiledDocuments.clear();
        },
    }

    const documentsAwaitingToChangeTheirLanguage = new Map<string, string>();

    context.subscriptions.push(
        openDocumentsDisposer,
        workspace.onDidOpenTextDocument(async doc => {
            const uri = doc.uri;
            const language = documentsAwaitingToChangeTheirLanguage.get(uri.toString());
            if(!language) return
    
            if ((await languages.getLanguages()).includes(language) && doc.languageId !== language) {
                // calling setTextDocumentLanguage will trigger onDidCloseTextDocument
                languages.setTextDocumentLanguage(doc, language);
            }
        }),
        workspace.onDidCloseTextDocument(doc => {
            const uri = doc.uri;
            if (documentsAwaitingToChangeTheirLanguage.has(uri.toString())) {
                // a document closing caused by `languages.setTextDocumentLanguage` invoked in `onDidOpenTextDocument`
                documentsAwaitingToChangeTheirLanguage.delete(uri.toString());
            } else {
                openedDecompiledDocuments.delete(uri.toString());
            }
        }),
    
    );

    const onDidChange = new vscode.EventEmitter<vscode.Uri>();
    const decompiler: TextDocumentContentProvider = {
        onDidChange: onDidChange.event,
        async provideTextDocumentContent(uri: Uri, token: CancellationToken): Promise<string | null> {
            if (openedDecompiledDocuments.has(uri.toString())) {
                const data = openedDecompiledDocuments.get(uri.toString())!;
                return data === 'error during decompilation' ? ERROR_DURING_DECOMPILATION_TEXT : data.code;
            }
            let response: DecompiledDocumentContent | null = null;
            try {
                response = await commands.executeCommand("decompile", uri.toString());
            } catch (e) {
                console.error("Error executing decompile command:", e);
                response = null;
            }
            if (!response) {
                openedDecompiledDocuments.set(uri.toString(), 'error during decompilation');
                return ERROR_DURING_DECOMPILATION_TEXT;
            }
            documentsAwaitingToChangeTheirLanguage.set(uri.toString(), response.language);
            openedDecompiledDocuments.set(uri.toString(), response);
            return response.code;
        }
    };

    for (const scheme of supportedProtocols) {
        context.subscriptions.push(workspace.registerTextDocumentContentProvider(scheme, decompiler));
    }

    // TODO should be called from LSP server by a notification after jar changed: LSP-154
    function invalidateAllOpenBinaryDocuments() {
        for (const document of vscode.workspace.textDocuments) {
            openedDecompiledDocuments.clear();
            if (document.uri.scheme in supportedProtocols) {
                onDidChange.fire(document.uri);
            }
        }
    }
}

export function registerOpeningJars() {
    /*
     * Registers command for navigating to jar/jrt locations.
     * See LSP-393
    */
    vscode.commands.registerCommand('jetbrains.navigateToJarLocation', async (uriString: string, line: number, character: number) => {
        try {
            const uri = vscode.Uri.parse(uriString);

            if (!supportedProtocols.includes(uri.scheme)) {
                console.error(`[NavigateToJar] Invalid URI decompiled scheme: ${uri.scheme}, expected 'jar' or 'jrt'`);
                return;
            }

            const doc = await vscode.workspace.openTextDocument(uri);

            const position = new vscode.Position(line, character);
            const range = new vscode.Range(position, position);
            await vscode.window.showTextDocument(doc, {
                selection: range,
                preserveFocus: false
            });
        } catch (e) {
            console.error(`[NavigateToJar] Failed to navigate:`, e);
            vscode.window.showErrorMessage(`Failed to navigate: ${e}`);
        }
    })
}

const ERROR_DURING_DECOMPILATION_TEXT = 'Cannot decompile file'

const supportedProtocols: readonly string[] = ["jar", "jrt"]
