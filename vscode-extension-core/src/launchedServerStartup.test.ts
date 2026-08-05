import assert from 'node:assert/strict';
import { type ChildProcessWithoutNullStreams } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import test from 'node:test';
import {
  type LaunchedServerState,
  LaunchedServerStartup,
  shouldSuppressRestart,
} from './launchedServerStartup';

type FakeProcess = ChildProcessWithoutNullStreams & {
  signals: Array<NodeJS.Signals | number | undefined>;
};

function fakeProcess(): FakeProcess {
  const process = new EventEmitter() as FakeProcess;
  process.signals = [];
  process.stdout = new PassThrough();
  process.kill = (signal?: NodeJS.Signals | number) => {
    process.signals.push(signal);
    return true;
  };
  return process;
}

test('waitForSpawn rejects when no process was set', async () => {
  await assert.rejects(
    new LaunchedServerStartup().waitForSpawn(),
    /Language server process has not been created/,
  );
});

test('propagates spawn errors', async () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);

  const spawned = startup.waitForSpawn();
  const error = new Error('spawn ENOENT');
  process.emit('error', error);

  await assert.rejects(spawned, error);
});

test('does not wait for an exit that a failed spawn will never deliver', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);

  const spawned = startup.waitForSpawn();
  process.emit('error', new Error('spawn ENOENT'));
  await assert.rejects(spawned);

  // resolves without the timer being ticked, i.e. without paying the full bound
  assert.equal(await startup.waitForExit(60_000), undefined);
});

test('reports process errors seen after spawn', async () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  const errors: string[] = [];
  startup.setProcess(process, (error) => errors.push(error.message));

  const spawned = startup.waitForSpawn();
  process.emit('spawn');
  await spawned;
  process.emit('error', new Error('kill EPERM'));

  assert.deepEqual(errors, ['kill EPERM']);
});

test('a stop racing the spawn keeps its escalation', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);

  startup.kill(); // stopLspClient reaching the attempt before startServer arms its timeout
  startup.startTimeout(60_000);
  t.mock.timers.tick(5_000);

  assert.deepEqual(process.signals, [undefined, 'SIGKILL']);
});

test('handles process errors after spawn', async () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);

  const spawned = startup.waitForSpawn();
  process.emit('spawn');
  await spawned;

  assert.doesNotThrow(() => {
    process.emit('error', new Error('kill EPERM'));
    process.emit('error', new Error('kill EPERM'));
  });
});

test('settled attempt does not kill its process', async () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  startup.startTimeout(1);
  startup.settle();

  await new Promise((resolve) => setTimeout(resolve, 10));

  assert.equal(process.signals.length, 0);
});

test('kills the process and reports an initialization timeout', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  startup.startTimeout(60_000);

  t.mock.timers.tick(60_000);

  assert.deepEqual(process.signals, [undefined]);
  const cause = new Error('connection closed');
  const error = startup.startupError(cause);
  assert.match(error.message, /Timed out waiting 60 seconds/);
  assert.equal(error.cause, cause);
});

test('closes the read side so a hanging startup fails without the process dying', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  startup.startTimeout(60_000);

  t.mock.timers.tick(60_000);

  assert.equal(process.stdout.destroyed, true);
});

test('escalates to SIGKILL when the process ignores the first signal', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  startup.startTimeout(60_000);

  t.mock.timers.tick(60_000);
  assert.deepEqual(process.signals, [undefined]);

  t.mock.timers.tick(5_000);
  assert.deepEqual(process.signals, [undefined, 'SIGKILL']);
});

test('settling a failed start does not cancel a pending escalation', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  startup.startTimeout(60_000);
  t.mock.timers.tick(60_000);

  startup.settle();
  t.mock.timers.tick(5_000);

  assert.deepEqual(process.signals, [undefined, 'SIGKILL']);
});

test('kill closes the read side and escalates as well', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);

  startup.kill();

  assert.deepEqual(process.signals, [undefined]);
  assert.equal(process.stdout.destroyed, true);
  t.mock.timers.tick(5_000);
  assert.deepEqual(process.signals, [undefined, 'SIGKILL']);
});

