import type { ExtensionContext } from 'vscode';
import { subscribeToClientEvent } from '@jetbrains/vscode-extension-core';
import { State } from 'vscode-languageclient/node';
import { registerJvmTestController } from './jvmTestController';
import { JvmTestCommands } from './jvmTestProtocol';

/**
 * Shows JVM tests in the Testing view and as gutter run icons, once the server can discover them.
 *
 * A test controller of its own shows the Testing view, so the tests of a server that has no test
 * commands stay out of the tree and out of the UI both.
 */
export default (context: ExtensionContext): void => {
  let registered = false;
  context.subscriptions.push(
    subscribeToClientEvent((client, stateChange) => {
      if (registered || stateChange.newState !== State.Running) return;
      const commands = client.initializeResult?.capabilities.executeCommandProvider?.commands ?? [];
      if (!commands.includes(JvmTestCommands.discoverTestsInFile)) return;
      registered = true;
      registerJvmTestController(context, client);
    }),
  );
};
