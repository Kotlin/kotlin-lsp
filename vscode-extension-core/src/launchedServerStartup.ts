import { type ChildProcessWithoutNullStreams } from 'node:child_process';
import { once } from 'node:events';

const EXPIRED_SERVER_BUILD_EXIT_CODE = 7; // AppExitCodes.LICENSE_ERROR
const KILL_ESCALATION_MS = 5_000;

interface ServerProcessExit {
  code: number | null;
  signal: NodeJS.Signals | null;
}

export interface LaunchedServerState {
  currentAttempt?: LaunchedServerStartup;
  initialStartSettled: boolean;
  /**
   * Summary of the error the server answered `initialize` with, which a retry can only repeat.
   * Reported in place of the full response, whose detail goes to the log.
   */
  initializationRejection?: string;
}

/**
 * Whether a closed connection must not trigger the language client's automatic restart. The failure
 * is reported to the extension host instead, so restarting would race that report and leave a second
 * server behind. Waits for the exit first so the caller can classify the exit code.
 *
 * A failed initial start only applies to servers we launched: an external dev server keeps the
 * client's normal reconnect behaviour, since nothing here owns its lifecycle.
 */
export async function shouldSuppressRestart(
  state: LaunchedServerState,
  exitWaitMs: number,
): Promise<boolean> {
  const failedInitialStart = !state.initialStartSettled && state.currentAttempt !== undefined;
  if (state.initializationRejection === undefined && !failedInitialStart) return false;
  await state.currentAttempt?.waitForExit(exitWaitMs);
  return true;
}

export class LaunchedServerStartup {
  private process?: ChildProcessWithoutNullStreams;
  private startupTimer?: NodeJS.Timeout;
  private startupTimeoutMs = 0;
  private killTimer?: NodeJS.Timeout;
  private timedOut = false;
  /** Set when kill() signalled a still-running process, so its exit is ours and not a diagnosis. */
  private killedWhileRunning = false;
  private spawnFailed = false;
  private exit?: ServerProcessExit;
  private exitPromise?: Promise<ServerProcessExit>;

  setProcess(
    process: ChildProcessWithoutNullStreams,
    onError: (error: Error) => void = () => {},
  ): void {
    if (this.process) throw new Error('Language server startup already owns a process');
    this.process = process;
    // Keep handling process errors after waitForSpawn's temporary listener is removed. Signalling a
    // process can fail (EPERM), and an unhandled 'error' event would take down the extension host.
    process.on('error', onError);
    this.exitPromise = new Promise((resolve) => {
      process.once('exit', (code, signal) => {
        this.exit = { code, signal };
        this.clearTimers();
        resolve(this.exit);
      });
    });
  }

  async waitForSpawn(): Promise<void> {
    if (!this.process) throw new Error('Language server process has not been created');
    try {
      await once(this.process, 'spawn');
    } catch (error) {
      // A process that never spawned never exits either, so stop anyone waiting for that exit.
      this.spawnFailed = true;
      throw error;
    }
  }

  async waitForExit(timeoutMs: number): Promise<ServerProcessExit | undefined> {
    if (this.exit) return this.exit;
    if (this.spawnFailed || !this.exitPromise) return undefined;
    const exitPromise = this.exitPromise;
    return new Promise((resolve) => {
      const timer = setTimeout(() => resolve(undefined), timeoutMs);
      void exitPromise.then((exit) => {
        clearTimeout(timer);
        resolve(exit);
      });
    });
  }

  startTimeout(timeoutMs: number): void {
    // Not clearTimers(): a stop racing the spawn may have already armed an escalation to survive.
    this.clearStartupTimer();
    this.startupTimeoutMs = timeoutMs;
    this.startupTimer = setTimeout(() => {
      this.timedOut = true;
      this.kill();
    }, timeoutMs);
  }

  settle(): void {
    this.clearStartupTimer();
  }

  /**
   * Stops a server that failed or hangs during initialization. Destroying the read side makes the
   * language client's connection report a close, which rejects its pending initialize request, so
   * startup fails within the advertised bound even when the process survives the signal.
   */
  kill(): void {
    const process = this.process;
    if (!process || this.exit) return;
    this.killedWhileRunning = true;
    process.kill();
    process.stdout.destroy();
    clearTimeout(this.killTimer);
    // unref: the escalation must not keep the host alive, and a dying host takes the child with it.
    this.killTimer = setTimeout(() => {
      if (!this.exit) process.kill('SIGKILL');
    }, KILL_ESCALATION_MS).unref();
  }

  private clearStartupTimer(): void {
    clearTimeout(this.startupTimer);
    this.startupTimer = undefined;
  }

  private clearTimers(): void {
    this.clearStartupTimer();
    clearTimeout(this.killTimer);
    this.killTimer = undefined;
  }

  get expiredBuild(): boolean {
    return this.exit?.code === EXPIRED_SERVER_BUILD_EXIT_CODE;
  }

  startupError(cause: Error): Error {
    if (this.timedOut) {
      return new Error(
        `Timed out waiting ${this.startupTimeoutMs / 1000} seconds for the language server to initialize`,
        { cause },
      );
    }
    // Only an exit the server chose diagnoses the failure. An exit we caused while cleaning up says
    // nothing, so the original initialization error stays the reported one.
    if (this.exit && !this.killedWhileRunning) {
      return new Error(
        `Language server process exited before initialization (code=${this.exit.code}, signal=${this.exit.signal})`,
        { cause },
      );
    }
    return cause;
  }
}
