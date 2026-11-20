// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { randomUUID } from 'node:crypto';
import {
  commands,
  debug,
  type DebugSession,
  type ExtensionContext,
  window,
  workspace,
} from 'vscode';
// A namespace read, not a named import: the `vscode` stubs of the product tests export only the names they list.
import * as vscode from 'vscode';
import {
  CANCEL_REQUEST,
  type CancelArguments,
  HOT_SWAP_COMMAND,
  HOT_SWAP_REQUEST,
  HOT_SWAP_SETTING,
  HOT_SWAP_SETTING_KEY,
  HOT_SWAP_SETTING_SECTION,
  HOT_SWAP_SUPPORTED_CONTEXT,
  type HotSwapMode,
  type HotSwapOutcome,
  HotSwapBatches,
  HotSwapCancel,
  type HotSwapResponse,
  HotSwapSessions,
  SaveCoalescer,
  hotSwapArguments,
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
 * documents, because the build tool reads the disk, and sends `intellij/hotSwap { build: true, buildId }` to every
 * session that can reload, one after another. IntelliJ builds once and reloads every session from that build; here
 * each session builds its own steps on the server, and the one `buildId` of the batch makes the server build each
 * step once: a later session skips what an earlier one built. The server builds, reloads and streams its lines as
 * `output` events, and the client shows only the summary, or a warning. With more than one session, each message
 * names its session. Cancel sends DAP `cancel` for the session's request (see `HotSwapCancel`) and closes the
 * notification at once; the batch ends once the server answered the cancelled request, or after a short timeout.
 */
export function registerHotSwap(context: ExtensionContext, debugTypes: Iterable<string>): void {
  const capabilities = new HotSwapSessions();
  const sessions = new Map<string, DebugSession>();
  const batches = new HotSwapBatches();
  // The cancel of the request in flight, by session id; a session has at most one, since batches run one at a time.
  const cancels = new Map<string, HotSwapCancel>();

  const mode = (): HotSwapMode =>
    hotSwapModeOf(workspace.getConfiguration(HOT_SWAP_SETTING_SECTION).get(HOT_SWAP_SETTING_KEY));
  const refreshContext = () =>
    void commands.executeCommand(
      'setContext',
      HOT_SWAP_SUPPORTED_CONTEXT,
      hotSwapContextValue(mode(), capabilities.supports(debug.activeDebugSession?.id)),
    );

  /**
   * Reloads [session] as part of the batch [buildId] and shows the answer. Resolves once the server answered, with
   * `true`, or, at a cancel, with `false` once the cancelled request settled: the notification closes at the cancel,
   * but the server keeps its hot swap until it answers, and the next batch must not run before (see
   * `HotSwapCancel.settled`).
   */
  const reload = async (
    session: DebugSession,
    named: boolean,
    buildId: string,
  ): Promise<boolean> => {
    const cancel = new HotSwapCancel(buildId);
    // Set before the request leaves, so the tracker sees the request.
    cancels.set(session.id, cancel);
    const request: Promise<HotSwapOutcome | undefined> = Promise.resolve(
      session.customRequest(HOT_SWAP_REQUEST, hotSwapArguments(buildId)),
    ).then(
      (body: unknown) => hotSwapOutcome(body as HotSwapResponse | undefined),
      (error: unknown) => hotSwapErrorOutcome(error),
    );
    // Kept until the answer: a cancel before the request left waits for the tracker to see it.
    void request.finally(() => {
      if (cancels.get(session.id) === cancel) cancels.delete(session.id);
    });
    const outcome = await window.withProgress(
      {
        location: vscode.ProgressLocation.Notification,
        title: named
          ? `Reloading changed classes of ${session.name}…`
          : 'Reloading changed classes…',
        cancellable: true,
      },
      (_progress, token) => {
        token.onCancellationRequested(() => sendCancel(session, cancel.cancel()));
        return cancel.until(request);
      },
    );
    // A session that ended meanwhile was stopped by the user: its answer says nothing new.
    // Not awaited: an open warning must not hold the next reload back.
    if (outcome && sessions.has(session.id)) {
      void show(namedHotSwapOutcome(outcome, named ? session.name : undefined), session);
    }
    if (!cancel.isCancelled) return true;
    await cancel.settled(request);
    return false;
  };

  /**
   * Reloads every session that can, one after another, in the order they started, as one build batch. A cancel ends
   * the batch: the user cancelled the reload, not the reload of one session.
   */
  const reloadAll = async (): Promise<void> => {
    const targets = capabilities
      .capableSessions()
      .map((id) => sessions.get(id))
      .filter((session) => session !== undefined);
    const buildId = randomUUID();
    for (const session of targets) {
      // A session that ended while an earlier one reloaded is skipped.
      if (sessions.has(session.id) && !(await reload(session, targets.length > 1, buildId))) return;
    }
  };

  // A save waits for the running batch, which may have built before the save.
  const coalescer = new SaveCoalescer(() => batches.startAfterRunning(reloadAll));

  const trackerFactory = {
    createDebugAdapterTracker: (session: DebugSession) => ({
      // VS Code -> adapter, with the `seq` the adapter sees.
      onWillReceiveMessage: (message: unknown) =>
        sendCancel(session, cancels.get(session.id)?.sent(message)),
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

/**
 * Sends DAP `cancel` with [args], if any. Nothing waits for its answer: a session that ended, or a server that cannot
 * cancel, fails it, and the reload has ended either way.
 */
function sendCancel(session: DebugSession, args: CancelArguments | undefined): void {
  if (args === undefined) return;
  void Promise.resolve()
    .then(() => session.customRequest(CANCEL_REQUEST, args))
    .then(
      () => undefined,
      () => undefined,
    );
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
