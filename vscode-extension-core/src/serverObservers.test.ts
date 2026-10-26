import assert from 'node:assert/strict';
import { test } from 'node:test';

import { launchToPublish, notifyObserver, reloadAndNotify } from './serverObservers';

const ROUTER = {
  throughRouter: true,
  launcherPath: '/srv/bin/intellij-server',
  devPort: -1,
  command: '/srv/bin/lsp-router',
  args: ['--server-launcher', '/srv/bin/intellij-server', '--'],
  env: { PATH: '/usr/bin' },
};

test('a start of the router counts', () => {
  assert.deepEqual(launchToPublish(ROUTER), {
    executable: '/srv/bin/lsp-router',
    args: ['--server-launcher', '/srv/bin/intellij-server', '--'],
    env: { PATH: '/usr/bin' },
  });
});

test('A start with a dev port', () => {
  assert.equal(launchToPublish({ ...ROUTER, devPort: 5007 }), undefined);
  assert.equal(
    launchToPublish({ ...ROUTER, devPort: 5007, launcherPath: undefined, args: [] }),
    undefined,
  );
});

test('A launch with no router', () => {
  assert.equal(
    launchToPublish({
      ...ROUTER,
      throughRouter: false,
      command: '/srv/bin/intellij-server',
      args: ['--stdio'],
    }),
    undefined,
  );
});

test('A failing callback', async () => {
  const logs: string[] = [];
  notifyObserver({
    name: 'onServerLaunched',
    call: () => {
      throw new Error('a thrown fault');
    },
    log: (line) => logs.push(line),
  });
  notifyObserver({
    name: 'onServerLaunched',
    call: () => Promise.reject(new Error('a rejected fault')),
    log: (line) => logs.push(line),
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(logs.length, 2);
  assert.ok(logs[0].includes('a thrown fault'));
  assert.ok(logs[1].includes('a rejected fault'));
});

test('A failed reload', async () => {
  const events: string[] = [];
  await assert.rejects(
    reloadAndNotify({
      send: () => Promise.reject(new Error('the reload failed')),
      apply: () => events.push('apply'),
      notify: () => events.push('notify'),
    }),
    /the reload failed/,
  );
  assert.deepEqual(events, []);
});

test('a reload notifies after it applied its options', async () => {
  const events: string[] = [];
  await reloadAndNotify({
    send: async () => events.push('send'),
    apply: () => events.push('apply'),
    notify: () => events.push('notify'),
  });
  assert.deepEqual(events, ['send', 'apply', 'notify']);
});
