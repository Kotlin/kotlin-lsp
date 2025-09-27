import { ExtensionContext } from 'vscode';
import { DocumentParser } from '@jetbrains/vscode-extension-core/DocumentParser';
import { registerHandleKeyType } from '@jetbrains/vscode-extension-core/handleKeyType';
import keyHandler from './keyHandler';
import { registerDirectoryNavigator } from './directoryNavigator';

export default async (context: ExtensionContext) => {
    const parser = await DocumentParser.create(context, 'kotlin');
    registerHandleKeyType(context, parser, keyHandler);
    registerDirectoryNavigator(context);
};
