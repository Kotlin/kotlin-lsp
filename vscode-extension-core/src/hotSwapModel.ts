// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import { errorMessage } from './buildTaskModel';

/**
 * HotSwap with the editor taken out (see `hotSwap.ts` for the wiring): the wire shape of the server's
 * `intellij/hotSwap` DAP request, what its answer shows, which sessions can reload, and the coalescing of saves.
 *
 * The server builds and reloads; it streams the build lines and the reload messages as `output` events, so the
 * client prints nothing of its own and only sums the answer up.
 */

/** The custom DAP request; see the server's `HotSwapRequest` (`platform/dap/protocol/.../HotSwap.kt`). */
export const HOT_SWAP_REQUEST = 'intellij/hotSwap';

/** The `initialize` capability a server that serves [HOT_SWAP_REQUEST] sets. */
const HOT_SWAP_CAPABILITY = 'supportsHotSwapRequest';

/** The command behind the debug toolbar button. */
export const HOT_SWAP_COMMAND = 'jetbrains.debug.reloadChangedClasses';

/** The context key the debug toolbar button shows on: the focused session can reload and the setting allows it. */
export const HOT_SWAP_SUPPORTED_CONTEXT = 'jetbrains.debug.hotSwapSupported';

export const HOT_SWAP_SETTING_SECTION = 'intellij.debug';
export const HOT_SWAP_SETTING_KEY = 'hotSwap';
export const HOT_SWAP_SETTING = `${HOT_SWAP_SETTING_SECTION}.${HOT_SWAP_SETTING_KEY}`;

/**
 * When classes reload: `manual` from the toolbar button, `onSave` also after a JVM source is saved, `never` hides the
 * button.
 */
export const HOT_SWAP_MODES = ['manual', 'onSave', 'never'] as const;
export type HotSwapMode = (typeof HOT_SWAP_MODES)[number];
const DEFAULT_HOT_SWAP_MODE: HotSwapMode = 'manual';

/** The quiet time after the last save before an `onSave` reload starts. */
export const SAVE_DEBOUNCE_MS = 750;

/**
 * The arguments of [HOT_SWAP_REQUEST]. The server builds first unless [build] is `false`. The sessions one reload
 * reaches send the same [buildId], so the server builds each build step once for all of them.
 */
export interface HotSwapArguments {
  build?: boolean;
  buildId?: string;
}

/** The arguments of one session's reload in the batch [buildId]: build first, each step once per batch. */
export function hotSwapArguments(buildId: string): HotSwapArguments {
  return { build: true, buildId };
}

/** One build or reload summary line; the severity is an open enum (`info`, `warning`, `error`). */
export interface HotSwapMessage {
  severity: string;
  text: string;
}

/**
 * The answer of [HOT_SWAP_REQUEST]. The status is an open enum: `reloaded`, `noChanges`, `buildFailed`, `failed`,
 * `unsupported`, or a value a newer server knows. The wire encodes defaults, but nothing relies on that.
 */
export interface HotSwapResponse {
  status: string;
  reloadedClasses?: number;
  messages?: HotSwapMessage[];
}

/**
 * What the client shows for an answer: a status bar message, or a warning that may offer to restart the session. Only
 * a reload the VM refused offers the restart: a new session runs the new code.
 */
export interface HotSwapOutcome {
  kind: 'status' | 'warning';
  text: string;
  offerRestart: boolean;
}

/** The explanation of a failed status that came without any message. */
const FAILURE_BY_STATUS: Record<string, string> = {
  buildFailed: 'the build failed',
  failed: 'the classes could not be reloaded',
  unsupported: 'the debug session cannot reload classes',
};

export function hotSwapModeOf(value: unknown): HotSwapMode {
  return HOT_SWAP_MODES.find((mode) => mode === value) ?? DEFAULT_HOT_SWAP_MODE;
}

