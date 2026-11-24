// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { type ExtensionContext, ExtensionMode } from 'vscode';
import { NotificationType0 } from 'vscode-languageclient/node';

/** The server sends it once per connection, after the initial import and the index flush. */
export const ReadyForTestNotification = new NotificationType0('intellij/ready-for-test');

/** The API that `activate()` returns in the test mode. The integration tests in VS Code use it. */
export interface ExtensionTestApi {
  /**
   * Resolves when the server of the current LSP client has sent `intellij/ready-for-test`. A client
   * stop and a new client start make it wait again, so a crashed server does not resolve it.
   */
  whenReadyForTest(): Promise<void>;
}

let readyForTest = false;
let readyForTestWaiters: (() => void)[] = [];

/** Called before a new LSP client starts, and when the client stops. */
export function resetReadyForTest(): void {
  readyForTest = false;
}

/** Called when the server sends {@link ReadyForTestNotification}. */
export function markReadyForTest(): void {
  readyForTest = true;
  const waiters = readyForTestWaiters;
  readyForTestWaiters = [];
  for (const resolve of waiters) resolve();
}

/** Returns the test API in the test mode, and `undefined` in all other modes. */
export function extensionTestApi(context: ExtensionContext): ExtensionTestApi | undefined {
  if (context.extensionMode !== ExtensionMode.Test) return undefined;
  return {
    whenReadyForTest: () =>
      readyForTest
        ? Promise.resolve()
        : new Promise<void>((resolve) => {
            readyForTestWaiters.push(resolve);
          }),
  };
}
