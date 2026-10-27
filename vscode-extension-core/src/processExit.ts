// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

const POLL_INTERVAL_MS = 100;

/** Whether a process with [pid] runs. Signal 0 delivers nothing and only checks. */
export function processAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    // EPERM: the process runs, but belongs to another user.
    return (error as NodeJS.ErrnoException).code === 'EPERM';
  }
}

export interface WaitForProcessExitOptions {
  pid: number;
  timeoutMs: number;
  pollIntervalMs?: number;
}

/**
 * Waits until the process [pid] does not run. Returns `false` when it still runs after [timeoutMs].
 *
 * The process is not a child of this one, so there is no exit event to wait for, and the check polls.
 */
export async function waitForProcessExit({
  pid,
  timeoutMs,
  pollIntervalMs = POLL_INTERVAL_MS,
}: WaitForProcessExitOptions): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (processAlive(pid)) {
    if (Date.now() >= deadline) return false;
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
  }
  return true;
}