/** Whether saving a document of [languageId] may change a class of the debuggee. */
export function isHotSwapDocument(languageId: string): boolean {
  return languageId === 'java' || languageId === 'kotlin';
}

/** The value of [HOT_SWAP_SUPPORTED_CONTEXT]: whether the focused session can reload under [mode]. */
export function hotSwapContextValue(mode: HotSwapMode, supported: boolean): boolean {
  return mode !== 'never' && supported;
}

/**
 * What the client shows for [response]. A success is a one-line summary; the lines themselves are in the output
 * already. A success with a warning or an error message (e.g. modules no build tool can build) warns with the summary
 * and the first such message, because a status bar message would hide it. Anything else, an unknown status included,
 * is a failure. Only `failed` (the VM refused the new code) offers a restart: a failed build fails again after it, and
 * a session that cannot reload cannot after it either.
 */
export function hotSwapOutcome(response: HotSwapResponse | undefined): HotSwapOutcome {
  switch (response?.status) {
    case 'reloaded': {
      const count = response.reloadedClasses ?? 0;
      return success(`Reloaded ${count} ${count === 1 ? 'class' : 'classes'}`, response);
    }
    case 'noChanges':
      return success('No changed classes to reload', response);
    default:
      return failure(firstProblem(response), response?.status === 'failed');
  }
}

/**
 * The message VS Code answers a request with when the debug adapter stops before it answered: the user stopped the
 * session.
 */
const STOPPED_SESSION_ERROR = 'canceled';

/**
 * What the client shows when the request itself failed (`success=false`, which `customRequest` throws): a warning
 * without a restart, or nothing when the session stopped before it answered.
 */
export function hotSwapErrorOutcome(error: unknown): HotSwapOutcome | undefined {
  const message = errorMessage(error);
  if (message === STOPPED_SESSION_ERROR) return undefined;
  return failure(message, false);
}

/** [outcome] with the name of its session in front; [sessionName] is `undefined` when only one session reloads. */
export function namedHotSwapOutcome(
  outcome: HotSwapOutcome,
  sessionName: string | undefined,
): HotSwapOutcome {
  if (sessionName === undefined) return outcome;
  return { ...outcome, text: `${sessionName}: ${outcome.text}` };
}

function success(summary: string, response: HotSwapResponse): HotSwapOutcome {
  const problem = response.messages?.find(
    (entry) => entry.severity === 'warning' || entry.severity === 'error',
  );
  if (problem)
    return { kind: 'warning', text: `${summary}: ${problem.text.trim()}`, offerRestart: false };
  return { kind: 'status', text: summary, offerRestart: false };
}

function failure(reason: string, offerRestart: boolean): HotSwapOutcome {
  return { kind: 'warning', text: `Reload failed: ${reason}`, offerRestart };
}

/** The first error of [response], else its first warning, else its first message, else what its status means. */
function firstProblem(response: HotSwapResponse | undefined): string {
  const messages = response?.messages ?? [];
  const message =
    messages.find((entry) => entry.severity === 'error') ??
    messages.find((entry) => entry.severity === 'warning') ??
    messages[0];
  if (message) return message.text.trim();
  if (response === undefined) return 'the debug adapter sent no answer';
  return FAILURE_BY_STATUS[response.status] ?? `unexpected status "${response.status}"`;
}

/** The wire shape of the DAP messages a tracker reads the capability from. */
interface DapResponseMessage {
  type?: string;
  command?: string;
  success?: boolean;
  body?: Record<string, unknown>;
}

/**
 * Whether the session can reload, read off its `initialize` response; `undefined` for any other message. A failed
 * `initialize`, or a server too old to declare the capability, means it cannot.
 */
export function hotSwapCapabilityOf(message: unknown): boolean | undefined {
  const { type, command, success, body } = (message ?? {}) as DapResponseMessage;
  if (type !== 'response' || command !== 'initialize') return undefined;
  return success === true && body?.[HOT_SWAP_CAPABILITY] === true;
}

