import { strict as assert } from 'node:assert';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { computePortFilePath, readPortFile } from './portFile';

// This vector must match the Kotlin side (WorkspacePortFileTest). Both hash the same string with SHA-256 and
// keep the first 16 hex characters. If one recipe changes, the client and server stop finding each other.
const WORKSPACE_ROOT = '/home/user/my-workspace';
const EXPECTED_FILE = 'e22d7af6977edd2e.port';

test('computePortFilePath names the file by the workspace-root hash', () => {
  const expected = path.join(os.tmpdir(), 'intellij-lsp-servers', EXPECTED_FILE);
  assert.equal(computePortFilePath(WORKSPACE_ROOT), expected);
});

test('readPortFile returns the published port', () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'port-file-test-'));
  try {
    const file = path.join(dir, 'server.port');
    writeFileSync(file, '54321\n');
    assert.equal(readPortFile(file), 54321);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('readPortFile rejects a missing, empty, or out-of-range file', () => {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'port-file-test-'));
  try {
    assert.equal(readPortFile(path.join(dir, 'absent.port')), undefined);

    const empty = path.join(dir, 'empty.port');
    writeFileSync(empty, '   ');
    assert.equal(readPortFile(empty), undefined);

    const tooBig = path.join(dir, 'big.port');
    writeFileSync(tooBig, '70000');
    assert.equal(readPortFile(tooBig), undefined);

    const notNumber = path.join(dir, 'text.port');
    writeFileSync(notNumber, 'not-a-port');
    assert.equal(readPortFile(notNumber), undefined);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
