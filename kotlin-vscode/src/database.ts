import {commands, type ExtensionContext, window,} from "vscode"

export function registerDatabase(context: ExtensionContext) {
    context.subscriptions.push(
            commands.registerCommand(
                    'database.create.data.source.by.user',
                    async () => {
                        const value = await window.showInputBox({
                            title: 'Create data source',
                            prompt: 'jdbc url',
                            placeHolder: '',
                            ignoreFocusOut: true,
                        });

                        if (value === undefined) {
                            return;
                        }

                        commands.executeCommand('database.create.data.source', value)
                    }
            )
    );
    context.subscriptions.push(
            commands.registerCommand(
                    'database.assign.data.source.by.user',
                    async () => {
                        const editor = window.activeTextEditor;
                        if (editor === undefined) {
                            window.showErrorMessage('No current file')
                            return;
                        }
                        const dataSources: any[] = await commands.executeCommand("database.list.data.sources")
                        if (dataSources.length == 0) {
                            window.showErrorMessage('No data sources available')
                            return;
                        }
                        const dataSource = await window.showQuickPick(dataSources.map((ds: any): any => ({label: ds.name, id: ds.uuid})));

                        if (dataSource === undefined) {
                            return;
                        }
                        const document = editor.document;
                        const url = document.uri.toString();
                        commands.executeCommand('database.assign.data.source', url, dataSource.id)
                    }
            )
    );
}