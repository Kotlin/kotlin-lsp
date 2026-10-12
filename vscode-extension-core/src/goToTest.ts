// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { commands, env, type ExtensionContext, FileType, Range, window, workspace } from 'vscode';
import { type GoToTestEditor, registerGoToTest } from './goToTestModel';

const vscodeEditor: GoToTestEditor = {
  get uriScheme() {
    return env.uriScheme;
  },
  registerCommand: (command, run) => commands.registerCommand(command, run),
  setContext: (key, value) => void commands.executeCommand('setContext', key, value),
  // The editor reports the error for a uri that cannot be read.
  isFile: async (uri) => {
    try {
      return ((await workspace.fs.stat(uri)).type & FileType.File) !== 0;
    } catch {
      return true;
    }
  },
  revealInExplorer: async (uri) => {
    await commands.executeCommand('revealInExplorer', uri);
  },
  open: async (uri, cursor) => {
    await window.showTextDocument(uri, { selection: cursor && new Range(cursor, cursor) });
  },
};

export function registerGoToTestCommand(context: ExtensionContext): void {
  const registration = registerGoToTest(vscodeEditor);
  if (registration) context.subscriptions.push(registration);
}
