import assert from 'node:assert/strict';
import { test } from 'node:test';
import { proxyJvmOptions } from './proxySettings';

test('configures HTTP and HTTPS JVM clients from an HTTP proxy', () => {
  assert.deepEqual(proxyJvmOptions(' http://127.0.0.1:9090 ', undefined), [
    '-Dhttp.proxyHost=127.0.0.1',
    '-Dhttp.proxyPort=9090',
    '-Dhttps.proxyHost=127.0.0.1',
    '-Dhttps.proxyPort=9090',
  ]);
});

test('uses the proxy scheme default port', () => {
  assert.deepEqual(proxyJvmOptions('https://proxy.example.com', undefined), [
    '-Dhttp.proxyHost=proxy.example.com',
    '-Dhttp.proxyPort=443',
    '-Dhttps.proxyHost=proxy.example.com',
    '-Dhttps.proxyPort=443',
  ]);
});

test('ignores empty, malformed, and unsupported proxy values', () => {
  assert.deepEqual(proxyJvmOptions('', undefined), []);
  assert.deepEqual(proxyJvmOptions('not a URL', undefined), []);
  assert.deepEqual(proxyJvmOptions('socks5://proxy.example.com', undefined), []);
});

test('honors disabled VS Code proxy support', () => {
  assert.deepEqual(proxyJvmOptions('http://proxy.example.com:3128', 'off'), []);
});
