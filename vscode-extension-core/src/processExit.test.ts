import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { test } from 'node:test';
import { processAlive, waitForProcessExit } from './processExit';

function spawnSleeper(): ReturnType<typeof spawn> {
  return spawn(process.execPath, ['-e', 'setTimeout(() => {}, 60_000)'], { stdio: 'ignore' });
}

test('sees this process as alive', () => {
  assert.equal(processAlive(process.pid), true);
});

test('returns once a process exits', async () => {
  const child = spawnSleeper();
  await once(child, 'spawn');
  const pid = child.pid!;
  const waited = waitForProcessExit({ pid, timeoutMs: 10_000, pollIntervalMs: 10 });
  child.kill('SIGKILL');
  await once(child, 'exit');
  assert.equal(await waited, true);
  assert.equal(processAlive(pid), false);
});

test('returns false for a process that outlives the timeout', async () => {
  const child = spawnSleeper();
  try {
    await once(child, 'spawn');
    assert.equal(await waitForProcessExit({ pid: child.pid!, timeoutMs: 50, pollIntervalMs: 10 }), false);
  } finally {
    child.kill('SIGKILL');
    await once(child, 'exit');
  }
});
