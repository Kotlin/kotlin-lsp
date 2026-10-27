// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import {
  commands,
  debug,
  type CancellationToken,
  type DebugSession,
  type ExtensionContext,
  window,
  workspace,
} from 'vscode';
// A namespace read, not a named import: the `vscode` stubs of the product tests export only the names they list.
import * as vscode from 'vscode';
import {
  HOT_SWAP_COMMAND,
  HOT_SWAP_REQUEST,
  HOT_SWAP_SETTING,
  HOT_SWAP_SETTING_KEY,
  HOT_SWAP_SETTING_SECTION,
  HOT_SWAP_SUPPORTED_CONTEXT,
  type HotSwapArguments,
  type HotSwapMode,
  type HotSwapOutcome,
  HotSwapBatches,
  type HotSwapResponse,
  HotSwapSessions,
  SaveCoalescer,
  hotSwapCapabilityOf,
  hotSwapContextValue,
  hotSwapErrorOutcome,
  hotSwapModeOf,
  hotSwapOutcome,
  isHotSwapDocument,
  namedHotSwapOutcome,
} from './hotSwapModel';

const STATUS_MESSAGE_MS = 5_000;
const RESTART_ACTION = 'Restart';

/**
 * "Reload Changed Classes" (HotSwap) for the IntelliJ debug sessions of [debugTypes] (see `hotSwapModel.ts` for the
 * logic). A tracker reads each session's `initialize` response for `supportsHotSwapRequest`; the debug toolbar button
 * shows for the focused session that has it, unless `intellij.debug.hotSwap` is `never`. The command saves all
 * documents, because the build tool reads the disk, and sends `intellij/hotSwap { build: true }` to every session that
 * can reload, one after another, like IntelliJ does: the server builds, reloads and streams its lines as `output`
 * events, and the client shows only the summary, or a warning. With more than one session, each message names its
 * session.
 */
export function registerHotSwap(context: ExtensionContext, debugTypes: Iterable<string>): void {
  const capabilities = new HotSwapSessions();
  const sessions = new Map<string, DebugSession>();
  const batches = new HotSwapBatches();

  const mode = (): HotSwapMode =>
    hotSwapModeOf(workspace.getConfiguration(HOT_SWAP_SETTING_SECTION).get(HOT_SWAP_SETTING_KEY));
  const refreshContext = () =>
    void commands.executeCommand(
      'setContext',
      HOT_SWAP_SUPPORTED_CONTEXT,
      hotSwapContextValue(mode(), capabilities.supports(debug.activeDebugSession?.id)),
    );

  /** Reloads [session] and shows the answer; resolves once the server answered. */
  const reload = async (session: DebugSession, named: boolean): Promise<void> => {
    const args: HotSwapArguments = { build: true };
    const request: Promise<HotSwapOutcome | undefined> = Promise.resolve(
      session.customRequest(HOT_SWAP_REQUEST, args),
    ).then(
      (body: unknown) => hotSwapOutcome(body as HotSwapResponse | undefined),
      (error: unknown) => hotSwapErrorOutcome(error),
    );
    // Cancel only stops the waiting: `customRequest` cannot be cancelled, and the server finishes the build.
    const outcome = await window.withProgress(
      {
        location: vscode.ProgressLocation.Notification,
        title: named
          ? `Reloading changed classes of ${session.name}…`
          : 'Reloading changed classes…',
        cancellable: true,
      },
      (_progress, token) => Promise.race([request, cancelled(token)]),
    );
    // A session that ended meanwhile was stopped by the user: its answer says nothing new.
    // Not awaited: an open warning must not hold the next reload back.
    if (outcome && sessions.has(session.id)) {
      void show(namedHotSwapOutcome(outcome, named ? session.name : undefined), session);
    }
    await request;
  };

  /** Reloads every session that can, one after another, in the order they started. */
  const reloadAll = async (): Promise<void> => {
    const targets = capabilities
      .capableSessions()
      .map((id) => sessions.get(id))
      .filter((session) => session !== undefined);
    for (const session of targets) {
      // A session that ended while an earlier one reloaded is skipped.
      if (sessions.has(session.id)) await reload(session, targets.length > 1);
    }
  };

  // A save waits for the running batch, which may have built before the save.
  const coalescer = new SaveCoalescer(() => batches.startAfterRunning(reloadAll));

  const trackerFactory = {
    createDebugAdapterTracker: (session: DebugSession) => ({
      onDidSendMessage: (message: unknown) => {
        const supported = hotSwapCapabilityOf(message);
        if (supported === undefined) return;
        sessions.set(session.id, session);
        capabilities.record(session.id, supported);
        refreshContext();
      },
    }),
  };
  for (const type of debugTypes) {
    context.subscriptions.push(debug.registerDebugAdapterTrackerFactory(type, trackerFactory));
  }

  context.subscriptions.push(
    { dispose: () => coalescer.dispose() },
    commands.registerCommand(HOT_SWAP_COMMAND, async () => {
      if (!capabilities.anySupported()) {
        void window.showInformationMessage('No debug session can reload changed classes');
        return;
      }
      await workspace.saveAll(false);
      // A manual reload while a batch runs is dropped. One that starts covers the saves so far, the ones of its own
      // `saveAll` too; saves during the batch reload after it.
      await batches.start(() => {
        coalescer.discardPending();
        return reloadAll();
      });
    }),
    debug.onDidChangeActiveDebugSession(() => refreshContext()),
    debug.onDidTerminateDebugSession((session) => {
      sessions.delete(session.id);
      capabilities.forget(session.id);
      refreshContext();
    }),
    workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration(HOT_SWAP_SETTING)) refreshContext();
    }),
    workspace.onDidSaveTextDocument((document) => {
      if (mode() !== 'onSave' || !isHotSwapDocument(document.languageId)) return;
      if (capabilities.anySupported()) coalescer.saved();
    }),
  );
  refreshContext();
}

/** Resolves with `undefined` once [token] is cancelled. */
function cancelled(token: CancellationToken): Promise<undefined> {
  return new Promise((resolve) => {
    token.onCancellationRequested(() => resolve(undefined));
  });
}

async function show(outcome: HotSwapOutcome, session: DebugSession): Promise<void> {
  if (outcome.kind === 'status') {
    window.setStatusBarMessage(outcome.text, STATUS_MESSAGE_MS);
    return;
  }
  const actions = outcome.offerRestart ? [RESTART_ACTION] : [];
  const choice = await window.showWarningMessage(outcome.text, ...actions);
  // The debug commands take the clicked element first and the session context second.
  if (choice === RESTART_ACTION) {
    await commands.executeCommand('workbench.action.debug.restart', undefined, {
      sessionId: session.id,
    });
  }
}
