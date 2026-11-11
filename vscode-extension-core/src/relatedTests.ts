// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  CancellationTokenSource,
  commands,
  type ExtensionContext,
  ProgressLocation,
  window,
} from 'vscode';
import {
  ErrorCodes,
  LSPErrorCodes,
  RequestType,
  ResponseError,
  State,
  type TextDocumentPositionParams,
} from 'vscode-languageclient/node';
import { getLspClient } from './lspClient';
import {
  GO_TO_TEST_FROM_EDITOR_COMMAND,
  goToRelatedTests,
  type GoToRelatedTestsEditor,
  type RelatedTestsResult,
} from './relatedTestsModel';

/** No capability flag: an older server answers `MethodNotFound`. */
const RelatedTestsRequest = new RequestType<TextDocumentPositionParams, RelatedTestsResult, void>(
  'intellij/relatedTests',
);

/** The request in flight. A new press cancels it: the server search is costly, and only the last press counts. */
let pending: CancellationTokenSource | undefined;

const vscodeEditor: GoToRelatedTestsEditor = {
  activeTarget: () => {
    const client = getLspClient();
    const editor = window.activeTextEditor;
    if (!client || !editor) return undefined;
    return {
      textDocument: { uri: client.code2ProtocolConverter.asUri(editor.document.uri) },
      position: client.code2ProtocolConverter.asPosition(editor.selection.active),
    };
  },
  relatedTests: async (params) => {
    const client = getLspClient();
    if (client?.state !== State.Running) {
      void window.showErrorMessage('The language server is not running');
      return undefined;
    }
    pending?.cancel();
    const cancellation = new CancellationTokenSource();
    pending = cancellation;
    try {
      return await window.withProgress(
        { location: ProgressLocation.Window, title: 'Go to Test' },
        () => client.sendRequest(RelatedTestsRequest, params, cancellation.token),
      );
    } catch (e) {
      if (e instanceof ResponseError) {
        if (e.code === ErrorCodes.MethodNotFound) {
          void window.showWarningMessage('Go to Test needs a newer language server');
          return undefined;
        }
        if (e.code === LSPErrorCodes.RequestCancelled || e.code === LSPErrorCodes.ServerCancelled) {
          return undefined;
        }
      }
      const message = e instanceof Error ? e.message : String(e);
      void window.showErrorMessage(`Go to Test failed: ${message}`);
      return undefined;
    } finally {
      if (pending === cancellation) pending = undefined;
      cancellation.dispose();
    }
  },
  // The built-in command opens one location, peeks several as Go to Definition does, and shows the
  // message at the caret for none.
  showLocations: async ({ locations, noResultsMessage }) => {
    const client = getLspClient();
    const editor = window.activeTextEditor;
    if (!client || !editor) return;
    await commands.executeCommand(
      'editor.action.goToLocations',
      editor.document.uri,
      editor.selection.active,
      locations.map((location) => client.protocol2CodeConverter.asLocation(location)),
      'peek',
      noResultsMessage,
    );
  },
};

export function registerGoToRelatedTestsCommands(context: ExtensionContext): void {
  context.subscriptions.push(
    commands.registerCommand(GO_TO_TEST_FROM_EDITOR_COMMAND, () => goToRelatedTests(vscodeEditor)),
  );
}
