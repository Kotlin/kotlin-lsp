// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import type { ExtensionContext } from 'vscode';
import { subscribeToClientEvent } from '@jetbrains/vscode-extension-core';
import { State } from 'vscode-languageclient/node';
import { registerTestController } from './testController';
import type { TestLanguage } from './testLanguage';

export function testingModule<Profile extends string>(
  language: TestLanguage<Profile>,
): (context: ExtensionContext) => void {
  return (context) => {
    let registered = false;
    context.subscriptions.push(
      subscribeToClientEvent((client, stateChange) => {
        if (registered || stateChange.newState !== State.Running) return;
        const capabilities = client.initializeResult?.capabilities;
        if (!capabilities || !language.discovery.supportedBy(capabilities)) return;
        registered = true;
        registerTestController(context, client, language);
      }),
    );
  };
}