/** The capability of every running IntelliJ session, by session id, in the order the sessions initialized. */
export class HotSwapSessions {
  private readonly supported = new Map<string, boolean>();

  record(sessionId: string, supported: boolean): void {
    this.supported.delete(sessionId);
    this.supported.set(sessionId, supported);
  }

  forget(sessionId: string): void {
    this.supported.delete(sessionId);
  }

  supports(sessionId: string | undefined): boolean {
    return sessionId !== undefined && this.supported.get(sessionId) === true;
  }

  anySupported(): boolean {
    return [...this.supported.values()].includes(true);
  }

  /** The sessions a reload goes to: every session that can reload, in the order they initialized. */
  capableSessions(): string[] {
    return [...this.supported.entries()].filter(([, supported]) => supported).map(([id]) => id);
  }
}

/** The timer functions [SaveCoalescer] and [HotSwapCancel] use; a test passes its own. */
export interface HotSwapTimers {
  set(run: () => void, ms: number): unknown;
  clear(handle: unknown): void;
}

const systemTimers: HotSwapTimers = {
  set: (run, ms) => setTimeout(run, ms),
  clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

/**
 * One reload batch at a time. A batch sends [HOT_SWAP_REQUEST] to each session in turn and ends when every answer is
 * in, or once a cancelled request settled (see [HotSwapCancel.settled]), so the server also never gets a second request
 * of a session while it serves one. [start] gives up while a batch runs (a manual reload), [startAfterRunning] waits for it (a save, whose
 * edit the running build may have missed).
 */
export class HotSwapBatches {
  private running: Promise<unknown> | undefined;

  /** Runs [batch], or returns `undefined` when a batch runs. */
  start<T>(batch: () => Promise<T>): Promise<T> | undefined {
    if (this.running !== undefined) return undefined;
    const run = batch();
    this.running = run;
    const forget = () => {
      if (this.running === run) this.running = undefined;
    };
    run.then(forget, forget);
    return run;
  }

  /** Waits until no batch runs, then runs [batch]. */
  async startAfterRunning<T>(batch: () => Promise<T>): Promise<T> {
    for (;;) {
      const running = this.running;
      if (running === undefined) return this.start(batch)!;
      await running.then(
        () => undefined,
        () => undefined,
      );
    }
  }
}

/** How long a batch waits, after a cancel, for the server to answer the cancelled request. */
export const CANCEL_SETTLE_TIMEOUT_MS = 5_000;

/** The standard DAP request that cancels a request in flight, by its `seq`. */
export const CANCEL_REQUEST = 'cancel';

/** The arguments of [CANCEL_REQUEST]. */
export interface CancelArguments {
  requestId: number;
}

/** The wire shape of the DAP request a tracker reads the `seq` from. */
interface DapRequestMessage {
  type?: string;
  command?: string;
  seq?: unknown;
  arguments?: { buildId?: unknown };
}

/**
 * The cancel of one session's [HOT_SWAP_REQUEST] of the batch [buildId] (LSP-2474). `customRequest` takes no cancel
 * token and does not tell the `seq` it sends, so a debug adapter tracker hands every message the client sends to the
 * adapter to [sent], which keeps the `seq` of the first such request. A cancel sends [CANCEL_REQUEST] with that `seq`:
 * the server cancels the request, which ends its build and frees its hot swap. A cancel before the request left sends
 * it once the request leaves. [sent] and [cancel] answer the arguments of the one cancel to send, else `undefined`.
 *
 * [until] waits for the answer, or for the cancel, so the notification closes at the cancel. [settled] is what the
 * batch waits for after a cancel: the server keeps its hot swap until the cancelled request ends, then answers it, so a
 * reload sent before that answer would be "already running". A server that never answers holds the batch only
 * [CANCEL_SETTLE_TIMEOUT_MS]. A cancel after the answer sends nothing.
 */
export class HotSwapCancel {
  private seq: number | undefined;
  private phase: 'running' | 'answered' | 'cancelled' = 'running';
  private cancelSent = false;
  private readonly cancelled: Promise<undefined>;
  private resolveCancelled!: () => void;

  constructor(
    private readonly buildId: string,
    private readonly timers: HotSwapTimers = systemTimers,
  ) {
    this.cancelled = new Promise((resolve) => {
      this.resolveCancelled = () => resolve(undefined);
    });
  }

  get isCancelled(): boolean {
    return this.phase === 'cancelled';
  }

  /** Reads a message the client sends to the adapter. */
  sent(message: unknown): CancelArguments | undefined {
    if (this.seq !== undefined) return undefined;
    const { type, command, seq, arguments: args } = (message ?? {}) as DapRequestMessage;
    if (type !== 'request' || command !== HOT_SWAP_REQUEST || typeof seq !== 'number')
      return undefined;
    if (args?.buildId !== this.buildId) return undefined;
    this.seq = seq;
    return this.cancelOnce();
  }

  /** The user cancelled the reload. */
  cancel(): CancelArguments | undefined {
    if (this.phase !== 'running') return undefined;
    this.phase = 'cancelled';
    this.resolveCancelled();
    return this.cancelOnce();
  }

  /** [answer], or `undefined` once the reload is cancelled. */
  until<T>(answer: Promise<T>): Promise<T | undefined> {
    const answered = answer.finally(() => {
      if (this.phase === 'running') this.phase = 'answered';
    });
    return Promise.race([answered, this.cancelled]);
  }

  /**
   * Settles once [answer] settled; after a cancel, at the latest [CANCEL_SETTLE_TIMEOUT_MS] after this call, which
   * follows the cancel at once.
   */
  settled(answer: Promise<unknown>): Promise<void> {
    const answered = answer.then(
      () => undefined,
      () => undefined,
    );
    if (!this.isCancelled) return answered;
    return new Promise((resolve) => {
      const timer = this.timers.set(resolve, CANCEL_SETTLE_TIMEOUT_MS);
      void answered.then(() => {
        this.timers.clear(timer);
        resolve();
      });
    });
  }

  private cancelOnce(): CancelArguments | undefined {
    if (this.phase !== 'cancelled' || this.seq === undefined || this.cancelSent) return undefined;
    this.cancelSent = true;
    return { requestId: this.seq };
  }
}

/**
 * Turns saves into reloads: a burst of saves runs one reload [SAVE_DEBOUNCE_MS] after the last of them, and saves
 * while a reload runs run one more reload after it (debounced again), not one each.
 */
export class SaveCoalescer {
  private timer: unknown;
  private running = false;
  private savedWhileRunning = false;
  private disposed = false;

  constructor(
    private readonly reload: () => Promise<void>,
    private readonly timers: HotSwapTimers = systemTimers,
  ) {}

  saved(): void {
    if (this.disposed) return;
    if (this.running) {
      this.savedWhileRunning = true;
      return;
    }
    this.schedule();
  }

  /** Drops a pending reload: the caller reloads now, and that covers the saves so far. A running reload is kept. */
  discardPending(): void {
    this.cancelTimer();
  }

  dispose(): void {
    this.disposed = true;
    this.savedWhileRunning = false;
    this.cancelTimer();
  }

  private schedule(): void {
    this.cancelTimer();
    this.timer = this.timers.set(() => {
      this.timer = undefined;
      void this.run();
    }, SAVE_DEBOUNCE_MS);
  }

  private cancelTimer(): void {
    if (this.timer === undefined) return;
    this.timers.clear(this.timer);
    this.timer = undefined;
  }

  private async run(): Promise<void> {
    this.running = true;
    try {
      await this.reload();
    } catch {
      // The reload shows its own failure; the next save reloads again.
    } finally {
      this.running = false;
    }
    if (this.savedWhileRunning) {
      this.savedWhileRunning = false;
      this.saved();
    }
  }
}
