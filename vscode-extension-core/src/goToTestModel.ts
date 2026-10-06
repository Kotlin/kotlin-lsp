// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { Disposable, Position, TestItem, Uri } from 'vscode';

export const GO_TO_TEST_COMMAND = 'jetbrains.goToTest';
export const GO_TO_TEST_MISSING_CONTEXT = 'jetbrains.goToTestMissing';

const UPSTREAM_URI_SCHEMES: ReadonlySet<string> = new Set([
  'vscode',
  'vscode-insiders',
  'code-oss',
]);

export interface GoToTestEditor {
  readonly uriScheme: string;
  registerCommand(command: string, run: (item: TestItem | undefined) => Promise<void>): Disposable;
  setContext(key: string, value: boolean): void;
  isFile(uri: Uri): Promise<boolean>;
  revealInExplorer(uri: Uri): Promise<void>;
  open(uri: Uri, cursor: Position | undefined): Promise<void>;
}

/** VS Code has its own button since 1.106. A fork may not, and an extension cannot check it. */
export function registerGoToTest(editor: GoToTestEditor): Disposable | undefined {
  if (isVsCode(editor.uriScheme)) return undefined;
  editor.setContext(GO_TO_TEST_MISSING_CONTEXT, true);
  return editor.registerCommand(GO_TO_TEST_COMMAND, async (item) => {
    const uri = item?.uri;
    if (!uri) return;
    if (await editor.isFile(uri)) await editor.open(uri, item.range?.start);
    else await editor.revealInExplorer(uri);
  });
}

function isVsCode(uriScheme: string): boolean {
  return UPSTREAM_URI_SCHEMES.has(uriScheme);
}
