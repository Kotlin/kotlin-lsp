import assert from 'node:assert/strict';
import { test } from 'node:test';
import { wslJvmOptions } from './wslJvmOptions';

const IPV4_STACK = ['-Djava.net.preferIPv4Stack=true'];

test('forces the IPv4 stack on a Remote-WSL extension host', () => {
  assert.deepEqual(wslJvmOptions('linux', 'wsl', {}), IPV4_STACK);
});

test('forces the IPv4 stack in a plain WSL shell', () => {
  assert.deepEqual(wslJvmOptions('linux', undefined, { WSL_DISTRO_NAME: 'Ubuntu' }), IPV4_STACK);
  assert.deepEqual(
    wslJvmOptions('linux', undefined, { WSL_INTEROP: '/run/WSL/1_interop' }),
    IPV4_STACK,
  );
});

test('leaves Linux without WSL markers alone', () => {
  assert.deepEqual(wslJvmOptions('linux', undefined, {}), []);
  assert.deepEqual(wslJvmOptions('linux', 'ssh-remote', { WSL_DISTRO_NAME: '' }), []);
});

test('ignores WSL markers on other platforms', () => {
  assert.deepEqual(wslJvmOptions('win32', 'wsl', { WSL_DISTRO_NAME: 'Ubuntu' }), []);
  assert.deepEqual(wslJvmOptions('darwin', undefined, { WSL_INTEROP: '/run/WSL/1_interop' }), []);
});