test('kill does not signal a process that already exited', () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  process.emit('exit', 7, null);

  startup.kill();

  assert.deepEqual(process.signals, []);
});

test('does not escalate once the process has exited', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  startup.startTimeout(60_000);
  t.mock.timers.tick(60_000);

  process.emit('exit', null, 'SIGTERM');
  t.mock.timers.tick(5_000);

  assert.deepEqual(process.signals, [undefined]);
});

test('returns the cause unchanged without a timeout or exit', () => {
  const startup = new LaunchedServerStartup();
  startup.setProcess(fakeProcess());
  const cause = new Error('initialization failed');

  assert.equal(startup.startupError(cause), cause);
});

test('keeps the initialization error when cleanup killed a running process', async () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  const cause = new Error('server rejected initialize');

  startup.kill();
  process.emit('exit', null, 'SIGTERM');
  await startup.waitForExit(50);

  assert.equal(startup.startupError(cause), cause);
});

test('still reports an exit that happened before cleanup', () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  process.emit('exit', 1, null);

  startup.kill();

  assert.match(startup.startupError(new Error('connection closed')).message, /code=1/);
});

test('reports the latched exit code and signal', () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  process.emit('exit', 3, 'SIGTERM');

  const error = startup.startupError(new Error('connection closed'));
  assert.match(error.message, /code=3, signal=SIGTERM/);
});

test('a second process cannot mask the first process exit', () => {
  const startup = new LaunchedServerStartup();
  const firstProcess = fakeProcess();
  startup.setProcess(firstProcess);
  firstProcess.emit('exit', 7, null);

  assert.throws(() => startup.setProcess(fakeProcess()), /already owns a process/);
  assert.equal(startup.expiredBuild, true);
});

test('waitForExit observes a late exit within the bound', async () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  setTimeout(() => process.emit('exit', 7, null), 5);

  assert.deepEqual(await startup.waitForExit(50), { code: 7, signal: null });
});

test('waitForExit gives up at the bound', async () => {
  const startup = new LaunchedServerStartup();
  startup.setProcess(fakeProcess());

  assert.equal(await startup.waitForExit(1), undefined);
});

test('delegates a close to the client once the initial start has settled', async () => {
  const startup = new LaunchedServerStartup();
  startup.setProcess(fakeProcess());
  const state: LaunchedServerState = { currentAttempt: startup, initialStartSettled: true };

  assert.equal(await shouldSuppressRestart(state, 50), false);
});

test('suppresses a restart while the initial start is still failing', async () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  const state: LaunchedServerState = { currentAttempt: startup, initialStartSettled: false };
  setTimeout(() => process.emit('exit', 7, null), 5);

  assert.equal(await shouldSuppressRestart(state, 50), true);
  // the wait is what lets the caller report an expired build instead of a generic failure
  assert.equal(startup.expiredBuild, true);
});

test('suppresses a restart after the server rejected initialize, even once started', async () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  const state: LaunchedServerState = {
    currentAttempt: startup,
    initialStartSettled: true,
    initializationRejection: 'EULA is not accepted',
  };
  setTimeout(() => process.emit('exit', 0, null), 5);

  assert.equal(await shouldSuppressRestart(state, 50), true);
});

test('suppresses a restart after an initialize rejection on an external dev server', async () => {
  const state: LaunchedServerState = {
    initialStartSettled: true,
    initializationRejection: 'EULA is not accepted',
  };

  assert.equal(await shouldSuppressRestart(state, 50), true);
});

test('lets the client reconnect to an external dev server it did not launch', async () => {
  const state: LaunchedServerState = { initialStartSettled: false };

  assert.equal(await shouldSuppressRestart(state, 50), false);
});

test('does not classify another exit code as an expired build', () => {
  const startup = new LaunchedServerStartup();
  const process = fakeProcess();
  startup.setProcess(process);
  process.emit('exit', 1, null);

  assert.equal(startup.expiredBuild, false);
});
