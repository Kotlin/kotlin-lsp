import assert from 'node:assert/strict';
import * as path from 'node:path';
import test from 'node:test';

import { routerArgs, routerFileName, routerPath, routerPathForLauncher } from './lspRouter';

test('the binary has an exe suffix on Windows only', () => {
  assert.equal(routerFileName('win32'), 'lsp-router.exe');
  assert.equal(routerFileName('linux'), 'lsp-router');
  assert.equal(routerFileName('darwin'), 'lsp-router');
});

test('the router ships beside the launcher of the same server', () => {
  assert.equal(
    routerPathForLauncher(path.join('/srv', 'bin', 'intellij-server'), 'linux'),
    path.join('/srv', 'bin', 'lsp-router'),
  );
});

test('the launcher decides the router', () => {
  const resolved = routerPath({
    launcherPath: path.join('/srv', 'bin', 'intellij-server'),
    env: {},
    platform: 'linux',
  });
  assert.equal(resolved, path.join('/srv', 'bin', 'lsp-router'));
});

/** A dev server on a port resolves no launcher, and so no server directory to look in. */
test('without a launcher and without the variable there is no router', () => {
  assert.equal(routerPath({ env: {}, platform: 'linux' }), undefined);
});

test('the environment variable wins over the shipped router', () => {
  const resolved = routerPath({
    launcherPath: path.join('/srv', 'bin', 'intellij-server'),
    env: { INTELLIJ_LSP_ROUTER: '/tmp/dev-router' },
    platform: 'linux',
  });
  assert.equal(resolved, '/tmp/dev-router');
});

test('a blank override is ignored', () => {
  const resolved = routerPath({
    launcherPath: path.join('/srv', 'bin', 'intellij-server'),
    env: { INTELLIJ_LSP_ROUTER: '   ' },
    platform: 'linux',
  });
  assert.equal(resolved, path.join('/srv', 'bin', 'lsp-router'));
});

test('the server arguments are passed through after a separator', () => {
  const args = routerArgs({
    launcherPath: '/srv/bin/intellij-server',
    serverArgs: ['--eula', 'abc'],
  });
  assert.deepEqual(args, ['--server-launcher', '/srv/bin/intellij-server', '--', '--eula', 'abc']);
});
